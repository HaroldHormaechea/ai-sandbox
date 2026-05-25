package com.aisandbox.android.terminal

import android.content.Context
import android.util.Log
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.net.ReconnectController
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.net.StreamClient
import com.aisandbox.android.ui.screens.HapticEvent
import com.aisandbox.android.ui.screens.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UC-21 — process-scoped owner of one session's terminal stream.
 *
 * <p>Previously the [com.aisandbox.android.ui.screens.TerminalViewModel] owned
 * the [StreamClient] and reconnect loop, so back-navigation (which clears the
 * NavBackStackEntry-scoped ViewModel) tore the WebSocket down. UC-21 AC#8
 * requires the session to keep syncing after the back arrow, so ownership moves
 * here, to an object held by [com.aisandbox.android.AppContainer] for the
 * process lifetime.
 *
 * <p>The controller owns:
 * <ul>
 *   <li>the [StreamClient] (rebuilt per connect attempt);</li>
 *   <li>the reconnect loop, on an application-scoped [CoroutineScope] backed by
 *       a [SupervisorJob] that is cancelled in [close] — so it cannot outlive an
 *       explicit disconnect (challenger guardrail #2);</li>
 *   <li>the [WsTerminalSession] (and its `TerminalEmulator` screen buffer),
 *       which survives back-nav for continuity;</li>
 *   <li>the output→emulator pump and the target/selection state.</li>
 * </ul>
 *
 * Threading: the reconnect loop and the WS pump run on [Dispatchers.Default];
 * the emulator/view are only ever touched on the main thread (the session
 * marshals [WsTerminalSession.feed] internally).
 */
class TerminalStreamController(
    appContext: Context,
    val sessionN: Int,
    private val profileStore: ServerProfileStore,
    private val httpClientFactory: (ServerProfile) -> AiSandboxHttpClient,
    private val streamClientFactory: (AiSandboxHttpClient, Int) -> StreamClient,
    private val onClosed: (Int) -> Unit,
) {

    /** Application-scoped — cancelled only by [close]; never tied to a screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconnect = ReconnectController()

    private var streamClient: StreamClient? = null
    private var connectJob: Job? = null

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _haptic = MutableSharedFlow<HapticEvent>(replay = 0, extraBufferCapacity = 4)
    val haptic: SharedFlow<HapticEvent> = _haptic.asSharedFlow()

    /** Current rendered geometry, mirrored to the server PTY + the FGS notification. */
    private val _size = MutableStateFlow(TermSize(DEFAULT_COLS, DEFAULT_ROWS))
    val size: StateFlow<TermSize> = _size.asStateFlow()

    // ── Agent-switcher state (populated by the M3 enumerate/select protocol) ──
    private val _targets = MutableStateFlow<List<StreamTarget>>(emptyList())
    val targets: StateFlow<List<StreamTarget>> = _targets.asStateFlow()

    private val _selectedTargetId = MutableStateFlow(MAIN_TARGET_ID)
    val selectedTargetId: StateFlow<String> = _selectedTargetId.asStateFlow()

    /** The WS↔emulator adapter; the view binds to [WsTerminalSession.session]. */
    val wsSession: WsTerminalSession = WsTerminalSession(
        appContext = appContext,
        onStdin = { bytes -> sendStdin(bytes) },
        onResize = { cols, rows -> sendResize(cols, rows) },
        emitBell = { _haptic.tryEmit(HapticEvent.Bell) },
    )

    /** Start (or no-op resume) the connect/reconnect loop. Idempotent. */
    fun attach(n: Int) {
        require(n == sessionN) { "controller bound to $sessionN, attach($n)" }
        if (connectJob?.isActive == true) return
        startConnectLoop()
    }

    /** Send PTY stdin bytes (from the view or the modifier bar). */
    fun sendStdin(bytes: ByteArray) {
        streamClient?.sendStdin(bytes)
    }

    /** Send a resize frame and remember the geometry for reconnect + the FGS. */
    fun sendResize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        _size.value = TermSize(cols, rows)
        streamClient?.sendResize(cols, rows)
    }

    /**
     * Switch the streamed target mid-stream on the existing WebSocket (no
     * reconnect). The actual `select-target` control frame is wired in M3; for
     * now this records the selection so reconnect can re-apply it (AC#14).
     */
    fun selectTarget(targetId: String) {
        _selectedTargetId.value = targetId
        // M3: streamClient?.sendSelectTarget(targetId); then re-send resize.
        streamClient?.sendResize(_size.value.cols, _size.value.rows)
    }

    /** AC#25 "tap to reconnect" — reset back-off and restart the loop. */
    fun userTriggeredReconnect() {
        reconnect.reset()
        startConnectLoop()
    }

    /**
     * Explicit teardown (Disconnect / Delete / session switch). Cancels the
     * application scope so the reconnect loop and pump cannot leak past
     * disconnect, closes the WebSocket, and drops this controller from the
     * container's registry.
     */
    fun close(reason: String = "controller-close") {
        connectJob = null
        scope.cancel()
        streamClient?.close(reason)
        streamClient = null
        wsSession.shutdown()
        _state.value = TerminalState.Idle
        onClosed(sessionN)
    }

    private fun startConnectLoop() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val profile = profileStore.current()
            if (profile == null) {
                _state.value = TerminalState.Failed("no_profile")
                return@launch
            }
            val http = httpClientFactory(profile)

            while (isActive) {
                val client = streamClientFactory(http, sessionN)
                streamClient = client
                _state.value = TerminalState.Connecting
                try {
                    client.connect()
                } catch (t: Throwable) {
                    Log.w(TAG, "connect threw: $t")
                }

                when (client.state.value) {
                    is StreamClient.State.Open -> {
                        reconnect.reset()
                        _state.value = TerminalState.Open
                        // Re-assert geometry + selected target so a reconnect
                        // restores the prior view (AC#4 / AC#14).
                        val s = _size.value
                        client.sendResize(s.cols, s.rows)
                        if (_selectedTargetId.value != MAIN_TARGET_ID) {
                            // M3: client.sendSelectTarget(_selectedTargetId.value)
                        }
                        // Pump WS stdout into the emulator until the WS leaves Open.
                        val pump = launch { client.incoming.collect { wsSession.feed(it) } }
                        val terminal = client.state.first { it !is StreamClient.State.Open }
                        pump.cancel()
                        if (terminal is StreamClient.State.Revoked) {
                            _state.value = TerminalState.Revoked
                            return@launch
                        }
                        _state.value = TerminalState.Connecting
                    }

                    is StreamClient.State.Revoked -> {
                        _state.value = TerminalState.Revoked
                        return@launch
                    }

                    else -> {
                        // Failed to open — fall through to the reconnect back-off.
                    }
                }

                if (!isActive) break
                if (reconnect.shouldGiveUp()) {
                    _state.value = TerminalState.GaveUp
                    NetworkEvents.tryEmit(NetworkEvent.StreamGaveUp(client.streamId))
                    return@launch
                }
                val delayMs = reconnect.nextDelayMs()
                NetworkEvents.tryEmit(
                    NetworkEvent.StreamReconnecting(client.streamId, reconnect.attemptCount, delayMs),
                )
                _state.value = TerminalState.Reconnecting(reconnect.attemptCount, delayMs)
                delay(delayMs)
            }
        }
    }

    companion object {
        private const val TAG = "TerminalStreamCtrl"

        /** The always-present main-session target id (AC#10). */
        const val MAIN_TARGET_ID = "main"

        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}

/** Rendered terminal geometry. */
data class TermSize(val cols: Int, val rows: Int)

/**
 * A selectable stream target — the main session or one agent-team member.
 * Mirrors the subset of the server's `TargetInfo` the switcher UI needs
 * (fully populated by the M3 enumerate protocol).
 */
data class StreamTarget(
    val id: String,
    val kind: String,
    val title: String,
    val agentName: String? = null,
    val agentType: String? = null,
    val agentColor: String? = null,
    val teamName: String? = null,
)
