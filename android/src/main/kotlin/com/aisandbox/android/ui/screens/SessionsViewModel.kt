package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.LifecycleAction
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.WorkspaceProjectInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * UC-98 — the workspace projects offered in the "New session" sheet's
     * drop-down, fetched from `GET /v1/workspace/projects` when the sheet opens
     * ([loadWorkspaceProjects]). Kept in a SEPARATE flow from [state] (mirroring
     * [com.aisandbox.android.ui.screens.ConversationViewModel]'s model-menu flow)
     * so a catalogue fetch never perturbs the sessions-list render state. Any
     * failure resolves to [WorkspaceProjectsState.Error]; the sheet still offers
     * "None" so spawning is always possible (AC3/AC7).
     */
    private val _workspaceProjects = MutableStateFlow<WorkspaceProjectsState>(WorkspaceProjectsState.Idle)
    val workspaceProjects: StateFlow<WorkspaceProjectsState> = _workspaceProjects.asStateFlow()

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
        // UC-92 — on a *transient* events-socket drop (the socket had Opened
        // successfully and then dropped for a non-identity reason — delete-
        // induced connection churn, back-nav STOP/START, a flaky link),
        // immediately re-fetch the list over REST so the rows repaint at once
        // instead of waiting out the exponential back-off (AC4/AC5). A genuine
        // cold outage never reaches Open, so this never fires there (AC6).
        onTransientDrop = { coordinator.refresh() },
    )

    init {
        refresh()
    }

    fun refresh() = coordinator.refresh()

    fun selectFilter(filter: SessionsFilter) = coordinator.selectFilter(filter)

    /** UC-98 — spawn carrying the optional workspace-project selection (null == "None"). */
    fun spawn(label: String?, workspaceProject: String? = null) = coordinator.spawn(label, workspaceProject)

    /**
     * UC-98 — fetch the workspace-project catalogue for the "New session" sheet's
     * drop-down (AC2). Publishes [WorkspaceProjectsState.Loading] immediately,
     * then [Loaded] / [Error] from `GET /v1/workspace/projects`. Mirrors
     * [com.aisandbox.android.ui.screens.ConversationViewModel.loadModels]. A null
     * profile (not enrolled) or any transport / HTTP failure resolves to
     * [WorkspaceProjectsState.Error] — the sheet still offers "None" so spawning
     * remains possible (AC3/AC7). Driven by the screen when the sheet opens.
     */
    fun loadWorkspaceProjects() {
        _workspaceProjects.value = WorkspaceProjectsState.Loading
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                _workspaceProjects.value = WorkspaceProjectsState.Error("Not enrolled")
                return@launch
            }
            val result = try {
                container.workspaceProjectsApi(container.httpClient(profile)).list()
            } catch (t: Throwable) {
                _workspaceProjects.value = WorkspaceProjectsState.Error(t.message ?: t.javaClass.simpleName)
                return@launch
            }
            _workspaceProjects.value = when (result) {
                is ApiResult.Success -> WorkspaceProjectsState.Loaded(result.value)
                is ApiResult.HttpFailure -> WorkspaceProjectsState.Error(result.detail.ifBlank { result.code })
            }
        }
    }

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
 * UC-98 — state of the "New session" sheet's workspace-project catalogue fetch.
 * Mirrors [com.aisandbox.android.ui.screens.ModelMenuState]. The drop-down always
 * renders a "None" option regardless of this state, so a Loading / Error state
 * simply means no real projects are offered yet — spawning is never blocked
 * (AC3/AC7).
 */
sealed interface WorkspaceProjectsState {
    data object Idle : WorkspaceProjectsState
    data object Loading : WorkspaceProjectsState
    data class Loaded(val projects: List<WorkspaceProjectInfo>) : WorkspaceProjectsState
    data class Error(val message: String) : WorkspaceProjectsState

    /** The real projects to render right now — empty unless [Loaded]. */
    val projectsOrEmpty: List<WorkspaceProjectInfo>
        get() = (this as? Loaded)?.projects ?: emptyList()
}

/**
 * Server-connectivity rendering for the Sessions-screen dot.
 *
 * <p>UC-54 introduced the REST-derived tri-state: UNKNOWN and CHECKING both
 * render yellow (never-reached vs. a check in flight; kept distinct so they are
 * independently testable), REACHABLE green, UNREACHABLE red.
 *
 * <p>UC-72 adds two states driven by the live-feed reconnect CYCLE (not the REST
 * ladder): RETRYING (yellow) while a reconnect attempt is actively in flight,
 * and BACKOFF (red) while the loop is waiting out the back-off delay between
 * attempts (and on terminal STOPPED). These take precedence over the REST ladder
 * so the dot reflects the moment-to-moment reconnect oscillation; green stays
 * REST-driven (REACHABLE).
 */
