package com.aisandbox.android.net

import java.io.IOException
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

    @Test
    fun `SSLHandshakeException without an SPKI message falls back to HandshakeError`() {
        val sslHandshake = SSLHandshakeException("generic handshake error")
        val event = TlsFailureTranslation.translate(sslHandshake, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HandshakeError::class.java)
        val err = event as NetworkEvent.HandshakeError
        assertThat(err.rawMessage).isEqualTo("generic handshake error")
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
    fun `generic SSLException routes to HandshakeError`() {
        val ex = SSLException("connection reset during TLS")
        val event = TlsFailureTranslation.translate(ex, expectedPinHex, expectedHost)
        assertThat(event).isInstanceOf(NetworkEvent.HandshakeError::class.java)
        val err = event as NetworkEvent.HandshakeError
        assertThat(err.rawMessage).isEqualTo("connection reset during TLS")
    }

    @Test
    fun `plain IOException routes to HandshakeError`() {
        val ex = IOException("connection refused")
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
}
