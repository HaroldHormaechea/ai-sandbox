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
 * UC-67 — [McpApi] decodes the per-session MCP endpoints against a real OkHttp +
 * [SpkiPinningTrustManager] + kotlinx stack over a pinned-HTTPS [MockWebServer]
 * (the same harness as [ModelsApiTest] / [SessionsApiTest]).
 *
 * <p>Pins: `GET /v1/sessions/{n}/mcp` decodes the controller's BARE JSON array of
 * `{name,transport,state,detail}` into [McpServerInfo] (AC3/AC4); the empty case
 * → empty Success (drives the screen's "no MCP servers" empty state, AC7);
 * `POST …/{name}/{action}` hits the exact two-segment path with the server name
 * URL-encoded (so a reserved-char name cannot break out of the path) and decodes
 * [McpActionResult] (AC6); a non-2xx problem+json → [ApiResult.HttpFailure]; and a
 * malformed 200 body → `HttpFailure(decode_error)` rather than throwing.
 *
 * <p>Cannot be run without the Android SDK locally — authored + reasoned for
 * red-green and validated in CI's `:android:test`.
 */
class McpApiTest {

    /** Empty PKCS#12 KeyManager — enough to advance the SSLContext; mirrors [ModelsApiTest.mockIdentity]. */
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
            .commonName("ai-sandbox-mcp-api-test")
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

    private fun apiFor(profile: ServerProfile): McpApi =
        McpApi(AiSandboxHttpClient(profile, mockIdentity()))

    // ──────────────────────── list (AC3/AC4) ─────────────────────────────────

    @Test
    fun list_decodesBareArrayOfServers() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // The REAL server shape: a bare JSON array of {name,transport,state,detail}.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"name":"call-graph","transport":"stdio","state":"connected","detail":"java -jar daemon.jar"},
                      {"name":"atlassian","transport":"sse","state":"needs_auth","detail":"https://mcp.atlassian.com/v1/sse"}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list(7)

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val servers = (result as ApiResult.Success).value
            assertThat(servers.map { it.name }).containsExactly("call-graph", "atlassian")
            assertThat(servers.map { it.state }).containsExactly("connected", "needs_auth")
            assertThat(servers.map { it.transport }).containsExactly("stdio", "sse")
            assertThat(servers[1].detail).isEqualTo("https://mcp.atlassian.com/v1/sse")

            val rr = server.takeRequest()
            assertThat(rr.method).isEqualTo("GET")
            assertThat(rr.path).isEqualTo("/v1/sessions/7/mcp")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun list_emptyArray_decodesToEmptySuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // AC7 — a session with no MCP servers returns []; the screen renders its
            // Empty state off an empty Success (not a failure).
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val result = apiFor(profile).list(3)

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            assertThat((result as ApiResult.Success).value).isEmpty()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun list_toleratesMissingOptionalFieldsAndUnknownKeys() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // transport/state/detail have serializer defaults so a partial server
            // payload still decodes; ignoreUnknownKeys tolerates extra fields.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"name":"bare","extra":"ignored"}]""",
                ),
            )

            val result = apiFor(profile).list(1)

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val s = (result as ApiResult.Success).value.single()
            assertThat(s.name).isEqualTo("bare")
            assertThat(s.transport).isEqualTo("unknown")
            assertThat(s.state).isEqualTo("unknown")
            assertThat(s.detail).isEqualTo("")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun list_problemJsonError_mapsToHttpFailure() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("""{"code":"service_unavailable","detail":"mcp temporarily unavailable"}"""),
            )

            val result = apiFor(profile).list(7)

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(503)
            assertThat(failure.code).isEqualTo("service_unavailable")
            assertThat(failure.detail).isEqualTo("mcp temporarily unavailable")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun list_malformedSuccessBody_mapsToDecodeErrorHttpFailure() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"not":"an array"}"""))

            val result = apiFor(profile).list(7)

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(200)
            assertThat(failure.code).isEqualTo("decode_error")
        } finally {
            server.shutdown()
        }
    }

    // ──────────────────────── operate (AC5/AC6) ──────────────────────────────

    @Test
    fun operate_postsToTheNameActionPath_andDecodesTheResult() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"name":"atlassian","state":"needs_auth","message":"Opens MCP authentication in the live session — complete it there, then refresh."}""",
                ),
            )

            val result = apiFor(profile).operate(7, "atlassian", "login")

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val action = (result as ApiResult.Success).value
            assertThat(action.name).isEqualTo("atlassian")
            assertThat(action.state).isEqualTo("needs_auth")
            assertThat(action.message).contains("complete it").containsIgnoringCase("live session")

            // AC6 — POST to the exact two-segment {name}/{action} path. No body required.
            val rr = server.takeRequest()
            assertThat(rr.method).isEqualTo("POST")
            assertThat(rr.path).isEqualTo("/v1/sessions/7/mcp/atlassian/login")
            assertThat(rr.bodySize).isEqualTo(0L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun operate_urlEncodesAServerNameWithReservedChars() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // A server name carrying a reserved char must be encoded as a single path
            // segment so it cannot break out of the path (addPathSegment encodes '/').
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"name":"weird/name","state":"connected","message":"ok"}""",
                ),
            )

            val result = apiFor(profile).operate(7, "weird/name", "reconnect")

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val rr = server.takeRequest()
            // The '/' in the name is percent-encoded — it is NOT a third path segment.
            assertThat(rr.path).isEqualTo("/v1/sessions/7/mcp/weird%2Fname/reconnect")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun operate_problemJsonError_mapsToHttpFailure() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"code":"internal_error","detail":"pane injection failed"}"""),
            )

            val result = apiFor(profile).operate(7, "atlassian", "login")

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(500)
            assertThat(failure.code).isEqualTo("internal_error")
            assertThat(failure.detail).isEqualTo("pane injection failed")
        } finally {
            server.shutdown()
        }
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
