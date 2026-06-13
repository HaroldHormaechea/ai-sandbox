package com.aisandbox.android.ui.screens

import android.util.Log
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.LifecycleAction
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.SessionsApi
import com.aisandbox.android.net.TerminatingSessionsStore
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
    // UC-28 — defaulted to a fresh store so pre-UC-28 unit tests that
    // construct the coordinator without it keep compiling (tests that
    // exercise terminating transitions inject one they control). Production
    // (SessionsViewModel) always passes the process-scoped shared store.
    private val terminatingSessions: TerminatingSessionsStore = TerminatingSessionsStore(),
) {

    init {
        // UC-28 — mirror the process-scoped optimistic-terminating set into
        // SessionsUiState on every change, so the single StateFlow the screen
        // collects carries it (preserving the one-StateFlow render contract).
        // The set is also reconciled in refresh() Success; this collector just
        // keeps the UI value in lock-step with the holder (e.g. when a
        // profile-switch clearAll() fires from AppContainer).
        scope.launch {
            terminatingSessions.flow.collect { set ->
                state.value = state.value.copy(terminating = set)
            }
        }
    }

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
            try {
                when (val r = apiFactory(profile).list()) {
                    is ApiResult.Success -> {
                        // UC-28 — race-safe reconcile of the optimistic-terminating
                        // set against the fresh server list (shared with the UC-32
                        // push-apply paths via reconcileTerminating).
                        // UC-52 — the server responded, so it is reachable: clear
                        // the transient unreachable banner (AC3 auto-recovery).
                        // UC-54 — and record that the server has answered, so the
                        // dot reads green (REACHABLE) rather than yellow (UNKNOWN).
                        state.value = state.value.copy(
                            loading = false,
                            sessions = r.value,
                            profile = profile,
                            terminating = reconcileTerminating(r.value),
                            unreachable = false,
                            serverResponded = true,
                        )
                    }
                    is ApiResult.HttpFailure -> {
                        // UC-52 — an HTTP status (even an error) proves the server
                        // answered: clear unreachable so a stale banner can't sit
                        // alongside the snackbar (single-surface invariant).
                        // UC-54 — an HTTP status also means the server is reachable.
                        state.value = state.value.copy(
                            loading = false,
                            lastError = "${r.code} (${r.status})",
                            unreachable = false,
                            serverResponded = true,
                        )
                    }
                }
            } catch (t: Throwable) {
                // UC-51 — refresh() previously had no try/catch, so a transport
                // throw (connection refused, timeout, unknown host, TLS) from
                // list() escaped uncaught on viewModelScope (Main) → FATAL
                // EXCEPTION: main. ALWAYS clear loading so there's no stuck
                // spinner (AC4); do NOT touch `sessions` so the last-known list
                // survives (AC1). UC-52 — classify the throw three ways via
                // surfaceTransportThrow: transient connectivity → unreachable
                // banner; ordinary error → snackbar; genuine TLS/identity →
                // leave both (the interceptor already bus-routed the identity
                // screen — no double-surface, AC5).
                Log.w(TAG, "Refresh threw: ${t.message}", t)
                state.value = state.value.copy(loading = false)
                surfaceTransportThrow(t, profile, "refresh_failed")
            }
        }
    }

    fun selectFilter(filter: SessionsFilter) {
        state.value = state.value.copy(filter = filter)
    }

    /**
     * UC-32 — apply an authoritative server [SessionEventMessage.Snapshot] from
     * the live push feed as a FULL RESYNC of the working set (AC5). Pure: it
     * mutates only [sessions] + the reconciled [terminating] set and NEVER
     * touches [loading] — the push channel must not drive the REST spinner, so a
     * dropped/reconnecting feed can't leave a stuck spinner (AC5). The filter,
     * `visible` ordering (sorted by `n`) and chip counts recompute automatically
     * off `sessions`.
     */
    fun applySnapshot(rows: List<SessionSummary>) {
        // UC-52 — a UC-32 push frame proves the server is reachable again, so
        // clear the transient unreachable banner: the feed auto-recovering is a
        // valid recovery path even without a manual Retry (AC3 auto-recovery).
        //
        // UC-61 (deliberate, M-A) — a snapshot is a FULL RESYNC: `sessions = rows`
        // replaces the whole working set with authoritative server rows. The
        // client-only optimistic placeholder (optimistic == true) is never in the
        // server's `rows`, so it is dropped wholesale here — an "evict-on-new-n"
        // pass like applyDelta's would be a pure no-op and is intentionally NOT
        // added. The dup-row defect arises ONLY on the incremental applyDelta
        // merge path, never here. The one trade-off is that a snapshot landing in
        // the narrow window AFTER the FAB tap but BEFORE spawn-Success can drop the
        // "starting" placeholder a beat early (brief flicker, not a duplicate). We
        // ACCEPT that under AC1 rather than preserve the placeholder across a
        // resync: re-injecting it risks coexisting with the server's real row (the
        // very duplicate this UC removes), and spawn-Success's reconcile + refresh
        // restores the authoritative row immediately. Purity preserved — this never
        // touches `loading`/`spawning`.
        state.value = state.value.copy(
            sessions = rows,
            terminating = reconcileTerminating(rows),
            unreachable = false,
            serverResponded = true,
        )
    }

    /**
     * UC-32 — apply an incremental [SessionEventMessage.Delta]: idempotent
     * upsert / remove keyed by `n` against the current working set (AC3). A
     * re-applied delta is a no-op; a server `terminating`/removal converges with
     * the UC-28 optimistic flag via [reconcileTerminating] (AC4). Pure, and (like
     * [applySnapshot]) never touches [loading].
     */
    fun applyDelta(upserts: List<SessionSummary>, removed: List<Int>) {
        if (upserts.isEmpty() && removed.isEmpty()) return
        val prior = state.value.sessions
        val priorNs = prior.mapTo(HashSet()) { it.n }
        val byN = prior.associateByTo(LinkedHashMap()) { it.n }
        upserts.forEach { byN[it.n] = it }
        removed.forEach { byN.remove(it) }
        // UC-61 — evict the optimistic placeholder ONLY when (a) one is pending in
        // the prior working set AND (b) this delta introduces a genuinely-new `n`
        // (absent from `priorNs`). That new `n` is the server's authoritative row
        // for the just-spawned session whose assigned number differs from the
        // client's optimistic max(n)+1 guess; without this the merge-by-`n` upsert
        // would ADD the real row beside the placeholder (two rows until a full
        // replace lands — the defect). Dropping the placeholder lets the real row
        // REPLACE it (AC3). A plain update to an existing `n` (uptime/state tick)
        // does NOT introduce a new `n`, so the placeholder is preserved — it is
        // only ever cleared by its real replacement, spawn-Success, or a rollback.
        // Single-flight (`spawning` guard in spawn()) bounds this to at most one
        // pending placeholder, so removing all optimistic rows is exact. Purity
        // preserved — never touches `loading`.
        val hadOptimistic = prior.any { it.optimistic }
        val introducesNewN = upserts.any { it.n !in priorNs }
        if (hadOptimistic && introducesNewN) {
            byN.values.removeAll { it.optimistic }
        }
        val merged = byN.values.toList()
        // UC-52 — like applySnapshot, an inbound delta proves reachability →
        // clear the transient unreachable banner (AC3 auto-recovery).
        state.value = state.value.copy(
            sessions = merged,
            terminating = reconcileTerminating(merged),
            unreachable = false,
            serverResponded = true,
        )
    }

    /**
     * UC-28 reconcile, shared by [refresh] and the UC-32 push-apply paths.
     * KEEP an optimistic-terminating `n` only while it is still present AND the
     * server still reports a non-resolving status (running / provisioning /
     * starting — possibly stale, so do NOT resurrect). CLEAR it when:
     *   • the row is absent  → teardown completed (AC7);
     *   • the server reports `terminating` → hand off to the server token (the
     *     union still keeps the pill);
     *   • the server reports `stopped`     → resolved.
     * Clears the resolved entries from the process-scoped store and returns the
     * surviving set for the UI state.
     */
    private fun reconcileTerminating(sessions: List<SessionSummary>): Set<Int> {
        val freshByN = sessions.associateBy { it.n }
        val current = terminatingSessions.flow.value
        val keep = current.filter { n ->
            val row = freshByN[n]
            row != null && row.state != "terminating" && row.state != "stopped"
        }.toSet()
        (current - keep).forEach { terminatingSessions.clear(it) }
        return keep
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
                // UC-61 — tag this row as the client-only optimistic placeholder so
                // the merge paths (applyDelta) and the rollback paths can identify
                // and evict it independently of the guessed `n` (which is exactly
                // what may be wrong).
                optimistic = true,
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
                    sessions = state.value.sessions.filterNot { it.optimistic },
                    lastError = "no_profile",
                )
                return@launch
            }
            try {
                when (val r = apiFactory(profile).spawn(label)) {
                    is ApiResult.Success -> {
                        // UC-61 — USE the authoritative SessionSummary the server
                        // returned (previously discarded). Drop every optimistic
                        // placeholder and upsert the real row by its server-assigned
                        // `n` into a LinkedHashMap (dedup if a UC-32 delta already
                        // inserted it), so the list converges to EXACTLY ONE row for
                        // the new session immediately — even when the server `n` ≠
                        // the client's max(n)+1 guess (AC1, AC2). Reconciling by the
                        // returned summary — not by `label` (blank/duplicable) or the
                        // guessed `n` (the thing that's wrong) — is the fix.
                        val byN = state.value.sessions
                            .filterNot { it.optimistic }
                            .associateByTo(LinkedHashMap()) { it.n }
                        byN[r.value.n] = r.value
                        state.value = state.value.copy(sessions = byN.values.toList())
                        // Still refresh() for a full server reconcile (uptime, any
                        // rows changed out of band). The nested refresh() is itself
                        // guarded (UC-51), so a spawn that succeeds server-side but
                        // whose follow-up refresh hits a transport throw does not
                        // crash.
                        refresh()
                    }
                    is ApiResult.HttpFailure -> {
                        // Roll back the optimistic insertion + surface the error.
                        // UC-52 — the server answered, so clear any stale
                        // unreachable banner (single-surface invariant).
                        // UC-54 — the server answered → it is reachable.
                        state.value = state.value.copy(
                            sessions = state.value.sessions.filterNot { it.optimistic },
                            lastError = "${r.code} (${r.status})",
                            unreachable = false,
                            serverResponded = true,
                        )
                    }
                }
            } catch (t: Throwable) {
                // UC-51 — spawn() previously had no try/catch, so a transport
                // throw on spawn() escaped uncaught on viewModelScope (Main) →
                // FATAL EXCEPTION: main. Roll back the optimistic "starting" row
                // with the SAME predicate the HttpFailure branch uses so a
                // phantom session can't persist (AC3). UC-52 — classify the
                // throw three ways (transient banner / snackbar / leave-for-
                // identity-screen) via surfaceTransportThrow, avoiding a double
                // surface (AC5).
                Log.w(TAG, "Spawn threw: ${t.message}", t)
                state.value = state.value.copy(
                    sessions = state.value.sessions.filterNot { it.optimistic },
                )
                surfaceTransportThrow(t, profile, "spawn_failed")
            } finally {
                // Always clear spawning so the FAB never sticks disabled (AC3) —
                // moved into finally because the old trailing assignment sat
                // after the `when`, so a throw skipped it → stuck FAB.
                state.value = state.value.copy(spawning = false)
            }
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
            // UC-28 — optimistic terminating BEFORE the call resolves (AC2):
            // the pill appears the instant the operator confirms, and the
            // re-delete guard (swipe + terminal menu) engages immediately.
            terminatingSessions.mark(n)
            try {
                when (val r = apiFactory(profile).delete(n, force)) {
                    is ApiResult.Success -> refresh()
                    is ApiResult.HttpFailure -> {
                        // The headline AC5 path: a non-204 (404 / 500 / …) is
                        // no longer a silent no-op — surface code + status.
                        // UC-28 AC8 — an explicit failure exits terminating so
                        // the row reverts to its real server status.
                        terminatingSessions.clear(n)
                        Log.w(TAG, "Delete $n failed: ${r.code} (${r.status}) ${r.detail}")
                        // UC-52 — the server answered → clear any stale
                        // unreachable banner (single-surface invariant).
                        // UC-54 — the server answered → it is reachable.
                        state.value = state.value.copy(
                            lastError = "${r.code} (${r.status})",
                            unreachable = false,
                            serverResponded = true,
                        )
                    }
                }
            } catch (t: Throwable) {
                // UC-28 AC8 — a transport throw also exits terminating (the
                // delete did not land); revert to the real status + surface.
                terminatingSessions.clear(n)
                // MANDATORY — delete() previously had no try/catch, so a
                // transport throw (connection drop, TLS) escaped uncaught on
                // viewModelScope (crash risk). UC-52 — classify three ways via
                // surfaceTransportThrow: transient connectivity → retryable
                // banner; ordinary error → snackbar; genuine TLS/identity →
                // leave both (the interceptor already bus-routed the identity
                // screen — no double-surface, AC5).
                Log.w(TAG, "Delete $n threw: ${t.message}", t)
                surfaceTransportThrow(t, profile, "delete_failed")
            }
        }
    }

    /**
     * UC-46 — drive a Docker-lifecycle action (stop/start/pause/unpause) on
     * session [n]. Mirrors [delete] for the optimistic-pending / surface-error
     * contract (AC6/AC7):
     *
     *   • mark [n] in [SessionsUiState.pendingActions] BEFORE the call resolves
     *     so the row's action control disables immediately (no double-fire);
     *   • on success, [refresh] reconciles the row to the authoritative server
     *     state (the next UC-32 push does the same out of band);
     *   • on an explicit HTTP failure (e.g. 409 `session_state_conflict` from a
     *     state that drifted) OR a transport throw, surface the error and clear
     *     the pending flag so the row reverts to its real state — never a stuck
     *     fake state (AC7);
     *   • the pending flag is always cleared in `finally`, so a thrown call
     *     can't leave the control permanently disabled.
     */
    fun lifecycle(n: Int, action: LifecycleAction) {
        scope.launch {
            val profile = profileSupplier() ?: run {
                state.value = state.value.copy(lastError = "no_profile")
                return@launch
            }
            // AC6 — optimistic pending the instant the action is requested.
            state.value = state.value.copy(pendingActions = state.value.pendingActions + n, lastError = null)
            try {
                when (val r = apiFactory(profile).lifecycle(n, action)) {
                    is ApiResult.Success -> refresh()
                    is ApiResult.HttpFailure -> {
                        Log.w(TAG, "Lifecycle ${action.token} $n failed: ${r.code} (${r.status}) ${r.detail}")
                        // UC-52 — the server answered → clear any stale
                        // unreachable banner (single-surface invariant).
                        // UC-54 — the server answered → it is reachable.
                        state.value = state.value.copy(
                            lastError = "${r.code} (${r.status})",
                            unreachable = false,
                            serverResponded = true,
                        )
                    }
                }
            } catch (t: Throwable) {
                // UC-52 — classify three ways via surfaceTransportThrow:
                // transient connectivity → retryable banner; ordinary error →
                // snackbar; genuine TLS/identity → leave both (the interceptor
                // already bus-routed the identity screen — no double, AC5).
                Log.w(TAG, "Lifecycle ${action.token} $n threw: ${t.message}", t)
                surfaceTransportThrow(t, profile, "lifecycle_failed")
            } finally {
                // AC6/AC7 — release the control; refresh() / the next push
                // carries the authoritative state.
                state.value = state.value.copy(pendingActions = state.value.pendingActions - n)
            }
        }
    }

    /**
     * UC-62 — create (or focus) the singleton server host-shell session, then
     * invoke [onReady] with its reserved id so the screen can navigate straight
     * into the terminal (AC1, AC2). The server enforces the singleton, so a
     * second tap returns the same row and [onReady] re-opens it (AC2 "second tap
     * focuses the existing row"). Errors surface like the other operations — an
     * HTTP failure as a snackbar (`lastError`), a transport throw via
     * [surfaceTransportThrow] — and [onReady] is NOT called, so no dead terminal
     * is opened.
     */
    fun openServerSsh(onReady: (Int) -> Unit) {
        scope.launch {
            val profile = profileSupplier() ?: run {
                state.value = state.value.copy(lastError = "no_profile")
                return@launch
            }
            try {
                when (val r = apiFactory(profile).createServerSsh()) {
                    is ApiResult.Success -> {
                        // The server responded → reachable; refresh so the pinned
                        // row appears in the list, then open its terminal.
                        state.value = state.value.copy(unreachable = false, serverResponded = true)
                        refresh()
                        onReady(r.value.n)
                    }
                    is ApiResult.HttpFailure -> {
                        Log.w(TAG, "createServerSsh failed: ${r.code} (${r.status}) ${r.detail}")
                        state.value = state.value.copy(
                            lastError = "${r.code} (${r.status})",
                            unreachable = false,
                            serverResponded = true,
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "createServerSsh threw: ${t.message}", t)
                surfaceTransportThrow(t, profile, "server_ssh_failed")
            }
        }
    }

    /**
     * UC-52 — single point that classifies a transport throwable caught around
     * an API call and updates the single-surface (`unreachable` XOR
     * [SessionsUiState.lastError]) invariant. Shared by every
     * [refresh]/[spawn]/[delete]/[lifecycle] catch so the three-way decision
     * stays identical:
     *
     *   • [NetworkEvent.ServerUnreachable] — a transient connectivity failure:
     *     raise the retryable, auto-recovering banner and CLEAR `lastError`
     *     (no snackbar). This is the non-identity, non-destructive path (AC1,
     *     AC2) — the [AiSandboxHttpClient] interceptor deliberately did NOT
     *     bus-route it, so there is no full-screen identity screen.
     *   • `null` — an ordinary non-identity error the translator doesn't own:
     *     surface it as a snackbar and clear `unreachable`.
     *   • anything else (a genuine TLS/identity event) — the interceptor
     *     already TRANSLATED + EMITTED it (full-screen ServerIdentityChanged),
     *     so leave BOTH `unreachable` and `lastError` untouched to avoid a
     *     double surface (AC5) and to keep genuine TLS routing intact (AC4).
     */
    private fun surfaceTransportThrow(t: Throwable, profile: ServerProfile, fallbackCode: String) {
        val host = profile.serverUrl
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore(':')
        when (TlsFailureTranslation.translate(t, profile.pinSha256Hex, host)) {
            is NetworkEvent.ServerUnreachable ->
                state.value = state.value.copy(unreachable = true, lastError = null)
            null ->
                state.value = state.value.copy(lastError = t.message ?: fallbackCode, unreachable = false)
            else -> {
                // Genuine TLS/identity — already surfaced full-screen; no-op here.
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
