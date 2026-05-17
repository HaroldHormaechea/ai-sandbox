package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC04 AC12 + AC25 — WebSocket open → server close with code 4401
 * (`revoked`) → client emits {@link NetworkEvent.CertRevoked} and
 * transitions to {@link StreamClient.State.Revoked}.
 *
 * <p>Drives a {@link MockWebServer} with a self-signed cert (pinned by
 * the [ServerProfile]) and uses MockWebServer's WebSocket support to
 * exercise close-code propagation end-to-end.
 *
 * <p>Note: the `android.util.Log` calls inside [StreamClient] are no-ops
 * under the Android module's `testOptions.unitTests.isReturnDefaultValues
 * = true` setting — no Robolectric required.
 */
class StreamClientTest {

    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile

    @BeforeEach
    fun setUp() {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("localhost"), 0)
        }
        val pinHex = MessageDigest.getInstance("SHA-256")
            .digest(cert.certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        profile = ServerProfile(
            serverUrl = "https://localhost:${server.port}",
            pinSha256Hex = pinHex,
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Mockito-mocked [KeyStoreIdentityManager] returning a JVM-built
     * KeyManagerFactory over an empty PKCS#12 keystore. The mock-maker-
     * inline backend (default in mockito 5.x) handles the final Kotlin
     * class.
     *
     * <p>The real implementation reaches into `AndroidKeyStore` which
     * does not exist on a pure-JVM test classpath; we sidestep that by
     * stubbing the only method [AiSandboxHttpClient] consumes.
     */
    private fun fakeIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        val m = mock(KeyStoreIdentityManager::class.java)
        `when`(m.keyManagerFactory()).thenReturn(factory)
        return m
    }

    @Test
    fun `close code 4401 transitions to Revoked and emits CertRevoked`() = runTest {
        // MockWebServer's WebSocketListener — once the client connects we
        // immediately close with the revocation code.
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                    ws.close(StreamClient.REVOKED_CLOSE_CODE, "revoked")
                }
            })
        )

        val networkEventsCollector = NetworkEventsCollector()
        val http = AiSandboxHttpClient(profile, fakeIdentity())
        val stream = StreamClient(http, sessionN = 7)

        // connect() suspends until onOpen or onClosed/onFailure completes
        // the openedSignal CompletableDeferred.
        stream.connect()

        // Drain any pending state — state should be Revoked.
        val finalState = withTimeoutOrNull(2_000L) {
            // Poll briefly for the close-frame to land on the listener thread.
            var s: StreamClient.State = stream.state.value
            repeat(20) {
                if (s is StreamClient.State.Revoked) return@withTimeoutOrNull s
                kotlinx.coroutines.delay(50)
                s = stream.state.value
            }
            s
        }
        assertThat(finalState).isInstanceOf(StreamClient.State.Revoked::class.java)
        // The CertRevoked event should have landed on the global bus.
        val seen = networkEventsCollector.snapshot()
        assertThat(seen).contains(NetworkEvent.CertRevoked)
    }

    @Test
    fun `normal close does NOT transition to Revoked`() = runTest {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                    ws.close(1000, "bye")
                }
            })
        )

        val collector = NetworkEventsCollector()
        val http = AiSandboxHttpClient(profile, fakeIdentity())
        val stream = StreamClient(http, sessionN = 7)
        stream.connect()

        val finalState = withTimeoutOrNull(2_000L) {
            var s: StreamClient.State = stream.state.value
            repeat(20) {
                if (s is StreamClient.State.Disconnected) return@withTimeoutOrNull s
                kotlinx.coroutines.delay(50)
                s = stream.state.value
            }
            s
        }
        assertThat(finalState).isInstanceOf(StreamClient.State.Disconnected::class.java)
        assertThat(collector.snapshot()).doesNotContain(NetworkEvent.CertRevoked)
    }

    @Test
    fun `incoming binary frame is propagated to the incoming flow`() = runTest {
        val payload = byteArrayOf(0x07, 0x41, 0x42, 0x43) // BEL + 'A','B','C'
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                    ws.send(okio.ByteString.of(*payload))
                    ws.close(1000, "bye")
                }
            })
        )

        val http = AiSandboxHttpClient(profile, fakeIdentity())
        val stream = StreamClient(http, sessionN = 7)
        stream.connect()

        // Take the first binary frame off the incoming SharedFlow.
        val received = withTimeoutOrNull(2_000L) { stream.incoming.first() }
        assertThat(received).isEqualTo(payload)
    }

    @Test
    fun `subprotocol header is set on the upgrade request`() = runTest {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                    ws.close(1000, "bye")
                }
            })
        )

        val http = AiSandboxHttpClient(profile, fakeIdentity())
        StreamClient(http, sessionN = 3).connect()

        val recorded = server.takeRequest()
        assertThat(recorded.headers["Sec-WebSocket-Protocol"]).isEqualTo("ai-sandbox.v1")
        assertThat(recorded.path).isEqualTo("/v1/sessions/3/stream")
    }

    @Test
    fun `revoked close code constant matches the server side`() {
        // Pin the wire constant so a future Android-side typo surfaces
        // here, not in a flaky end-to-end run.
        assertThat(StreamClient.REVOKED_CLOSE_CODE).isEqualTo(4401)
        assertThat(StreamClient.SUBPROTOCOL).isEqualTo("ai-sandbox.v1")
    }

    /**
     * Tiny harness that snapshots [NetworkEvents] emissions on a
     * background coroutine. Used as a poor-man's spy since the bus is
     * a process-wide singleton.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private class NetworkEventsCollector {
        private val seen = java.util.Collections.synchronizedList(mutableListOf<NetworkEvent>())
        private val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            NetworkEvents.flow.collect { seen.add(it) }
        }
        fun snapshot(): List<NetworkEvent> = seen.toList()

        // Best-effort cancellation — the SharedFlow has replay=0 so a
        // straggler subscriber doesn't bleed events between tests.
        @Suppress("unused")
        fun stop() = job.cancel()
    }
}
