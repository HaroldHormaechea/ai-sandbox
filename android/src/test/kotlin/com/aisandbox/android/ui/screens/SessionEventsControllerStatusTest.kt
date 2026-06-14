package com.aisandbox.android.ui.screens

import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.net.SessionEventMessage
import com.aisandbox.android.net.SessionEventsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/**
 * UC-70 status-emission contract + **UC-71** unlimited-retry reconciliation for
 * the [SessionEventsController] connect/back-off loop. This is the one place the
 * "Not connected, retrying…" background's source-of-truth is computed, so the
 * invariants are pinned HERE on the controller loop itself, not just on the
 * rendered surface.
 *
 * <h2>UC-71 — what changed</h2>
 * Production now builds its [com.aisandbox.android.net.ReconnectController] with
 * the **unlimited default** (no [retryBudgetMs]). So on the real loop:
 * <ul>
 *   <li>every RECONNECTING emission carries {@code giveUpAtMs == null} (no
 *       finite give-up instant to surface — the UI's "limit" line vanishes);</li>
 *   <li>the loop **never** emits the terminal {@code STOPPED} phase — it retries
 *       forever (bounded only by lifecycle STOP, tested elsewhere). The finite
 *       give-up → STOPPED capability still exists as an injectable seam and is
 *       unit-covered in
 *       {@link com.aisandbox.android.net.ReconnectControllerTest}.</li>
 * </ul>
 *
 * <h2>Deterministic driving</h2>
 * The loop's real back-off delays (1, 2, 4, … s) and its `nowMs` clock are both
 * driven from the [runTest] virtual scheduler: the controller is handed a scope
 * on an [UnconfinedTestDispatcher] over that scheduler (so `delay()` is virtual
 * and the loop body runs eagerly) and `nowMs = { testScheduler.currentTime }`
 * (so the emitted next-retry instants are computed against the SAME virtual
 * clock the schedule advances against). The feed [SessionEventsClient] is a
 * Mockito mock whose `state` is scripted per attempt, so we choose exactly when
 * a connect "fails" (→ Disconnected) versus "succeeds" (→ Open). The injected
 * scope is cancelled at the end of each test so the loop's final park (collecting
 * on an Open feed) never leaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionEventsControllerStatusTest {

    /** A scripted feed client: its `state` is fixed, `incoming` is inert. */
    private fun fakeClient(state: SessionEventsClient.State): SessionEventsClient {
        val client = Mockito.mock(SessionEventsClient::class.java)
        Mockito.`when`(client.state).thenReturn(MutableStateFlow(state))
        Mockito.`when`(client.incoming).thenReturn(MutableSharedFlow<SessionEventMessage>())
        return client
    }

    /**
     * A [ServerProfileStore] whose suspend `current()` returns [profile]. Plain
     * `when(store.current()).thenReturn(...)` does NOT work for a suspend fun —
     * Mockito matches the implicit Continuation argument by equality, and the
     * continuation at stub-time differs from the one at call-time, so the stub
     * never fires (the call falls through to null). A method-name default Answer
     * sidesteps argument matching entirely.
     */
    private fun profileStoreReturning(profile: ServerProfile?): ServerProfileStore =
        Mockito.mock(ServerProfileStore::class.java, Answer { inv ->
            if (inv.method.name == "current") profile else Mockito.RETURNS_DEFAULTS.answer(inv)
        })

    @Test
    fun connecting_then_reconnecting_sequence_never_re_emits_connecting() = runTest {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        val profile = Mockito.mock(ServerProfile::class.java)
        val profileStore = profileStoreReturning(profile)

        // First two connects fail (→ Disconnected → back-off); the third opens.
        var attempt = 0
        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = {
                val state =
                    if (attempt < 2) {
                        SessionEventsClient.State.Disconnected("forced-fail")
                    } else {
                        SessionEventsClient.State.Open
                    }
                attempt++
                fakeClient(state)
            },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = { emissions.add(it) },
            nowMs = { testScheduler.currentTime },
            scope = loopScope,
        )

        controller.connect()
        advanceUntilIdle()
        loopScope.cancel()

        // The full phase sequence — and the #1 invariant: CONNECTING is the FIRST
        // emission and the ONLY one; it is never re-emitted between attempts.
        assertThat(emissions.map { it.phase }).containsExactly(
            SessionsFeedStatus.Phase.CONNECTING,
            SessionsFeedStatus.Phase.RECONNECTING,
            SessionsFeedStatus.Phase.RECONNECTING,
            SessionsFeedStatus.Phase.CONNECTED,
        )
        assertThat(emissions.count { it.phase == SessionsFeedStatus.Phase.CONNECTING })
            .`as`("CONNECTING is emitted exactly once (anti-flicker hard-req #1)")
            .isEqualTo(1)

        // #2/#3 — the first RECONNECTING carries attempt 1, next-retry at now(0)+1s.
        // UC-71 — giveUpAtMs is null: production uses the unlimited default so no
        // finite give-up instant is surfaced (was GIVE_UP_AFTER_MS).
        val first = emissions[1]
        assertThat(first.attempt).isEqualTo(1)
        assertThat(first.nextRetryAtMs).isEqualTo(1_000L)
        assertThat(first.giveUpAtMs)
            .`as`("UC-71 — unlimited default surfaces no give-up instant")
            .isNull()
        assertThat(first.reconnecting).isTrue()

        // The second RECONNECTING fires after the 1s delay elapsed: attempt 2,
        // next-retry at now(1_000)+2s = 3_000, still no give-up instant (UC-71).
        val second = emissions[2]
        assertThat(second.attempt).isEqualTo(2)
        assertThat(second.nextRetryAtMs).isEqualTo(3_000L)
        assertThat(second.giveUpAtMs).isNull()

        // CONNECTED clears the surface (default phase carries no timing/attempt).
        val connected = emissions.last()
        assertThat(connected.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)
        assertThat(connected.reconnecting).isFalse()
    }

    @Test
    fun never_gives_up_past_the_old_5min_budget_then_reconnects_when_the_server_returns() = runTest {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        val profile = Mockito.mock(ServerProfile::class.java)
        val profileStore = profileStoreReturning(profile)

        // The first 50 connects fail; the 51st opens. With the 10 s cap the
        // cumulative back-off is 1+2+4+8 + 10·46 = 475 s — WELL past the old
        // 5-minute (300 s) give-up budget. Pre-UC-71 the loop would have emitted
        // a terminal STOPPED long before; UC-71 must keep retrying and recover.
        val failuresBeforeOpen = 50
        var attempt = 0
        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = {
                val state =
                    if (attempt < failuresBeforeOpen) {
                        SessionEventsClient.State.Disconnected("down")
                    } else {
                        SessionEventsClient.State.Open
                    }
                attempt++
                fakeClient(state)
            },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = { emissions.add(it) },
            nowMs = { testScheduler.currentTime },
            scope = loopScope,
        )

        controller.connect()
        advanceUntilIdle()
        loopScope.cancel()

        // AC1/AC4 — the loop NEVER gives up on its own: no terminal STOPPED is
        // ever emitted, no matter how long the outage runs.
        assertThat(emissions.map { it.phase })
            .`as`("UC-71 — unlimited retries never produce a terminal STOPPED")
            .doesNotContain(SessionsFeedStatus.Phase.STOPPED)

        // Exactly one CONNECTING; then a long run of RECONNECTING; finally CONNECTED.
        assertThat(emissions.first().phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTING)
        assertThat(emissions.count { it.phase == SessionsFeedStatus.Phase.CONNECTING }).isEqualTo(1)
        assertThat(emissions.last().phase)
            .`as`("AC7 — the server returns and the feed reconnects")
            .isEqualTo(SessionsFeedStatus.Phase.CONNECTED)

        val reconnects = emissions.filter { it.phase == SessionsFeedStatus.Phase.RECONNECTING }
        // One RECONNECTING per failed attempt — well past the old 6-entry cap (AC5/AC6).
        assertThat(reconnects).hasSize(failuresBeforeOpen)
        assertThat(reconnects.last().attempt)
            .`as`("attemptCount keeps climbing past the former cap (AC5/AC6)")
            .isEqualTo(failuresBeforeOpen)

        // AC5 — under the unlimited default every RECONNECTING surfaces NO give-up
        // instant (the UI's "giving up / limit" line stays hidden).
        assertThat(reconnects)
            .`as`("UC-71 — unlimited default: no RECONNECTING carries a give-up instant")
            .allMatch { it.giveUpAtMs == null }

        // AC2 — inter-attempt back-off never exceeds the 10 s cap. nextRetryAtMs
        // is now()+delay at emission time; since the scheduler advances by exactly
        // each delay, successive emitted instants step by ≤ 10 s.
        val retryInstants = reconnects.map { it.nextRetryAtMs!! }
        for (i in 1 until retryInstants.size) {
            assertThat(retryInstants[i] - retryInstants[i - 1])
                .`as`("back-off step never exceeds the 10 s cap (AC2)")
                .isLessThanOrEqualTo(10_000L)
        }

        // No CONNECTING ever re-appears after back-off begins (anti-flicker).
        val firstReconnectIdx = emissions.indexOfFirst { it.phase == SessionsFeedStatus.Phase.RECONNECTING }
        assertThat(emissions.drop(firstReconnectIdx))
            .noneMatch { it.phase == SessionsFeedStatus.Phase.CONNECTING }
    }

    @Test
    fun no_profile_emits_nothing_and_builds_no_client() = runTest {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        // A profile store that reports no configured profile (current() → null).
        val profileStore = profileStoreReturning(null)

        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { error("no profile → the HTTP client must not be built") },
            eventsClientFactory = { error("no profile → the events client must not be built") },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = { emissions.add(it) },
            nowMs = { testScheduler.currentTime },
            scope = loopScope,
        )

        controller.connect()
        advanceUntilIdle()
        loopScope.cancel()

        assertThat(emissions)
            .`as`("an unconfigured feed stays silent — never flashes the retrying background (hard-req #5)")
            .isEmpty()
    }
}
