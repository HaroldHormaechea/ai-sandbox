package com.aisandbox.android.ui

import com.aisandbox.android.net.NetworkEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-56 § AC2 / AC6 — coverage matrix for the pure single-shot routing
 * decider [decideNetworkRoute].
 *
 * <p><b>What this guards.</b> Pre-fix, every genuine TLS/identity
 * [NetworkEvent] re-pushed {@code ServerIdentityChangedScreen} via
 * {@code navController.navigate(..)}. When the sessions-list {@code refresh()}
 * re-failed through the shared interceptor on each
 * {@code repeatOnLifecycle(STARTED)} re-START (returning from a conversation),
 * the bus re-emitted the event and the screen was re-pushed as fast as the
 * user dismissed it — the flicker loop UC-56 describes. [decideNetworkRoute]
 * makes the route single-shot: an identity event arriving while the identity
 * screen is ALREADY on top is a [NetworkRouteDecision.Suppress] no-op
 * (distinct from [NetworkRouteDecision.NoOp] so the test can prove the
 * single-shot guard fired rather than the event being merely irrelevant).
 *
 * <h2>Decision matrix</h2>
 *
 * <table>
 *   <tr><th>event</th><th>identityRouteActive</th><th>decision</th><th>AC</th></tr>
 *   <tr><td>PinMismatch / HostnameMismatch / HandshakeError</td><td>false</td><td>Navigate</td><td>AC5 (genuine identity routes once)</td></tr>
 *   <tr><td>PinMismatch / HostnameMismatch / HandshakeError</td><td>true</td><td>Suppress</td><td>AC2 (single-shot, no re-push loop)</td></tr>
 *   <tr><td>ServerUnreachable</td><td>any</td><td>NoOp</td><td>AC4 (transient never touches identity screen)</td></tr>
 *   <tr><td>CertRevoked</td><td>any</td><td>NoOp</td><td>routed separately by the caller</td></tr>
 *   <tr><td>StreamReconnecting / StreamGaveUp</td><td>any</td><td>NoOp</td><td>terminal-local, root composable ignores</td></tr>
 * </table>
 *
 * <h2>Why this is pure JUnit 5 (no Robolectric)</h2>
 *
 * <p>[decideNetworkRoute] is a deliberately pure function — no Compose, no
 * Context, no NavController (all of which stay in the [AiSandboxApp] caller).
 * That shape lets us assert the matrix with raw JUnit 5 + AssertJ, sidestepping
 * the Robolectric instability documented in UC-14/UC-16. Same pattern as
 * [StartDestinationDecisionTest].
 */
class NetworkEventRouteTest {

    private val pinMismatch =
        NetworkEvent.PinMismatch(expectedPinHex = "a".repeat(64), observedPinHex = "b".repeat(64), rawMessage = "pin")
    private val hostnameMismatch =
        NetworkEvent.HostnameMismatch(expectedHost = "potato-server", rawMessage = "san")
    private val handshakeError =
        NetworkEvent.HandshakeError(rawMessage = "handshake")

    // ── Genuine identity events while the screen is NOT yet active → Navigate (AC5) ──

    @Test
    fun `PinMismatch with identity route inactive navigates`() {
        assertThat(decideNetworkRoute(pinMismatch, identityRouteActive = false))
            .describedAs("AC5: a genuine pin mismatch must surface the identity screen on first arrival")
            .isEqualTo(NetworkRouteDecision.Navigate)
    }

    @Test
    fun `HostnameMismatch with identity route inactive navigates`() {
        assertThat(decideNetworkRoute(hostnameMismatch, identityRouteActive = false))
            .isEqualTo(NetworkRouteDecision.Navigate)
    }

    @Test
    fun `HandshakeError with identity route inactive navigates`() {
        assertThat(decideNetworkRoute(handshakeError, identityRouteActive = false))
            .isEqualTo(NetworkRouteDecision.Navigate)
    }

    // ── Same identity events while the screen is ALREADY active → Suppress (AC2) ──
    //
    // This is the load-bearing UC-56 guard: the second (and every subsequent)
    // identity event arriving while ServerIdentityChangedScreen is on top must
    // be suppressed, so a re-emitting bus / re-failing refresh tick cannot
    // re-push the screen into a flicker loop.

    @Test
    fun `PinMismatch with identity route active is suppressed (single-shot guard)`() {
        assertThat(decideNetworkRoute(pinMismatch, identityRouteActive = true))
            .describedAs("AC2: a repeated identity event while the screen is shown must NOT re-push (no loop)")
            .isEqualTo(NetworkRouteDecision.Suppress)
    }

    @Test
    fun `HostnameMismatch with identity route active is suppressed (single-shot guard)`() {
        assertThat(decideNetworkRoute(hostnameMismatch, identityRouteActive = true))
            .isEqualTo(NetworkRouteDecision.Suppress)
    }

    @Test
    fun `HandshakeError with identity route active is suppressed (single-shot guard)`() {
        assertThat(decideNetworkRoute(handshakeError, identityRouteActive = true))
            .describedAs("AC2: the exact flicker-loop case — a re-emitted HandshakeError must be suppressed")
            .isEqualTo(NetworkRouteDecision.Suppress)
    }

    // ── ServerUnreachable is transient — NoOp regardless of route state (AC4) ──

    @Test
    fun `ServerUnreachable is a no-op when identity route inactive`() {
        assertThat(decideNetworkRoute(NetworkEvent.ServerUnreachable, identityRouteActive = false))
            .describedAs("AC4: the transient connectivity signal must never touch the identity screen")
            .isEqualTo(NetworkRouteDecision.NoOp)
    }

    @Test
    fun `ServerUnreachable is a no-op when identity route active`() {
        assertThat(decideNetworkRoute(NetworkEvent.ServerUnreachable, identityRouteActive = true))
            .isEqualTo(NetworkRouteDecision.NoOp)
    }

    // ── CertRevoked is routed separately by the caller → NoOp here ──

    @Test
    fun `CertRevoked is a no-op (routed separately by the caller)`() {
        assertThat(decideNetworkRoute(NetworkEvent.CertRevoked, identityRouteActive = false))
            .isEqualTo(NetworkRouteDecision.NoOp)
        assertThat(decideNetworkRoute(NetworkEvent.CertRevoked, identityRouteActive = true))
            .isEqualTo(NetworkRouteDecision.NoOp)
    }

    // ── Terminal-local Stream* events → NoOp (handled inside TerminalScreen) ──

    @Test
    fun `StreamReconnecting is a no-op (handled in TerminalScreen)`() {
        assertThat(decideNetworkRoute(NetworkEvent.StreamReconnecting("s1", 1, 1000L), identityRouteActive = false))
            .isEqualTo(NetworkRouteDecision.NoOp)
    }

    @Test
    fun `StreamGaveUp is a no-op (handled in TerminalScreen)`() {
        assertThat(decideNetworkRoute(NetworkEvent.StreamGaveUp("s1"), identityRouteActive = false))
            .isEqualTo(NetworkRouteDecision.NoOp)
    }
}
