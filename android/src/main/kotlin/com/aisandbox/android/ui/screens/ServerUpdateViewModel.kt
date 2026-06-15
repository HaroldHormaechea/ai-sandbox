package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UC-84 — ViewModel for the Settings "Server updates" section. A thin Android
 * wrapper (mirrors [SessionsViewModel]): owns the [MutableStateFlow], wires the
 * coordinator's Android dependencies, and exposes the read-only [state] plus
 * pass-through actions to Compose. All orchestration lives in the JVM-testable
 * [ServerUpdateCoordinator].
 *
 * <p>Does NOT check on construction — the check is user-triggered only (AC3).
 */
class ServerUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container

    private val _state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
    val state: StateFlow<ServerUpdateUiState> = _state.asStateFlow()

    private val coordinator = ServerUpdateCoordinator(
        state = _state,
        scope = viewModelScope,
        apiSupplier = {
            container.profileStore.current()?.let { profile ->
                container.serverUpdateApi(container.httpClient(profile))
            }
        },
    )

    /** AC3 — user tapped "Look for server updates". */
    fun check() = coordinator.check()

    /** AC7 — user tapped "Update to version X" → raise the confirm dialog. */
    fun requestConfirm() = coordinator.requestConfirm()

    /** AC7 — user dismissed the confirm dialog. */
    fun cancelConfirm() = coordinator.cancelConfirm()

    /** AC7/AC9 — user confirmed → apply + poll healthz + report. */
    fun confirmApply() = coordinator.confirmApply()

    /** Return to the explicit-check entry state. */
    fun reset() = coordinator.reset()
}
