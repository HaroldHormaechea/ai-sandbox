package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 *
 * <p>This class is now a thin Android wrapper: all create / list / delete
 * orchestration lives in [SessionsCoordinator] (a plain, JVM-unit-testable
 * class). The wrapper owns the [MutableStateFlow], wires the coordinator's
 * three Android dependencies (profile supplier, API factory, coroutine
 * scope), and exposes the read-only [state] plus pass-through methods to
 * Compose.
 */
class SessionsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private val coordinator = SessionsCoordinator(
        state = _state,
        scope = viewModelScope,
        profileSupplier = { container.profileStore.current() },
        apiFactory = { profile -> container.sessionsApi(container.httpClient(profile)) },
        terminatingSessions = container.terminatingSessions,
    )

    init {
        refresh()
    }

    fun refresh() = coordinator.refresh()

    fun selectFilter(filter: SessionsFilter) = coordinator.selectFilter(filter)

    fun spawn(label: String?) = coordinator.spawn(label)

    fun delete(n: Int, force: Boolean) = coordinator.delete(n, force)

    fun clearError() = coordinator.clearError()
}

/** Read-only state surfaced to the Compose layer. */
data class SessionsUiState(
    val loading: Boolean = false,
    val spawning: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val filter: SessionsFilter = SessionsFilter.ALL,
    val profile: ServerProfile? = null,
    val lastError: String? = null,
    /**
     * UC-28 — session numbers the operator has optimistically marked as
     * terminating (delete confirmed, not yet resolved). Mirrored from the
     * process-scoped [com.aisandbox.android.net.TerminatingSessionsStore] by
     * [SessionsCoordinator] so the single-StateFlow render contract holds.
     */
    val terminating: Set<Int> = emptySet(),
) {
    /**
     * UC-28 — the effective state for display: the UNION of the client-side
     * optimistic flag (`row.n in terminating`) and the server-reported
     * `terminating` status. Either alone shows the "awaiting termination"
     * pill; everything else passes the real server state through.
     */
    fun effectiveState(row: SessionSummary): String =
        if (row.n in terminating || row.state == "terminating") "terminating" else row.state

    /** The list filtered + sorted by N for display (filter sees the effective state). */
    val visible: List<SessionSummary>
        get() = sessions.filter { filter.matches(effectiveState(it)) }.sortedBy { it.n }

    /** Counts for the chip badges — computed on the effective state (UC-28). */
    val countAll: Int get() = sessions.size
    val countRunning: Int
        get() = sessions.count {
            val s = effectiveState(it)
            s == "running" || s == "provisioning" || s == "starting" || s == "terminating"
        }
    val countStopped: Int get() = sessions.count { effectiveState(it) == "stopped" }
}

/** Filter chip selection. */
enum class SessionsFilter {
    ALL, RUNNING, STOPPED;

    /**
     * Bucket the (effective) state. UC-28 buckets `terminating` with RUNNING:
     * a session being torn down is still "active" from the operator's view and
     * must remain visible (with its pill) under the Running chip until the
     * teardown resolves.
     */
    fun matches(state: String): Boolean = when (this) {
        ALL -> true
        RUNNING -> state == "running" || state == "starting" || state == "provisioning" || state == "terminating"
        STOPPED -> state == "stopped"
    }
}
