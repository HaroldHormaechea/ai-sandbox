package com.aisandbox.android.ui.screens

import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ReconnectController
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
 * UC-70 — the [SessionEventsController] connect/back-off loop's NEW
 * status-emission contract (challenger hard-reqs #1/#2/#3/#4/#5). This is the
 * one place the "Not connected, retrying…" background's source-of-truth is
 * computed, so the invariants are pinned HERE on the controller loop itself,
 * not just on the rendered surface.
 *
 * <h2>Deterministic driving</h2>
 * The loop's real back-off delays (1, 2, 4, … s) and its `nowMs` clock are both
 * driven from the [runTest] virtual scheduler: the controller is handed a scope
 * on an [UnconfinedTestDispatcher] over that scheduler (so `delay()` is virtual
 * and the loop body runs eagerly) and `nowMs = { testScheduler.currentTime }`
 * (so the emitted next-retry / give-up instants are computed against the SAME
 * virtual clock the schedule advances against). The feed [SessionEventsClient]
 * is a Mockito mock whose `state` is scripted per attempt, so we choose exactly
 * when a connect "fails" (→ Disconnected) versus "succeeds" (→ Open) without any
 * real socket. The injected scope is cancelled at the end of each test so the
 * loop's final park (collecting on an Open feed) never leaks.
 *
 * <h2>AC / hard-req → test map</h2>
 * <ul>
 *   <li>#1 anti-flicker (REQUIRED) —
 *       {@link #connecting_then_reconnecting_sequence_never_re_emits_connecting()}:
 *       CONNECTING is emitted exactly once (before the first failure) and is NEVER
 *       re-emitted between back-off attempts; the sequence is
 *       CONNECTING → RECONNECTING → RECONNECTING → … → CONNECTED.</li>
 *   <li>#2/#3 (AC3/AC4/AC5) — the SAME test asserts the exact post-increment
 *       attempt count, the next-retry instant (= now + backoff delay), and the
 *       finite give-up instant carried by each RECONNECTING emission.</li>
 *   <li>#4 (AC-give-up) — {@link #exhausting_the_budget_emits_a_terminal_stopped()}:
 *       once the cumulative budget is spent the loop emits a terminal STOPPED.</li>
 *   <li>#5 (silent no-profile) — {@link #no_profile_emits_nothing_and_builds_no_client()}:
 *       with no configured profile the feed stays silent (no emission, no client).</li>
 * </ul>
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

        // #2/#3 — the first RECONNECTING carries attempt 1, next-retry at now(0)+1s,
        // and the finite 5-min give-up instant (firstFailure 0 + budget).
        val first = emissions[1]
        assertThat(first.attempt).isEqualTo(1)
        assertThat(first.nextRetryAtMs).isEqualTo(1_000L)
        assertThat(first.giveUpAtMs).isEqualTo(ReconnectController.GIVE_UP_AFTER_MS)
        assertThat(first.reconnecting).isTrue()

        // The second RECONNECTING fires after the 1s delay elapsed: attempt 2,
        // next-retry at now(1_000)+2s = 3_000, same finite give-up instant.
        val second = emissions[2]
        assertThat(second.attempt).isEqualTo(2)
        assertThat(second.nextRetryAtMs).isEqualTo(3_000L)
        assertThat(second.giveUpAtMs).isEqualTo(ReconnectController.GIVE_UP_AFTER_MS)

        // CONNECTED clears the surface (default phase carries no timing/attempt).
        val connected = emissions.last()
        assertThat(connected.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)
        assertThat(connected.reconnecting).isFalse()
    }

    @Test
    fun exhausting_the_budget_emits_a_terminal_stopped() = runTest {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        val profile = Mockito.mock(ServerProfile::class.java)
        val profileStore = profileStoreReturning(profile)

        // Every connect fails forever → the loop backs off until the cumulative
        // 5-min budget is spent, then gives up.
        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = { fakeClient(SessionEventsClient.State.Disconnected("down")) },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = { emissions.add(it) },
            nowMs = { testScheduler.currentTime },
            scope = loopScope,
        )

        controller.connect()
        advanceUntilIdle()
        loopScope.cancel()

        // Exactly one CONNECTING, at least one RECONNECTING, terminal STOPPED.
        assertThat(emissions.first().phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTING)
        assertThat(emissions.last().phase)
            .`as`("the budget runs out → a terminal STOPPED (hard-req #4)")
            .isEqualTo(SessionsFeedStatus.Phase.STOPPED)
        assertThat(emissions.count { it.phase == SessionsFeedStatus.Phase.CONNECTING }).isEqualTo(1)

        val reconnects = emissions.filter { it.phase == SessionsFeedStatus.Phase.RECONNECTING }
        assertThat(reconnects).isNotEmpty()
        // AC5 finite-budget branch — while a real budget exists every RECONNECTING
        // surfaces a non-null give-up instant (the UI's "giving up" line shows).
        assertThat(reconnects).allMatch { it.giveUpAtMs != null }
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
