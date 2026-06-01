package com.aisandbox.android.net

import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * BUG 1 root / Fix B — [SessionsApi.list] decodes `GET /v1/sessions`
 * by inspecting the ACTUAL JSON shape (bare array vs `{sessions:[…]}`
 * envelope), driven against a real OkHttp + [SpkiPinningTrustManager] +
 * kotlinx stack over a pinned-HTTPS [MockWebServer] (the same harness
 * as [AiSandboxHttpClientTest]).
 *
 * <h2>#5 decode-gate — pre-Fix-B framing (throws, not null)</h2>
 *
 * <p>The bare-array case ([bareJsonArrayBody_parsesToSuccess]) is the
 * regression guard for the BUG 1 root. The developer empirically
 * verified that the pre-Fix-B envelope-FIRST decode
 * (`decodeFromString(SessionsListEnvelope.serializer(), body).sessions ?: <bare list>`)
 * does NOT silently return null on a bare array — kotlinx's structure
 * decoder requires a `{` and THROWS a {@code JsonDecodingException} (a
 * {@code SerializationException}) before the {@code ?:} fallback can
 * run. So pre-Fix-B {@code list()} on the real (bare-array) server body
 * always landed in {@code mapResponse}'s catch and returned
 * {@code HttpFailure(decode_error)} — never {@code Success}. This test
 * pins {@code Success} on a bare array, so it is RED on pre-Fix-B
 * SessionsApi.kt (commit 13f1e86) and GREEN post-Fix-B. (Cannot be run
 * locally — no Android SDK — so this is authored + reasoned for
 * red-green and validated in CI's `:android:test`.)
 */
class SessionsApiTest {

    /**
     * Empty PKCS#12-backed [KeyStoreIdentityManager] — enough for the
     * SSLContext to advance through the KeyManager side without
     * presenting a client cert. MockWebServer is configured NOT to
     * request one (mTLS identity is the KeyManager's job). Mirrors
     * [AiSandboxHttpClientTest.mockIdentity].
     */
    private fun mockIdentity(): com.aisandbox.android.identity.KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        return mock(com.aisandbox.android.identity.KeyStoreIdentityManager::class.java).also {
            `when`(it.keyManagerFactory()).thenReturn(factory)
        }
    }

    private fun startPinnedServer(): Pair<MockWebServer, ServerProfile> {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-sessions-api-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:${server.port}",
            pinSha256Hex = spkiHex(cert.certificate.publicKey.encoded),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        return server to profile
    }

    private fun apiFor(profile: ServerProfile): SessionsApi =
        SessionsApi(AiSandboxHttpClient(profile, mockIdentity()))

    @Test
    fun bareJsonArrayBody_parsesToSuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // The REAL server shape: a bare JSON array (ResponseEntity.ok(List)).
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"n":3,"label":"alpha","tmuxTitle":"work","state":"running","uptimeSec":120,"activeStreams":1,"startedAt":"2026-05-22T09:00:00Z"},
                      {"n":5,"label":"beta","tmuxTitle":"(idle)","state":"stopped","uptimeSec":0,"activeStreams":0,"startedAt":null}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result)
                .`as`(
                    "Fix B — a BARE JSON array MUST decode to ApiResult.Success. " +
                        "Pre-Fix-B the envelope-first decode THROWS JsonDecodingException on a " +
                        "'[' (it never returns null), so list() returned HttpFailure(decode_error) " +
                        "— the BUG 1 root. RED on pre-Fix-B SessionsApi.kt (13f1e86), GREEN post-fix.",
                )
                .isInstanceOf(ApiResult.Success::class.java)
            val success = result as ApiResult.Success
            assertThat(success.value.map { it.n }).containsExactly(3, 5)
            assertThat(success.value.map { it.state }).containsExactly("running", "stopped")
            assertThat(success.value[0].label).isEqualTo("alpha")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionsEnvelopeBody_parsesToSuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // Legacy/tolerated shape: a { "sessions": [...] } envelope.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"sessions":[
                      {"n":7,"label":"gamma","tmuxTitle":"main","state":"running","uptimeSec":42,"activeStreams":2,"startedAt":null}
                    ]}
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result)
                .`as`("Fix B — the { sessions: [...] } envelope MUST still decode to Success")
                .isInstanceOf(ApiResult.Success::class.java)
            val success = result as ApiResult.Success
            assertThat(success.value.map { it.n }).containsExactly(7)
            assertThat(success.value[0].activeStreams).isEqualTo(2)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bareEmptyArrayBody_parsesToEmptySuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // The common "no sessions yet" case: a bare empty array.
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            assertThat((result as ApiResult.Success).value).isEmpty()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun provisioningStateBody_roundTrips() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // UC-27 — the server can now report `provisioning` (container up,
            // toolchains installing). It must decode verbatim. A second row
            // carries an unknown state token + an unknown field to pin the
            // `ignoreUnknownKeys` + lenient-token tolerance (StatusPill renders
            // an unknown token raw rather than throwing).
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"n":4,"label":"installing","tmuxTitle":"(unavailable)","state":"provisioning","uptimeSec":3,"activeStreams":0,"startedAt":null},
                      {"n":6,"label":"future","tmuxTitle":"","state":"some_future_state","uptimeSec":0,"activeStreams":0,"startedAt":null,"unknownField":"x"}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val success = result as ApiResult.Success
            assertThat(success.value.map { it.n }).containsExactly(4, 6)
            // UC-27 — provisioning round-trips as the known token.
            assertThat(success.value[0].state).isEqualTo("provisioning")
            assertThat(success.value[0].label).isEqualTo("installing")
            // ignoreUnknownKeys tolerance: unknown state token + unknown field
            // still decode without throwing.
            assertThat(success.value[1].state).isEqualTo("some_future_state")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun problemJsonError_mapsToHttpFailureWithCode() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // A non-2xx problem+json body must surface as HttpFailure with
            // the parsed `code` — guards parseProblem() against the
            // bare-array/envelope changes (it shares the same JSON config).
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setBody("""{"code":"session_not_found","detail":"session 9 not found"}"""),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(404)
            assertThat(failure.code).isEqualTo("session_not_found")
        } finally {
            server.shutdown()
        }
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
