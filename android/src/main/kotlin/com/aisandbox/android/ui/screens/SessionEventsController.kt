package com.aisandbox.android.ui.screens

import android.util.Log
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ReconnectController
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.net.SessionEventMessage
import com.aisandbox.android.net.SessionEventsClient
import com.aisandbox.android.net.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UC-32 — owns the live sessions-list push feed: one [SessionEventsClient] plus
 * a [ReconnectController] back-off loop. Each (re)connect routes the server's
 * authoritative initial [SessionEventMessage.Snapshot] to [onSnapshot] as a full
 * resync (AC5) and every [SessionEventMessage.Delta] to [onDelta] (AC3).
 *
 * <p>Lifecycle (AC6): unlike the terminal stream's process-lifetime controller,
 * this feed is foreground-bound. The screen drives [connect] on
 * {@code Lifecycle.State.STARTED} and [disconnect] on STOP via
 * {@code repeatOnLifecycle}, so the socket is never held open behind the user's
 * back. [close] cancels the controller scope for good (ViewModel teardown).
 *
 * <p>Disconnect resilience (AC5): a dropped feed simply backs off and reconnects
 * (re-syncing via the fresh Snapshot); if the cumulative cap is hit the loop
 * ends quietly — the sessions screen's REST refresh (entry + (re)START) is the
 * fallback, and a later (re)START reconnects. No crash, no spinner ownership.
 */
