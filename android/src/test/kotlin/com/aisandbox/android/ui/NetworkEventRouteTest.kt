package com.aisandbox.android.ui

import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.TlsFailureTranslation
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
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

    // ── UC-61 — non-QR TLS misroute fix, end-to-end (translator → router) ─────
    //
    // The UC-61 fix lives upstream in [TlsFailureTranslation]: a generic,
    // non-identity TLS handshake failure on a routine REST call now classifies as
    // [NetworkEvent.ServerUnreachable] instead of [NetworkEvent.HandshakeError].
    // The router is unchanged — but these tests cross BOTH pure functions to pin
    // the observable contract: the SAME raw SSLHandshakeException that pre-fix
    // would have driven a Navigate to ServerIdentityChangedScreen now resolves to
    // a NoOp (never the re-scan-QR screen), while a GENUINE identity handshake
    // (CertificateException cause) still Navigates (AC5 / AC6).

    @Test
    fun `UC-61 — a generic REST handshake failure resolves to NoOp, never the identity screen (AC5)`() {
        val event = TlsFailureTranslation.translate(
            SSLHandshakeException("generic handshake error"),
            expectedPinHex = "a".repeat(64),
            expectedHost = "potato-server",
        )
        // Step 1: the translator reclassifies it as the transient signal.
        assertThat(event)
            .`as`("AC5 — a non-identity handshake on a REST call is transient, not identity")
            .isEqualTo(NetworkEvent.ServerUnreachable)
        // Step 2: the router never sends the transient signal to the identity screen.
        assertThat(decideNetworkRoute(event!!, identityRouteActive = false))
            .`as`("AC5 — no ServerIdentityChanged navigation for a generic handshake on spawn/list/refresh")
            .isEqualTo(NetworkRouteDecision.NoOp)
        assertThat(decideNetworkRoute(event, identityRouteActive = true))
            .isEqualTo(NetworkRouteDecision.NoOp)
    }

    @Test
    fun `UC-61 — a genuine identity handshake (cert cause) still Navigates to the identity screen (AC6)`() {
        val event = TlsFailureTranslation.translate(
            SSLHandshakeException("handshake aborted")
                .initCause(CertificateException("Server presented no certificate chain")),
            expectedPinHex = "a".repeat(64),
            expectedHost = "potato-server",
        )
        // Step 1: identity wins — the cert cause keeps it a HandshakeError.
        assertThat(event)
            .`as`("AC6 — a CertificateException-caused handshake stays a HandshakeError")
            .isInstanceOf(NetworkEvent.HandshakeError::class.java)
        // Step 2: a genuine identity event still routes to the identity screen on first arrival.
        assertThat(decideNetworkRoute(event!!, identityRouteActive = false))
            .`as`("AC6 — genuine identity failures still surface the re-enroll screen")
            .isEqualTo(NetworkRouteDecision.Navigate)
    }
}
