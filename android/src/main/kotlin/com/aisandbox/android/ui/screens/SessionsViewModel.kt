package com.aisandbox.android.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.SessionsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * UC04-2 sessions list ViewModel.
 *
 * <p>Holds:
 *
 * <ul>
 *   <li>The latest [SessionSummary] list (from `GET /v1/sessions`).</li>
 *   <li>The current filter chip selection (`All` / `Running` / `Stopped`).</li>
 *   <li>A transient "spawn in flight" flag so the bottom-sheet FAB
 *       doesn't double-fire.</li>
 *   <li>A nullable error code from the most recent call — surfaced as a
 *       toast / snackbar by the screen.</li>
 * </ul>
 *
 * <p>On entry, [refresh] is called once; the screen ALSO subscribes to
 * `Lifecycle.Event.ON_RESUME` and re-fetches when coming back from the
 * Terminal screen so a backgrounded list doesn't show stale state.
 */
class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, lastError = null)
            val profile = container.profileStore.current()
            if (profile == null) {
                _state.value = SessionsUiState(loading = false, lastError = "no_profile")
                return@launch
            }
            val client = container.httpClient(profile)
            val api = container.sessionsApi(client)
            when (val r = api.list()) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        sessions = r.value,
                        profile = profile,
                    )
                }
                is ApiResult.HttpFailure -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        lastError = "${r.code} (${r.status})",
                    )
                }
            }
        }
    }

    fun selectFilter(filter: SessionsFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    fun spawn(label: String?) {
        if (_state.value.spawning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(spawning = true, lastError = null)
            // Optimistic insertion (AC9) — append a synthetic "starting"
            // row immediately so the FAB tap feels instant. The next
            // refresh reconciles N + uptime.
            val optimisticN = (_state.value.sessions.maxOfOrNull { it.n } ?: 0) + 1
            val optimistic = SessionSummary(
                n = optimisticN,
                label = label.orEmpty(),
                tmuxTitle = "",
                state = "starting",
                uptimeSec = 0L,
                activeStreams = 0,
                startedAt = null,
            )
            _state.value = _state.value.copy(sessions = _state.value.sessions + optimistic)

            val profile = _state.value.profile ?: run {
                _state.value = _state.value.copy(spawning = false, lastError = "no_profile")
                return@launch
            }
            val api = container.sessionsApi(container.httpClient(profile))
            when (val r = api.spawn(label)) {
                is ApiResult.Success -> {
                    // Replace the optimistic row with the server's
                    // authoritative summary on the next refresh.
                    refresh()
                }
                is ApiResult.HttpFailure -> {
                    // Roll back the optimistic insertion + surface the error.
                    _state.value = _state.value.copy(
                        sessions = _state.value.sessions.filterNot { it.n == optimisticN && it === optimistic },
                        lastError = "${r.code} (${r.status})",
                    )
                }
            }
            _state.value = _state.value.copy(spawning = false)
        }
    }

    fun delete(n: Int, force: Boolean) {
        viewModelScope.launch {
            val profile = container.profileStore.current() ?: return@launch
            val api = container.sessionsApi(container.httpClient(profile))
            when (val r = api.delete(n, force)) {
                is ApiResult.Success -> refresh()
                is ApiResult.HttpFailure -> {
                    Log.w(TAG, "Delete $n failed: ${r.code} (${r.status}) ${r.detail}")
                    _state.value = _state.value.copy(lastError = "${r.code} (${r.status})")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SessionsVM"
    }
}

/** Read-only state surfaced to the Compose layer. */
data class SessionsUiState(
    val loading: Boolean = false,
    val spawning: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val filter: SessionsFilter = SessionsFilter.ALL,
    val profile: ServerProfile? = null,
    val lastError: String? = null,
) {
    /** The list filtered + sorted by N for display. */
    val visible: List<SessionSummary>
        get() = sessions.filter { filter.matches(it.state) }.sortedBy { it.n }

    /** Counts for the chip badges. */
    val countAll: Int get() = sessions.size
    val countRunning: Int get() = sessions.count { it.state == "running" }
    val countStopped: Int get() = sessions.count { it.state == "stopped" }
}

/** Filter chip selection. */
enum class SessionsFilter {
    ALL, RUNNING, STOPPED;

    fun matches(state: String): Boolean = when (this) {
        ALL -> true
        RUNNING -> state == "running" || state == "starting"
        STOPPED -> state == "stopped"
    }
}