class SessionEventsController(
    private val profileStore: ServerProfileStore,
    private val httpClientFactory: (ServerProfile) -> AiSandboxHttpClient,
    private val eventsClientFactory: (AiSandboxHttpClient) -> SessionEventsClient,
    private val onSnapshot: (List<SessionSummary>) -> Unit,
    private val onDelta: (upserts: List<SessionSummary>, removed: List<Int>) -> Unit,
    // UC-70 — feed-status sink (mirrors onSnapshot/onDelta): the connect loop
    // pushes a SessionsFeedStatus on every meaningful transition so the sessions
    // list can render the "Not connected, retrying…" background. Defaulted to a
    // no-op so pre-UC-70 callers/tests keep compiling.
    private val onStatus: (SessionsFeedStatus) -> Unit = {},
    // UC-92 — fast-recovery hook fired exactly once when a HEALTHILY-OPEN socket
    // drops for a non-identity reason (a *transient* drop). The ViewModel wires
    // this to an immediate REST refresh so the list repaints from the
    // authoritative GET at once (AC4/AC5) instead of waiting out the back-off.
    // Defaulted to a no-op so pre-UC-92 callers/tests keep compiling. Safety
    // (AC6): a genuine cold outage never reaches Open, so this can never fire
    // there ⇒ it cannot turn an outage into a tight retry loop.
    private val onTransientDrop: () -> Unit = {},
    // UC-70 — a SINGLE shared clock instance, handed to BOTH this controller and
    // its [ReconnectController], so the next-retry/give-up instants the status
    // carries are computed against the same `now` the back-off schedules against
    // (no drift between two clocks). Tests inject a fake to drive deterministic
    // timing.
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val reconnect = ReconnectController(nowMs = nowMs)
    private var eventsClient: SessionEventsClient? = null
    private var connectJob: Job? = null

    /** Start (or no-op resume) the connect/reconnect loop. Idempotent — called on each foreground START. */
    fun connect() {
        if (connectJob?.isActive == true) return
        startConnectLoop()
    }

    /**
     * Foreground STOP — close the socket and stop the loop, but keep the
     * controller (and its scope) alive so the next [connect] can resume. Resets
     * the back-off so a re-START reconnects immediately.
     */
    fun disconnect(reason: String = "lifecycle-stop") {
        connectJob?.cancel()
        connectJob = null
        eventsClient?.close(reason)
        eventsClient = null
        reconnect.reset()
    }

    /** Permanent teardown (ViewModel.onCleared) — cancels the controller scope. */
    fun close(reason: String = "controller-close") {
        disconnect(reason)
        scope.cancel()
    }

    private fun startConnectLoop() {
        // UC-88 (challenger re-review) — NEVER relaunch over a connect that is still
        // healthily inside its handshake window (Connecting) or already Open under an
        // ACTIVE loop. Force-cancelling such a connect as part of a "relaunch" would
        // let rapid chat→list bouncing thrash a slow-but-progressing connect
        // (connect→cancel→reconnect→cancel…) and starve it from ever completing. The
        // relaunch-cancel below is reserved for an ACTUAL new attempt / teardown,
        // where the prior client is orphaned (its loop already ended) or has genuinely
        // failed. (connect() already no-ops while a loop is active; this encodes that
        // contract directly in the loop starter so it holds for every caller.)
        if (connectJob?.isActive == true) {
            when (eventsClient?.state?.value) {
                is SessionEventsClient.State.Connecting,
                is SessionEventsClient.State.Open -> return
                else -> { /* active loop but the client failed/terminal — relaunch is fine */ }
            }
        }
        connectJob?.cancel()
        // Cancelling the coroutine does NOT cancel the OkHttp socket it owns, so
        // force-drop the prior/orphaned client BEFORE relaunching — a graceful close
        // alone lets a half-open socket linger 30–60 s and pile up past the server's
        // per-fingerprint cap. This close is intentional (quiescent onFailure) and,
        // per the guard above, only ever runs on a terminal/orphaned client.
        eventsClient?.close("reconnect")
        eventsClient = null
        connectJob = scope.launch {
            val profile = profileStore.current()
            if (profile == null) {
                Log.i(TAG, "no profile — events feed idle")
                // UC-70 hard-req #5 — the no-profile branch emits NOTHING; the
                // feed stays silent so an unconfigured client never shows the
                // retrying background.
                return@launch
            }
            val http = httpClientFactory(profile)

            while (isActive) {
                // UC-72 loop-top dial emit — fired before EVERY client.connect()
                // to mark the attempt as in-flight (activity = ATTEMPTING → the
                // status dot reads yellow). This replaces UC-70's
                // attemptCount==0-only CONNECTING emit; the anti-flicker contract
                // is preserved by keeping the PHASE stable across the cycle:
                //
                //  • attempt 0 (initial connect, no failure yet) → phase
                //    CONNECTING — still the silent, non-retrying phase UC-70's
                //    background ignores, so an initial connect never flashes the
                //    "retrying" surface. Defaults (attempt 0, both instants null).
                //  • attempt > 0 (a reconnect dial) → phase RECONNECTING, HELD
                //    from the preceding WAITING emit so UC-70's background never
                //    flickers away mid-sequence. We carry the SAME attempt and
                //    giveUpAtMs the WAITING emit carried (so UC-70's "attempt N" /
                //    give-up lines stay stable), and only nextRetryAtMs flips to
                //    null — there is no scheduled next retry while the dial is in
                //    flight, so the countdown hides during the attempt. We never
                //    re-emit CONNECTING once a failure has been recorded.
                if (reconnect.attemptCount == 0) {
                    onStatus(
                        SessionsFeedStatus(
                            phase = SessionsFeedStatus.Phase.CONNECTING,
                            activity = SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
                            attempt = 0,
                            nextRetryAtMs = null,
                            giveUpAtMs = null,
                        ),
                    )
                } else {
                    onStatus(
                        SessionsFeedStatus(
                            phase = SessionsFeedStatus.Phase.RECONNECTING,
                            activity = SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
                            attempt = reconnect.attemptCount,
                            // null during the dial — no next retry is scheduled
                            // while the attempt is in flight (countdown hides).
                            nextRetryAtMs = null,
                            giveUpAtMs = reconnect.giveUpAtMs(),
                        ),
                    )
                }
                val client = eventsClientFactory(http)
                eventsClient = client
                try {
                    client.connect()
                } catch (t: Throwable) {
                    Log.w(TAG, "events connect threw: $t")
                }

                when (client.state.value) {
                    is SessionEventsClient.State.Open -> {
                        reconnect.reset()
                        // UC-70 — the socket is up: clear any retrying background.
                        // UC-72 — activity IDLE: the dot returns to the REST ladder.
                        onStatus(
                            SessionsFeedStatus(
                                phase = SessionsFeedStatus.Phase.CONNECTED,
                                activity = SessionsFeedStatus.ReconnectActivity.IDLE,
                            ),
                        )
                        // The server pushes a fresh Snapshot immediately on
                        // connect, so collecting incoming is the whole job —
                        // the resync happens for free (AC5).
                        val pump = launch {
                            client.incoming.collect { msg ->
                                when (msg) {
                                    is SessionEventMessage.Snapshot -> onSnapshot(msg.sessions)
                                    is SessionEventMessage.Delta -> onDelta(msg.upserts, msg.removed)
                                }
                            }
                        }
                        val terminal = client.state.first { it !is SessionEventsClient.State.Open }
                        pump.cancel()
                        if (terminal is SessionEventsClient.State.Revoked) {
                            // CertRevoked already emitted by the client → root
                            // composable routes to the dialog; stop the loop.
                            return@launch
                        }
                        // UC-92 — the socket had been healthily Open and then
                        // dropped for a non-identity reason (a TRANSIENT drop:
                        // delete-induced connection churn, back-nav STOP/START,
                        // or a flaky link). Kick an immediate REST refresh so the
                        // list repaints from the authoritative GET at once rather
                        // than waiting out the exponential back-off (AC4/AC5). The
                        // back-off was already reset by reconnect.reset() on Open
                        // above, so the next reconnect dial fires at ~1s. This
                        // only runs on the Open→non-Revoked terminal edge, so a
                        // genuine cold outage (which never Opens) can't reach it
                        // ⇒ no tight retry loop (AC6 preserved).
                        onTransientDrop()
                    }

                    is SessionEventsClient.State.Revoked -> return@launch

                    else -> {
                        // Failed to open — fall through to the back-off.
                    }
                }

                if (!isActive) break
                // UC-88 — if the server explicitly refused the feed (per-client cap
                // SERVICE_OVERLOAD / POLICY_VIOLATION) log it distinctly, so a wedge
                // caused by overload is visible in the logs instead of looking like an
                // ordinary transient drop. We still honour the unlimited-retry
                // contract (UC-70/71/72) and back off below — we just don't pretend a
                // refusal was silent.
                (client.state.value as? SessionEventsClient.State.Disconnected)
                    ?.let { logServerRefusalIfAny(it.reason) }
                // UC-71 — this give-up branch only fires under an injected finite
                // retry budget; with the unlimited default ctor it is unreachable.
                if (reconnect.shouldGiveUp()) {
                    // Cumulative cap hit — defer to the REST fallback (AC5). A
                    // later foreground (re)START reconnects with a fresh resync.
                    Log.i(TAG, "events feed gave up after back-off cap; REST refresh is the fallback")
                    // UC-70 hard-req #4 — terminal give-up: a static "Not
                    // connected" background, no countdown.
                    // UC-72 — activity IDLE; the dot's STOPPED→BACKOFF/red arm is
                    // keyed off phase, not activity.
                    onStatus(
                        SessionsFeedStatus(
                            phase = SessionsFeedStatus.Phase.STOPPED,
                            activity = SessionsFeedStatus.ReconnectActivity.IDLE,
                        ),
                    )
                    return@launch
                }
                // UC-70 hard-req #2 — advance the schedule FIRST (nextDelayMs()
                // records the first-failure instant + bumps attemptCount), THEN
                // emit RECONNECTING so attempt / nextRetryAtMs / giveUpAtMs are
                // all the post-increment values. nextRetryAtMs and giveUpAtMs are
                // computed off the SAME shared `nowMs` clock the controller uses.
                val delayMs = reconnect.nextDelayMs()
                onStatus(
                    SessionsFeedStatus(
                        phase = SessionsFeedStatus.Phase.RECONNECTING,
                        // UC-72 — WAITING: sitting out the back-off delay before
                        // the next attempt; the status dot reads red. The next
                        // loop-top ATTEMPTING emit (same attempt / giveUpAtMs)
                        // flips it back to yellow and hides the countdown.
                        activity = SessionsFeedStatus.ReconnectActivity.WAITING,
                        attempt = reconnect.attemptCount,
                        nextRetryAtMs = nowMs() + delayMs,
                        giveUpAtMs = reconnect.giveUpAtMs(),
                    ),
                )
                delay(delayMs)
            }
        }
    }

    /**
     * UC-88 — log a server-initiated *refusal* close distinctly. The client
     * encodes server closes as `"$code:$reason"` in [SessionEventsClient.State.Disconnected];
     * a SERVICE_OVERLOAD (per-fingerprint cap) or POLICY_VIOLATION means the
     * server is actively rejecting us, which is the wedge-by-overload symptom of
     * this UC — surface it instead of letting it read as a routine drop. Does NOT
     * change the back-off behaviour (the unlimited-retry contract stands).
     */
    private fun logServerRefusalIfAny(reason: String) {
        when {
            reason.startsWith("${SessionEventsClient.SERVICE_OVERLOAD_CLOSE_CODE}:") ->
                Log.w(TAG, "events feed refused by server — SERVICE_OVERLOAD (per-client cap): $reason; backing off, not hammering")
            reason.startsWith("${SessionEventsClient.POLICY_VIOLATION_CLOSE_CODE}:") ->
                Log.w(TAG, "events feed refused by server — POLICY_VIOLATION: $reason")
        }
    }

    companion object {
        private const val TAG = "SessionEventsCtrl"
    }
}
