package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.LifecycleAction
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

    /**
     * UC-32 — owns the foreground-bound live push feed. The controller routes
     * the server's Snapshot/Delta frames into the coordinator's pure apply
     * methods, so an inbound frame updates the same single [state] StateFlow the
     * screen renders (AC1/AC3) without a manual [refresh]. The screen drives
     * [connectEvents]/[disconnectEvents] by lifecycle (AC6).
     */
    private val eventsController = SessionEventsController(
        profileStore = container.profileStore,
        httpClientFactory = container::httpClient,
        eventsClientFactory = { client -> container.sessionEventsClient(client) },
        onSnapshot = { rows -> coordinator.applySnapshot(rows) },
        onDelta = { upserts, removed -> coordinator.applyDelta(upserts, removed) },
    )

    init {
        refresh()
    }

    fun refresh() = coordinator.refresh()

    fun selectFilter(filter: SessionsFilter) = coordinator.selectFilter(filter)

    fun spawn(label: String?) = coordinator.spawn(label)

    fun delete(n: Int, force: Boolean) = coordinator.delete(n, force)

    /** UC-46 — drive a Docker-lifecycle action (stop/start/pause/unpause). */
    fun lifecycle(n: Int, action: LifecycleAction) = coordinator.lifecycle(n, action)

    fun clearError() = coordinator.clearError()

    /** UC-32 — open the live push feed; driven by the screen on foreground START (AC6). */
    fun connectEvents() = eventsController.connect()

    /** UC-32 — close the live push feed; driven by the screen on foreground STOP (AC6). */
    fun disconnectEvents() = eventsController.disconnect()

    /**
     * UC-52 — full feed revival for the "reconnecting" banner's Retry action
     * (AC2/AC3). A bare [connectEvents] is NOT enough: once the UC-32 feed hits
     * its 5-min cumulative back-off cap, [SessionEventsController]'s loop returns
     * WITHOUT resetting its [com.aisandbox.android.net.ReconnectController]
     * (stale `firstFailureAtMs`), so a plain reconnect re-trips `shouldGiveUp()`
     * immediately and dies. [SessionEventsController.disconnect] calls
     * `reconnect.reset()`, so disconnect-then-connect revives the push feed
     * cleanly. Reuses the existing controller machinery — no parallel recovery
     * path. The screen pairs this with a [refresh] so REST recovery and feed
     * revival happen together.
     */
    fun reconnectEvents() {
        eventsController.disconnect("uc52-retry")
        eventsController.connect()
    }

    override fun onCleared() {
        // Permanent teardown of the push feed's scope when the ViewModel dies.
        eventsController.close()
        super.onCleared()
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
    /**
     * UC-52 — the server is transiently UNREACHABLE (a connectivity failure,
     * NOT a TLS/identity compromise): the sessions list shows an inline,
     * retryable, auto-recovering "reconnecting" banner instead of the
     * destructive ServerIdentityChangedScreen. Single-surface invariant
     * maintained by [SessionsCoordinator]: `unreachable` and [lastError] are
     * mutually exclusive — any operation that proves the server responded
     * (Success, an HTTP failure, or a UC-32 push frame) clears it (AC2/AC3).
     */
    val unreachable: Boolean = false,
    /**
     * UC-28 — session numbers the operator has optimistically marked as
     * terminating (delete confirmed, not yet resolved). Mirrored from the
     * process-scoped [com.aisandbox.android.net.TerminatingSessionsStore] by
     * [SessionsCoordinator] so the single-StateFlow render contract holds.
     */
    val terminating: Set<Int> = emptySet(),
    /**
     * UC-46 — session numbers with an in-flight lifecycle action
     * (stop/start/pause/unpause). While a row is here, AC6: the row shows a
     * pending treatment and its action control is disabled so the action
     * can't be double-fired. The set is cleared when the call resolves; the
     * authoritative state arrives on the next refresh / UC-32 push.
     */
    val pendingActions: Set<Int> = emptySet(),
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
            // UC-28 adds `terminating`; `starting` is intentionally EXCLUDED —
            // the UC-04/UC-27 badge contract counts running only (not starting),
            // even though the RUNNING filter view still buckets starting in.
            s == "running" || s == "provisioning" || s == "terminating"
        }
    // UC-46 — `paused` buckets with `stopped` under the Stopped chip (both are
    // non-running, resumable-or-restartable states the operator manages there).
    val countStopped: Int
        get() = sessions.count {
            val s = effectiveState(it)
            s == "stopped" || s == "paused"
        }

    /** UC-46 — whether a lifecycle action for [row] is currently in flight. */
    fun isPending(row: SessionSummary): Boolean = row.n in pendingActions
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
        // UC-46 — `paused` shows under Stopped alongside `stopped`.
        STOPPED -> state == "stopped" || state == "paused"
    }
}
