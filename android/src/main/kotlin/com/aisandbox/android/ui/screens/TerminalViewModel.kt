package com.aisandbox.android.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.net.ReconnectController
import com.aisandbox.android.net.StreamClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * UC04-3 terminal screen ViewModel — orchestrates one [StreamClient]
 * for the current session.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Build and own the per-session [StreamClient].</li>
 *   <li>Manage the AC24 reconnect loop via [ReconnectController]; emit
 *       [NetworkEvent.StreamReconnecting] / [NetworkEvent.StreamGaveUp]
 *       so the toolbar can render the AC24 indicator and the AC25
 *       "tap to reconnect" terminal state.</li>
 *   <li>Pipe the WS [StreamClient.incoming] flow into [output] for the
 *       terminal surface to consume.</li>
 *   <li>Send AC18 resize frames on `onResize(cols, rows)`.</li>
 *   <li>Detect the BEL (0x07) bytestream and emit a haptic event for
 *       AC14.</li>
 * </ul>
 *
 * <p>Does NOT own the foreground service — that's a separate flow
 * (TerminalForegroundService) which the screen starts on `onResume` and
 * stops on `onPause` if the WS isn't carrying us through the lock.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container
    private val reconnect = ReconnectController()

    /** Active session number, set once via [attach]. */
    private var sessionN: Int = -1
    private var streamClient: StreamClient? = null
    private var ioJob: Job? = null

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    /** Hot flow of PTY stdout bytes; the terminal surface consumes. */
    val output: SharedFlow<ByteArray>
        get() = _output.asSharedFlow()
    private val _output = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)

    /** Haptic events surfaced to the screen — currently only AC14 bell. */
    val haptic: SharedFlow<HapticEvent>
        get() = _haptic.asSharedFlow()
    private val _haptic = MutableSharedFlow<HapticEvent>(replay = 0, extraBufferCapacity = 4)

    fun attach(sessionN: Int) {
        if (this.sessionN == sessionN && streamClient != null) return
        this.sessionN = sessionN
        connectLoop()
    }

    private fun connectLoop() {
        ioJob?.cancel()
        ioJob = viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                _state.value = TerminalState.Failed("no_profile")
                return@launch
            }
            val client = container.streamClient(container.httpClient(profile), sessionN)
            streamClient = client

            while (true) {
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
                        // Pipe incoming bytes; BEL detection runs inline.
                        client.incoming.collectLatest { bytes ->
                            _output.tryEmit(bytes)
                            if (bytes.any { it == 0x07.toByte() }) {
                                _haptic.tryEmit(HapticEvent.Bell)
                            }
                        }
                        // Returns when the flow completes (close).
                        _state.value = TerminalState.Connecting
                        continue
                    }
                    is StreamClient.State.Revoked -> {
                        _state.value = TerminalState.Revoked
                        return@launch
                    }
                    else -> {
                        // Disconnected — fall through to reconnect.
                    }
                }
                if (reconnect.shouldGiveUp()) {
                    _state.value = TerminalState.GaveUp
                    NetworkEvents.tryEmit(NetworkEvent.StreamGaveUp(client.streamId))
                    return@launch
                }
                val delayMs = reconnect.nextDelayMs()
                NetworkEvents.tryEmit(
                    NetworkEvent.StreamReconnecting(client.streamId, reconnect.attemptCount, delayMs)
                )
                _state.value = TerminalState.Reconnecting(attempt = reconnect.attemptCount, nextDelayMs = delayMs)
                delay(delayMs)
            }
        }
    }

    /** Send PTY stdin bytes. */
    fun sendStdin(bytes: ByteArray) {
        streamClient?.sendStdin(bytes)
    }

    /** AC18 — fire a resize on viewport change. */
    fun sendResize(cols: Int, rows: Int) {
        streamClient?.sendResize(cols, rows)
    }

    /** AC25 "tap to reconnect" — reset the give-up state and re-run the loop. */
    fun userTriggeredReconnect() {
        reconnect.reset()
        connectLoop()
    }

    /** Close the stream cleanly (user navigated back or activity finished). */
    override fun onCleared() {
        super.onCleared()
        ioJob?.cancel()
        streamClient?.close("vm-cleared")
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}

/** UI state for the terminal screen. */
sealed interface TerminalState {
    data object Idle : TerminalState
    data object Connecting : TerminalState
    data object Open : TerminalState
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : TerminalState
    data object Revoked : TerminalState
    data object GaveUp : TerminalState
    data class Failed(val reason: String) : TerminalState
}

/** Haptic events the screen translates into Vibrator calls. */
sealed interface HapticEvent {
    /** AC14 — terminal BEL (0x07) → 150 ms vibration. */
    data object Bell : HapticEvent
}
