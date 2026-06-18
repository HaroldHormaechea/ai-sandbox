package com.aisandbox.android.ui.screens

import com.aisandbox.android.net.SessionSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 AC8 — the sessions-list filter chips "All / Running / Stopped"
 * + per-chip counts. Pure data shape; no Android dependencies.
 *
 * <p>The corresponding ViewModel tests (optimistic spawn + rollback,
 * spawn error surfacing) require Robolectric + a fake AppContainer
 * seam — deferred to the instrumented-test tier (androidTest/) since
 * production code accesses the container via the Application subclass
 * which isn't unit-testable without Robolectric. Explicit gap noted in
 * the TEST SUMMARY.
 */
class SessionsUiStateTest {

    private fun row(n: Int, state: String, label: String = ""): SessionSummary =
        SessionSummary(
            n = n,
            label = label,
            tmuxTitle = "",
            state = state,
            uptimeSec = 0L,
            activeStreams = 0,
            startedAt = null,
        )

    @Test
    fun `filter ALL matches every state`() {
        assertThat(SessionsFilter.ALL.matches("running")).isTrue
        assertThat(SessionsFilter.ALL.matches("starting")).isTrue
        assertThat(SessionsFilter.ALL.matches("stopped")).isTrue
    }

    @Test
    fun `filter RUNNING matches both running and starting per the design`() {
        // UC04 AC8: "Running · <m>" chip — the design groups "running"
        // and "starting" together so the user doesn't have to wait
        // for a container to finish coming up before they can see it.
        assertThat(SessionsFilter.RUNNING.matches("running")).isTrue
        assertThat(SessionsFilter.RUNNING.matches("starting")).isTrue
        assertThat(SessionsFilter.RUNNING.matches("stopped")).isFalse
    }

    @Test
    fun `filter STOPPED matches only stopped`() {
        assertThat(SessionsFilter.STOPPED.matches("running")).isFalse
        assertThat(SessionsFilter.STOPPED.matches("starting")).isFalse
        assertThat(SessionsFilter.STOPPED.matches("stopped")).isTrue
    }

    @Test
    fun `filter RUNNING also matches provisioning (UC-27)`() {
        // UC-27: a container that is up but still installing its spawn-time
        // toolchains is grouped under the "Running" chip (alongside running +
        // starting) so it doesn't vanish from the list while provisioning.
        assertThat(SessionsFilter.RUNNING.matches("provisioning")).isTrue
    }

    @Test
    fun `filter STOPPED excludes provisioning`() {
        assertThat(SessionsFilter.STOPPED.matches("provisioning")).isFalse
    }

    @Test
    fun `filter ALL matches provisioning`() {
        assertThat(SessionsFilter.ALL.matches("provisioning")).isTrue
    }

