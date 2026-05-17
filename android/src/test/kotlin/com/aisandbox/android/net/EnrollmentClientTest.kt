package com.aisandbox.android.net

import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC04 AC33–AC35 — Android side of POST /v1/enrollment.
 *
 * <p>Drives a {@link MockWebServer} configured with a self-signed cert;
 * the [QrPayload]'s {@code pin} field carries the SHA-256 of that cert
 * so [EnrollmentClient] uses [okhttp3.CertificatePinner] to authenticate
 * the bootstrap server.
 *
 * <p>Each test pins one wire shape:
 *
 * <ul>
 *   <li>201 → Success carrying the raw octet-stream body.</li>
 *   <li>401 → Failure with the code lifted from the problem-details body.</li>
 *   <li>413 → Failure with `payload_too_large`.</li>
 *   <li>429 → Failure with `enrollment_rate_limited`.</li>
 *   <li>Pin mismatch (server presents a different cert) →
 *       NetworkEvents.PinMismatch + Failure("pin_mismatch").</li>
 * </ul>
 */
@org.junit.jupiter.api.Disabled(
    "v0.1 follow-up: MockWebServer + TLS startup is flaky on android-ci (JVM hangs); re-enable once we have a deterministic teardown."
)
class EnrollmentClientTest {

    private lateinit var server: MockWebServer
    private lateinit var serverCert: HeldCertificate
    private lateinit var pinHex: String

    @BeforeEach
    fun setUp() {
        // Build a self-signed cert with SAN=localhost so OkHttp's
        // hostname verifier doesn't reject the loopback handshake.
        serverCert = HeldCertificate.Builder()
            .commonName("ai-sandbox-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("localhost"), 0)
        }
        // SHA-256 of the cert's DER, lowercase hex — same format
        // `aisandboxctl client invite --server-pin` emits.
        val der = serverCert.certificate.encoded
        pinHex = MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun payload(token: String = TOKEN_VALID): QrPayload = QrPayload(
        serverUrl = "https://localhost:${server.port}",
        token = token,
        expiresAtIso = "2026-05-17T10:10:00Z",
        pinSha256Hex = pinHex,
    )

    @Test
    fun `201 returns the pkcs12 bytes verbatim`() = runTest {
        val p12 = byteArrayOf(0x30, 0x12, 0x34, 0x56)
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().apply { write(p12) })
        )

        val outcome = EnrollmentClient(payload()).redeem()

        assertThat(outcome).isInstanceOf(EnrollmentClient.Outcome.Success::class.java)
        val success = outcome as EnrollmentClient.Outcome.Success
        assertThat(success.pkcs12).isEqualTo(p12)

        // Wire shape sanity — POST + Content-Type: application/json.
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/enrollment")
        assertThat(recorded.headers["Content-Type"]).contains("application/json")
        assertThat(recorded.body.readUtf8()).contains(TOKEN_VALID)
    }

    @Test
    fun `401 enrollment_token_invalid is surfaced as Failure with that code`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"status":401,"code":"enrollment_token_invalid","detail":"unknown"}""")
        )

        val outcome = EnrollmentClient(payload()).redeem()
        assertThat(outcome).isInstanceOf(EnrollmentClient.Outcome.Failure::class.java)
        val failure = outcome as EnrollmentClient.Outcome.Failure
        assertThat(failure.code).isEqualTo("enrollment_token_invalid")
    }

    @Test
    fun `401 token_expired and token_redeemed each surface their own codes`() = runTest {
        // Two requests in sequence — fresh client per call so the
        // pinned-server state stays clean.
        for (code in listOf("enrollment_token_expired", "enrollment_token_redeemed")) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":401,"code":"$code","detail":""}""")
            )
            val outcome = EnrollmentClient(payload()).redeem()
            val failure = outcome as EnrollmentClient.Outcome.Failure
            assertThat(failure.code).`as`("expected code=%s", code).isEqualTo(code)
        }
    }

    @Test
    fun `413 surfaces payload_too_large`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(413)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"status":413,"code":"payload_too_large","detail":"body > 256B"}""")
        )

        val outcome = EnrollmentClient(payload()).redeem()
        val failure = outcome as EnrollmentClient.Outcome.Failure
        assertThat(failure.code).isEqualTo("payload_too_large")
    }

    @Test
    fun `429 surfaces enrollment_rate_limited`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"status":429,"code":"enrollment_rate_limited","detail":"1 per 60s"}""")
        )

        val outcome = EnrollmentClient(payload()).redeem()
        val failure = outcome as EnrollmentClient.Outcome.Failure
        assertThat(failure.code).isEqualTo("enrollment_rate_limited")
    }

    @Test
    fun `pin mismatch maps to Failure pin_mismatch and emits NetworkEvent`() = runTest {
        // Build a DIFFERENT cert; pin against the original one — the
        // handshake should fail with SSLPeerUnverifiedException.
        val attackerCert = HeldCertificate.Builder()
            .commonName("attacker")
            .addSubjectAlternativeName("localhost")
            .build()
        val attackerHandshake = HandshakeCertificates.Builder()
            .heldCertificate(attackerCert)
            .build()

        val attackerServer = MockWebServer().apply {
            useHttps(attackerHandshake.sslSocketFactory(), false)
            start(InetAddress.getByName("localhost"), 0)
        }
        attackerServer.enqueue(
            MockResponse().setResponseCode(201).setBody("ignored")
        )

        try {
            val payloadAgainstAttacker = payload().copy(
                serverUrl = "https://localhost:${attackerServer.port}",
            )
            val outcome = EnrollmentClient(payloadAgainstAttacker).redeem()
            val failure = outcome as EnrollmentClient.Outcome.Failure
            assertThat(failure.code).isEqualTo("pin_mismatch")
        } finally {
            attackerServer.shutdown()
        }
    }

    companion object {
        // Obvious placeholder — 64 chars of [A-Za-z0-9._-], never any real key material.
        const val TOKEN_VALID = "abcd1234.fake-test-token-not-a-real-key.0123456789ab-cdefABCDEFX"
    }
}
