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
 * UC-66 AC2/AC3 — [ModelsApi.list] decodes `GET /v1/models` against a real
 * OkHttp + [SpkiPinningTrustManager] + kotlinx stack over a pinned-HTTPS
 * [MockWebServer] (the same harness as [SessionsApiTest]).
 *
 * <p>The server's `ModelController` does `ResponseEntity.ok(List<ModelSummary>)`
 * → Jackson → a BARE JSON array, so the client decodes with the list serializer
 * (no envelope). These tests pin: a bare array → [ApiResult.Success]; the empty
 * "no models" case → empty Success (drives the dialog's Empty state); a label
 * that the server omitted defaults to "" (the dialog falls back to the id); and
 * a non-2xx problem+json / a malformed body → [ApiResult.HttpFailure] so the
 * dialog can render an error state rather than throwing.
 *
 * <p>Cannot be run locally without the Android SDK — authored + reasoned for
 * red-green and validated in CI's `:android:test`.
 */
class ModelsApiTest {

    /** Empty PKCS#12 KeyManager — enough to advance the SSLContext; mirrors [SessionsApiTest.mockIdentity]. */
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
            .commonName("ai-sandbox-models-api-test")
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

    private fun apiFor(profile: ServerProfile): ModelsApi =
        ModelsApi(AiSandboxHttpClient(profile, mockIdentity()))

    @Test
    fun bareArrayBody_decodesToSuccessfulModelList() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // AC2/AC3 — the REAL server shape: a bare JSON array of {id,label}.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"id":"opus","label":"Opus 4.8"},
                      {"id":"sonnet","label":"Sonnet 4.5"},
                      {"id":"haiku","label":"Haiku (latest)"}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val models = (result as ApiResult.Success).value
            assertThat(models.map { it.id }).containsExactly("opus", "sonnet", "haiku")
            assertThat(models.map { it.label })
                .containsExactly("Opus 4.8", "Sonnet 4.5", "Haiku (latest)")

            // It must hit the documented route.
            val rr = server.takeRequest()
            assertThat(rr.method).isEqualTo("GET")
            assertThat(rr.path).isEqualTo("/v1/models")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bareEmptyArray_decodesToEmptySuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // AC3 — a server configured with no models returns []; the dialog
            // renders its Empty state off an empty Success (not a failure).
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            assertThat((result as ApiResult.Success).value).isEmpty()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun missingLabel_defaultsToEmptyString_andUnknownFieldsTolerated() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // label has a serializer default of "" so an older/partial server
            // payload still decodes; ignoreUnknownKeys tolerates extra fields.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """[{"id":"opus","extra":"ignored"},{"id":"sonnet","label":"Sonnet 4.5"}]""",
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val models = (result as ApiResult.Success).value
            assertThat(models.map { it.id }).containsExactly("opus", "sonnet")
            assertThat(models[0].label).isEqualTo("") // default → dialog falls back to id
            assertThat(models[1].label).isEqualTo("Sonnet 4.5")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun problemJsonError_mapsToHttpFailureWithCode() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // A non-2xx problem+json body surfaces as HttpFailure with the parsed
            // code/detail — the ViewModel renders this as ModelMenuState.Error.
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("""{"code":"service_unavailable","detail":"catalogue temporarily unavailable"}"""),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(503)
            assertThat(failure.code).isEqualTo("service_unavailable")
            assertThat(failure.detail).isEqualTo("catalogue temporarily unavailable")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun malformedSuccessBody_mapsToDecodeErrorHttpFailure() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // A 200 whose body is not a JSON array can't be decoded; rather than
            // throwing, list() returns HttpFailure(decode_error) so the caller
            // can render an error state.
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"not":"an array"}"""))

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(200)
            assertThat(failure.code).isEqualTo("decode_error")
        } finally {
            server.shutdown()
        }
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
