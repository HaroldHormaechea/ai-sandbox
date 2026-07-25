package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC-100 (AC1 / AC4) — the load-bearing assertion of the whole use case: a
 * delete-session → create-session flow rides the ONE multiplexed WebSocket and
 * opens **no new realtime TCP connection**. Session open/close are
 * `subscribe`/`unsubscribe` control frames on the single live socket, never a
 * fresh dial — which is exactly what stops the per-IP-rate-limiter reconnect
 * storm the UC was filed for.
 *
 * <p>Driven with the robust real-time {@code runBlocking} + MockWebServer
 * pattern (the one the disabled runTest-based StreamClientTest's comment
 * recommends): a server-side listener records the received frames, the test
 * observes only server-side state (received queue + {@code requestCount}), never
 * collects a client SharedFlow, uses bounded polls, and tears the connection +
 * scope down explicitly. The server-side "zero Rate-limit reject" half is
 * covered by the unchanged {@code PerIpRateLimiterTest} (AC9) and the UC-85
 * gate's healthy run.
 */
class MuxConnectionSingleSocketTest {

    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile
    private var scope: CoroutineScope? = null
    private var conn: MuxConnection? = null

    @BeforeEach
    fun setUp() {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val pinHex = MessageDigest.getInstance("SHA-256")
            .digest(cert.certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        profile = ServerProfile(
            serverUrl = "https://127.0.0.1:${server.port}",
            pinSha256Hex = pinHex,
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
    }

    @AfterEach
    fun tearDown() {
        conn?.close("test-teardown")
        scope?.cancel()
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun fakeIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        val m = org.mockito.Mockito.mock(KeyStoreIdentityManager::class.java)
        org.mockito.Mockito.`when`(m.keyManagerFactory()).thenReturn(factory)
        return m
    }

    @Test
    fun `subscribe unsubscribe subscribe cycles ride one TCP connection`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // stay open; the client drives subscribe/unsubscribe frames
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )

        val s = CoroutineScope(Dispatchers.IO)
        scope = s
        val http = AiSandboxHttpClient(profile, fakeIdentity())
        val c = MuxConnection(http, s)
        conn = c
        c.start()

        // Wait for the single socket to reach Open.
        val opened = withTimeoutOrNull(5_000) {
            while (c.state.value !is MuxConnection.State.Open) delay(20)
            true
        }
        assertThat(opened).`as`("the one mux socket opened").isTrue

        // The delete→create shape: open session 1, delete it, create session 2 —
        // all as control frames on the SAME socket, never a new dial.
        c.subscribe(MuxEnvelope.CHANNEL_STREAM, 1)
        c.unsubscribe(MuxEnvelope.CHANNEL_STREAM, 1)
        c.subscribe(MuxEnvelope.CHANNEL_STREAM, 2)

        // Wait until the server has recorded hello + the three lifecycle frames.
        withTimeoutOrNull(5_000) {
            while (received.size < 4) delay(20)
        }

        // AC1 — exactly ONE upgrade request = ONE TCP connection across the whole flow.
        assertThat(server.requestCount).`as`("no new TCP connection on delete→create").isEqualTo(1)

        // AC4 — the lifecycle happened via subscribe/unsubscribe control frames.
        val all = received.toList()
        assertThat(all).anyMatch { it.contains("\"type\":\"hello\"") }
        assertThat(all).anyMatch {
            it.contains("\"type\":\"subscribe\"") && it.contains("\"channel\":\"stream\"") && it.contains("\"sessionId\":1")
        }
        assertThat(all).anyMatch {
            it.contains("\"type\":\"unsubscribe\"") && it.contains("\"sessionId\":1")
        }
        assertThat(all).anyMatch {
            it.contains("\"type\":\"subscribe\"") && it.contains("\"sessionId\":2")
        }
    }
}
