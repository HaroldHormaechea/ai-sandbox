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
        connectJob?.cancel()
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
                // UC-70 hard-req #1 (anti-flicker) — emit CONNECTING (a silent,
                // non-retrying phase) ONLY before the first failure
                // (attemptCount == 0). Once any failure has been recorded the
                // back-off has already emitted RECONNECTING, and we must HOLD it
                // across the wait + the next attempt — never re-emit CONNECTING
                // mid-sequence, or the background would flicker away and back.
                if (reconnect.attemptCount == 0) {
                    onStatus(SessionsFeedStatus(phase = SessionsFeedStatus.Phase.CONNECTING))
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
                        onStatus(SessionsFeedStatus(phase = SessionsFeedStatus.Phase.CONNECTED))
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
                    }

                    is SessionEventsClient.State.Revoked -> return@launch

                    else -> {
                        // Failed to open — fall through to the back-off.
                    }
                }

                if (!isActive) break
                // UC-71 — this give-up branch only fires under an injected finite
                // retry budget; with the unlimited default ctor it is unreachable.
                if (reconnect.shouldGiveUp()) {
                    // Cumulative cap hit — defer to the REST fallback (AC5). A
                    // later foreground (re)START reconnects with a fresh resync.
                    Log.i(TAG, "events feed gave up after back-off cap; REST refresh is the fallback")
                    // UC-70 hard-req #4 — terminal give-up: a static "Not
                    // connected" background, no countdown.
                    onStatus(SessionsFeedStatus(phase = SessionsFeedStatus.Phase.STOPPED))
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
                        attempt = reconnect.attemptCount,
                        nextRetryAtMs = nowMs() + delayMs,
                        giveUpAtMs = reconnect.giveUpAtMs(),
                    ),
                )
                delay(delayMs)
            }
        }
    }

    companion object {
        private const val TAG = "SessionEventsCtrl"
    }
}
