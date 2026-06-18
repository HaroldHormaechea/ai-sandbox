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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/**
 * UC-70 status-emission contract + **UC-71** unlimited-retry reconciliation +
 * **UC-72** attempt-vs-backoff activity for the [SessionEventsController]
 * connect/back-off loop. This is the one place the "Not connected, retrying…"
 * background AND UC-72's yellow/red status dot derive their source-of-truth, so
 * the invariants are pinned HERE on the controller loop itself, not just on the
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
 * <h2>UC-72 — the activity field</h2>
 * Each emission now also carries a [SessionsFeedStatus.ReconnectActivity],
 * ORTHOGONAL to the phase:
 * <ul>
 *   <li>before EVERY {@code client.connect()} dial the loop emits
 *       {@code ATTEMPTING} (status dot → yellow), with {@code nextRetryAtMs ==
 *       null} (no scheduled retry while the dial is in flight);</li>
 *   <li>before each back-off {@code delay()} it emits {@code WAITING} (status dot
 *       → red), carrying the post-increment attempt + the {@code nextRetryAtMs}
 *       the countdown ticks toward;</li>
 *   <li>an Open feed and a give-up are {@code IDLE}.</li>
 * </ul>
 * The anti-flicker contract from UC-70 is preserved because the ATTEMPTING dial
 * emit HOLDS the same {@code phase}, {@code attempt}, and {@code giveUpAtMs} as
 * the preceding WAITING emit — only {@code activity} and {@code nextRetryAtMs}
 * toggle. That stability is pinned by
 * [consecutive_waiting_then_attempting_hold_attempt_and_giveUp_only_dot_toggles].
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
 * scope is cancelled at the end of each run so the loop's final park (collecting
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

    /**
     * Drive the connect/back-off loop until idle and return every emitted
     * [SessionsFeedStatus] in order. The first [failuresBeforeOpen] dials fail
     * (→ Disconnected → back-off); the next one opens. `failuresBeforeOpen == 0`
     * opens on the very first dial (a clean initial connect).
     */
    private fun TestScope.collectFeedEmissions(failuresBeforeOpen: Int): List<SessionsFeedStatus> {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        val profile = Mockito.mock(ServerProfile::class.java)
        val profileStore = profileStoreReturning(profile)
        var attempt = 0
        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = {
                val state =
                    if (attempt < failuresBeforeOpen) {
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
        return emissions
    }

    @Test
    fun connecting_then_reconnecting_sequence_never_re_emits_connecting() = runTest {
        // Two connects fail (→ Disconnected → back-off); the third opens. The
        // UC-72 dial+backoff emits double the entries vs. UC-70: every loop top
        // now emits an ATTEMPTING dial, and each back-off emits a WAITING.
        val emissions = collectFeedEmissions(failuresBeforeOpen = 2)

        // The full (phase, activity) sequence — and the #1 invariant: CONNECTING
        // is the FIRST emission and the ONLY one; it is never re-emitted.
        assertThat(emissions.map { it.phase }).containsExactly(
            SessionsFeedStatus.Phase.CONNECTING, // dial #1 (initial, attempt 0)
            SessionsFeedStatus.Phase.RECONNECTING, // back-off after fail #1
            SessionsFeedStatus.Phase.RECONNECTING, // dial #2 (attempt 1)
            SessionsFeedStatus.Phase.RECONNECTING, // back-off after fail #2
            SessionsFeedStatus.Phase.RECONNECTING, // dial #3 (attempt 2)
            SessionsFeedStatus.Phase.CONNECTED, // open
        )
        assertThat(emissions.map { it.activity }).containsExactly(
            SessionsFeedStatus.ReconnectActivity.ATTEMPTING, // dial #1 → yellow
            SessionsFeedStatus.ReconnectActivity.WAITING, // back-off → red
            SessionsFeedStatus.ReconnectActivity.ATTEMPTING, // dial #2 → yellow
            SessionsFeedStatus.ReconnectActivity.WAITING, // back-off → red
            SessionsFeedStatus.ReconnectActivity.ATTEMPTING, // dial #3 → yellow
            SessionsFeedStatus.ReconnectActivity.IDLE, // open → dot rejoins REST ladder
        )
        assertThat(emissions.count { it.phase == SessionsFeedStatus.Phase.CONNECTING })
            .`as`("CONNECTING is emitted exactly once (anti-flicker hard-req #1)")
            .isEqualTo(1)

        // #2/#3 — the first WAITING back-off carries attempt 1, next-retry at
        // now(0)+1s. UC-71 — giveUpAtMs is null: production uses the unlimited
        // default so no finite give-up instant is surfaced.
        val firstWait = emissions[1]
        assertThat(firstWait.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.WAITING)
        assertThat(firstWait.attempt).isEqualTo(1)
        assertThat(firstWait.nextRetryAtMs).isEqualTo(1_000L)
        assertThat(firstWait.giveUpAtMs)
            .`as`("UC-71 — unlimited default surfaces no give-up instant")
            .isNull()
        assertThat(firstWait.reconnecting).isTrue()

        // The second WAITING fires after the 1s delay elapsed: attempt 2,
        // next-retry at now(1_000)+2s = 3_000, still no give-up instant (UC-71).
        val secondWait = emissions[3]
        assertThat(secondWait.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.WAITING)
        assertThat(secondWait.attempt).isEqualTo(2)
        assertThat(secondWait.nextRetryAtMs).isEqualTo(3_000L)
        assertThat(secondWait.giveUpAtMs).isNull()

        // CONNECTED clears the surface (default phase carries no timing/attempt).
        val connected = emissions.last()
        assertThat(connected.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)
        assertThat(connected.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.IDLE)
        assertThat(connected.reconnecting).isFalse()
    }

    @Test
    fun initial_dial_emits_connecting_attempting_with_no_retrying_background() = runTest {
        // AC2 (initial-connect edge) — the first-ever dial, before any failure,
        // is CONNECTING + ATTEMPTING: the dot reads yellow (RETRYING) while the
        // initial connect is in flight, yet phase stays CONNECTING so UC-70's
        // retrying BACKGROUND never flashes on a healthy first load.
        val emissions = collectFeedEmissions(failuresBeforeOpen = 0)

        val firstDial = emissions.first()
        assertThat(firstDial.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTING)
        assertThat(firstDial.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.ATTEMPTING)
        assertThat(firstDial.attempt).isEqualTo(0)
        assertThat(firstDial.nextRetryAtMs)
            .`as`("no scheduled retry while the dial is in flight (countdown hidden)")
            .isNull()

        // The dot reads yellow (RETRYING) on the initial dial …
        assertThat(SessionsUiState(feedStatus = firstDial).connectivity)
            .isEqualTo(Connectivity.RETRYING)
        // … but the retrying background stays hidden (CONNECTING is silent).
        assertThat(SessionsUiState(feedStatus = firstDial).showRetryingBackground)
            .`as`("AC6/anti-flicker — the silent initial connect shows no retrying background")
            .isFalse()

        // It opens immediately on the first dial → CONNECTED + IDLE.
        val connected = emissions.last()
        assertThat(connected.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)
        assertThat(connected.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.IDLE)
    }

    @Test
    fun reconnect_cycle_oscillates_attempting_then_waiting_per_back_off() = runTest {
        // AC4 — across the reconnect cycle the activity oscillates: each back-off
        // is WAITING (red) and is immediately followed by the next dial's
        // ATTEMPTING (yellow), so the dot cycles red→yellow→red→yellow until the
        // feed opens. (Two fails → two WAITING, plus the dials at attempts 1 & 2.)
        val reconnecting = collectFeedEmissions(failuresBeforeOpen = 2)
            .filter { it.phase == SessionsFeedStatus.Phase.RECONNECTING }

        assertThat(reconnecting.map { it.activity }).containsExactly(
            SessionsFeedStatus.ReconnectActivity.WAITING, // after fail #1 → red
            SessionsFeedStatus.ReconnectActivity.ATTEMPTING, // dial #2 → yellow
            SessionsFeedStatus.ReconnectActivity.WAITING, // after fail #2 → red
            SessionsFeedStatus.ReconnectActivity.ATTEMPTING, // dial #3 → yellow
        )

        // Map each through the dot's connectivity: the oscillation the user sees.
        assertThat(reconnecting.map { SessionsUiState(feedStatus = it).connectivity })
            .containsExactly(
                Connectivity.BACKOFF, // red
                Connectivity.RETRYING, // yellow
                Connectivity.BACKOFF, // red
                Connectivity.RETRYING, // yellow
            )
    }

    @Test
    fun consecutive_waiting_then_attempting_hold_attempt_and_giveUp_only_dot_toggles() = runTest {
        // UC-70 ANTI-REGRESSION pinned at the SOURCE — a WAITING emit and the
        // ATTEMPTING dial that immediately follows it carry the SAME attempt and
        // giveUpAtMs; only `activity` flips (red→yellow) and `nextRetryAtMs` drops
        // to null (the countdown hides during the dial). This is exactly what
        // keeps UC-70's "attempt N" / give-up lines stable while the dot toggles.
        val emissions = collectFeedEmissions(failuresBeforeOpen = 2)

        // (WAITING #1 → ATTEMPTING #2) and (WAITING #2 → ATTEMPTING #3).
        val pairs = listOf(emissions[1] to emissions[2], emissions[3] to emissions[4])
        for ((waiting, attempting) in pairs) {
            assertThat(waiting.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.WAITING)
            assertThat(attempting.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.ATTEMPTING)

            assertThat(attempting.attempt)
                .`as`("attempt is HELD across the WAITING→ATTEMPTING boundary")
                .isEqualTo(waiting.attempt)
            assertThat(attempting.giveUpAtMs)
                .`as`("giveUpAtMs is HELD across the WAITING→ATTEMPTING boundary")
                .isEqualTo(waiting.giveUpAtMs)
            assertThat(attempting.phase)
                .`as`("phase is HELD (RECONNECTING) so UC-70's background never flickers")
                .isEqualTo(waiting.phase)

            assertThat(waiting.nextRetryAtMs)
                .`as`("WAITING carries the scheduled next-retry instant")
                .isNotNull()
            assertThat(attempting.nextRetryAtMs)
                .`as`("ATTEMPTING hides the countdown — no retry scheduled mid-dial")
                .isNull()
        }
    }

    @Test
    fun open_after_back_off_emits_connected_and_idle() = runTest {
        // AC4 tail — once a dial finally opens, the controller emits CONNECTED +
        // IDLE, so the dot leaves the reconnect cycle and rejoins the REST ladder
        // (green when the server has answered).
        val connected = collectFeedEmissions(failuresBeforeOpen = 2).last()

        assertThat(connected.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)
        assertThat(connected.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.IDLE)
        assertThat(connected.attempt).isEqualTo(0)
        assertThat(connected.nextRetryAtMs).isNull()
        assertThat(connected.reconnecting).isFalse()
    }

    @Test
    fun never_gives_up_past_the_old_5min_budget_then_reconnects_when_the_server_returns() = runTest {
        // The first 50 connects fail; the 51st opens. With the 10 s cap the
        // cumulative back-off is 1+2+4+8 + 10·46 = 475 s — WELL past the old
        // 5-minute (300 s) give-up budget. Pre-UC-71 the loop would have emitted
        // a terminal STOPPED long before; UC-71 must keep retrying and recover.
        val failuresBeforeOpen = 50
        val emissions = collectFeedEmissions(failuresBeforeOpen = failuresBeforeOpen)

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
        assertThat(emissions.last().activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.IDLE)

        // UC-72 — the WAITING back-off emits are the per-failed-attempt signal:
        // one per failure, attempt climbing to 50. (The interleaved ATTEMPTING
        // dial emits double the RECONNECTING count but carry no next-retry.)
        val reconnects = emissions.filter { it.phase == SessionsFeedStatus.Phase.RECONNECTING }
        val waiting = reconnects.filter { it.activity == SessionsFeedStatus.ReconnectActivity.WAITING }
        val attempting = reconnects.filter { it.activity == SessionsFeedStatus.ReconnectActivity.ATTEMPTING }
        assertThat(waiting).hasSize(failuresBeforeOpen)
        assertThat(attempting)
            .`as`("one ATTEMPTING dial per reconnect (attempts 1..50)")
            .hasSize(failuresBeforeOpen)
        assertThat(waiting.last().attempt)
            .`as`("attemptCount keeps climbing past the former cap (AC5/AC6)")
            .isEqualTo(failuresBeforeOpen)

        // AC5 — under the unlimited default every RECONNECTING (dial OR wait)
        // surfaces NO give-up instant (the UI's "giving up / limit" line stays
        // hidden).
        assertThat(reconnects)
            .`as`("UC-71 — unlimited default: no RECONNECTING carries a give-up instant")
            .allMatch { it.giveUpAtMs == null }

        // UC-72 — the ATTEMPTING dial emits hide the countdown (no scheduled retry
        // mid-dial); only WAITING emits carry next-retry.
        assertThat(attempting)
            .`as`("UC-72 — the dial-in-flight emit carries no next-retry instant")
            .allMatch { it.nextRetryAtMs == null }

        // AC2 — inter-attempt back-off never exceeds the 10 s cap. The WAITING
        // emits' nextRetryAtMs is now()+delay at emission time; since the
        // scheduler advances by exactly each delay, successive instants step ≤10s.
        val retryInstants = waiting.map { it.nextRetryAtMs!! }
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

    // ── UC-92 — onTransientDrop fast-recovery hook (AC5 / AC9) ────────────────
    //
    // The controller fires [onTransientDrop] exactly once on the Open→terminal
    // (non-Revoked) edge — a socket that had healthily Opened and then dropped
    // for a non-identity reason (delete-induced churn, back-nav STOP/START, a
    // flaky link). The ViewModel wires this to an immediate REST refresh so the
    // list repaints at once instead of waiting out the back-off. Two invariants
    // are pinned here at the source: (1) the hook fires on a transient drop and
    // the back-off reset on Open makes the FIRST post-drop reconnect dial at ~1s
    // (AC5/AC9 fast recovery); (2) a genuine cold outage (a socket that NEVER
    // Opens) can NOT reach the hook, so it can never turn an outage into a tight
    // retry loop (AC6 safety).

    /** A client whose `state` flow is [stateFlow] (so the test can drop it). */
    private fun clientWith(stateFlow: MutableStateFlow<SessionEventsClient.State>): SessionEventsClient {
        val client = Mockito.mock(SessionEventsClient::class.java)
        Mockito.`when`(client.state).thenReturn(stateFlow)
        Mockito.`when`(client.incoming).thenReturn(MutableSharedFlow<SessionEventMessage>())
        return client
    }

    @Test
    fun transient_drop_after_open_fires_onTransientDrop_once_and_first_reconnect_is_1s() = runTest {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        var drops = 0
        val profile = Mockito.mock(ServerProfile::class.java)
        val profileStore = profileStoreReturning(profile)

        // The first dial opens; every later dial returns an inert disconnected
        // client (we cancel before the back-off delay elapses, so it is never
        // actually built — but be robust if it is).
        val openState = MutableStateFlow<SessionEventsClient.State>(SessionEventsClient.State.Open)
        var calls = 0
        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = {
                calls++
                if (calls == 1) {
                    clientWith(openState)
                } else {
                    fakeClient(SessionEventsClient.State.Disconnected("post-drop"))
                }
            },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = { emissions.add(it) },
            onTransientDrop = { drops++ },
            nowMs = { testScheduler.currentTime },
            scope = loopScope,
        )

        controller.connect()
        advanceUntilIdle()
        // The socket is healthily Open and the loop is parked on the terminal
        // wait — the hook must NOT have fired yet.
        assertThat(drops)
            .`as`("the hook does not fire while the socket is healthily Open")
            .isEqualTo(0)
        assertThat(emissions.last().phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)

        // Drop the healthy socket for a NON-identity reason (a transient drop).
        openState.value = SessionEventsClient.State.Disconnected("transient-churn")
        runCurrent()
        loopScope.cancel()

        // AC5/AC9 — the hook fired exactly once on the Open→terminal edge …
        assertThat(drops)
            .`as`("a transient drop of a healthy socket fires the fast-recovery hook exactly once")
            .isEqualTo(1)

        // … and because the back-off was reset on Open, the FIRST post-drop
        // reconnect dial is scheduled at ~1s (not a climbed back-off delay).
        val firstWaitAfterDrop = emissions.last { it.activity == SessionsFeedStatus.ReconnectActivity.WAITING }
        assertThat(firstWaitAfterDrop.attempt)
            .`as`("back-off was reset on Open → the first post-drop attempt is 1")
            .isEqualTo(1)
        assertThat(firstWaitAfterDrop.nextRetryAtMs)
            .`as`("AC5/AC9 — first reconnect dial after a transient drop fires at ~1s")
            .isEqualTo(1_000L)
    }

    @Test
    fun revoked_terminal_does_not_fire_onTransientDrop() = runTest {
        // A 4401 Revoked teardown is an IDENTITY event, not a transient drop —
        // the loop returns before the hook, so a revocation must NOT trigger a
        // REST refresh (it routes to the cert dialog instead).
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var drops = 0
        val profile = Mockito.mock(ServerProfile::class.java)
        val profileStore = profileStoreReturning(profile)
        val openState = MutableStateFlow<SessionEventsClient.State>(SessionEventsClient.State.Open)
        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = { clientWith(openState) },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = {},
            onTransientDrop = { drops++ },
            nowMs = { testScheduler.currentTime },
            scope = loopScope,
        )

        controller.connect()
        advanceUntilIdle()
        openState.value = SessionEventsClient.State.Revoked
        runCurrent()
        loopScope.cancel()

        assertThat(drops)
            .`as`("a Revoked (4401) teardown is an identity event — the transient-drop hook must NOT fire")
            .isEqualTo(0)
    }

    @Test
    fun cold_outage_that_never_opens_never_fires_onTransientDrop() = runTest {
        // AC6 safety — a genuine cold outage: every dial fails (never Open). The
        // loop backs off and retries forever, but the Open→terminal edge is never
        // reached, so the fast-recovery hook can NEVER fire ⇒ it cannot turn an
        // outage into a tight retry loop.
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var drops = 0
        val profile = Mockito.mock(ServerProfile::class.java)
        val profileStore = profileStoreReturning(profile)
        val controller = SessionEventsController(
            profileStore = profileStore,
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = { fakeClient(SessionEventsClient.State.Disconnected("cold-outage")) },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = {},
            onTransientDrop = { drops++ },
            nowMs = { testScheduler.currentTime },
            scope = loopScope,
        )

        controller.connect()
        // Let several back-off cycles (1+2+4+8+10… s) elapse — the feed keeps
        // failing to open the whole time.
        advanceTimeBy(30_000)
        runCurrent()
        loopScope.cancel()

        assertThat(drops)
            .`as`("AC6 — a socket that never Opens can never reach the transient-drop hook")
            .isEqualTo(0)
    }
}
