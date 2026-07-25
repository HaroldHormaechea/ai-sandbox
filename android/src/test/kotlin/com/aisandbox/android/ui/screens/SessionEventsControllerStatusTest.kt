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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/**
 * UC-100 (mandate #2) — the [SessionEventsController] is now a pure
 * STATE-REFLECTOR over the single shared connection. It no longer owns a
 * [com.aisandbox.android.net.ReconnectController] or a connect/back-off loop:
 * reconnection + back-off are owned centrally by the one
 * [com.aisandbox.android.net.MuxConnection] (AC6 — provably one reconnect loop).
 *
 * <p>So [SessionsFeedStatus] is derived directly from the shared connection's
 * mapped state, WITHOUT a per-controller attempt count or next-retry countdown:
 * <ul>
 *   <li>Open → CONNECTED / IDLE;</li>
 *   <li>Connecting → CONNECTING / ATTEMPTING (attempt 0);</li>
 *   <li>Disconnected → RECONNECTING / WAITING (attempt 1, {@code nextRetryAtMs}
 *       and {@code giveUpAtMs} both null — the timing belongs to the shared
 *       connection's one controller, not here).</li>
 * </ul>
 * The UC-92 fast-recovery hook still fires exactly once on a HEALTHILY-OPEN →
 * drop edge (and never on a cold outage or a Revoked identity teardown).
 *
 * <p>This replaces the old oscillating-back-off assertions (climbing attempt,
 * 1s/3s next-retry instants, terminal STOPPED) which pinned the per-controller
 * loop that UC-100 deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionEventsControllerStatusTest {

    private fun clientWith(stateFlow: MutableStateFlow<SessionEventsClient.State>): SessionEventsClient {
        val client = Mockito.mock(SessionEventsClient::class.java)
        Mockito.`when`(client.state).thenReturn(stateFlow)
        Mockito.`when`(client.incoming).thenReturn(MutableSharedFlow<SessionEventMessage>())
        return client
    }

    /** A [ServerProfileStore] whose suspend `current()` returns [profile] (Answer sidesteps Continuation matching). */
    private fun profileStoreReturning(profile: ServerProfile?): ServerProfileStore =
        Mockito.mock(ServerProfileStore::class.java, Answer { inv ->
            if (inv.method.name == "current") profile else Mockito.RETURNS_DEFAULTS.answer(inv)
        })

    private class Rig(
        val emissions: MutableList<SessionsFeedStatus>,
        val drops: IntArray,
        val loopScope: CoroutineScope,
    )

    /** Build a reflector controller over a caller-controlled client-state flow. */
    private fun TestScope.rig(stateFlow: MutableStateFlow<SessionEventsClient.State>, profile: ServerProfile?): Rig {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        val drops = intArrayOf(0)
        val controller = SessionEventsController(
            profileStore = profileStoreReturning(profile),
            httpClientFactory = { Mockito.mock(AiSandboxHttpClient::class.java) },
            eventsClientFactory = { clientWith(stateFlow) },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = { emissions.add(it) },
            onTransientDrop = { drops[0]++ },
            scope = loopScope,
        )
        controller.connect()
        advanceUntilIdle()
        return Rig(emissions, drops, loopScope)
    }

    @Test
    fun open_reflects_connected_and_idle() = runTest {
        val flow = MutableStateFlow<SessionEventsClient.State>(SessionEventsClient.State.Open)
        val rig = rig(flow, Mockito.mock(ServerProfile::class.java))

        val last = rig.emissions.last()
        assertThat(last.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTED)
        assertThat(last.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.IDLE)
        assertThat(last.reconnecting).isFalse()
        rig.loopScope.cancel()
    }

    @Test
    fun connecting_reflects_connecting_attempting_with_no_countdown() = runTest {
        val flow = MutableStateFlow<SessionEventsClient.State>(SessionEventsClient.State.Connecting)
        val rig = rig(flow, Mockito.mock(ServerProfile::class.java))

        val first = rig.emissions.first()
        assertThat(first.phase).isEqualTo(SessionsFeedStatus.Phase.CONNECTING)
        assertThat(first.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.ATTEMPTING)
        assertThat(first.attempt).isEqualTo(0)
        assertThat(first.nextRetryAtMs).isNull()
        assertThat(first.giveUpAtMs).isNull()
        rig.loopScope.cancel()
    }

    @Test
    fun disconnected_reflects_reconnecting_waiting_without_per_controller_timing() = runTest {
        val flow = MutableStateFlow<SessionEventsClient.State>(
            SessionEventsClient.State.Disconnected("dropped"),
        )
        val rig = rig(flow, Mockito.mock(ServerProfile::class.java))

        val status = rig.emissions.last()
        assertThat(status.phase).isEqualTo(SessionsFeedStatus.Phase.RECONNECTING)
        assertThat(status.activity).isEqualTo(SessionsFeedStatus.ReconnectActivity.WAITING)
        assertThat(status.attempt).isEqualTo(1)
        // The shared connection's single ReconnectController owns the countdown now.
        assertThat(status.nextRetryAtMs)
            .`as`("no per-controller next-retry instant — timing is central to the shared connection")
            .isNull()
        assertThat(status.giveUpAtMs).isNull()
        rig.loopScope.cancel()
    }

    @Test
    fun state_transitions_reflect_connecting_then_connected_then_reconnecting() = runTest {
        val flow = MutableStateFlow<SessionEventsClient.State>(SessionEventsClient.State.Connecting)
        val rig = rig(flow, Mockito.mock(ServerProfile::class.java))

        flow.value = SessionEventsClient.State.Open
        runCurrent()
        flow.value = SessionEventsClient.State.Disconnected("drop")
        runCurrent()

        assertThat(rig.emissions.map { it.phase }).containsSubsequence(
            SessionsFeedStatus.Phase.CONNECTING,
            SessionsFeedStatus.Phase.CONNECTED,
            SessionsFeedStatus.Phase.RECONNECTING,
        )
        rig.loopScope.cancel()
    }

    // ── UC-92 fast-recovery hook (unchanged behaviour, new source of truth) ──

    @Test
    fun transient_drop_after_open_fires_onTransientDrop_once() = runTest {
        val flow = MutableStateFlow<SessionEventsClient.State>(SessionEventsClient.State.Open)
        val rig = rig(flow, Mockito.mock(ServerProfile::class.java))
        assertThat(rig.drops[0]).`as`("hook does not fire while healthily Open").isEqualTo(0)

        flow.value = SessionEventsClient.State.Disconnected("transient-churn")
        runCurrent()

        assertThat(rig.drops[0])
            .`as`("a transient drop of a healthy socket fires the fast-recovery hook exactly once")
            .isEqualTo(1)
        rig.loopScope.cancel()
    }

    @Test
    fun cold_outage_that_never_opens_never_fires_onTransientDrop() = runTest {
        val flow = MutableStateFlow<SessionEventsClient.State>(
            SessionEventsClient.State.Disconnected("cold-outage"),
        )
        val rig = rig(flow, Mockito.mock(ServerProfile::class.java))

        assertThat(rig.drops[0])
            .`as`("a socket that never Opened can never reach the transient-drop hook")
            .isEqualTo(0)
        rig.loopScope.cancel()
    }

    @Test
    fun revoked_after_open_does_not_fire_onTransientDrop() = runTest {
        val flow = MutableStateFlow<SessionEventsClient.State>(SessionEventsClient.State.Open)
        val rig = rig(flow, Mockito.mock(ServerProfile::class.java))

        flow.value = SessionEventsClient.State.Revoked
        runCurrent()

        assertThat(rig.drops[0])
            .`as`("a Revoked (4401) teardown is an identity event — the transient-drop hook must NOT fire")
            .isEqualTo(0)
        rig.loopScope.cancel()
    }

    @Test
    fun no_profile_emits_nothing_and_builds_no_client() = runTest {
        val loopScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emissions = mutableListOf<SessionsFeedStatus>()
        val controller = SessionEventsController(
            profileStore = profileStoreReturning(null),
            httpClientFactory = { error("no profile → the HTTP client must not be built") },
            eventsClientFactory = { error("no profile → the events client must not be built") },
            onSnapshot = {},
            onDelta = { _, _ -> },
            onStatus = { emissions.add(it) },
            scope = loopScope,
        )

        controller.connect()
        advanceUntilIdle()
        loopScope.cancel()

        assertThat(emissions)
            .`as`("an unconfigured feed stays silent — never flashes the retrying background")
            .isEmpty()
    }
}
