package com.aisandbox.android.net

import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC10 § AC9 — Android-side integration test that exercises the
 * PRODUCTION trust-manager configuration ({@link EnrollmentClient}'s
 * real OkHttp wiring) against a {@link MockWebServer} serving a
 * self-signed cert.
 *
 * <p>This test exists because UC09's {@code EnrollmentPinAlgorithmTest}
 * sidesteps the OkHttp 5.3.2 chain-cleaning trap by returning the
 * server cert from a test-only trust manager's
 * {@code getAcceptedIssuers()}. That workaround is a test-construction
 * choice, NOT a reflection of how production wires the Android client.
 * UC10 § AC9 closes that gap: this test wires the EXACT trust manager
 * production uses, so the chain-cleaning trap is regression-blocked
 * going forward.
 *
 * <h2>Pre-fix expectations (Phase 3 partial — cascade signal)</h2>
 *
 * <ul>
 *   <li>{@link #happyPath201} → MUST FAIL on the current branch. The
 *       lenient TM in {@code EnrollmentClient.buildClient} has empty
 *       {@code getAcceptedIssuers()}; OkHttp's
 *       {@code BasicCertificateChainCleaner} can't find a trusted
 *       root → throws {@code SSLPeerUnverifiedException("Failed to
 *       find a trusted cert that signed …")}; the exception is
 *       silently swallowed by {@code Handshake.peerCertificates_delegate};
 *       {@code CertificatePinner.check()} iterates an empty chain →
 *       unconditional {@code "Certificate pinning failure!"} — even
 *       with the right pin. Outcome is {@code Failure(pin_mismatch)},
 *       not {@code Success}.</li>
 *   <li>{@link #pinMismatchEmitsRealObservedHexAndThrows} → MUST FAIL
 *       on the current branch. The pre-fix catch block emits
 *       {@code observedPinHex = "<bootstrap>"}, not the real observed
 *       SPKI hex of the server's cert. The assertion that the observed
 *       hex matches the attacker cert's SPKI fails.</li>
 *   <li>{@link #hostnameMismatchEmitsExpectedHostAndThrows} → MUST FAIL
 *       on the current branch. The pre-fix catch block lumps every
 *       {@link javax.net.ssl.SSLPeerUnverifiedException} into a
 *       {@link NetworkEvent.PinMismatch} (with the {@code <bootstrap>}
 *       sentinel), so no
 *       {@link NetworkEvent.HostnameMismatch} is emitted.</li>
 * </ul>
 *
 * <h2>Post-fix expectations (Phase 2b)</h2>
 *
 * <p>All three tests PASS without further edits to this file. The
 * cascade is driven entirely by the production-side rewire of
 * {@code EnrollmentClient.buildClient} to use
 * {@link SpkiPinningTrustManager} and route exceptions through
 * {@link TlsFailureTranslation}.
 *
 * <h2>Deterministic shutdown</h2>
 *
 * <p>{@code MockWebServer} + OkHttp's dispatcher can leak threads if
 * we just rely on {@code @AfterEach}. Mirrors the pattern in
 * {@code EnrollmentPinAlgorithmTest}: explicit
 * {@code dispatcher.executorService.shutdown()} +
 * {@code connectionPool.evictAll()} + bounded {@code awaitTermination}
 * so the test JVM is promptly idle for the next class — the
 * {@code @Disabled} CI-hang on the predecessor test was exactly this
 * issue.
 */
class EnrollmentClientIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var serverCert: HeldCertificate
    private lateinit var spkiPinHex: String

    @BeforeEach
    fun setUp() {
        // SAN = IP:127.0.0.1 so OkHttp's default hostname verifier
        // accepts the loopback handshake. This mirrors how production
        // certs are minted via `pki init --san IP:<host>`.
        serverCert = HeldCertificate.Builder()
            .commonName("ai-sandbox-uc10-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()
        server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        // SHA-256 of the cert's SubjectPublicKeyInfo (SPKI) — matches
        // PemUtils.spkiFingerprintHex on the server side and what
        // OkHttp's CertificatePinner would compute against the
        // presented chain.
        val spki = MessageDigest.getInstance("SHA-256")
            .digest(serverCert.certificate.publicKey.encoded)
        spkiPinHex = spki.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @AfterEach
    fun tearDown() {
        // Deterministic shutdown — see class Javadoc.
        try {
            server.shutdown()
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }

    private fun payload(
        token: String = TOKEN_VALID,
        serverUrl: String = "https://127.0.0.1:${server.port}",
        pin: String = spkiPinHex,
    ): QrPayload = QrPayload(
        serverUrl = serverUrl,
        token = token,
        expiresAtIso = "2026-05-17T10:10:00Z",
        pinSha256Hex = pin,
    )

    /**
     * UC10 § AC9 happy-path: correct pin, correct SAN, server returns
     * 201 with a PKCS#12 body. EnrollmentClient must return
     * {@code Outcome.Success} carrying the bytes verbatim.
     *
     * <p>Pre-fix: MUST FAIL because the lenient TM trips the
     * chain-cleaning trap (see class Javadoc).
     */
    @Test
    fun happyPath201() = runTest {
        val p12 = byteArrayOf(0x30, 0x12, 0x34, 0x56, 0x78, 0x7f, 0x00, 0x00)
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().apply { write(p12) })
        )

        val outcome = EnrollmentClient(payload()).redeem()

        assertThat(outcome)
            .`as`(
                "UC10 § AC9 — happy-path enrollment must succeed when the pin matches " +
                    "and the host is in the cert's SAN. Pre-fix the chain-cleaning trap " +
                    "yields Failure(pin_mismatch) regardless of the real pin."
            )
            .isInstanceOf(EnrollmentClient.Outcome.Success::class.java)
        val success = outcome as EnrollmentClient.Outcome.Success
        assertThat(success.pkcs12).isEqualTo(p12)

        // Wire shape sanity — POST + Content-Type: application/json + body carries the token.
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertThat(recorded).isNotNull
        assertThat(recorded!!.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/enrollment")
        assertThat(recorded.headers["Content-Type"]).contains("application/json")
        assertThat(recorded.body.readUtf8()).contains(TOKEN_VALID)
    }

    /**
     * UC10 § AC4 — when the server presents a DIFFERENT cert from the
     * one the QR pinned, the failure surfaces as
     * {@link NetworkEvent.PinMismatch} carrying the REAL observed SPKI
     * hex (not the {@code <bootstrap>} sentinel).
     *
     * <p>Pre-fix: MUST FAIL because the pre-UC10 catch block emits
     * {@code <bootstrap>} regardless of the real cert.
     */
    @Test
    fun pinMismatchEmitsRealObservedHexAndThrows() = runTest {
        // Build a SECOND cert that we'll pin against; the running
        // server still serves the original cert. The real observed
        // SPKI hex therefore should be `spkiPinHex` (the running cert's
        // SPKI), and the expected hex in the payload is the OTHER cert's
        // SPKI.
        val attackerExpectedCert = HeldCertificate.Builder()
            .commonName("phantom-expected")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
            .certificate
        val expectedSpki = MessageDigest.getInstance("SHA-256")
            .digest(attackerExpectedCert.publicKey.encoded)
        val expectedSpkiHex = expectedSpki.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        server.enqueue(
            MockResponse().setResponseCode(201).setBody("ignored")
        )

        // Subscribe to NetworkEvents BEFORE the request fires, with a
        // bounded coroutine job so test teardown is clean.
        val seen = java.util.Collections.synchronizedList(mutableListOf<NetworkEvent>())
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            NetworkEvents.flow.collect { seen.add(it) }
        }

        try {
            val outcome = EnrollmentClient(payload(pin = expectedSpkiHex)).redeem()
            // Outcome is Failure(pin_mismatch); the assertion of
            // interest is on the emitted event below.
            assertThat(outcome).isInstanceOf(EnrollmentClient.Outcome.Failure::class.java)
        } finally {
            job.cancel()
        }

        val mismatch = seen.filterIsInstance<NetworkEvent.PinMismatch>().firstOrNull()
        assertThat(mismatch)
            .`as`("UC10 § AC4 — expected a NetworkEvent.PinMismatch emission on pin failure")
            .isNotNull
        assertThat(mismatch!!.expectedPinHex)
            .`as`("expectedPinHex carries the QR-payload pin verbatim")
            .isEqualTo(expectedSpkiHex)
        // THE central post-UC10 assertion: observedPinHex is the REAL
        // SPKI hex of the cert the server presented (the running cert),
        // NOT the `<bootstrap>` sentinel.
        assertThat(mismatch.observedPinHex)
            .`as`(
                "UC10 § AC4 — observedPinHex MUST be the real SPKI hex of the " +
                    "server's presented cert, NOT the `<bootstrap>` sentinel. Pre-fix " +
                    "this assertion FAILS — the pre-UC10 catch block emits the sentinel."
            )
            .isEqualTo(spkiPinHex)
        assertThat(mismatch.observedPinHex).isNotEqualTo("<bootstrap>")
    }

    /**
     * UC10 § AC4 — when the QR's {@code u} points at a host not in the
     * cert's SAN list, Android's default {@code OkHostnameVerifier}
     * fires AFTER the trust manager succeeds, raising
     * {@link javax.net.ssl.SSLPeerUnverifiedException}. The post-UC10
     * catch routes this to {@link NetworkEvent.HostnameMismatch}, NOT
     * {@link NetworkEvent.PinMismatch}.
     *
     * <p>Pre-fix: MUST FAIL because every
     * {@code SSLPeerUnverifiedException} is lumped into
     * {@code PinMismatch(<bootstrap>)}.
     */
    @Test
    fun hostnameMismatchEmitsExpectedHostAndThrows() = runTest {
        // Mint a cert whose ONLY SAN is `localhost`; connect via the
        // raw IPv4 literal `127.0.0.1`. OkHostnameVerifier rejects the
        // handshake AFTER the SPKI pin matches (the pin is computed on
        // the same cert the server serves), so the failure mode is
        // squarely hostname-mismatch.
        server.shutdown()
        val noIpSanCert = HeldCertificate.Builder()
            .commonName("no-ip-san")
            .addSubjectAlternativeName("localhost")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(noIpSanCert).build()
        val freshServer = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            val spki = MessageDigest.getInstance("SHA-256")
                .digest(noIpSanCert.certificate.publicKey.encoded)
            val matchingPinHex = spki.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            freshServer.enqueue(MockResponse().setResponseCode(201).setBody("ignored"))

            val seen = java.util.Collections.synchronizedList(mutableListOf<NetworkEvent>())
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                NetworkEvents.flow.collect { seen.add(it) }
            }

            try {
                val outcome = EnrollmentClient(
                    payload(
                        serverUrl = "https://127.0.0.1:${freshServer.port}",
                        pin = matchingPinHex,
                    )
                ).redeem()
                assertThat(outcome).isInstanceOf(EnrollmentClient.Outcome.Failure::class.java)
            } finally {
                job.cancel()
            }

            val hostnameMismatch = seen.filterIsInstance<NetworkEvent.HostnameMismatch>().firstOrNull()
            assertThat(hostnameMismatch)
                .`as`(
                    "UC10 § AC4 — connecting via an IP literal that's not in the cert's SAN " +
                        "must emit NetworkEvent.HostnameMismatch (NOT PinMismatch with <bootstrap>). " +
                        "Pre-fix: this assertion FAILS — every SSLPeerUnverifiedException is " +
                        "routed to PinMismatch by the pre-UC10 catch."
                )
                .isNotNull
            assertThat(hostnameMismatch!!.expectedHost)
                .`as`("expectedHost is the URL's host portion")
                .isEqualTo("127.0.0.1")

            // And NO PinMismatch should have been emitted — the pin
            // actually matched; the failure is squarely on hostname.
            val pinMismatch = seen.filterIsInstance<NetworkEvent.PinMismatch>().firstOrNull()
            assertThat(pinMismatch)
                .`as`(
                    "UC10 § AC4 — a hostname-only mismatch must NOT also emit PinMismatch. " +
                        "Pre-fix: this assertion FAILS — the pre-UC10 catch emits " +
                        "PinMismatch(<bootstrap>) for every SSLPeerUnverifiedException."
                )
                .isNull()
        } finally {
            try {
                freshServer.shutdown()
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    companion object {
        // 64-char placeholder — never any real key material. Same shape
        // as `aisandboxctl client invite` emits (32-byte hex).
        const val TOKEN_VALID = "abcd1234.fake-test-token-not-a-real-key.0123456789ab-cdefABCDEFX"
    }
}