    @Test
    fun `countAll counts every row regardless of state`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "starting"),
                row(3, "stopped"),
                row(4, "stopped"),
            )
        )
        assertThat(s.countAll).isEqualTo(4)
    }

    @Test
    fun `countRunning counts running only (NOT starting)`() {
        // Per data shape: countRunning literally counts state=="running".
        // The RUNNING filter merges starting (above) but the chip BADGE
        // shows only confirmed-running. This is a subtle but pinned
        // contract.
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "starting"),
                row(3, "running"),
                row(4, "stopped"),
            )
        )
        assertThat(s.countRunning).isEqualTo(2)
    }

    @Test
    fun `countStopped counts only stopped`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "stopped"),
                row(3, "stopped"),
                row(4, "starting"),
            )
        )
        assertThat(s.countStopped).isEqualTo(2)
    }

    @Test
    fun `countRunning includes provisioning (UC-27)`() {
        // UC-27: the "Running" badge counts confirmed-running AND provisioning
        // (container up, toolchains installing) — but NOT starting or stopped.
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "provisioning"),
                row(3, "starting"),
                row(4, "stopped"),
            )
        )
        assertThat(s.countRunning).isEqualTo(2)
    }

    @Test
    fun `countStopped unaffected by provisioning`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "provisioning"),
                row(2, "stopped"),
                row(3, "stopped"),
            )
        )
        assertThat(s.countStopped).isEqualTo(2)
    }

    @Test
    fun `visible RUNNING filter includes provisioning rows sorted by N`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(3, "provisioning"),
                row(1, "running"),
                row(2, "stopped"),
                row(5, "starting"),
            ),
            filter = SessionsFilter.RUNNING,
        )
        // provisioning(3) + running(1) + starting(5) under RUNNING; stopped(2) out.
        assertThat(s.visible.map { it.n }).containsExactly(1, 3, 5)
    }

    @Test
    fun `visible sorts by N within the selected filter`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(3, "running"),
                row(1, "running"),
                row(2, "stopped"),
                row(5, "starting"),
            ),
            filter = SessionsFilter.RUNNING,
        )
        assertThat(s.visible.map { it.n }).containsExactly(1, 3, 5)
    }

    @Test
    fun `visible respects the STOPPED filter`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "stopped"),
                row(3, "starting"),
            ),
            filter = SessionsFilter.STOPPED,
        )
        assertThat(s.visible.map { it.n }).containsExactly(2)
    }

    // ── UC-28 — terminating state: filter bucketing, effectiveState union, counts ──

    @Test
    fun `filter RUNNING matches terminating (UC-28)`() {
        // A session being torn down is still "active" from the operator's view
        // and stays under the Running chip until the teardown resolves.
        assertThat(SessionsFilter.RUNNING.matches("terminating")).isTrue
    }

    @Test
    fun `filter STOPPED excludes terminating (UC-28)`() {
        assertThat(SessionsFilter.STOPPED.matches("terminating")).isFalse
    }

    @Test
    fun `filter ALL matches terminating (UC-28)`() {
        assertThat(SessionsFilter.ALL.matches("terminating")).isTrue
    }

    @Test
    fun `effectiveState is terminating when the row is optimistically flagged (UC-28 AC2)`() {
        // Client-side optimistic half: the server still reports running, but the
        // operator just confirmed a delete → the union yields terminating.
        val s = SessionsUiState(
            sessions = listOf(row(1, "running")),
            terminating = setOf(1),
        )
        assertThat(s.effectiveState(row(1, "running"))).isEqualTo("terminating")
    }

    @Test
    fun `effectiveState is terminating when the server reports terminating (UC-28 AC3)`() {
        // Server-reported half: no optimistic flag, but the wire token wins.
        val s = SessionsUiState(sessions = listOf(row(1, "terminating")))
        assertThat(s.effectiveState(row(1, "terminating"))).isEqualTo("terminating")
    }

    @Test
    fun `effectiveState passes unknown and normal tokens through (UC-28 AC10)`() {
        // Neither flagged nor server-terminating → the real state passes through,
        // including an unknown future token (rendered raw/neutral by StatusPill).
        val s = SessionsUiState(sessions = listOf(row(1, "running")), terminating = setOf(2))
        assertThat(s.effectiveState(row(1, "running"))).isEqualTo("running")
        assertThat(s.effectiveState(row(3, "frobnicate"))).isEqualTo("frobnicate")
    }

    @Test
    fun `countRunning includes terminating rows (UC-28)`() {
        // terminating buckets with RUNNING, so a teardown-in-progress row counts
        // toward the Running badge (server-reported here).
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "terminating"),
                row(3, "stopped"),
            ),
        )
        assertThat(s.countRunning).isEqualTo(2)
        assertThat(s.countStopped).isEqualTo(1)
    }

    @Test
    fun `countRunning counts an optimistically-flagged row as terminating (UC-28)`() {
        // The optimistic flag flips a server-running row into the terminating
        // bucket via effectiveState — it still counts under Running.
        val s = SessionsUiState(
            sessions = listOf(row(1, "running"), row(2, "running")),
            terminating = setOf(1),
        )
        assertThat(s.countRunning).isEqualTo(2)
    }

    @Test
    fun `visible RUNNING keeps a server-terminating row visible (UC-28 AC3)`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "terminating"),
                row(3, "stopped"),
            ),
            filter = SessionsFilter.RUNNING,
        )
        // The terminating row must NOT vanish under RUNNING (it stays visible
        // with its pill until the teardown resolves).
        assertThat(s.visible.map { it.n }).containsExactly(1, 2)
    }

    @Test
    fun `visible STOPPED hides a terminating row (UC-28)`() {
        val s = SessionsUiState(
            sessions = listOf(row(1, "terminating"), row(2, "stopped")),
            filter = SessionsFilter.STOPPED,
        )
        assertThat(s.visible.map { it.n }).containsExactly(2)
    }

    @Test
    fun `optimistic insertion appears before rollback`() {
        // Models the AC9 optimistic-spawn behaviour at the data layer:
        // before server confirmation, the list carries N+1 entries; on
        // HTTP failure, the entry is removed by referential equality.
        val running = row(1, "running")
        val optimistic = row(2, "starting", label = "release-build")

        val withOptimistic = SessionsUiState(sessions = listOf(running, optimistic))
        assertThat(withOptimistic.sessions).hasSize(2)
        assertThat(withOptimistic.countAll).isEqualTo(2)

        // Roll back via filterNot with referential equality (the precise
        // pattern in SessionsViewModel.spawn rollback).
        val rolledBack = withOptimistic.copy(
            sessions = withOptimistic.sessions.filterNot { it === optimistic },
        )
        assertThat(rolledBack.sessions).containsExactly(running)
    }

    // ── UC-46 — paused bucketing + pending-action gating ─────────────────────

    @Test
    fun `filter STOPPED matches paused (UC-46)`() {
        // A paused (frozen) session is a non-running, resumable state the
        // operator manages under the Stopped chip alongside stopped.
        assertThat(SessionsFilter.STOPPED.matches("paused")).isTrue
        assertThat(SessionsFilter.STOPPED.matches("stopped")).isTrue
    }

    @Test
    fun `filter RUNNING excludes paused (UC-46)`() {
        assertThat(SessionsFilter.RUNNING.matches("paused")).isFalse
    }

    @Test
    fun `filter ALL matches paused (UC-46)`() {
        assertThat(SessionsFilter.ALL.matches("paused")).isTrue
    }

    @Test
    fun `countStopped includes paused rows (UC-46)`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(1, "running"),
                row(2, "paused"),
                row(3, "stopped"),
                row(4, "paused"),
            ),
        )
        // paused + stopped both bucket under Stopped.
        assertThat(s.countStopped).isEqualTo(3)
        assertThat(s.countRunning).isEqualTo(1)
    }

    @Test
    fun `visible STOPPED shows paused alongside stopped sorted by N (UC-46)`() {
        val s = SessionsUiState(
            sessions = listOf(
                row(4, "paused"),
                row(1, "running"),
                row(2, "stopped"),
            ),
            filter = SessionsFilter.STOPPED,
        )
        assertThat(s.visible.map { it.n }).containsExactly(2, 4)
    }

    @Test
    fun `isPending is true only for rows in pendingActions (UC-46 AC6)`() {
        val s = SessionsUiState(
            sessions = listOf(row(1, "running"), row(2, "paused")),
            pendingActions = setOf(2),
        )
        assertThat(s.isPending(row(2, "paused")))
            .`as`("a row with an in-flight lifecycle action is pending (control disabled)")
            .isTrue
        assertThat(s.isPending(row(1, "running"))).isFalse
    }

    @Test
    fun `isPending defaults to false with no pending actions (UC-46)`() {
        val s = SessionsUiState(sessions = listOf(row(1, "running")))
        assertThat(s.isPending(row(1, "running"))).isFalse
    }

    // ── UC-54 — tri-state connectivity derivation (AC1/AC2/AC3/AC4/AC6/AC7) ───
    //
    // Pure derivation on SessionsUiState.connectivity — no Android, no
    // Robolectric, no network. This is the AC#7 core: the color-state mapping
    // is JVM-unit-tested on state. (AC#1 — the dot binding to this value — is
    // covered transitively: the screen reads state.connectivity and the
    // testTag "sessions_connectivity_dot" pins the wiring.)

    @Test
    fun `connectivity is UNKNOWN for the default pre-first-call state (UC-54 AC3)`() {
        // Nothing has resolved yet: not in-flight, not unreachable, the server
        // has never answered → UNKNOWN (rendered yellow). Distinguishes
        // "never reached yet" from "last call succeeded".
        assertThat(SessionsUiState().connectivity).isEqualTo(Connectivity.UNKNOWN)
    }

    @Test
    fun `connectivity is CHECKING while loading (UC-54 AC3)`() {
        assertThat(SessionsUiState(loading = true).connectivity)
            .isEqualTo(Connectivity.CHECKING)
    }

    @Test
    fun `connectivity is CHECKING while spawning (UC-54 AC3)`() {
        assertThat(SessionsUiState(spawning = true).connectivity)
            .isEqualTo(Connectivity.CHECKING)
    }

    @Test
    fun `connectivity is CHECKING while a lifecycle action is pending (UC-54 AC3)`() {
        assertThat(SessionsUiState(pendingActions = setOf(1)).connectivity)
            .isEqualTo(Connectivity.CHECKING)
    }

    @Test
    fun `connectivity is REACHABLE when the server answered and nothing is in flight (UC-54 AC2)`() {
        // serverResponded=true, not loading/spawning/pending, not unreachable
        // → REACHABLE (green): the last interaction succeeded.
        val s = SessionsUiState(serverResponded = true)
        assertThat(s.connectivity).isEqualTo(Connectivity.REACHABLE)
    }

    @Test
    fun `connectivity is UNREACHABLE when the last interaction failed (UC-54 AC4)`() {
        // unreachable=true, not in-flight → UNREACHABLE (red). Holds even once
        // the server has answered before (a later drop dominates a prior
        // success).
        assertThat(SessionsUiState(unreachable = true).connectivity)
            .isEqualTo(Connectivity.UNREACHABLE)
        assertThat(SessionsUiState(unreachable = true, serverResponded = true).connectivity)
            .isEqualTo(Connectivity.UNREACHABLE)
    }

    @Test
    fun `connectivity precedence — CHECKING outranks UNREACHABLE while a refresh is in flight (UC-54)`() {
        // The deliberate CHECKING > UNREACHABLE design decision: a foreground
        // ON_RESUME fires refresh(), so a persistently-down server flickers
        // red→yellow→red ("retrying now") rather than sitting flat red.
        val s = SessionsUiState(unreachable = true, loading = true)
        assertThat(s.connectivity).isEqualTo(Connectivity.CHECKING)
    }

    @Test
    fun `connectivity recovers to REACHABLE after an unreachable drop is cleared (UC-54 AC6)`() {
        // The full recovery cycle on state: a drop sets unreachable=true (red);
        // a later successful operation clears unreachable and sets
        // serverResponded → REACHABLE (green) with no manual retry.
        val dropped = SessionsUiState(unreachable = true)
        assertThat(dropped.connectivity).isEqualTo(Connectivity.UNREACHABLE)

        val recovered = dropped.copy(unreachable = false, serverResponded = true)
        assertThat(recovered.connectivity)
            .`as`("AC6 — a successful operation auto-returns the dot to green (no manual retry)")
            .isEqualTo(Connectivity.REACHABLE)
    }

    // ── UC-62 — server host-shell row: pin-top, filter-survival, count-exclusion ──

    private fun sshRow(): SessionSummary =
        SessionSummary(
            n = com.aisandbox.android.net.SessionsApi.SERVER_SSH_N,
            label = "",
            tmuxTitle = "(idle)",
            state = "running",
            uptimeSec = 0L,
            activeStreams = 0,
            startedAt = null,
            type = com.aisandbox.android.net.SessionsApi.TYPE_SERVER_SSH,
        )

    @Test
    fun `visible pins the server-ssh row to the top above every claude row (UC-62 AC3)`() {
        // The SSH row is reserved id 0, but pinning is by type, not by n: it sits
        // ABOVE the n-ascending Claude rows regardless. Here the claude rows are
        // deliberately out of order to prove the SSH row isn't merely "n==0 first".
        val s = SessionsUiState(
            sessions = listOf(
                row(3, "running"),
                row(1, "running"),
                sshRow(),
                row(2, "stopped"),
            ),
        )
        assertThat(s.visible.first().isServerSsh)
            .`as`("AC3 — the server-ssh row is pinned first")
            .isTrue
        assertThat(s.visible.map { it.n }).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `visible keeps the server-ssh row under EVERY filter chip (UC-62 AC3)`() {
        // The SSH row is not a running/stopped Claude session; it must survive all
        // three chips so the operator can always reach the host shell.
        for (filter in SessionsFilter.values()) {
            val s = SessionsUiState(
                sessions = listOf(sshRow(), row(1, "running"), row(2, "stopped")),
                filter = filter,
            )
            assertThat(s.visible.firstOrNull()?.isServerSsh)
                .`as`("server-ssh row present + pinned under the %s chip", filter)
                .isTrue
        }
    }

    @Test
    fun `chip counts exclude the server-ssh row (UC-62)`() {
        // The chips describe the Claude session population; the host-shell row is
        // not a Claude session and is excluded from All / Running / Stopped.
        val s = SessionsUiState(
            sessions = listOf(
                sshRow(),
                row(1, "running"),
                row(2, "running"),
                row(3, "stopped"),
            ),
        )
        assertThat(s.countAll).`as`("countAll excludes the SSH row").isEqualTo(3)
        assertThat(s.countRunning).`as`("countRunning excludes the SSH row").isEqualTo(2)
        assertThat(s.countStopped).`as`("countStopped excludes the SSH row").isEqualTo(1)
    }

    @Test
    fun `server-ssh row is reachable even when it is the only row (UC-62)`() {
        val s = SessionsUiState(sessions = listOf(sshRow()), filter = SessionsFilter.STOPPED)
        // Even under STOPPED, the lone host-shell row stays visible (it's pinned,
        // not filtered) while contributing zero to every chip count.
        assertThat(s.visible.map { it.n }).containsExactly(0)
        assertThat(s.countAll).isZero()
    }

    // ── UC-70 / UC-92 — showRetryingBackground truth table ────────────────────
    //
    // The derived flag the screen reads to decide whether to OVERRIDE the whole
    // list region with the full-screen "Not connected, retrying…" background.
    // UC-92 narrows the contract to "full-screen ONLY when zero rows are known":
    // the background appears only when there are genuinely ZERO known rows to
    // show AND the outage is real (REST unreachable / never-responded) OR the
    // feed has terminally given up (phase STOPPED → a static "Not connected").
    // With rows still in memory the background is suppressed and the slim
    // non-destructive banner ([showReconnectingBanner], covered in the UC-92
    // block below) is used instead, so a transient reconnect never blanks an
    // in-memory list. It stays FALSE for a healthy connected feed (so a genuine
    // "zero sessions" empty state is NOT mistaken for a disconnect — the use
    // case's headline pitfall) and FALSE during the silent initial connect.

    @Test
    fun `feedStatus defaults to a connected phase (no background until a real drop)`() {
        assertThat(SessionsUiState().feedStatus.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)
        assertThat(SessionsUiState().showRetryingBackground).isFalse
    }

    @Test
    fun `showRetryingBackground is false for a connected feed (AC6)`() {
        val s = SessionsUiState(feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.CONNECTED))
        assertThat(s.showRetryingBackground).isFalse
    }

    @Test
    fun `connected feed with zero sessions does NOT show the retrying background (pitfall)`() {
        // The edge the use case calls out: "connected but genuinely empty" must
        // render the normal empty state, never the retrying message.
        val s = SessionsUiState(
            sessions = emptyList(),
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.CONNECTED),
        )
        assertThat(s.visible).isEmpty()
        assertThat(s.showRetryingBackground)
            .`as`("an empty-but-connected list shows EmptyState, not the disconnect message")
            .isFalse
    }

    @Test
    fun `showRetryingBackground is false during the silent initial connect (anti-flicker)`() {
        // CONNECTING (the first connect, before any failure) is silent — it must
        // not flash the retrying message on a healthy first load.
        val s = SessionsUiState(feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.CONNECTING))
        assertThat(s.showRetryingBackground).isFalse
    }

    @Test
    fun `showRetryingBackground is true while the feed is reconnecting (AC1)`() {
        val s = SessionsUiState(
            feedStatus = SessionsFeedStatus(
                phase = SessionsFeedStatus.Phase.RECONNECTING,
                attempt = 3,
                nextRetryAtMs = 10_000L,
                giveUpAtMs = 300_000L,
            ),
        )
        assertThat(s.feedStatus.reconnecting).isTrue
        assertThat(s.showRetryingBackground).isTrue
    }

    @Test
    fun `showRetryingBackground is true once the feed has terminally given up (STOPPED)`() {
        // Hard-req #4 — a gave-up feed still shows the (static) background, not a
        // blank list, so the operator sees "Not connected" rather than nothing.
        val s = SessionsUiState(feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.STOPPED))
        assertThat(s.feedStatus.reconnecting).isFalse
        assertThat(s.showRetryingBackground).isTrue
    }

    @Test
    fun `a reconnecting feed with known rows is NON-destructive — no full-screen background (UC-92 AC2)`() {
        // UC-92 INVERTS the old UC-70 contract (which blanked the list region on
        // any reconnect). A transient reconnect must NOT wipe an in-memory list:
        // with rows still known the full-screen RetryingBackground is suppressed
        // and the slim non-destructive banner is shown above the preserved rows.
        val s = SessionsUiState(
            sessions = listOf(row(1, "running"), row(2, "running")),
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.RECONNECTING, attempt = 1),
            serverResponded = true,
        )
        assertThat(s.visible).isNotEmpty()
        assertThat(s.showRetryingBackground)
            .`as`("with rows still in state a reconnect keeps them visible — no full-screen background (AC2)")
            .isFalse
        assertThat(s.showReconnectingBanner)
            .`as`("the slim non-destructive banner is shown above the preserved rows (AC2)")
            .isTrue
    }

    // ── UC-92 — non-destructive reconnect: banner vs. full-screen gating ──────
    //
    // The headline UC-92 change: the full-screen RetryingBackground is now gated
    // on `visible.isEmpty()` AND a *genuine* outage, while a slim non-destructive
    // banner ([showReconnectingBanner]) covers the "rows still known" reconnect.
    // These cases pin the truth table directly on the two derived flags + the
    // [genuinelyOffline] heuristic that distinguishes a transient feed blip
    // (REST still healthy) from a real outage (REST unreachable / never-answered).

    @Test
    fun `genuinelyOffline is false when REST is healthy and the server has answered (UC-92)`() {
        // A transient events-socket drop (delete churn / back-nav re-START) leaves
        // REST reachable and the server having answered → NOT genuinely offline.
        val s = SessionsUiState(serverResponded = true, unreachable = false)
        assertThat(s.genuinelyOffline).isFalse
    }

    @Test
    fun `genuinelyOffline is true when REST is unreachable or the server never answered (UC-92 AC6)`() {
        assertThat(SessionsUiState(unreachable = true, serverResponded = true).genuinelyOffline)
            .`as`("a failed REST interaction marks the outage genuine")
            .isTrue
        assertThat(SessionsUiState(unreachable = false, serverResponded = false).genuinelyOffline)
            .`as`("a server that has never answered this session is genuinely offline")
            .isTrue
    }

    @Test
    fun `reconnecting with rows shows the banner and never the background regardless of REST health (UC-92 AC2)`() {
        // Both the transient (REST healthy) and the genuinely-offline-but-rows-known
        // cases keep the rows: visible.isNotEmpty() alone suppresses the full-screen
        // background, so a reconnect never blanks a populated list.
        for (responded in listOf(true, false)) {
            val s = SessionsUiState(
                sessions = listOf(row(1, "running")),
                feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.RECONNECTING, attempt = 2),
                serverResponded = responded,
                unreachable = !responded,
            )
            assertThat(s.showReconnectingBanner)
                .`as`("rows known + feed down → slim banner (serverResponded=%s)", responded)
                .isTrue
            assertThat(s.showRetryingBackground)
                .`as`("rows known → never the full-screen background (serverResponded=%s)", responded)
                .isFalse
        }
    }

    @Test
    fun `zero rows + reconnecting + reachable shows neither background nor banner — normal empty state (UC-92 AC7)`() {
        // AC7 — the only session was just deleted: zero rows but REST is healthy.
        // Neither the full-screen background NOR the banner shows → the normal
        // empty state renders (not the retrying surface).
        val s = SessionsUiState(
            sessions = emptyList(),
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.RECONNECTING, attempt = 1),
            serverResponded = true,
            unreachable = false,
        )
        assertThat(s.visible).isEmpty()
        assertThat(s.genuinelyOffline).isFalse
        assertThat(s.showRetryingBackground)
            .`as`("zero rows but REST healthy → normal empty state, NOT the retrying background (AC7)")
            .isFalse
        assertThat(s.showReconnectingBanner)
            .`as`("the banner needs known rows; with none it stays hidden (AC7)")
            .isFalse
    }

    @Test
    fun `zero rows + reconnecting + genuinely offline shows the full-screen background (UC-92 AC6)`() {
        // AC6 — a real outage with no rows known: the full-screen retrying state
        // is preserved (both the REST-unreachable and the never-responded paths).
        val unreachable = SessionsUiState(
            sessions = emptyList(),
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.RECONNECTING, attempt = 4),
            unreachable = true,
            serverResponded = true,
        )
        assertThat(unreachable.showRetryingBackground)
            .`as`("genuine outage (REST unreachable) + zero rows → full-screen background (AC6)")
            .isTrue
        assertThat(unreachable.showReconnectingBanner).isFalse

        val neverResponded = SessionsUiState(
            sessions = emptyList(),
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.RECONNECTING, attempt = 1),
            serverResponded = false,
        )
        assertThat(neverResponded.showRetryingBackground)
            .`as`("server never answered + zero rows → full-screen background (AC6)")
            .isTrue
    }

    @Test
    fun `terminally STOPPED feed with known rows shows the banner, not the full-screen background (UC-92 AC10)`() {
        // The stale-phase edge: even once the feed has terminally given up
        // (phase STOPPED), as long as rows are still known the list keeps them
        // behind the slim banner — never the full-screen background. (The STOPPED
        // banner copy switches to the static "Not connected" in the composable.)
        val s = SessionsUiState(
            sessions = listOf(row(1, "running"), row(2, "stopped")),
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.STOPPED),
            serverResponded = true,
        )
        assertThat(s.showRetryingBackground)
            .`as`("STOPPED with rows known still keeps them visible (AC10)")
            .isFalse
        assertThat(s.showReconnectingBanner)
            .`as`("a gave-up feed with rows shows the slim banner above them (AC10)")
            .isTrue
    }

    @Test
    fun `terminally STOPPED feed with zero rows still shows the full-screen background (UC-92 — STOPPED arm)`() {
        // Symmetric to the above: with NO rows the STOPPED arm of the gate keeps
        // the full-screen "Not connected" surface (independent of genuinelyOffline).
        val s = SessionsUiState(
            sessions = emptyList(),
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.STOPPED),
            serverResponded = true,
            unreachable = false,
        )
        assertThat(s.genuinelyOffline)
            .`as`("REST healthy → not genuinely offline …")
            .isFalse
        assertThat(s.showRetryingBackground)
            .`as`("… but the STOPPED arm still shows the full-screen background when zero rows are known")
            .isTrue
    }

    // ── UC-72 — connectivity-dot reconnect-cycle precedence (AC1/AC2/AC3/AC5/AC6) ─
    //
    // Pure derivation on SessionsUiState.connectivity. UC-72 adds two arms driven
    // by the live-feed reconnect CYCLE (SessionsFeedStatus.activity) that OUTRANK
    // the UC-54 REST ladder: ATTEMPTING → RETRYING (yellow), WAITING/STOPPED →
    // BACKOFF (red). Green stays REST-derived (REACHABLE). These are the AC2/AC3
    // mappings + AC5 (derived from reconnect state, not a timer) + AC6 (consistent
    // with UC-70, which keys showRetryingBackground off PHASE, not activity).

    private fun feed(
        phase: SessionsFeedStatus.Phase = SessionsFeedStatus.Phase.CONNECTED,
        activity: SessionsFeedStatus.ReconnectActivity = SessionsFeedStatus.ReconnectActivity.IDLE,
    ) = SessionsFeedStatus(phase = phase, activity = activity)

    @Test
    fun `connectivity is RETRYING while a reconnect attempt is in flight (UC-72 AC2)`() {
        // activity == ATTEMPTING → the dot is yellow (RETRYING), regardless of the
        // coarse phase (CONNECTING initial dial or RECONNECTING re-dial).
        assertThat(
            SessionsUiState(
                feedStatus = feed(
                    SessionsFeedStatus.Phase.RECONNECTING,
                    SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
                ),
            ).connectivity,
        ).isEqualTo(Connectivity.RETRYING)

        assertThat(
            SessionsUiState(
                feedStatus = feed(
                    SessionsFeedStatus.Phase.CONNECTING,
                    SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
                ),
            ).connectivity,
        ).`as`("the initial connect dial also reads yellow (AC2 edge)").isEqualTo(Connectivity.RETRYING)
    }

    @Test
    fun `connectivity is BACKOFF while waiting out the back-off delay (UC-72 AC3)`() {
        assertThat(
            SessionsUiState(
                feedStatus = feed(
                    SessionsFeedStatus.Phase.RECONNECTING,
                    SessionsFeedStatus.ReconnectActivity.WAITING,
                ),
            ).connectivity,
        ).isEqualTo(Connectivity.BACKOFF)
    }

    @Test
    fun `connectivity is BACKOFF when the feed has terminally stopped (UC-72)`() {
        // A gave-up feed (phase STOPPED, activity IDLE) reads red — the dot's
        // STOPPED arm is keyed off the phase, not the activity.
        assertThat(
            SessionsUiState(feedStatus = feed(SessionsFeedStatus.Phase.STOPPED)).connectivity,
        ).isEqualTo(Connectivity.BACKOFF)
    }

    @Test
    fun `RETRYING outranks the REST ladder even when unreachable and answered (UC-72 precedence)`() {
        // AC5/precedence — while the push feed is actively dialing, that live
        // signal wins over a possibly-stale REST verdict (loading + unreachable +
        // serverResponded would otherwise yield CHECKING/UNREACHABLE/REACHABLE).
        val s = SessionsUiState(
            loading = true,
            unreachable = true,
            serverResponded = true,
            pendingActions = setOf(1),
            feedStatus = feed(
                SessionsFeedStatus.Phase.RECONNECTING,
                SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
            ),
        )
        assertThat(s.connectivity).isEqualTo(Connectivity.RETRYING)
    }

    @Test
    fun `BACKOFF outranks the REST ladder even when the server has answered (UC-72 precedence)`() {
        val s = SessionsUiState(
            serverResponded = true,
            feedStatus = feed(
                SessionsFeedStatus.Phase.RECONNECTING,
                SessionsFeedStatus.ReconnectActivity.WAITING,
            ),
        )
        assertThat(s.connectivity)
            .`as`("the WAITING back-off (red) wins over a prior REACHABLE")
            .isEqualTo(Connectivity.BACKOFF)
    }

    @Test
    fun `with an IDLE feed the REST ladder is unchanged (UC-72 — green stays REST-driven)`() {
        // activity == IDLE (CONNECTED feed) → the dot falls through to the exact
        // UC-54 REST ladder: CHECKING / UNREACHABLE / REACHABLE / UNKNOWN.
        val idle = feed() // CONNECTED + IDLE

        assertThat(SessionsUiState(feedStatus = idle, loading = true).connectivity)
            .`as`("IDLE + loading → CHECKING").isEqualTo(Connectivity.CHECKING)
        assertThat(SessionsUiState(feedStatus = idle, spawning = true).connectivity)
            .`as`("IDLE + spawning → CHECKING").isEqualTo(Connectivity.CHECKING)
        assertThat(SessionsUiState(feedStatus = idle, pendingActions = setOf(1)).connectivity)
            .`as`("IDLE + a pending action → CHECKING").isEqualTo(Connectivity.CHECKING)
        assertThat(SessionsUiState(feedStatus = idle, unreachable = true).connectivity)
            .`as`("IDLE + unreachable → UNREACHABLE").isEqualTo(Connectivity.UNREACHABLE)
        assertThat(SessionsUiState(feedStatus = idle, serverResponded = true).connectivity)
            .`as`("IDLE + answered → REACHABLE (green stays REST-driven)").isEqualTo(Connectivity.REACHABLE)
        assertThat(SessionsUiState(feedStatus = idle).connectivity)
            .`as`("IDLE + nothing resolved → UNKNOWN").isEqualTo(Connectivity.UNKNOWN)
    }

    @Test
    fun `showRetryingBackground is unaffected by the dot activity (UC-72 orthogonality, AC6)`() {
        // The UC-70 background is keyed off PHASE/reconnecting, NOT the UC-72 dot
        // activity, so toggling ATTEMPTING↔WAITING within a phase never changes it
        // (no conflict between the dot and the background — AC6).
        val reconnectingAttempt = SessionsUiState(
            feedStatus = feed(
                SessionsFeedStatus.Phase.RECONNECTING,
                SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
            ),
        )
        val reconnectingWait = SessionsUiState(
            feedStatus = feed(
                SessionsFeedStatus.Phase.RECONNECTING,
                SessionsFeedStatus.ReconnectActivity.WAITING,
            ),
        )
        assertThat(reconnectingAttempt.showRetryingBackground)
            .`as`("RECONNECTING shows the background whether the dot is yellow …").isTrue
        assertThat(reconnectingWait.showRetryingBackground)
            .`as`("… or red — the activity does not drive the background").isTrue

        // And the silent initial connect (CONNECTING) keeps the background hidden
        // even though its activity is ATTEMPTING (the dot is yellow).
        val connecting = SessionsUiState(
            feedStatus = feed(
                SessionsFeedStatus.Phase.CONNECTING,
                SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
            ),
        )
        assertThat(connecting.connectivity).isEqualTo(Connectivity.RETRYING)
        assertThat(connecting.showRetryingBackground)
            .`as`("the CONNECTING dial shows no background despite the yellow dot").isFalse
    }
}
