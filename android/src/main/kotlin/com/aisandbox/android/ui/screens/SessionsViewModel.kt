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
        // UC-70 — route feed connection-status transitions into the coordinator
        // so a disconnected/reconnecting feed surfaces the "Not connected,
        // retrying…" background on the same single [state] StateFlow.
        onStatus = { status -> coordinator.applyFeedStatus(status) },
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

    /**
     * UC-62 — create (or focus) the singleton server host-shell session and
     * open its terminal via [onReady]. Driven by the sessions-list top-bar
     * shell icon.
     */
    fun openServerSsh(onReady: (Int) -> Unit) = coordinator.openServerSsh(onReady)

    fun clearError() = coordinator.clearError()

    /** UC-32 — open the live push feed; driven by the screen on foreground START (AC6). */
    fun connectEvents() = eventsController.connect()

    /** UC-32 — close the live push feed; driven by the screen on foreground STOP (AC6). */
    fun disconnectEvents() = eventsController.disconnect()

    override fun onCleared() {
        // Permanent teardown of the push feed's scope when the ViewModel dies.
        eventsController.close()
        super.onCleared()
    }
}

/**
 * UC-54 — tri-state server connectivity for the Sessions-screen dot.
 * UNKNOWN and CHECKING both render yellow; they are kept distinct so the two
 * sub-cases (never-reached vs. a check in flight) are independently testable.
 */
enum class Connectivity { UNKNOWN, CHECKING, REACHABLE, UNREACHABLE }

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
     * UC-54 — true once the server has answered at least once this session
     * (any Success, any HTTP failure, or any UC-32 push frame). Lets the
     * tri-state [connectivity] dot tell "never reached yet" (UNKNOWN → yellow)
     * apart from "last interaction succeeded" (REACHABLE → green) when
     * [unreachable] is false. Set by [SessionsCoordinator] at every site where
     * the server demonstrably responded; never cleared once set.
     */
    val serverResponded: Boolean = false,
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
    /**
     * UC-70 — the live sessions-events feed's connection status, mirrored from
     * [SessionEventsController] by [SessionsCoordinator.applyFeedStatus]. Drives
     * the "Not connected, retrying…" background (via [showRetryingBackground]).
     * Defaults to a CONNECTED phase so the normal empty/list rendering holds
     * until a real disconnect occurs. This is deliberately INDEPENDENT of the
     * UC-54 [connectivity] dot (REST-derived) and the UC-52 [unreachable] banner
     * state — UC-70 owns only the list-background treatment.
     */
    val feedStatus: SessionsFeedStatus = SessionsFeedStatus(),
) {
    /**
     * UC-28 — the effective state for display: the UNION of the client-side
     * optimistic flag (`row.n in terminating`) and the server-reported
     * `terminating` status. Either alone shows the "awaiting termination"
     * pill; everything else passes the real server state through.
     */
    fun effectiveState(row: SessionSummary): String =
        if (row.n in terminating || row.state == "terminating") "terminating" else row.state

    /**
     * The list filtered + sorted by N for display (filter sees the effective
     * state). UC-62 — the server host-shell row (`type == server-ssh`) is
     * partitioned out and PREPENDED unconditionally, so it stays pinned to the
     * top of the list above every Claude row (AC3) and survives every filter
     * chip (AC3 — it is not a "running/stopped" Claude session and must always
     * be reachable). Only the ordinary Claude rows are filtered + n-sorted.
     */
    val visible: List<SessionSummary>
        get() {
            val (ssh, claude) = sessions.partition { it.isServerSsh }
            return ssh + claude.filter { filter.matches(effectiveState(it)) }.sortedBy { it.n }
        }

    /**
     * Counts for the chip badges — computed on the effective state (UC-28).
     * UC-62 — the host-shell row is excluded from all three counts: it is not a
     * Claude session and the chips describe the Claude session population.
     */
    private val claudeSessions: List<SessionSummary> get() = sessions.filterNot { it.isServerSsh }
    val countAll: Int get() = claudeSessions.size
    val countRunning: Int
        get() = claudeSessions.count {
            val s = effectiveState(it)
            // UC-28 adds `terminating`; `starting` is intentionally EXCLUDED —
            // the UC-04/UC-27 badge contract counts running only (not starting),
            // even though the RUNNING filter view still buckets starting in.
            s == "running" || s == "provisioning" || s == "terminating"
        }
    // UC-46 — `paused` buckets with `stopped` under the Stopped chip (both are
    // non-running, resumable-or-restartable states the operator manages there).
    val countStopped: Int
        get() = claudeSessions.count {
            val s = effectiveState(it)
            s == "stopped" || s == "paused"
        }

    /** UC-46 — whether a lifecycle action for [row] is currently in flight. */
    fun isPending(row: SessionSummary): Boolean = row.n in pendingActions

    /**
     * UC-54 — the tri-state connectivity the Sessions-screen dot binds to.
     * Precedence (highest first):
     *   • a check/refresh/spawn in flight → CHECKING (yellow);
     *   • else the last interaction failed → UNREACHABLE (red);
     *   • else the server has answered before → REACHABLE (green);
     *   • else nothing has resolved yet → UNKNOWN (yellow).
     * CHECKING is deliberately ABOVE UNREACHABLE — a foreground ON_RESUME fires
     * refresh(), so a persistently-down server intentionally flickers
     * red→yellow→red ("retrying now") rather than sitting flat red.
     */
    val connectivity: Connectivity
        get() = when {
            loading || spawning || pendingActions.isNotEmpty() -> Connectivity.CHECKING
            unreachable -> Connectivity.UNREACHABLE
            serverResponded -> Connectivity.REACHABLE
            else -> Connectivity.UNKNOWN
        }

    /**
     * UC-70 — whether the list region should render the light-grey
     * "Not connected, retrying…" background instead of the rows / normal empty
     * state (AC1, AC2). True while the feed is actively reconnecting
     * ([SessionsFeedStatus.reconnecting]) AND, per challenger hard-req #4, once
     * it has terminally given up (phase STOPPED → a static "Not connected"). The
     * silent CONNECTING phase (initial connect, no failure yet) and the normal
     * CONNECTED phase both leave this false, so the message never flashes during
     * a healthy first connect or distracts from a genuine "zero sessions" empty
     * state.
     */
    val showRetryingBackground: Boolean
        get() = feedStatus.reconnecting || feedStatus.phase == SessionsFeedStatus.Phase.STOPPED
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
