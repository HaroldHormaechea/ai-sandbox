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
        assertThat(s.visible).extracting { it.n }.containsExactly(1, 3, 5)
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
        assertThat(s.visible).extracting { it.n }.containsExactly(2)
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
}
