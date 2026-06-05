package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    /**
     * UC-28 — true while this session is terminating, from the UNION of the
     * client-side optimistic flag (the process-scoped store, shared with the
     * sessions list and surviving back-navigation) and the server-reported
     * `terminating` status on the current summary. Drives the hamburger
     * "Delete session" item's `enabled = !isTerminating` guard (AC5) so a
     * teardown-in-progress session cannot be re-deleted (force included).
     */
    val terminating: StateFlow<Boolean> =
        combine(container.terminatingSessions.flow, _currentSummary) { set, summary ->
            val n = sessionN
            (n >= 0 && set.contains(n)) || summary?.state == "terminating"
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** UC-28 — in-flight re-entrancy guard so a second confirm can't stack a delete. */
    private var deleting = false

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

    /**
     * UC-28 — re-fetch the current session's summary when the hamburger menu
     * opens. This closes the one-shot-attach staleness window: a session that
     * went `terminating` (or vanished) on the server after the screen attached
     * — e.g. a delete from another client, or a cold resume — is reflected in
     * the Delete-item guard the moment the operator opens the menu.
     */
    fun onMenuOpened() {
        val n = sessionN
        if (n < 0) return
        viewModelScope.launch { refreshSummary(n) }
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
     * AC#7 — Disconnect: tear down the stream. No confirmation; the screen
     * navigates back immediately after. UC-34 — we no longer stop the foreground
     * service from here: `controller.close(...)` drives the controller's state to
     * Idle, and the running service self-stops by observing that transition
     * (background-legal, vs. the old `startService(ACTION_STOP)` that crashed when
     * disconnect happened while backgrounded).
     */
    fun disconnect() {
        controller?.close("user-disconnect")
        controller = null
        sessionN = -1
    }

    /**
     * AC#6 — Delete the session on the server (reusing UC-20's force semantics),
     * then tear down the stream + foreground service. [onResult] is invoked with
     * the outcome on the main thread; the screen navigates back on success.
     */
    fun deleteSession(force: Boolean, onResult: (Boolean) -> Unit) {
        val n = sessionN
        // UC-28 — re-entrancy guard: ignore a second confirm while a delete is
        // already in flight (also blocks the force path — same dialog, AC5).
        if (deleting) return
        if (terminating.value) {
            // Already terminating (server token or optimistic flag) — the menu
            // item is disabled, but short-circuit defensively too.
            onResult(false)
            return
        }
        deleting = true
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                deleting = false
                onResult(false)
                return@launch
            }
            // UC-28 AC2 — optimistic terminating before the call resolves; the
            // shared store carries it back to the sessions list on nav-back.
            container.terminatingSessions.mark(n)
            try {
                val api = container.sessionsApi(container.httpClient(profile))
                val ok = api.delete(n, force) is ApiResult.Success
                if (ok) {
                    // UC-34 — close() drives state → Idle and the running FGS
                    // self-stops by observing it (no UI-issued stopService, which
                    // crashed when the delete completed while backgrounded).
                    controller?.close("deleted")
                    controller = null
                    sessionN = -1
                } else {
                    // UC-28 AC8 — failure exits terminating; revert to real status.
                    container.terminatingSessions.clear(n)
                }
                onResult(ok)
            } catch (t: Throwable) {
                container.terminatingSessions.clear(n)
                onResult(false)
            } finally {
                deleting = false
            }
        }
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
