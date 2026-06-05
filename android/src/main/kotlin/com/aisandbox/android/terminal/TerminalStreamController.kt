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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    private val controlJson = Json { ignoreUnknownKeys = true; isLenient = true }

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
     * AC#11 — switch the streamed target mid-stream on the existing WebSocket
     * (no reconnect). Optimistically records the selection (the server confirms
     * with a {@code target-selected} frame) and re-sends resize so the new PTY
     * matches the rendered geometry.
     */
    fun selectTarget(targetId: String) {
        _selectedTargetId.value = targetId
        val client = streamClient ?: return
        client.sendSelectTarget(targetId)
        client.sendResize(_size.value.cols, _size.value.rows)
    }

    /** Parse a server control frame (targets / target-selected / error). */
    private fun onControlFrame(text: String) {
        val obj = runCatching { controlJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "targets" -> {
                val arr = obj["targets"] as? JsonArray ?: JsonArray(emptyList())
                _targets.value = arr.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    StreamTarget(
                        id = id,
                        kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "swarm",
                        title = o["title"]?.jsonPrimitive?.contentOrNull
                            ?: o["agentName"]?.jsonPrimitive?.contentOrNull ?: id,
                        agentName = o["agentName"]?.jsonPrimitive?.contentOrNull,
                        agentType = o["agentType"]?.jsonPrimitive?.contentOrNull,
                        agentColor = o["agentColor"]?.jsonPrimitive?.contentOrNull,
                        teamName = o["teamName"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                obj["selectedId"]?.jsonPrimitive?.contentOrNull?.let { _selectedTargetId.value = it }
            }
            "target-selected" ->
                obj["targetId"]?.jsonPrimitive?.contentOrNull?.let { _selectedTargetId.value = it }
            "error" -> Log.w(TAG, "stream control error frame: ${text.take(200)}")
            else -> { /* unknown frame type — ignore */ }
        }
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
                        // Re-assert geometry, refresh the target list, and
                        // re-apply any prior selection so a reconnect restores
                        // the prior view (AC#4 / AC#14).
                        val s = _size.value
                        client.sendResize(s.cols, s.rows)
                        client.sendEnumerate()
                        if (_selectedTargetId.value != MAIN_TARGET_ID) {
                            client.sendSelectTarget(_selectedTargetId.value)
                        }
                        // Pump WS stdout into the emulator + collect control
                        // frames until the WS leaves Open.
                        val pump = launch { client.incoming.collect { wsSession.feed(it) } }
                        val control = launch { client.controlIncoming.collect { onControlFrame(it) } }
                        // UC-24 / AC#12 — periodic re-enumeration so teammates that
                        // appear or disappear mid-stream are reflected without a
                        // reconnect. `_targets` already updates idempotently from
                        // each `targets` frame, so the switcher row reacts
                        // automatically. Scoped to this Open block and cancelled
                        // with pump/control on every reconnect/teardown so it can
                        // never leak or multiply across reconnects.
                        val enumerate = launch {
                            while (isActive) {
                                delay(ENUMERATE_INTERVAL_MS)
                                client.sendEnumerate()
                            }
                        }
                        val terminal = client.state.first { it !is StreamClient.State.Open }
                        pump.cancel()
                        control.cancel()
                        enumerate.cancel()
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

        /**
         * UC-24 / AC#12 — interval between mid-stream target re-enumerations so the
         * switcher row tracks teammates appearing/disappearing while connected.
         */
        const val ENUMERATE_INTERVAL_MS = 3_000L
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
