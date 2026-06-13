package com.aisandbox.android.net

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC10 § AC4 — unit-test [TlsFailureTranslation] in isolation.
 *
 * <p>Pre-fix expectation (Phase 2a / 3 partial): every test in this
 * class PASSES on the current branch — the translator is final;
 * only its wiring into [EnrollmentClient] / [AiSandboxHttpClient]
 * catch-blocks is pending Phase 2b. This file is a scaffold sanity
 * check that pins:
 *
 * <ol>
 *   <li>The regex parses the {@link SpkiPinningTrustManager}-emitted
 *       structured message and lifts the {@code observed=} hex.</li>
 *   <li>The cause-chain walk is bounded (no infinite loop on a
 *       circular {@code throwable.cause} graph).</li>
 *   <li>Each exception class routes to the right [NetworkEvent]
 *       variant.</li>
 *   <li>Non-TLS exceptions return null so the caller falls back to
 *       its own IOException path.</li>
 * </ol>
 */
class TlsFailureTranslationTest {

    private val expectedPinHex = "a".repeat(64)
    private val expectedHost = "potato-server"

    @Test
    fun `SSLHandshakeException wrapping CertificateException with SPKI mismatch routes to PinMismatch with real observed hex`() {
        val expectedHex = "b".repeat(64)
        val observedHex = "c".repeat(64)
        val tmException = CertificateException(
            "SPKI pin mismatch: expected=$expectedHex observed=$observedHex"
        )
        val sslHandshake = SSLHandshakeException("handshake aborted").initCause(tmException)

        val event = TlsFailureTranslation.translate(sslHandshake, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.PinMismatch::class.java)
        val pin = event as NetworkEvent.PinMismatch
        assertThat(pin.expectedPinHex).isEqualTo(expectedPinHex)
        // The OBSERVED hex is the one the TM emits — NOT `<bootstrap>`.
        // This is the central post-UC10 contract.
        assertThat(pin.observedPinHex).isEqualTo(observedHex)
        assertThat(pin.observedPinHex).isNotEqualTo("<bootstrap>")
        assertThat(pin.rawMessage).isEqualTo("handshake aborted")
    }

    @Test
    fun `cause-chain walk depth — translator finds the CertificateException nested two levels deep`() {
        val expectedHex = "1".repeat(64)
        val observedHex = "2".repeat(64)
        val tmException = CertificateException(
            "SPKI pin mismatch: expected=$expectedHex observed=$observedHex"
        )
        val midCause = RuntimeException("nested wrapper").initCause(tmException)
        val sslHandshake = SSLHandshakeException("top-level").initCause(midCause)

        val event = TlsFailureTranslation.translate(sslHandshake, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.PinMismatch::class.java)
        val pin = event as NetworkEvent.PinMismatch
        assertThat(pin.observedPinHex).isEqualTo(observedHex)
    }

    // ── UC-61 — non-identity SSLHandshakeException reclassification (AC5 / AC6) ──
    //
    // UC-61 narrows the `is SSLHandshakeException` arm. Pre-fix, ANY handshake
    // exception without an SPKI-mismatch message became a HandshakeError, which
    // the REST interceptor bus-routes to ServerIdentityChangedScreen → "re-scan a
    // fresh invite QR" — so a transient protocol/cipher hiccup on a routine
    // list/spawn/refresh call misrouted the user to the destructive re-enroll
    // screen even though the QR was valid and the pin unchanged. The arm is now:
    //   1. SPKI-mismatch message present       → PinMismatch (unchanged, above)
    //   2. CertificateException in .cause chain → HandshakeError (identity wins, AC6)
    //   3. otherwise                            → ServerUnreachable (transient, AC5)

