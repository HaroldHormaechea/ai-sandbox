package com.aisandbox.android.ui.screens

import android.util.Log
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.ReconnectController
import com.aisandbox.android.net.ServerUpdateApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * UC-84 — the discrete states of the Settings "Server updates" section. A
 * single [MutableStateFlow] drives the UI (mirrors the sessions-screen
 * one-StateFlow contract).
 */
sealed interface ServerUpdateUiState {
    /** Nothing requested yet — the explicit "Look for server updates" button is shown (AC3). */
    data object Idle : ServerUpdateUiState

    /** A check is in flight. */
    data object Checking : ServerUpdateUiState

    /** Checked: the server is already current (AC6). */
    data class UpToDate(val current: String) : ServerUpdateUiState

    /** Checked: a newer release exists (AC6). [releaseHtmlUrl] backs the external-browser Changelog link. */
    data class UpdateAvailable(val current: String, val latest: String, val releaseHtmlUrl: String?) :
        ServerUpdateUiState

    /** The in-app confirmation dialog is up (AC7); carries the same fields as [UpdateAvailable]. */
    data class Confirming(val current: String, val latest: String, val releaseHtmlUrl: String?) :
        ServerUpdateUiState

    /** Apply accepted; polling /v1/healthz while the server installs + restarts (AC9). */
    data class Updating(val target: String?) : ServerUpdateUiState

    /** The server is back; [newVersion] is its now-running version (AC9). */
    data class Done(val newVersion: String) : ServerUpdateUiState

    /** A check/apply failed, or the server did not return in time. Surfaced without taking anything down (AC14). */
    data class Error(val code: String, val detail: String) : ServerUpdateUiState
}

/**
 * UC-84 — Android-free orchestration core for the Settings "Server updates"
 * section: check → (confirm) → apply → poll healthz → report.
 *
 * <p>Extracted from [ServerUpdateViewModel] so the flow is unit-testable on a
 * pure JVM (no Robolectric), exactly like [SessionsCoordinator]. The Android
 * touch points are constructor-injected:
 *
 * <ul>
 *   <li>[apiSupplier] — builds a [ServerUpdateApi] bound to the active profile,
 *       or {@code null} when no profile is enrolled.</li>
 *   <li>[scope] — the coroutine scope work launches on (prod: {@code viewModelScope}).</li>
 *   <li>[delayFn] / [newReconnectController] — time seams so a test drives the
 *       healthz poll without real waits.</li>
 * </ul>
 */
class ServerUpdateCoordinator(
    private val state: MutableStateFlow<ServerUpdateUiState>,
    private val scope: CoroutineScope,
    private val apiSupplier: suspend () -> ServerUpdateApi?,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val maxHealthPolls: Int = 90,
    private val newReconnectController: () -> ReconnectController = { ReconnectController() },
) {

    /** AC3 — explicit, user-triggered check (never auto-run on screen open). */
    fun check() {
        scope.launch {
            state.value = ServerUpdateUiState.Checking
            val api = apiSupplier()
            if (api == null) {
                state.value = ServerUpdateUiState.Error("no_profile", "No server profile is enrolled.")
                return@launch
            }
            try {
                when (val r = api.check()) {
                    is ApiResult.Success -> {
                        val v = r.value
                        state.value = if (v.updateAvailable && v.latestVersion != null) {
                            ServerUpdateUiState.UpdateAvailable(v.currentVersion, v.latestVersion, v.releaseHtmlUrl)
                        } else {
                            ServerUpdateUiState.UpToDate(v.currentVersion)
                        }
                    }
                    is ApiResult.HttpFailure ->
                        state.value = ServerUpdateUiState.Error(r.code, "${r.detail} (${r.status})")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "update check threw: ${t.message}", t)
                state.value = ServerUpdateUiState.Error("unreachable", t.message ?: "Server unreachable")
            }
        }
    }

    /** AC7 — raise the confirmation dialog. No-op unless an update is currently offered. */
    fun requestConfirm() {
        val s = state.value
        if (s is ServerUpdateUiState.UpdateAvailable) {
            state.value = ServerUpdateUiState.Confirming(s.current, s.latest, s.releaseHtmlUrl)
        }
    }

    /** AC7 — dismiss the confirmation dialog without applying. */
    fun cancelConfirm() {
        val s = state.value
        if (s is ServerUpdateUiState.Confirming) {
            state.value = ServerUpdateUiState.UpdateAvailable(s.current, s.latest, s.releaseHtmlUrl)
        }
    }

    /**
     * AC7/AC9 — only on explicit confirm: call apply, then poll /v1/healthz
     * (ReconnectController cadence) until the server is back, then re-check and
     * report the now-running version.
     */
    fun confirmApply() {
        val confirming = state.value as? ServerUpdateUiState.Confirming
        val target = confirming?.latest
        scope.launch {
            state.value = ServerUpdateUiState.Updating(target)
            val api = apiSupplier()
            if (api == null) {
                state.value = ServerUpdateUiState.Error("no_profile", "No server profile is enrolled.")
                return@launch
            }
            val applied = try {
                api.apply()
            } catch (t: Throwable) {
                Log.w(TAG, "update apply threw: ${t.message}", t)
                state.value = ServerUpdateUiState.Error("unreachable", t.message ?: "Server unreachable")
                return@launch
            }
            when (applied) {
                is ApiResult.HttpFailure -> {
                    state.value = ServerUpdateUiState.Error(applied.code, "${applied.detail} (${applied.status})")
                    return@launch
                }
                is ApiResult.Success -> {
                    // best-effort: prefer the server's reported target over the checked one
                    val tgt = applied.value.targetVersion ?: target
                    state.value = ServerUpdateUiState.Updating(tgt)
                    pollUntilBack(api, tgt)
                }
            }
        }
    }

    /** Return to the explicit-check entry state (after Done / Error / UpToDate). */
    fun reset() {
        state.value = ServerUpdateUiState.Idle
    }

    private suspend fun pollUntilBack(api: ServerUpdateApi, target: String?) {
        // Give the server a moment to actually drop its connection before the
        // first probe, so we don't read a 200 from the not-yet-restarted process.
        val backoff = newReconnectController()
        var attempts = 0
        while (attempts < maxHealthPolls) {
            attempts++
            delayFn(backoff.nextDelayMs())
            val back = try {
                api.healthz() is ApiResult.Success
            } catch (_: Throwable) {
                false // still restarting
            }
            if (back) {
                state.value = ServerUpdateUiState.Done(resolveRunningVersion(api, target))
                return
            }
        }
        state.value = ServerUpdateUiState.Error(
            "update_timeout",
            "The server did not come back online in time. It may still be updating — re-check shortly.",
        )
    }

    private suspend fun resolveRunningVersion(api: ServerUpdateApi, target: String?): String {
        return try {
            when (val r = api.check()) {
                is ApiResult.Success -> r.value.currentVersion.ifBlank { target ?: "" }
                is ApiResult.HttpFailure -> target ?: ""
            }
        } catch (_: Throwable) {
            target ?: ""
        }
    }

    private companion object {
        const val TAG = "ServerUpdateCoord"
    }
}
