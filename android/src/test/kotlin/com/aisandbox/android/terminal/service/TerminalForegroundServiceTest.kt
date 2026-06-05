package com.aisandbox.android.terminal.service

import android.content.Intent
import android.os.Looper
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.AppContainer
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.net.StreamClient
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.screens.TerminalState
import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * UC-34 / UC-35 — regression coverage for the self-managing
 * [TerminalForegroundService] (commit 3d9b877). The whole point of the fix is
 * that the dataSync FGS tears itself down from the BACKGROUND without the UI
 * ever issuing a background-illegal {@code startService(ACTION_STOP)} /
 * {@code startForegroundService(...)} — which on Android 8+/12+ throws
 * {@code BackgroundServiceStartNotAllowedException} /
 * {@code ForegroundServiceStartNotAllowedException} and crashes the app.
 *
 * <p>The service is exercised in isolation via Robolectric
 * {@code buildService(...)} — i.e. NOT from a {@code RESUMED} Activity, which
 * mirrors the crash scenario (give-up / revoke / disconnect landing while the
 * app is backgrounded; UC-34 AC6 demands the regression run in a non-{@code
 * RESUMED} state). It drives the process-scoped [TerminalStreamController]'s
 * {@code state} through the give-up / revoke / disconnect transitions using the
 * same private-field reflection seam {@code TerminalStreamControllerTest} uses,
 * and asserts the running service self-stops ({@code stopForeground} +
 * {@code stopSelf}, both background-legal) with no exception and no zombie
 * notification.
 *
 * <h2>Setup invariant</h2>
 * <p>In production the screen only ever STARTS this service from an {@code Open}
 * transition (gated to a foreground lifecycle). So each test puts the bound
 * controller into {@code Open} BEFORE the start — otherwise the state collector
 * would (correctly) self-stop immediately, since {@code Idle} is not an active
 * stream state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TerminalForegroundServiceTest {

    private lateinit var app: AiSandboxApplication
    private lateinit var container: AppContainer
    private val closed = mutableListOf<Int>()
    private lateinit var serviceController: ServiceController<TerminalForegroundService>

    private val service: TerminalForegroundService get() = serviceController.get()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as AiSandboxApplication
        container = app.container
    }

    // ── UC-34 AC1 / AC3 / AC5 / AC6 — terminal states tear the FGS down ───────

    @Test
    fun `GaveUp tears down the foreground service from the background without crashing`() {
        assertTearsDownOn(TerminalState.GaveUp)
    }

    @Test
    fun `Revoked tears down the foreground service from the background without crashing`() {
        assertTearsDownOn(TerminalState.Revoked)
    }

    @Test
    fun `Idle (explicit disconnect close) tears down the foreground service`() {
        assertTearsDownOn(TerminalState.Idle)
    }

    @Test
    fun `Failed tears down the foreground service`() {
        assertTearsDownOn(TerminalState.Failed("no_profile"))
    }

    /**
     * Start the FGS for an {@code Open} stream, assert it is up, then drive the
     * controller to [terminal] and assert the running service self-stops
     * (foreground stopped + stopSelf reached + notification removed) — with no
     * exception thrown. Covers UC-34 AC1 (give-up), AC3 (revoke), AC5 (no
     * notification leak / stopSelf reached) and AC6 (regression in a
     * non-RESUMED lifecycle).
     */
    private fun assertTearsDownOn(terminal: TerminalState) {
        val controller = registerController(SESSION_A).apply { driveState(TerminalState.Open) }
        createService()
        startFor(SESSION_A)

        val shadow = shadowOf(service)
        // Up while Open — the FGS is holding the WS at foreground priority.
        assertThat(shadow.isForegroundStopped).isFalse()
        assertThat(shadow.isStoppedBySelf).isFalse()
        assertThat(shadow.lastForegroundNotification).isNotNull()

        // The give-up / revoke / disconnect lands while the host is NOT resumed.
        controller.driveState(terminal)
        idle()

        assertThat(shadow.isForegroundStopped).`as`("stopForeground reached on $terminal").isTrue()
        assertThat(shadow.isStoppedBySelf).`as`("stopSelf reached on $terminal (AC5)").isTrue()
        // STOP_FOREGROUND_REMOVE — no zombie "FOREGROUND · dataSync" notification.
        assertThat(shadow.notificationShouldRemoved).`as`("notification removed on $terminal").isTrue()
    }

    // ── UC-34 AC2 — notification "Disconnect" (ACTION_STOP) ───────────────────

    @Test
    fun `ACTION_STOP closes the bound controller then self-stops`() {
        val controller = registerController(SESSION_A).apply { driveState(TerminalState.Open) }
        createService()
        startFor(SESSION_A)
        assertThat(shadowOf(service).isForegroundStopped).isFalse()

        // The notification's Disconnect action fires while backgrounded.
        service.onStartCommand(stopIntent(), 0, 2)
        idle()

        // The controller was closed (kills the zombie reconnect loop) ...
        assertThat(closed).contains(SESSION_A)
        assertThat(controller.state.value).isEqualTo(TerminalState.Idle)
        // ... and the service self-stopped (background-legal).
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        assertThat(shadowOf(service).isForegroundStopped).isTrue()
    }

    // ── UC-34 AC7 / UC-35 — reconnect window must NOT tear the FGS down ───────

    @Test
    fun `Connecting and Reconnecting keep the foreground service alive`() {
        val controller = registerController(SESSION_A).apply { driveState(TerminalState.Open) }
        createService()
        startFor(SESSION_A)
        val shadow = shadowOf(service)

        // A transient drop → the controller flips to Connecting then Reconnecting
        // while the dataSync FGS must stay up so the locked-screen / backgrounded
        // stream survives the give-up window (UC-04 AC21, UC-34 AC7, UC-35).
        controller.driveState(TerminalState.Connecting)
        idle()
        assertThat(shadow.isForegroundStopped).`as`("Connecting must not stop the FGS").isFalse()

        controller.driveState(TerminalState.Reconnecting(attempt = 1, nextDelayMs = 1_000L))
        idle()
        assertThat(shadow.isForegroundStopped).`as`("Reconnecting must not stop the FGS").isFalse()
        assertThat(shadow.isStoppedBySelf).isFalse()

        // And it self-stops once the reconnect ultimately gives up.
        controller.driveState(TerminalState.GaveUp)
        idle()
        assertThat(shadow.isForegroundStopped).isTrue()
    }

    // ── UC-04 AC21 guard — session switch A→B rebinds + FGS stays up ──────────

    @Test
    fun `a session switch A to B rebinds the collectors and keeps the FGS up for B`() {
        val a = registerController(SESSION_A).apply { driveState(TerminalState.Open) }
        val b = registerController(SESSION_B).apply { driveState(TerminalState.Open) }
        createService()
        startFor(SESSION_A)
        val shadow = shadowOf(service)
        assertThat(shadow.isForegroundStopped).isFalse()

        // A start for a DIFFERENT session arrives at the still-running service →
        // it rebinds to B (no restart) and stays up.
        startFor(SESSION_B)
        assertThat(shadow.isForegroundStopped).`as`("FGS stays up across the A→B switch").isFalse()

        // A's terminal state is now stale — the collectors are bound to B, so A
        // going Idle must NOT tear the (B-serving) FGS down.
        a.driveState(TerminalState.Idle)
        idle()
        assertThat(shadow.isForegroundStopped).`as`("stale session A must not stop B's FGS").isFalse()

        // B's terminal state DOES tear it down.
        b.driveState(TerminalState.GaveUp)
        idle()
        assertThat(shadow.isForegroundStopped).`as`("B's give-up tears the FGS down").isTrue()
        assertThat(shadow.isStoppedBySelf).isTrue()
    }

    // ── stale / raced ACTION_START with no live controller → self-stop ────────

    @Test
    fun `ACTION_START with no existing controller self-stops instead of resurrecting a stream`() {
        // No controller registered for SESSION_A — a stale/raced intent.
        createService()
        startFor(SESSION_A)

        val shadow = shadowOf(service)
        assertThat(shadow.isStoppedBySelf).`as`("stale ACTION_START self-stops").isTrue()
        assertThat(shadow.isForegroundStopped).isTrue()
    }

    // ── UC-21 AC#8 — START_STICKY null-intent redelivery → self-stop ──────────

    @Test
    fun `a null-intent redelivery self-stops rather than leaking a zombie notification`() {
        createService()
        // The OS killed + restarted the process; START_STICKY redelivers a null
        // intent. The WS + emulator died with the process and there is no session
        // context to re-attach, so the service must self-stop (no zombie FGS).
        val result = service.onStartCommand(null, 0, 1)
        idle()

        assertThat(result).isEqualTo(android.app.Service.START_STICKY)
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createService() {
        serviceController = Robolectric.buildService(TerminalForegroundService::class.java).create()
    }

    private fun startFor(n: Int) {
        service.onStartCommand(startIntent(n), 0, 1)
        idle()
    }

    private fun startIntent(n: Int): Intent =
        TerminalForegroundService.NotificationParams(
            sessionN = n,
            wssUrl = "wss://server.example",
            cols = 80,
            rows = 24,
            idleSec = 0,
        ).toIntent(TerminalForegroundService.ACTION_START, app)

    private fun stopIntent(): Intent =
        Intent(app, TerminalForegroundService::class.java)
            .setAction(TerminalForegroundService.ACTION_STOP)

    /**
     * Construct a [TerminalStreamController] with mocked collaborators (mirrors
     * {@code TerminalStreamControllerTest}) and inject it directly into the
     * container's private registry — bypassing {@code terminalController()},
     * which would tear down any other live session. That lets the A→B switch
     * test hold two live controllers at once.
     */
    private fun registerController(n: Int): TerminalStreamController {
        val controller = TerminalStreamController(
            appContext = app,
            sessionN = n,
            profileStore = mock(ServerProfileStore::class.java),
            httpClientFactory = { mock(AiSandboxHttpClient::class.java) },
            streamClientFactory = { _, _ -> mock(StreamClient::class.java) },
            onClosed = { n2 ->
                closed += n2
                controllersMap().remove(n2)
            },
        )
        controllersMap()[n] = controller
        return controller
    }

    @Suppress("UNCHECKED_CAST")
    private fun controllersMap(): HashMap<Int, TerminalStreamController> {
        val f = AppContainer::class.java.getDeclaredField("controllers")
        f.isAccessible = true
        return f.get(container) as HashMap<Int, TerminalStreamController>
    }

    /** Drive the controller's private {@code _state} StateFlow — the same seam
     *  {@code TerminalStreamControllerTest} uses to inject the stream client. */
    @Suppress("UNCHECKED_CAST")
    private fun TerminalStreamController.driveState(state: TerminalState) {
        val f = TerminalStreamController::class.java.getDeclaredField("_state")
        f.isAccessible = true
        (f.get(this) as MutableStateFlow<TerminalState>).value = state
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        private const val SESSION_A = 7
        private const val SESSION_B = 8
    }
}
