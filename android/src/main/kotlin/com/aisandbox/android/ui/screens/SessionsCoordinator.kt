package com.aisandbox.android.ui.screens

import android.util.Log
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.SessionsApi
import com.aisandbox.android.net.TlsFailureTranslation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Android-free orchestration core for the sessions-list screen
 * (create / list / delete).
 *
 * <p>Extracted from [SessionsViewModel] so the logic is unit-testable on a
 * pure JVM (JUnit 5) without Robolectric. The three Android touch points
 * are constructor-injected:
 *
 * <ul>
 *   <li>[profileSupplier] — reads the active [ServerProfile]
 *       (prod: `profileStore::current`). This is the single source of
 *       truth for the profile on EVERY operation — see the BUG 1 note on
 *       [spawn].</li>
 *   <li>[apiFactory] — builds a [SessionsApi] bound to a profile
 *       (prod: `{ container.sessionsApi(container.httpClient(it)) }`).</li>
 *   <li>[scope] — the coroutine scope work launches on
 *       (prod: `viewModelScope`).</li>
 * </ul>
 *
 * <p>All mutations target the injected [state] flow, which the ViewModel
 * exposes read-only to Compose. The coordinator only references
 * `android.util.Log`, which returns no-op defaults under
 * `testOptions.unitTests.isReturnDefaultValues = true` — so unit tests
 * need no Robolectric.
 */
class SessionsCoordinator(
    private val state: MutableStateFlow<SessionsUiState>,
    private val scope: CoroutineScope,
    private val profileSupplier: suspend () -> ServerProfile?,
    private val apiFactory: (ServerProfile) -> SessionsApi,
) {

    fun refresh() {
        scope.launch {
            state.value = state.value.copy(loading = true, lastError = null)
            val profile = profileSupplier()
            if (profile == null) {
                state.value = SessionsUiState(loading = false, lastError = "no_profile")
                return@launch
            }
            // BUG 1 (top-bar host display) — publish the profile as soon as
            // the store read returns, INDEPENDENT of whether the list call
            // succeeds. The top bar reads `state.profile?.serverUrl`; before
            // this fix the profile was only set on a successful list, so a
            // failed first fetch (e.g. the Fix B decode bug) left the host
            // line blank.
            state.value = state.value.copy(profile = profile)
            when (val r = apiFactory(profile).list()) {
                is ApiResult.Success -> {
                    state.value = state.value.copy(
                        loading = false,
                        sessions = r.value,
                        profile = profile,
                    )
                }
                is ApiResult.HttpFailure -> {
                    state.value = state.value.copy(
                        loading = false,
                        lastError = "${r.code} (${r.status})",
                    )
                }
            }
        }
    }

    fun selectFilter(filter: SessionsFilter) {
        state.value = state.value.copy(filter = filter)
    }

    fun spawn(label: String?) {
        if (state.value.spawning) return
        scope.launch {
            state.value = state.value.copy(spawning = true, lastError = null)
            // Optimistic insertion (AC9) — append a synthetic "starting"
            // row immediately so the FAB tap feels instant. The next
            // refresh reconciles N + uptime.
            val optimisticN = (state.value.sessions.maxOfOrNull { it.n } ?: 0) + 1
            val optimistic = SessionSummary(
                n = optimisticN,
                label = label.orEmpty(),
                tmuxTitle = "",
                state = "starting",
                uptimeSec = 0L,
                activeStreams = 0,
                startedAt = null,
            )
            state.value = state.value.copy(sessions = state.value.sessions + optimistic)

            // BUG 1 (root) — source the profile from the supplier (the same
            // source refresh()/delete() use), NOT from state.profile.
            // state.profile was null whenever the initial list fetch failed,
            // so the FAB tap silently no-op'd while STILL leaking the
            // optimistic "starting" row. Roll that row back on the early
            // no-profile return so the phantom can't persist.
            val profile = profileSupplier() ?: run {
                state.value = state.value.copy(
                    spawning = false,
                    sessions = state.value.sessions.filterNot { it.n == optimisticN && it === optimistic },
                    lastError = "no_profile",
                )
                return@launch
            }
            when (val r = apiFactory(profile).spawn(label)) {
                is ApiResult.Success -> {
                    // Replace the optimistic row with the server's
                    // authoritative summary on the next refresh.
                    refresh()
                }
                is ApiResult.HttpFailure -> {
                    // Roll back the optimistic insertion + surface the error.
                    state.value = state.value.copy(
                        sessions = state.value.sessions.filterNot { it.n == optimisticN && it === optimistic },
                        lastError = "${r.code} (${r.status})",
                    )
                }
            }
            state.value = state.value.copy(spawning = false)
        }
    }

    fun delete(n: Int, force: Boolean) {
        scope.launch {
            val profile = profileSupplier() ?: run {
                // Match refresh()/spawn() — surface the missing-profile case
                // rather than silently no-op'ing (AC5).
                state.value = state.value.copy(lastError = "no_profile")
                return@launch
            }
            try {
                when (val r = apiFactory(profile).delete(n, force)) {
                    is ApiResult.Success -> refresh()
                    is ApiResult.HttpFailure -> {
                        // The headline AC5 path: a non-204 (404 / 500 / …) is
                        // no longer a silent no-op — surface code + status.
                        Log.w(TAG, "Delete $n failed: ${r.code} (${r.status}) ${r.detail}")
                        state.value = state.value.copy(lastError = "${r.code} (${r.status})")
                    }
                }
            } catch (t: Throwable) {
                // MANDATORY — delete() previously had no try/catch, so a
                // transport throw (connection drop, TLS) escaped uncaught on
                // viewModelScope (crash risk). The AiSandboxHttpClient
                // interceptor already TRANSLATED + EMITTED a NetworkEvent
                // (full-screen ServerIdentityChangedScreen) for any SSL/IO
                // failure before re-throwing, so we only raise a snackbar for
                // throwables it did NOT surface — avoids double-surfacing the
                // same error (AC5).
                Log.w(TAG, "Delete $n threw: ${t.message}", t)
                val host = profile.serverUrl
                    .substringAfter("://")
                    .substringBefore('/')
                    .substringBefore(':')
                if (TlsFailureTranslation.translate(t, profile.pinSha256Hex, host) == null) {
                    state.value = state.value.copy(lastError = t.message ?: "delete_failed")
                }
            }
        }
    }

    /**
     * Clear the surfaced error after the screen has shown it. Required
     * because [lastError] is a plain StateFlow value — without resetting it
     * to null, a repeat same-code failure would not re-trigger the screen's
     * snackbar effect (its key would be unchanged).
     */
    fun clearError() {
        state.value = state.value.copy(lastError = null)
    }

    companion object {
        private const val TAG = "SessionsCoord"
    }
}
