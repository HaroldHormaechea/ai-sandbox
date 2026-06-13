package com.aisandbox.android.ui.screens

import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.SessionsApi
import com.aisandbox.android.net.TerminatingSessionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-32 — the live-push apply paths on [SessionsCoordinator]
 * ([SessionsCoordinator.applySnapshot] / [SessionsCoordinator.applyDelta]).
 *
 * <p>These are the pure, Android-free core the events feed drives: they mutate
 * only the working set + the reconciled optimistic-terminating set and run on a
 * plain JVM (no Robolectric, no network). The coordinator is constructed with
 * suppliers that THROW if touched — proving the apply paths update the
 * [SessionsUiState] StateFlow with NO `refresh()` / REST round-trip (AC7).
 *
 * <p>The scope uses [Dispatchers.Unconfined] so the constructor's
 * terminating-mirror collector runs synchronously, making every assertion
 * deterministic.
 *
 * <h2>AC → test map</h2>
 * <ul>
 *   <li>AC1 / AC7 — {@link #delta_updates_row_state_and_chip_counts_without_refresh}</li>
 *   <li>AC3      — {@link #delta_upsert_adds_a_new_row}, {@link #delta_removal_drops_a_row},
 *                  {@link #delta_is_idempotent_when_reapplied}</li>
 *   <li>AC4      — {@link #push_removal_converges_with_optimistic_terminating},
 *                  {@link #server_terminating_upsert_hands_off_optimistic_flag}</li>
 *   <li>AC5      — {@link #snapshot_is_a_full_resync}, {@link #apply_paths_never_touch_loading}</li>
 * </ul>
 */
class SessionsCoordinatorEventsTest {

    private fun summary(n: Int, state: String): SessionSummary =
        SessionSummary(n = n, label = "s$n", tmuxTitle = "", state = state, uptimeSec = 0L, activeStreams = 0)

    /** apiFactory / profileSupplier must NEVER be reached by an apply path. */
    private fun newCoordinator(
        state: MutableStateFlow<SessionsUiState>,
        store: TerminatingSessionsStore = TerminatingSessionsStore(),
    ): SessionsCoordinator = SessionsCoordinator(
        state = state,
        scope = CoroutineScope(Dispatchers.Unconfined),
        profileSupplier = { error("profileSupplier must not be called by a push-apply path") },
        apiFactory = { _: ServerProfile -> error("apiFactory must not be called by a push-apply path") },
        terminatingSessions = store,
    )

    @Test
    fun delta_updates_row_state_and_chip_counts_without_refresh() {
        val state = MutableStateFlow(
            SessionsUiState(sessions = listOf(summary(1, "running"), summary(2, "stopped"))),
        )
        val coordinator = newCoordinator(state)

        assertThat(state.value.countRunning).isEqualTo(1)
        assertThat(state.value.countStopped).isEqualTo(1)

        // Server pushes: session 1 transitioned running → stopped.
        coordinator.applyDelta(upserts = listOf(summary(1, "stopped")), removed = emptyList())

        val s1 = state.value.sessions.first { it.n == 1 }
        assertThat(s1.state).isEqualTo("stopped")
        assertThat(state.value.effectiveState(s1)).isEqualTo("stopped")
        // Counts recompute off the new working set — no manual refresh.
        assertThat(state.value.countRunning).isEqualTo(0)
        assertThat(state.value.countStopped).isEqualTo(2)
    }

    @Test
    fun delta_upsert_adds_a_new_row() {
        val state = MutableStateFlow(SessionsUiState(sessions = listOf(summary(1, "running"))))
        val coordinator = newCoordinator(state)

        coordinator.applyDelta(upserts = listOf(summary(2, "starting")), removed = emptyList())

        assertThat(state.value.sessions.map { it.n }).containsExactly(1, 2)
        assertThat(state.value.sessions.first { it.n == 2 }.state).isEqualTo("starting")
    }

    // ── UC-61 — a delta carrying the server's real row replaces the optimistic
    //            placeholder instead of adding a second row (AC3). ────────────
    //
    // spawn() inserts a client-only optimistic placeholder keyed by a GUESSED
    // `n` (max(n)+1). When the server assigns a DIFFERENT `n`, the UC-32 push
    // feed's merge-by-`n` upsert would historically ADD the real row beside the
    // placeholder (two rows until a full snapshot/refresh landed — the defect).
    // applyDelta now evicts the placeholder when (a) one is pending AND (b) the
    // delta introduces a genuinely-new `n`, so the real row REPLACES it.

    /** An optimistic placeholder row as spawn() builds it (optimistic = true). */
    private fun placeholder(n: Int): SessionSummary =
        SessionSummary(n = n, label = "", tmuxTitle = "", state = "starting", optimistic = true)

    @Test
    fun delta_with_new_n_replaces_optimistic_placeholder_instead_of_duplicating() {
        // Prior set: an authoritative row n=1 + an optimistic placeholder guessed
        // at n=2. The server actually assigned n=5 (≠ the guess) and the push feed
        // delivers it as a delta.
        val state = MutableStateFlow(
            SessionsUiState(sessions = listOf(summary(1, "running"), placeholder(2))),
        )
        val coordinator = newCoordinator(state)

        coordinator.applyDelta(upserts = listOf(summary(5, "starting")), removed = emptyList())

        // AC3 — the placeholder (guessed n=2) is gone; exactly the real rows remain.
        assertThat(state.value.sessions.map { it.n })
            .`as`("the optimistic placeholder is replaced by the server's real row, not duplicated")
            .containsExactly(1, 5)
        assertThat(state.value.sessions)
            .`as`("no optimistic placeholder survives once the real row arrives")
            .noneMatch { it.optimistic }
    }

    @Test
    fun delta_updating_an_existing_n_does_not_evict_a_pending_placeholder() {
        // A plain uptime/state tick for an EXISTING n introduces no new `n`, so a
        // still-pending placeholder (spawn-Success not landed yet) MUST be kept —
        // it is only ever cleared by its real replacement, spawn-Success, or a
        // rollback. Evicting it on a mere tick would flicker the row away early.
        val state = MutableStateFlow(
            SessionsUiState(sessions = listOf(summary(1, "running"), placeholder(2))),
        )
        val coordinator = newCoordinator(state)

        coordinator.applyDelta(upserts = listOf(summary(1, "stopped")), removed = emptyList())

        assertThat(state.value.sessions.map { it.n })
            .`as`("a tick on an existing row must not drop the still-pending placeholder")
            .containsExactlyInAnyOrder(1, 2)
        assertThat(state.value.sessions.first { it.n == 2 }.optimistic).isTrue()
    }

    @Test
    fun delta_with_new_n_and_no_placeholder_still_just_adds_the_row() {
        // Regression guard: with NO optimistic placeholder pending, the eviction
        // pass is inert and a genuinely-new `n` is added exactly as before
        // (the original delta_upsert_adds_a_new_row contract is preserved).
        val state = MutableStateFlow(SessionsUiState(sessions = listOf(summary(1, "running"))))
        val coordinator = newCoordinator(state)

        coordinator.applyDelta(upserts = listOf(summary(2, "starting")), removed = emptyList())

        assertThat(state.value.sessions.map { it.n }).containsExactly(1, 2)
    }

    @Test
    fun delta_removal_drops_a_row() {
        val state = MutableStateFlow(
            SessionsUiState(sessions = listOf(summary(1, "running"), summary(2, "running"))),
        )
        val coordinator = newCoordinator(state)

        coordinator.applyDelta(upserts = emptyList(), removed = listOf(2))

        assertThat(state.value.sessions.map { it.n }).containsExactly(1)
    }

    @Test
    fun delta_is_idempotent_when_reapplied() {
        val state = MutableStateFlow(SessionsUiState(sessions = listOf(summary(1, "running"))))
        val coordinator = newCoordinator(state)

        val upserts = listOf(summary(2, "running"))
        coordinator.applyDelta(upserts = upserts, removed = emptyList())
        coordinator.applyDelta(upserts = upserts, removed = emptyList())

        // Keyed by n — a replayed delta is a no-op, never a duplicate row.
        assertThat(state.value.sessions.map { it.n }).containsExactly(1, 2)
    }

    @Test
    fun snapshot_is_a_full_resync() {
        val state = MutableStateFlow(
            SessionsUiState(sessions = listOf(summary(1, "running"), summary(2, "stopped"))),
        )
        val coordinator = newCoordinator(state)

        // The authoritative snapshot replaces the working set wholesale.
        coordinator.applySnapshot(listOf(summary(3, "provisioning")))

        assertThat(state.value.sessions.map { it.n }).containsExactly(3)
        assertThat(state.value.sessions.single().state).isEqualTo("provisioning")
    }

    @Test
    fun apply_paths_never_touch_loading() {
        val state = MutableStateFlow(
            SessionsUiState(loading = true, sessions = listOf(summary(1, "running"))),
        )
        val coordinator = newCoordinator(state)

        coordinator.applyDelta(upserts = listOf(summary(1, "stopped")), removed = emptyList())
        assertThat(state.value.loading).isTrue()

        coordinator.applySnapshot(listOf(summary(1, "running")))
        // A reconnecting feed must not drive the REST spinner (AC5).
        assertThat(state.value.loading).isTrue()
    }

    @Test
    fun push_removal_converges_with_optimistic_terminating() {
        // UC-28: operator optimistically marked session 1 terminating.
        val store = TerminatingSessionsStore().apply { mark(1) }
        val state = MutableStateFlow(SessionsUiState(sessions = listOf(summary(1, "running"))))
        val coordinator = newCoordinator(state, store)

        // The Unconfined collector mirrored the optimistic flag into the state.
        assertThat(state.value.terminating).containsExactly(1)

        // Server confirms teardown by removing the row.
        coordinator.applyDelta(upserts = emptyList(), removed = listOf(1))

        // The optimistic flag is cleared (teardown completed, AC4/AC7) — no
        // stuck "awaiting termination", and the store is reconciled too.
        assertThat(state.value.sessions).isEmpty()
        assertThat(state.value.terminating).isEmpty()
        assertThat(store.flow.value).isEmpty()
    }

    @Test
    fun server_terminating_upsert_hands_off_optimistic_flag() {
        val store = TerminatingSessionsStore().apply { mark(1) }
        val state = MutableStateFlow(SessionsUiState(sessions = listOf(summary(1, "running"))))
        val coordinator = newCoordinator(state, store)
        assertThat(state.value.terminating).containsExactly(1)

        // Server now authoritatively reports `terminating` for the same row.
        coordinator.applyDelta(upserts = listOf(summary(1, "terminating")), removed = emptyList())

        val row = state.value.sessions.single()
        // Optimistic flag handed off to the server token; the pill stays via the
        // server state — no flicker back to running (AC4).
        assertThat(state.value.terminating).isEmpty()
        assertThat(state.value.effectiveState(row)).isEqualTo("terminating")
    }
}
