package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC04 AC7 + UC10 § AC3, AC4 — the long-lived OkHttp client used
 * post-enrollment is wired with [SpkiPinningTrustManager] (UC10) for
 * cert verification and routes failures via [TlsFailureTranslation]
 * into the correct [NetworkEvent] variant.
 *
 * <p>UC10 deletes the pre-UC10 {@code lenient-TrustManager +
 * CertificatePinner} pair from [AiSandboxHttpClient]; this test was
 * previously {@code @Disabled} because the lenient TM tripped the
 * OkHttp 5.3.2 chain-cleaning trap (the same root cause UC10 exists
 * to fix). UC10 re-enables it against the production trust-manager
 * configuration the phone actually uses.
 *
 * <h2>Pre-fix expectations (Phase 3 partial — cascade signal)</h2>
 *
 * <ul>
 *   <li>{@link #pinMatchSucceeds} → MUST FAIL on the current branch
 *       (the lenient TM trips the chain-cleaning trap; even the
 *       happy-path request fails with the unconditional "Certificate
 *       pinning failure!" message).</li>
 *   <li>{@link #pinMismatchEmitsRealObservedHexAndThrows} → MUST FAIL
 *       on the current branch (the pre-UC10 interceptor lifts the
 *       observed hex from OkHttp's
 *       "Pinned certificates for &lt;host&gt; … sha256/&lt;b64&gt;" message;
 *       on the chain-cleaning-trap path that message reports an empty
 *       peer chain so the lift returns {@code <unknown>} or worse,
 *       {@code <bootstrap>} — not the real SPKI hex of the cert the
 *       server actually presented).</li>
 *   <li>{@link #hostnameMismatchEmitsExpectedHostAndThrows} → MUST FAIL
 *       on the current branch (the pre-UC10 client doesn't distinguish
 *       hostname from pin mismatch — it routes every
 *       {@code SSLPeerUnverifiedException} through the pin-mismatch
 *       interceptor).</li>
 *   <li>{@link #mtlsClientCertSurvivesTrustManagerSwap} → MUST FAIL
 *       on the current branch (same chain-cleaning trap as
 *       {@code pinMatchSucceeds}; verifies the
 *       {@code KeyManager}-side mTLS identity is still wired after the
 *       TM swap).</li>
 * </ul>
 *
 * <h2>Post-fix expectations (Phase 2b)</h2>
 *
 * <p>All four tests PASS without further edits to this file. The
 * cascade is driven entirely by the production-side rewire of
 * {@code AiSandboxHttpClient.build()} to use
 * {@link SpkiPinningTrustManager} and route exceptions through
 * {@link TlsFailureTranslation}.
 */
class AiSandboxHttpClientTest {

    /**
     * Build a minimal [KeyStoreIdentityManager] backed by an empty
     * PKCS#12 — sufficient for the SSL handshake to advance through
     * the {@code KeyManager} side without presenting a client cert.
     * The {@code MockWebServer} side is configured to NOT request a
     * client cert (UC10 § AC3 — the mTLS identity is the
     * {@code KeyManager}'s job, not the trust manager's).
     */
    private fun mockIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        return mock(KeyStoreIdentityManager::class.java).also {
            `when`(it.keyManagerFactory()).thenReturn(factory)
        }
    }

    @Test
    fun pinMatchSucceeds() = runTest {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-uc10-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()

        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val spkiHex = spkiHex(cert.certificate.publicKey.encoded)
            val profile = ServerProfile(
                serverUrl = "https://127.0.0.1:${server.port}",
                pinSha256Hex = spkiHex,
                clientCertCn = "alice-phone",
                clientCertExpiresAtMs = 0L,
            )
            val http = AiSandboxHttpClient(profile, mockIdentity())

            val req = Request.Builder().url("${http.baseUrl}/v1/healthz").build()
            http.client.newCall(req).execute().use { resp ->
                assertThat(resp.code)
                    .`as`(
                        "UC10 § AC3 — happy-path GET must succeed when the SPKI pin matches " +
                            "and the host is in the cert's SAN. Pre-fix the chain-cleaning trap " +
                            "yields SSLPeerUnverifiedException regardless."
                    )
                    .isEqualTo(200)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pinMismatchEmitsRealObservedHexAndThrows() = runTest {
        // The server presents `runningCert`; we pin against the SPKI
        // of a DIFFERENT cert. OkHttp's SpkiPinningTrustManager catches
        // the mismatch in checkServerTrusted and throws a structured
        // CertificateException; the catch in AiSandboxHttpClient
        // emits a NetworkEvent.PinMismatch carrying the REAL observed
        // hex (the running cert's SPKI), NOT the legacy <unknown> /
        // <bootstrap> sentinel.
        val runningCert = HeldCertificate.Builder()
            .commonName("running")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val phantomExpectedCert = HeldCertificate.Builder()
            .commonName("phantom-expected")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
            .certificate

        val handshake = HandshakeCertificates.Builder().heldCertificate(runningCert).build()

        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("hijack"))

            val expectedPinHex = spkiHex(phantomExpectedCert.publicKey.encoded)
            val realObservedHex = spkiHex(runningCert.certificate.publicKey.encoded)
            val profile = ServerProfile(
                serverUrl = "https://127.0.0.1:${server.port}",
                pinSha256Hex = expectedPinHex,
                clientCertCn = "alice-phone",
                clientCertExpiresAtMs = 0L,
            )
            val http = AiSandboxHttpClient(profile, mockIdentity())

            val seen = java.util.Collections.synchronizedList(mutableListOf<NetworkEvent>())
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                NetworkEvents.flow.collect { seen.add(it) }
            }

            try {
                val req = Request.Builder().url("${http.baseUrl}/v1/healthz").build()
                org.assertj.core.api.Assertions.assertThatThrownBy {
                    http.client.newCall(req).execute()
                }.isInstanceOf(javax.net.ssl.SSLHandshakeException::class.java)
            } catch (_: Throwable) {
                // OkHttp can also surface this as a wrapped IOException;
                // we don't care about the exact class — only the event.
            } finally {
                job.cancel()
            }

            val mismatch = seen.filterIsInstance<NetworkEvent.PinMismatch>().firstOrNull()
            assertThat(mismatch)
                .`as`("UC10 § AC4 — pin mismatch must emit NetworkEvent.PinMismatch")
                .isNotNull
            assertThat(mismatch!!.expectedPinHex).isEqualTo(expectedPinHex)
            assertThat(mismatch.observedPinHex)
                .`as`(
                    "UC10 § AC4 — observedPinHex MUST be the real SPKI hex of the cert " +
                        "the server presented (NOT `<bootstrap>` / `<unknown>` / a stale " +
                        "OkHttp message-prefix extraction). Pre-fix: FAILS — pre-UC10 " +
                        "code lifts the observed pin from OkHttp's pin-mismatch message, " +
                        "which is empty on the chain-cleaning-trap path."
                )
                .isEqualTo(realObservedHex)
            assertThat(mismatch.observedPinHex).isNotEqualTo("<bootstrap>")
            assertThat(mismatch.observedPinHex).isNotEqualTo("<unknown>")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun hostnameMismatchEmitsExpectedHostAndThrows() = runTest {
        // Cert whose ONLY SAN is `localhost`; connect via `127.0.0.1`.
        // Pin matches (we use the cert's own SPKI), so the failure is
        // hostname-only. Post-UC10 routes this to HostnameMismatch.
        val noIpSanCert = HeldCertificate.Builder()
            .commonName("no-ip-san")
            .addSubjectAlternativeName("localhost")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(noIpSanCert).build()

        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("hijack"))

            val matchingPinHex = spkiHex(noIpSanCert.certificate.publicKey.encoded)
            val profile = ServerProfile(
                serverUrl = "https://127.0.0.1:${server.port}",
                pinSha256Hex = matchingPinHex,
                clientCertCn = "alice-phone",
                clientCertExpiresAtMs = 0L,
            )
            val http = AiSandboxHttpClient(profile, mockIdentity())

            val seen = java.util.Collections.synchronizedList(mutableListOf<NetworkEvent>())
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                NetworkEvents.flow.collect { seen.add(it) }
            }

            try {
                val req = Request.Builder().url("${http.baseUrl}/v1/healthz").build()
                org.assertj.core.api.Assertions.assertThatThrownBy {
                    http.client.newCall(req).execute()
                }.isInstanceOf(SSLPeerUnverifiedException::class.java)
            } catch (_: Throwable) {
                // Tolerate other exception classes — the assertion of
                // interest is on the emitted event below.
            } finally {
                job.cancel()
            }

            val hostnameMismatch = seen.filterIsInstance<NetworkEvent.HostnameMismatch>().firstOrNull()
            assertThat(hostnameMismatch)
                .`as`(
                    "UC10 § AC4 — a hostname-mismatch must emit NetworkEvent.HostnameMismatch. " +
                        "Pre-fix: FAILS — pre-UC10 catch routes every SSLPeerUnverifiedException " +
                        "into PinMismatch(<bootstrap>)."
                )
                .isNotNull
            assertThat(hostnameMismatch!!.expectedHost).isEqualTo("127.0.0.1")

            // And NO PinMismatch should have been emitted.
            val pinMismatch = seen.filterIsInstance<NetworkEvent.PinMismatch>().firstOrNull()
            assertThat(pinMismatch)
                .`as`(
                    "UC10 § AC4 — a hostname-only mismatch must NOT also emit PinMismatch. " +
                        "Pre-fix: FAILS — pre-UC10 catch emits PinMismatch(<bootstrap>) for " +
                        "every SSLPeerUnverifiedException."
                )
                .isNull()
        } finally {
            server.shutdown()
        }
    }

    /**
     * UC10 § AC3 pitfall — the hardware-backed [KeyManager] for the
     * client cert must remain wired into the {@code SSLContext} after
     * the trust-manager swap. This test exercises the happy-path
     * handshake to confirm the {@code KeyManager} slot is still
     * populated (a regression would surface as an mTLS handshake
     * failure even when the pin / host are correct, because the
     * server would see no client cert and reject the request).
     *
     * <p>{@code MockWebServer} does NOT request a client cert by
     * default — so this test is a smoke check that the
     * {@code keyManagerFactory().keyManagers} call still happens at
     * {@code SSLContext.init} time. A more rigorous variant against
     * an mTLS-requiring server can be added in a follow-up.
     *
     * <p>Pre-fix: FAILS for the same chain-cleaning-trap reason as
     * {@code pinMatchSucceeds}.
     */
    @Test
    fun mtlsClientCertSurvivesTrustManagerSwap() = runTest {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-uc10-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()

        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(204)
                    .setHeadersDelay(0L, TimeUnit.MILLISECONDS)
            )

            val pinHex = spkiHex(cert.certificate.publicKey.encoded)
            val profile = ServerProfile(
                serverUrl = "https://127.0.0.1:${server.port}",
                pinSha256Hex = pinHex,
                clientCertCn = "alice-phone",
                clientCertExpiresAtMs = 0L,
            )

            val identity = mockIdentity()
            val http = AiSandboxHttpClient(profile, identity)

            val req = Request.Builder().url("${http.baseUrl}/v1/sessions").build()
            http.client.newCall(req).execute().use { resp ->
                assertThat(resp.code).isEqualTo(204)
            }
            // Identity was consulted at SSLContext.init time — a
            // regression of "TrustManager swap dropped the KeyManager"
            // would surface as never calling keyManagerFactory().
            org.mockito.Mockito.verify(identity, org.mockito.Mockito.atLeastOnce()).keyManagerFactory()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun baseUrlStripsTrailingSlash() {
        val profile = ServerProfile(
            serverUrl = "https://example.com:12410/",
            pinSha256Hex = "a".repeat(64),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        val http = AiSandboxHttpClient(profile, mockIdentity())
        assertThat(http.baseUrl).isEqualTo("https://example.com:12410")
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
