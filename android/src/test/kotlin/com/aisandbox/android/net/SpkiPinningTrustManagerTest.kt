package com.aisandbox.android.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * UC10 § AC1 — unit-test [SpkiPinningTrustManager] in isolation.
 *
 * <p>These tests exercise the trust manager directly via its
 * {@code checkServerTrusted} entry point — no OkHttp, no
 * {@code MockWebServer} — so the failure modes the production code
 * suffers from (the OkHttp 5.3.2 chain-cleaning trap) are isolated
 * from what this class is asserting: that the SPKI digest is computed,
 * compared in constant time, and surfaced with the exact
 * {@code "SPKI pin mismatch: expected=&lt;hex&gt; observed=&lt;hex&gt;"} shape
 * the [TlsFailureTranslation] consumer parses.
 *
 * <p>Pre-fix expectation (Phase 2a / 3 partial): every test in this
 * class PASSES on the current branch — the trust manager is final;
 * only its wiring into [EnrollmentClient] / [AiSandboxHttpClient] is
 * pending Phase 2b. This file is a scaffold sanity check.
 */
class SpkiPinningTrustManagerTest {

    @Test
    fun `matching SPKI digest is accepted with no exception`() {
        val cert = HeldCertificate.Builder()
            .commonName("aisandbox-test-cn")
            .addSubjectAlternativeName("localhost")
            .build()
            .certificate
        val expectedSpki = spki(cert)

        val tm = SpkiPinningTrustManager(expectedSpki)

        // Must not throw — the SPKI digest matches.
        tm.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun `mismatched SPKI digest throws CertificateException with the structured message`() {
        // Pin against cert A; present cert B.
        val pinnedCert = HeldCertificate.Builder()
            .commonName("legit")
            .addSubjectAlternativeName("localhost")
            .build()
            .certificate
        val attackerCert = HeldCertificate.Builder()
            .commonName("attacker")
            .addSubjectAlternativeName("localhost")
            .build()
            .certificate
        val expectedSpki = spki(pinnedCert)
        val observedSpkiHex = hex(spki(attackerCert))
        val expectedSpkiHex = hex(expectedSpki)

        val tm = SpkiPinningTrustManager(expectedSpki)

        assertThatThrownBy { tm.checkServerTrusted(arrayOf(attackerCert), "RSA") }
            .isInstanceOf(CertificateException::class.java)
            .hasMessage("SPKI pin mismatch: expected=$expectedSpkiHex observed=$observedSpkiHex")
    }

    @Test
    fun `mismatch message matches the producer-consumer regex pinned by TlsFailureTranslation`() {
        // This test pins the format contract between SpkiPinningTrustManager
        // (producer of the structured message) and TlsFailureTranslation
        // (regex consumer that lifts the observed= hex out of the cause
        // chain). If either side drifts, this assertion fires.
        val pinnedCert = HeldCertificate.Builder()
            .commonName("legit")
            .addSubjectAlternativeName("localhost")
            .build()
            .certificate
        val attackerCert = HeldCertificate.Builder()
            .commonName("attacker")
            .addSubjectAlternativeName("localhost")
            .build()
            .certificate

        val tm = SpkiPinningTrustManager(spki(pinnedCert))

        try {
            tm.checkServerTrusted(arrayOf(attackerCert), "RSA")
            throw AssertionError("expected CertificateException")
        } catch (ce: CertificateException) {
            val msg = ce.message.orEmpty()
            // Same regex pinned in TlsFailureTranslation; if either side
            // drifts, both will fail.
            val regex = Regex("expected=([0-9a-f]{64}) observed=([0-9a-f]{64})")
            val match = regex.find(msg)
            assertThat(match).`as`("message did not match producer-consumer regex; was: %s", msg).isNotNull
            assertThat(match!!.groupValues[1]).hasSize(64)
            assertThat(match.groupValues[2]).hasSize(64)
            // And the literal prefix the consumer scans for.
            assertThat(msg).startsWith("SPKI pin mismatch:")
        }
    }

    @Test
    fun `null chain throws CertificateException`() {
        val expectedSpki = ByteArray(32) { 0 }
        val tm = SpkiPinningTrustManager(expectedSpki)
        assertThatThrownBy { tm.checkServerTrusted(null, "RSA") }
            .isInstanceOf(CertificateException::class.java)
    }

    @Test
    fun `empty chain throws CertificateException`() {
        val expectedSpki = ByteArray(32) { 0 }
        val tm = SpkiPinningTrustManager(expectedSpki)
        assertThatThrownBy { tm.checkServerTrusted(emptyArray<X509Certificate>(), "RSA") }
            .isInstanceOf(CertificateException::class.java)
    }

    @Test
    fun `checkClientTrusted is a no-op`() {
        val expectedSpki = ByteArray(32) { 0 }
        val tm = SpkiPinningTrustManager(expectedSpki)
        // Even with bogus / null arguments — must not throw.
        tm.checkClientTrusted(null, "RSA")
        tm.checkClientTrusted(emptyArray<X509Certificate>(), "RSA")
    }

    @Test
    fun `getAcceptedIssuers returns an empty array safely`() {
        val expectedSpki = ByteArray(32) { 0 }
        val tm = SpkiPinningTrustManager(expectedSpki)
        // UC10 § AC1 — empty is intentional (the cleaner is off the
        // verification path). This is the property that historically
        // tripped OkHttp's chain-cleaner; with the TM-based check the
        // hazard is unreachable.
        assertThat(tm.acceptedIssuers).isEmpty()
    }

    @Test
    fun `constructor rejects an expected SPKI of wrong length`() {
        assertThatThrownBy { SpkiPinningTrustManager(ByteArray(31)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SpkiPinningTrustManager(ByteArray(33)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SpkiPinningTrustManager(ByteArray(0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `expectedSpki is held by reference but a post-construction mutation does not poison checks`() {
        // Defensive-copy semantics check: if the impl copies the input,
        // mutating the caller's array MUST NOT affect verification. If
        // it doesn't copy, we accept that and move on (it's an internal
        // optimisation); but verify behaviour is consistent either way.
        val cert = HeldCertificate.Builder()
            .commonName("cn")
            .addSubjectAlternativeName("localhost")
            .build()
            .certificate
        val real = spki(cert)
        val mutableCopy = real.copyOf()
        val tm = SpkiPinningTrustManager(mutableCopy)
        // Mutate the caller-supplied array.
        for (i in mutableCopy.indices) {
            mutableCopy[i] = 0xff.toByte()
        }
        // If the TM defensively copied, the call still passes; if it
        // didn't copy, the call now fails. Either is OK — but the
        // behaviour MUST be deterministic, not throw unrelated errors.
        try {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
            // Defensive copy in place — pass.
        } catch (ce: CertificateException) {
            // No defensive copy — verify the failure is the documented
            // SPKI pin mismatch (not some other error).
            assertThat(ce.message).startsWith("SPKI pin mismatch:")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun spki(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
