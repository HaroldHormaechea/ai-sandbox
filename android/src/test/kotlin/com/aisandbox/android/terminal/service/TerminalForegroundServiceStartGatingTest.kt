package com.aisandbox.android.terminal.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import com.aisandbox.android.ui.screens.TerminalState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC-35 — start-gating regression. The terminal screen reacts to a
 * process-scoped {@code controller.state} reaching {@code Open} by STARTING the
 * dataSync foreground service. On Android 12+ a background
 * {@code startForegroundService(...)} throws
 * {@code ForegroundServiceStartNotAllowedException}, so an {@code Open} that
 * lands while the app is backgrounded (a reconnect succeeding behind a locked
 * screen) must NOT trigger the start.
 *
 * <p>The fix gates the start effect on a {@code STARTED} lifecycle via
 * {@code repeatOnLifecycle(Lifecycle.State.STARTED)} (see
 * {@code TerminalScreen.kt}): the collector only runs while the host is at least
 * {@code STARTED} (foreground), so a background {@code Open} is simply not acted
 * on, and the running service self-keeps the FGS alive across the background
 * window (UC-34/UC-35 combined design). The Composable {@code LaunchedEffect}
 * itself is not directly unit-testable without a Compose+Robolectric host, so
 * this test exercises the SAME gating primitive the effect is built on —
 * {@code repeatOnLifecycle(STARTED)} driving a {@code state.collect} that starts
 * the FGS only on {@code Open} — with a [TestLifecycleOwner] standing in for the
 * screen's lifecycle. The "start the FGS" side effect is modelled by a counter;
 * what is under test is exactly WHEN the gate lets it through.
 *
 * <p>Pure JVM (JUnit-5) — no Android runtime; the lifecycle machinery runs on a
 * test dispatcher installed as {@code Dispatchers.Main}.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalForegroundServiceStartGatingTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        // repeatOnLifecycle hops to Dispatchers.Main.immediate internally, and
        // TestLifecycleOwner advances state on the dispatcher we give it.
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * UC-35 AC1 / AC4 — an {@code Open} transition that lands while the host is
     * NOT at least {@code STARTED} (i.e. backgrounded — CREATED) must NOT start
     * the FGS. This is the crash path: a background start would throw
     * {@code ForegroundServiceStartNotAllowedException}.
     */
    @Test
    fun `Open while backgrounded (CREATED) does not start the FGS`() = runTest {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED, dispatcher)
        val state = MutableStateFlow<TerminalState>(TerminalState.Connecting)
        var startCount = 0

        val collector = launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { if (it is TerminalState.Open) startCount++ }
            }
        }
        runCurrent()

        // Reconnect succeeds while still backgrounded → Open.
        state.value = TerminalState.Open
        runCurrent()

        assertThat(startCount).isZero()
        collector.cancel()
    }

    /**
     * UC-35 AC3 — the first-ever {@code Open} while the host IS foreground
     * ({@code STARTED}) starts the FGS exactly once (the normal initial-connect
     * path, preserved).
     */
    @Test
    fun `Open while foreground (STARTED) starts the FGS exactly once`() = runTest {
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)
        val state = MutableStateFlow<TerminalState>(TerminalState.Connecting)
        var startCount = 0

        val collector = launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { if (it is TerminalState.Open) startCount++ }
            }
        }
        runCurrent()

        state.value = TerminalState.Open
        runCurrent()

        assertThat(startCount).isEqualTo(1)
        collector.cancel()
    }

    /**
     * UC-35 AC1 → AC2 — the deferred-start contract: a background {@code Open}
     * is not acted on, but if the stream is still {@code Open} when the user
     * returns to the foreground, the gate re-opens and the FGS is (legally)
     * started then. No start fires while backgrounded; exactly one fires on the
     * foreground return.
     */
    @Test
    fun `background Open is deferred and starts once on the foreground return`() = runTest {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED, dispatcher)
        val state = MutableStateFlow<TerminalState>(TerminalState.Reconnecting(1, 1_000L))
        var startCount = 0

        val collector = launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { if (it is TerminalState.Open) startCount++ }
            }
        }
        runCurrent()

        // Reconnect lands while backgrounded — gate closed, no start.
        state.value = TerminalState.Open
        runCurrent()
        assertThat(startCount).isZero()

        // User returns to the foreground — gate opens, the still-Open stream
        // starts the FGS from a legal (foreground) context.
        owner.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertThat(startCount).isEqualTo(1)

        collector.cancel()
    }

    /**
     * Non-{@code Open} states never start the FGS regardless of lifecycle — the
     * start edge is {@code Open}-only. (Teardown for the inactive states is the
     * running service's job, covered by TerminalForegroundServiceTest.)
     */
    @Test
    fun `non-Open states never start the FGS even while foreground`() = runTest {
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)
        val state = MutableStateFlow<TerminalState>(TerminalState.Idle)
        var startCount = 0

        val collector = launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { if (it is TerminalState.Open) startCount++ }
            }
        }
        runCurrent()

        state.value = TerminalState.Connecting
        runCurrent()
        state.value = TerminalState.Reconnecting(2, 2_000L)
        runCurrent()
        state.value = TerminalState.GaveUp
        runCurrent()

        assertThat(startCount).isZero()
        collector.cancel()
    }
}