enum class Connectivity { UNKNOWN, CHECKING, REACHABLE, UNREACHABLE, RETRYING, BACKOFF }

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
     * The connectivity the Sessions-screen dot binds to. Precedence (highest
     * first):
     *
     * UC-72 — the live-feed reconnect CYCLE is checked FIRST, so the dot tracks
     * the moment-to-moment attempt/backoff oscillation:
     *   • a reconnect attempt is in flight → RETRYING (yellow);
     *   • else the loop is waiting out the back-off delay → BACKOFF (red);
     *   • else the feed has terminally given up → BACKOFF (red);
     *
     * UC-54 — otherwise the existing REST-derived ladder applies (green stays
     * REST-driven):
     *   • a check/refresh/spawn in flight → CHECKING (yellow);
     *   • else the last interaction failed → UNREACHABLE (red);
     *   • else the server has answered before → REACHABLE (green);
     *   • else nothing has resolved yet → UNKNOWN (yellow).
     *
     * The feed-reconnect arms intentionally OUTRANK the REST ladder: while the
     * push feed is cycling attempt↔backoff, that is the most live, precise
     * connectivity signal we have, so it should win over a possibly-stale REST
     * verdict. CHECKING remains ABOVE UNREACHABLE within the REST ladder — a
     * foreground ON_RESUME fires refresh(), so a persistently-down server
     * flickers red→yellow→red rather than sitting flat red.
     */
    val connectivity: Connectivity
        get() = when {
            feedStatus.activity == SessionsFeedStatus.ReconnectActivity.ATTEMPTING -> Connectivity.RETRYING
            feedStatus.activity == SessionsFeedStatus.ReconnectActivity.WAITING -> Connectivity.BACKOFF
            feedStatus.phase == SessionsFeedStatus.Phase.STOPPED -> Connectivity.BACKOFF
            loading || spawning || pendingActions.isNotEmpty() -> Connectivity.CHECKING
            unreachable -> Connectivity.UNREACHABLE
            serverResponded -> Connectivity.REACHABLE
            else -> Connectivity.UNKNOWN
        }

    /**
     * UC-70 — the coarse "is the live feed down right now" signal: true while
     * the feed is actively reconnecting ([SessionsFeedStatus.reconnecting]) or,
     * per challenger hard-req #4, once it has terminally given up (phase STOPPED).
     * The silent CONNECTING phase (initial connect, no failure yet) and the
     * normal CONNECTED phase both leave this false. UC-92 derives BOTH the
     * full-screen [showRetryingBackground] and the slim [showReconnectingBanner]
     * from this, gating them on whether any rows are still known.
     */
    val isFeedDisconnected: Boolean
        get() = feedStatus.reconnecting || feedStatus.phase == SessionsFeedStatus.Phase.STOPPED

    /**
     * UC-92 — a *genuine* outage vs. a *transient* feed blip. Reuses the UC-52
     * [unreachable] flag (the last REST interaction failed) and the UC-54
     * [serverResponded] flag (the server has answered at least once this
     * session): the feed counts as genuinely offline only when REST is also
     * unreachable OR the server has never responded. A transient events-socket
     * drop (delete-induced churn, back-nav re-START) leaves REST healthy, so
     * this stays false and the list keeps its rows behind the slim banner.
     */
    val genuinelyOffline: Boolean
        get() = unreachable || !serverResponded

    /**
     * UC-70 / UC-92 — render the full-screen "Not connected, retrying…"
     * background ONLY when there are genuinely zero known rows to show AND the
     * outage is real (REST unreachable / never-responded) or the feed has
     * terminally given up (phase STOPPED → a static "Not connected"). With rows
     * in hand the non-destructive [showReconnectingBanner] is used instead
     * (AC2/AC3/AC10), so a transient reconnect never blanks an in-memory list.
     * Because this branch requires `visible.isEmpty()`, the rule holds in EVERY
     * feed phase (RECONNECTING / STOPPED / CONNECTING). When zero rows are known
     * but REST is still healthy (e.g. the only session was just deleted), both
     * this and the banner stay false → the normal empty state shows (AC7).
     */
    val showRetryingBackground: Boolean
        get() = isFeedDisconnected && visible.isEmpty() &&
            (genuinelyOffline || feedStatus.phase == SessionsFeedStatus.Phase.STOPPED)

    /**
     * UC-92 (AC2/AC10) — show the slim, non-destructive reconnect indicator
     * above the list whenever the feed is disconnected/reconnecting BUT known
     * rows are still in state. Keeps every row visible (never the full-screen
     * background) through a transient drop, whether triggered by a delete-
     * induced feed churn or by back-navigation re-entering the list.
     */
    val showReconnectingBanner: Boolean
        get() = isFeedDisconnected && visible.isNotEmpty()
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