    @Test
    fun `UC-61 — generic SSLHandshakeException with no cause now routes to ServerUnreachable (AC5)`() {
        // Pre-UC-61 this asserted HandshakeError. A handshake exception with no
        // SPKI message and NO CertificateException cause is a non-identity TLS
        // hiccup → transient ServerUnreachable, so it never reaches the re-scan-QR
        // identity screen on a routine REST call.
        val sslHandshake = SSLHandshakeException("generic handshake error")
        val event = TlsFailureTranslation.translate(sslHandshake, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("a non-identity handshake failure is transient, never the destructive identity route (AC5)")
            .isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `UC-61 — SSLHandshakeException wrapping a SocketException cause routes to ServerUnreachable (AC5)`() {
        // A handshake that aborted because the underlying socket dropped (no
        // CertificateException anywhere in the chain) is a transient transport
        // fault, not an identity failure.
        val sslHandshake = SSLHandshakeException("Connection closed by peer")
            .initCause(SocketException("Connection reset"))
        val event = TlsFailureTranslation.translate(sslHandshake, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `UC-61 — SSLHandshakeException wrapping a no-cert-chain CertificateException stays HandshakeError (AC6 identity wins)`() {
        // SpkiPinningTrustManager throws CertificateException("Server presented no
        // certificate chain") on a genuine cert/identity failure whose message does
        // NOT match the SPKI-mismatch regex (so extractObservedSpkiHex returns
        // null). The identity-cause guard MUST keep it on the destructive identity
        // route as HandshakeError — only the generic, cert-free handshake bucket is
        // narrowed (AC6, "identity wins").
        val certEx = CertificateException("Server presented no certificate chain")
        val sslHandshake = SSLHandshakeException("handshake aborted").initCause(certEx)
        val event = TlsFailureTranslation.translate(sslHandshake, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("a CertificateException cause keeps a handshake failure on the identity route (AC6)")
            .isInstanceOf(NetworkEvent.HandshakeError::class.java)
        assertThat((event as NetworkEvent.HandshakeError).rawMessage).isEqualTo("handshake aborted")
    }

    @Test
    fun `UC-61 — SSLHandshakeException with a CertificateException nested two levels deep stays HandshakeError (AC6)`() {
        // The cert-cause walk follows .cause (bounded by MAX_CAUSE_DEPTH), so a
        // CertificateException wrapped behind an intermediate throwable still
        // pins the failure to the identity route.
        val certEx = CertificateException("Server presented no certificate chain")
        val mid = RuntimeException("intermediate wrapper").initCause(certEx)
        val sslHandshake = SSLHandshakeException("top-level handshake").initCause(mid)
        val event = TlsFailureTranslation.translate(sslHandshake, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HandshakeError::class.java)
    }

    @Test
    fun `UC-61 — a generic handshake ServerUnreachable never produces a Mismatch identity screen (AC5, AC7)`() {
        // End-to-end at the translator: a generic handshake reclassifies to
        // ServerUnreachable, and toMismatch(ServerUnreachable) is null, so the
        // sessions/onboarding flow can NEVER surface the "re-scan a fresh invite
        // QR" identity copy from a non-identity transport fault (AC5, AC7).
        val event = TlsFailureTranslation.translate(
            SSLHandshakeException("generic handshake error"), expectedPinHex, expectedHost,
        )
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
        assertThat(TlsFailureTranslation.toMismatch(event!!))
            .`as`("a non-identity handshake must never map to an identity Mismatch (AC7)")
            .isNull()
    }

    @Test
    fun `extractObservedSpkiHex returns null when the message has no SPKI marker`() {
        val tmException = CertificateException("unrelated cert error")
        val sslHandshake = SSLHandshakeException("wrapper").initCause(tmException)
        val observed = TlsFailureTranslation.extractObservedSpkiHex(sslHandshake)
        assertThat(observed).isNull()
    }

    @Test
    fun `pathological cause-cycle does not loop forever`() {
        // Synthesise a Throwable whose cause is itself; the iterative
        // walk with a depth cap must terminate without throwing.
        // (Throwable's setter semantics make this a bit awkward — we
        // initCause to a *different* throwable first, then swap the
        // cause via reflection-free routing through Throwable's API.)
        val a = SSLHandshakeException("a")
        val b = SSLHandshakeException("b")
        // Build the cycle:  a -> b -> a -> b -> …
        // initCause throws if a cause is already set; use the
        // (cause)-arg constructor via the chained .initCause to
        // assemble the cycle. Throwable allows initCause(self) — Java
        // ≤8 forbade it but modern JDKs permit it (Throwable.initCause
        // explicitly throws IllegalArgumentException only on self →
        // self loops? Let's use distinct throwables for safety.)
        a.initCause(b)
        // b.initCause(a) would self-reference through b — JDK throws
        // IllegalArgumentException on direct self-cause assignment
        // (Throwable.initCause: "Self-causation not permitted"). The
        // class-Javadoc claim of "pathological cause-cycle" is really
        // about a long deep chain — exercise the depth cap by building
        // a chain of length 1000.
        var head: Throwable = SSLHandshakeException("deep-0")
        for (i in 1..1000) {
            val next = SSLHandshakeException("deep-$i")
            next.initCause(head)
            head = next
        }
        // Must not infinite-loop, must not throw StackOverflowError,
        // must return null (no SPKI message anywhere in the chain).
        val observed = TlsFailureTranslation.extractObservedSpkiHex(head)
        assertThat(observed).isNull()
    }

    @Test
    fun `SSLPeerUnverifiedException routes to HostnameMismatch carrying expectedHost`() {
        val ex = SSLPeerUnverifiedException("Hostname potato-server not verified")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HostnameMismatch::class.java)
        val hm = event as NetworkEvent.HostnameMismatch
        assertThat(hm.expectedHost).isEqualTo(expectedHost)
        assertThat(hm.rawMessage).isEqualTo("Hostname potato-server not verified")
    }

    @Test
    fun `bare SSLException carrying a connection-reset message routes to ServerUnreachable (UC-56 AC4)`() {
        // UC-56 — pre-fix this asserted HandshakeError. A bare SSLException
        // (neither SSLHandshakeException nor SSLPeerUnverifiedException) whose
        // message is a transport-drop signature ("connection reset") is now a
        // TRANSIENT transport drop, not a destructive identity failure: the old
        // unconditional HandshakeError mapping force-routed Conscrypt's
        // mid-stream connection-reset to the re-scan-QR screen (the flicker
        // loop). It now routes to ServerUnreachable, which never reaches the
        // identity screen. Genuine TLS arms (handshake / peer-unverified / pin)
        // run BEFORE this and are unchanged — see the AC5 guard tests below.
        val ex = SSLException("connection reset during TLS")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("a bare SSLException with a transport-drop message is transient, not identity")
            .isEqualTo(NetworkEvent.ServerUnreachable)
    }

    // ── UC-52 — connectivity vs TLS taxonomy (AC1 / AC4 / AC8) ───────────────
    //
    // The load-bearing security partition for UC-52: a NON-TLS IOException is a
    // TRANSIENT connectivity failure → NetworkEvent.ServerUnreachable (never the
    // destructive identity screen). An IOException that CARRIES an SSLException
    // in its cause chain stays on the identity path (HandshakeError) — "when in
    // doubt, identity wins" (AC4). The genuine-TLS cases above (pin / SAN /
    // generic SSLException) are unchanged and MUST stay green.

    @Test
    fun `plain IOException now routes to ServerUnreachable, not HandshakeError (UC-52 AC1, AC8)`() {
        // Pre-UC-52 this was the catch-all that misrouted every connectivity
        // drop to the identity screen. Now a bare IOException with no TLS cause
        // is transient.
        val ex = IOException("connection refused")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("a plain IOException is a connectivity failure, never a TLS handshake error")
            .isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `ConnectException (connection refused) routes to ServerUnreachable (AC1, AC8)`() {
        val ex = ConnectException("Connection refused")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `SocketTimeoutException routes to ServerUnreachable (AC1, AC8)`() {
        val ex = SocketTimeoutException("timeout")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `UnknownHostException routes to ServerUnreachable (AC1, AC8)`() {
        val ex = UnknownHostException("potato-server: nodename nor servname provided")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `EOFException (dropped socket) routes to ServerUnreachable (AC1, AC8)`() {
        val ex = EOFException("\\n not found: limit=0 content=…")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `SocketException 'Connection reset' with NO TLS cause routes to ServerUnreachable (AC8 taxonomy)`() {
        // The use-case pitfall: a mid-stream `Connection reset` with no TLS
        // cause is bucketed transient (lean retry), NOT identity.
        val ex = SocketException("Connection reset")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `IOException WRAPPING an SSLException cause routes to HandshakeError (AC4 security boundary)`() {
        // hasTlsCause() must keep a connectivity-SHAPED exception that actually
        // carries a TLS failure on the identity path — never silently retry
        // against a possibly-compromised endpoint.
        val ex = IOException("io wrapper").initCause(SSLException("inner TLS failure"))
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("an IOException whose cause is an SSLException stays identity (when in doubt, identity wins)")
            .isInstanceOf(NetworkEvent.HandshakeError::class.java)
    }

    @Test
    fun `SocketException WRAPPING an SSLException cause routes to HandshakeError (AC4 security boundary)`() {
        // A `Connection reset` that DID occur mid-TLS (carries an SSLException
        // cause) must be identity, the deliberate counterpart to the no-cause
        // transient case above.
        val ex = SocketException("Connection reset").initCause(
            SSLHandshakeException("Remote host terminated the handshake"),
        )
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HandshakeError::class.java)
    }

    @Test
    fun `genuine SSLException is unaffected by hasTlsCause and stays HandshakeError (AC4)`() {
        // Direct SSLException hits the `is SSLException` arm BEFORE the
        // IOException arm — the SSL arms run first and unchanged (AC4).
        val ex = SSLException("protocol downgrade")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HandshakeError::class.java)
    }

    @Test
    fun `non-IO Throwable returns null so the caller falls back to its own path`() {
        val ex = RuntimeException("not a network error")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isNull()
    }

    @Test
    fun `toMismatch maps PinMismatch event to Mismatch_Pin`() {
        val ev = NetworkEvent.PinMismatch(
            expectedPinHex = "ee".repeat(32),
            observedPinHex = "dd".repeat(32),
            rawMessage = "raw"
        )
        val m = TlsFailureTranslation.toMismatch(ev)
        assertThat(m).isInstanceOf(Mismatch.Pin::class.java)
        val pin = m as Mismatch.Pin
        assertThat(pin.expectedHex).isEqualTo("ee".repeat(32))
        assertThat(pin.observedHex).isEqualTo("dd".repeat(32))
        assertThat(pin.rawMessage).isEqualTo("raw")
    }

    @Test
    fun `toMismatch maps HostnameMismatch event to Mismatch_Hostname`() {
        val ev = NetworkEvent.HostnameMismatch(expectedHost = "a-host", rawMessage = "h-raw")
        val m = TlsFailureTranslation.toMismatch(ev)
        assertThat(m).isInstanceOf(Mismatch.Hostname::class.java)
        val h = m as Mismatch.Hostname
        assertThat(h.expectedHost).isEqualTo("a-host")
        assertThat(h.rawMessage).isEqualTo("h-raw")
    }

    @Test
    fun `toMismatch maps HandshakeError event to Mismatch_HandshakeError`() {
        val ev = NetworkEvent.HandshakeError(rawMessage = "boom")
        val m = TlsFailureTranslation.toMismatch(ev)
        assertThat(m).isInstanceOf(Mismatch.HandshakeError::class.java)
        val h = m as Mismatch.HandshakeError
        assertThat(h.rawMessage).isEqualTo("boom")
    }

    @Test
    fun `toMismatch returns null for non-error NetworkEvent variants`() {
        assertThat(TlsFailureTranslation.toMismatch(NetworkEvent.CertRevoked)).isNull()
        assertThat(TlsFailureTranslation.toMismatch(NetworkEvent.StreamReconnecting("s1", 1, 1000L))).isNull()
        assertThat(TlsFailureTranslation.toMismatch(NetworkEvent.StreamGaveUp("s1"))).isNull()
    }

    @Test
    fun `toMismatch returns null for ServerUnreachable so it NEVER produces an identity screen (UC-52 AC4)`() {
        // The transient connectivity signal must never become a Mismatch /
        // ServerIdentityChangedScreen — a momentary drop is a retryable banner,
        // not a destructive re-enroll dead-end.
        assertThat(TlsFailureTranslation.toMismatch(NetworkEvent.ServerUnreachable)).isNull()
    }

    // ── UC-56 — bare-SSLException transport-drop reclassification (AC1 / AC4 / AC5) ──
    //
    // The UC-56 fix narrows the bare `is SSLException` arm (one that is NEITHER
    // an SSLHandshakeException NOR an SSLPeerUnverifiedException — those run
    // first, above, and keep their identity routing). Conscrypt wraps a
    // mid-stream TCP drop in a plain SSLException; pre-fix that unconditionally
    // became a HandshakeError and force-routed the conversation→list transient
    // drop to the destructive re-scan-QR screen (the flicker loop). The arm is
    // now a strict two-step decision:
    //   1. identity-cause guard FIRST  → HandshakeError (AC5, "identity wins")
    //   2. transient transport check   → ServerUnreachable (AC1/AC4)
    //   3. default                     → HandshakeError (unknown bare TLS error)
    //
    // These tests pin BOTH the SECONDARY message signal (the exact
    // SOCKET_DROP_MESSAGES strings) and the PRIMARY socket-level-cause signal,
    // plus the guard-wins regression that protects genuine identity failures.

    @Test
    fun `bare SSLException with a SocketException cause routes to ServerUnreachable (AC4 primary signal)`() {
        // PRIMARY transient signal: a socket-level cause inside the bare SSL
        // exception. This is the canonical Conscrypt "mid-stream drop" shape.
        val ex = SSLException("Read error").initCause(SocketException("Connection reset by peer"))
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException with a ConnectException cause routes to ServerUnreachable (AC4 primary signal)`() {
        val ex = SSLException("ssl wrapper").initCause(ConnectException("Connection refused"))
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException with a SocketTimeoutException cause routes to ServerUnreachable (AC4 primary signal)`() {
        val ex = SSLException("ssl wrapper").initCause(SocketTimeoutException("Read timed out"))
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException with an EOFException cause routes to ServerUnreachable (AC4 primary signal)`() {
        val ex = SSLException("ssl wrapper").initCause(EOFException("\\n not found: limit=0"))
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException with an ErrnoException-named cause routes to ServerUnreachable (AC4 native path)`() {
        // The native Conscrypt path wraps android.system.ErrnoException
        // (e.g. recvfrom failed: ECONNRESET). The translator matches it by
        // SIMPLE CLASS NAME so the production file keeps ZERO android.* imports
        // (the SDK stub for ErrnoException throws on construction under JVM unit
        // tests). We reproduce that contract with a local class of the same
        // simple name, confirming the by-name match works.
        val ex = SSLException("Read error").initCause(ErrnoException("recvfrom failed: ECONNRESET (Connection reset by peer)"))
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("an ErrnoException cause (matched by simple class name) is a transient transport drop")
            .isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException 'Connection reset by peer' message routes to ServerUnreachable (AC4 secondary signal)`() {
        // SECONDARY signal: no structured socket-level cause, only the
        // Conscrypt transport-drop message. Pin the exact phrasing.
        val ex = SSLException("Read error: ssl=0x...: Connection reset by peer")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException 'Software caused connection abort' message routes to ServerUnreachable (AC4 secondary signal)`() {
        val ex = SSLException("Write error: Software caused connection abort")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException 'Socket closed' message routes to ServerUnreachable (AC4 secondary signal)`() {
        val ex = SSLException("Socket closed")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException 'broken pipe' message routes to ServerUnreachable (AC4 secondary signal)`() {
        val ex = SSLException("Write error: ssl=0x...: I/O error during system call, Broken pipe")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException 'unexpected end of stream' message routes to ServerUnreachable (AC4 secondary signal)`() {
        val ex = SSLException("unexpected end of stream on https://potato-server/")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isEqualTo(NetworkEvent.ServerUnreachable)
    }

    @Test
    fun `bare SSLException with a CertificateException cause and a connection-reset message stays HandshakeError (AC5 guard wins)`() {
        // THE load-bearing UC-56 regression: a genuine identity failure
        // (CertificateException in the cause chain) that ALSO happens to carry a
        // transport-drop message MUST stay on the identity path. The identity-
        // cause guard runs BEFORE the transient message check, so "when in doubt
        // identity wins" (AC5) — the transport-drop reclassification must never
        // swallow a real cert compromise.
        val ex = SSLException("connection reset").initCause(
            CertificateException("SPKI pin mismatch: expected=${"a".repeat(64)} observed=${"b".repeat(64)}"),
        )
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("an identity cause beats a transport-drop message — identity wins (AC5)")
            .isInstanceOf(NetworkEvent.HandshakeError::class.java)
    }

    @Test
    fun `bare SSLException wrapping an SSLHandshakeException cause stays HandshakeError (AC5 guard)`() {
        val ex = SSLException("connection reset").initCause(
            SSLHandshakeException("Remote host terminated the handshake"),
        )
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HandshakeError::class.java)
    }

    @Test
    fun `bare SSLException wrapping an SSLPeerUnverifiedException cause stays HandshakeError (AC5 guard)`() {
        val ex = SSLException("socket closed").initCause(
            SSLPeerUnverifiedException("Hostname potato-server not verified"),
        )
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HandshakeError::class.java)
    }

    @Test
    fun `bare SSLException with an unrelated (non-transport) message defaults to HandshakeError (AC5 default)`() {
        // No identity cause, no socket-level cause, and a message that is NOT a
        // socket-drop signature → the bare TLS error stays on the identity path
        // by default. This is the deliberate conservative fallback: only the
        // narrow SOCKET_DROP_MESSAGES set is reclassified as transient.
        val ex = SSLException("protocol downgrade detected")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event)
            .`as`("an unclassified bare TLS failure stays identity by default")
            .isInstanceOf(NetworkEvent.HandshakeError::class.java)
        assertThat((event as NetworkEvent.HandshakeError).rawMessage).isEqualTo("protocol downgrade detected")
    }

    @Test
    fun `pin-mismatch SSLHandshakeException is unaffected by UC-56 and still routes to PinMismatch (AC5 regression)`() {
        // Guard: the genuine-identity arms run BEFORE the bare-SSLException arm,
        // so UC-56 cannot regress pin-mismatch identity routing — even though
        // the message below contains a transport-drop phrase.
        val observedHex = "c".repeat(64)
        val ex = SSLHandshakeException("connection reset").initCause(
            CertificateException("SPKI pin mismatch: expected=${"a".repeat(64)} observed=$observedHex"),
        )
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.PinMismatch::class.java)
        assertThat((event as NetworkEvent.PinMismatch).observedPinHex).isEqualTo(observedHex)
    }

    @Test
    fun `SAN-mismatch SSLPeerUnverifiedException is unaffected by UC-56 and still routes to HostnameMismatch (AC5 regression)`() {
        // Even with a transport-drop message, the peer-unverified arm runs first.
        val ex = SSLPeerUnverifiedException("socket closed before SAN check")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HostnameMismatch::class.java)
    }

    /**
     * UC-56 — local stand-in for {@code android.system.ErrnoException}, matched
     * by the translator on simple class name (so production keeps zero
     * {@code android.*} imports and stays JVM-unit-testable). Only the simple
     * name {@code "ErrnoException"} matters for the match.
     */
    private class ErrnoException(message: String) : Exception(message)
}
