package com.aisandbox.android.ui.screens

import android.util.Log
import com.aisandbox.android.net.AiSandboxHttpClient
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
    }

    /** Permanent teardown (ViewModel.onCleared) — cancels the controller scope. */
    fun close(reason: String = "controller-close") {
        disconnect(reason)
        scope.cancel()
    }

    /**
     * UC-100 — subscribe the global `events` channel over the single shared
     * [com.aisandbox.android.net.MuxConnection] and reflect that connection's
     * state into [SessionsFeedStatus]. Reconnection + back-off are owned
     * centrally by the one connection (AC6 — provably one reconnect loop); this
     * controller no longer runs its own retry loop, rebuilds a socket, or holds a
     * [com.aisandbox.android.net.ReconnectController]. The `events` channel is
     * reference-counted in the manager, so the second consumer
     * ([com.aisandbox.android.notifications.PendingQuestionService]) shares the
     * same live channel rather than opening a second one.
     *
     * <p>The server pushes a fresh [SessionEventMessage.Snapshot] on every
     * (re)subscribe, so collecting [SessionEventsClient.incoming] is the whole
     * job — the resync happens for free (AC5). On an Open→drop edge we fire
     * [onTransientDrop] so the list repaints from the authoritative REST GET at
     * once (UC-92 AC4/AC5) instead of waiting for the shared connection's
     * back-off; a genuine cold outage never reaches Open, so this can't turn an
     * outage into a tight loop (AC6 preserved).
     */
    private fun startConnectLoop() {
        connectJob?.cancel()
        eventsClient?.close("reconnect")
        eventsClient = null
        connectJob = scope.launch {
            val profile = profileStore.current()
            if (profile == null) {
                Log.i(TAG, "no profile — events feed idle")
                // UC-70 hard-req #5 — the no-profile branch emits NOTHING.
                return@launch
            }
            val http = httpClientFactory(profile)
            val client = eventsClientFactory(http)
            eventsClient = client
            var wasOpen = false

            // Persistent incoming collector over the shared connection's stable
            // relay flow (survives the connection's own reconnects).
            launch {
                client.incoming.collect { msg ->
                    when (msg) {
                        is SessionEventMessage.Snapshot -> onSnapshot(msg.sessions)
                        is SessionEventMessage.Delta -> onDelta(msg.upserts, msg.removed)
                    }
                }
            }

            // Subscribe the events channel over the shared socket.
            launch { client.connect() }

            // Reflect the shared connection's state into the feed-status dot.
            client.state.collect { s ->
                when (s) {
                    is SessionEventsClient.State.Open -> {
                        wasOpen = true
                        onStatus(
                            SessionsFeedStatus(
                                phase = SessionsFeedStatus.Phase.CONNECTED,
                                activity = SessionsFeedStatus.ReconnectActivity.IDLE,
                            ),
                        )
                    }
                    is SessionEventsClient.State.Connecting ->
                        onStatus(
                            SessionsFeedStatus(
                                phase = SessionsFeedStatus.Phase.CONNECTING,
                                activity = SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
                                attempt = 0,
                                nextRetryAtMs = null,
                                giveUpAtMs = null,
                            ),
                        )
                    is SessionEventsClient.State.Disconnected -> {
                        onStatus(
                            SessionsFeedStatus(
                                phase = SessionsFeedStatus.Phase.RECONNECTING,
                                activity = SessionsFeedStatus.ReconnectActivity.WAITING,
                                attempt = 1,
                                // Timing is owned by the shared connection's one
                                // ReconnectController; no per-controller countdown.
                                nextRetryAtMs = null,
                                giveUpAtMs = null,
                            ),
                        )
                        // UC-92 — Open→drop for a non-identity reason: kick a REST
                        // refresh once so the list repaints from the authoritative
                        // GET immediately. Guarded by wasOpen so a cold outage
                        // (never Open) can't reach it.
                        if (wasOpen) {
                            wasOpen = false
                            onTransientDrop()
                        }
                    }
                    // CertRevoked is emitted by the client → root composable routes
                    // to the dialog; nothing to do here.
                    is SessionEventsClient.State.Revoked -> { /* handled by the bus */ }
                    is SessionEventsClient.State.Idle -> { /* not yet subscribed */ }
                }
            }
        }
    }

    companion object {
        private const val TAG = "SessionEventsCtrl"
    }
}
