package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.terminal.service.TerminalForegroundService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UC-21 — thin adapter over the process-scoped [TerminalStreamController].
 *
 * <p>Before UC-21 this ViewModel owned the [com.aisandbox.android.net.StreamClient]
 * and the reconnect loop, so back-navigation (which clears this
 * NavBackStackEntry-scoped ViewModel) tore the WebSocket down. That ownership
 * now lives in the [TerminalStreamController] held by
 * [com.aisandbox.android.AppContainer], so the stream + emulator survive
 * back-navigation (AC#8). This ViewModel merely:
 *
 * <ul>
 *   <li>resolves the controller for the active session and starts its loop;</li>
 *   <li>re-publishes the controller's state/haptic/targets flows so the screen
 *       collectors are stable across controller swaps;</li>
 *   <li>forwards input (stdin / resize / target select / reconnect).</li>
 * </ul>
 *
 * <p>{@link #onCleared()} does <b>not</b> close the stream — only the proxy
 * collectors (cancelled automatically with {@code viewModelScope}). Explicit
 * teardown is the screen's Disconnect/Delete actions (which call
 * {@code controller.close(...)}).
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container

    private var sessionN: Int = -1
    private var controller: TerminalStreamController? = null

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _haptic = MutableSharedFlow<HapticEvent>(replay = 0, extraBufferCapacity = 4)
    val haptic: SharedFlow<HapticEvent> = _haptic.asSharedFlow()

    private val _targets = MutableStateFlow<List<StreamTarget>>(emptyList())
    val targets: StateFlow<List<StreamTarget>> = _targets.asStateFlow()

    private val _selectedTargetId = MutableStateFlow(TerminalStreamController.MAIN_TARGET_ID)
    val selectedTargetId: StateFlow<String> = _selectedTargetId.asStateFlow()

    /**
     * The current session's summary, used by the hamburger Delete action's
     * confirm dialog (the force toggle keys on `activeStreams`). Best-effort —
     * falls back to a minimal summary if the list call fails (AC#6).
     */
    private val _currentSummary = MutableStateFlow<SessionSummary?>(null)
    val currentSummary: StateFlow<SessionSummary?> = _currentSummary.asStateFlow()

    /** Resolve the controller for [sessionN] and (idempotently) start its loop. */
    fun attach(sessionN: Int) {
        if (this.sessionN == sessionN && controller != null) {
            controller?.attach(sessionN)
            return
        }
        this.sessionN = sessionN
        val c = container.terminalController(sessionN)
        controller = c
        c.attach(sessionN)
        // Mirror the controller's flows into stable, VM-owned flows.
        viewModelScope.launch { c.state.collect { _state.value = it } }
        viewModelScope.launch { c.haptic.collect { _haptic.tryEmit(it) } }
        viewModelScope.launch { c.targets.collect { _targets.value = it } }
        viewModelScope.launch { c.selectedTargetId.collect { _selectedTargetId.value = it } }
        viewModelScope.launch { refreshSummary(sessionN) }
    }

    private suspend fun refreshSummary(n: Int) {
        val profile = container.profileStore.current() ?: run {
            _currentSummary.value = SessionSummary(n = n)
            return
        }
        val api = container.sessionsApi(container.httpClient(profile))
        _currentSummary.value = when (val r = api.list()) {
            is ApiResult.Success -> r.value.firstOrNull { it.n == n } ?: SessionSummary(n = n)
            is ApiResult.HttpFailure -> SessionSummary(n = n)
        }
    }

    /** Send PTY stdin bytes (the modifier bar dispatches through here — AC#3). */
    fun sendStdin(bytes: ByteArray) {
        controller?.sendStdin(bytes)
    }

    /** AC#4 — forward a resize when the rendered geometry changes. */
    fun sendResize(cols: Int, rows: Int) {
        controller?.sendResize(cols, rows)
    }

    /** AC#11 — switch the streamed target (main ↔ agent). */
    fun selectTarget(targetId: String) {
        controller?.selectTarget(targetId)
    }

    /** AC#25 "tap to reconnect". */
    fun userTriggeredReconnect() {
        controller?.userTriggeredReconnect()
    }

    /**
     * AC#7 — Disconnect: tear down the stream + stop the foreground service.
     * No confirmation; the screen navigates back immediately after.
     */
    fun disconnect() {
        controller?.close("user-disconnect")
        controller = null
        sessionN = -1
        stopForegroundService()
    }

    /**
     * AC#6 — Delete the session on the server (reusing UC-20's force semantics),
     * then tear down the stream + foreground service. [onResult] is invoked with
     * the outcome on the main thread; the screen navigates back on success.
     */
    fun deleteSession(force: Boolean, onResult: (Boolean) -> Unit) {
        val n = sessionN
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                onResult(false)
                return@launch
            }
            val api = container.sessionsApi(container.httpClient(profile))
            val ok = api.delete(n, force) is ApiResult.Success
            if (ok) {
                controller?.close("deleted")
                controller = null
                sessionN = -1
                stopForegroundService()
            }
            onResult(ok)
        }
    }

    private fun stopForegroundService() {
        TerminalForegroundService.stop(getApplication<Application>())
    }

    // NOTE: onCleared() intentionally does NOT close the controller — the WS
    // and emulator are process-scoped now (AC#8). Only the proxy collectors die
    // with viewModelScope.
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
