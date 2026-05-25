package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC-21 AC#13 — the Android [StreamClient] mirror of the server's
 * agent-switcher protocol: the outbound {@code enumerate-targets} /
 * {@code select-target} frames it emits, and the inbound JSON text frames it
 * surfaces on [StreamClient.controlIncoming].
 *
 * <h2>Why a fresh class (the pre-existing {@code StreamClientTest} stays disabled)</h2>
 *
 * <p>{@code StreamClientTest} is {@code @Disabled} because its
 * {@code runTest}-based bodies hang the JUnit-Platform executor: under
 * {@code runTest}'s virtual clock, {@code delay()} fast-forwards and the
 * {@code SharedFlow.collect} collector never observes the real-thread
 * MockWebServer callbacks (and is never torn down). Re-enabling it as-is would
 * re-introduce that hang, and the developer's UC-21 changes are purely additive
 * ({@code controlIncoming} / {@code sendEnumerate} / {@code sendSelectTarget}).
 * So rather than disturb it, this class covers the new surface with the robust
 * pattern its own disable-comment recommends: real-time {@code runBlocking}
 * (so real-thread WS callbacks resume normally), a server-side listener that
 * records frames into a {@link LinkedBlockingQueue}, a subscription gate before
 * triggering an inbound emit, bounded polls, and explicit collector teardown.
 */
class StreamClientControlFrameTest {

    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile
    private var http: AiSandboxHttpClient? = null

    @BeforeEach
    fun setUp() {
        // SAN = IP:127.0.0.1 so OkHttp's default hostname verifier accepts the
        // loopback handshake and we sidestep the localhost→[::1] resolution that
        // MockWebServer (bound to IPv4) refuses.
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
        // SpkiPinningTrustManager pins the SubjectPublicKeyInfo (SPKI), i.e. the
        // SHA-256 of publicKey.encoded — NOT the whole-certificate DER.
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
        // Deterministic shutdown — drain OkHttp's dispatcher + connection pool
        // first so MockWebServer.shutdown() doesn't time out ("Gave up waiting
        // for queue to shut down") on the still-pooled WebSocket connection.
        http?.client?.dispatcher?.executorService?.shutdown()
        http?.client?.connectionPool?.evictAll()
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // best-effort cleanup
        }
    }

    /** Build a client (recorded for teardown) and a stream over it. */
    private fun newStream(n: Int): StreamClient {
        val h = AiSandboxHttpClient(profile, fakeIdentity())
        http = h
        return StreamClient(h, sessionN = n)
    }

    private fun fakeIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        val m = mock(KeyStoreIdentityManager::class.java)
        `when`(m.keyManagerFactory()).thenReturn(factory)
        return m
    }

    // ── before connect: no socket → false ────────────────────────────────────

    @Test
    fun `enumerate and select are no-ops before the socket is open`() {
        val stream = newStream(7)
        assertThat(stream.sendEnumerate()).isFalse
        assertThat(stream.sendSelectTarget("main")).isFalse
    }

    // ── outbound frame shapes (captured server-side) ──────────────────────────

    @Test
    fun `sendEnumerate emits the enumerate-targets control frame`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )
        val stream = newStream(7)
        stream.connect()

        assertThat(stream.sendEnumerate()).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo("""{"type":"enumerate-targets"}""")
        stream.close()
    }

    @Test
    fun `sendSelectTarget emits a select-target frame with the target id`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )
        val stream = newStream(3)
        stream.connect()

        assertThat(stream.sendSelectTarget("swarm:claude-swarm-1:0.1")).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS))
            .isEqualTo("""{"type":"select-target","targetId":"swarm:claude-swarm-1:0.1"}""")
        stream.close()
    }

    @Test
    fun `sendSelectTarget json-escapes quotes and backslashes in the target id`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )
        val stream = newStream(1)
        stream.connect()

        // raw id: x"y\z  → wire must escape to  x\"y\\z  (still valid JSON).
        stream.sendSelectTarget("x\"y\\z")
        val frame = received.poll(2, TimeUnit.SECONDS)
        assertThat(frame).isNotNull
        assertThat(frame).contains(""""type":"select-target"""")
        assertThat(frame).contains("""x\"y\\z""")
        stream.close()
    }

    // ── inbound control frames → controlIncoming ──────────────────────────────

    @Test
    fun `inbound text frame is surfaced on controlIncoming`() = runBlocking {
        val targetsFrame = """{"type":"targets","targets":[],"selectedId":"main"}"""
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Reply to the client's enumerate with a server control frame.
                    webSocket.send(targetsFrame)
                }
            }),
        )
        val stream = newStream(7)
        stream.connect()

        val received = LinkedBlockingQueue<String>()
        val collector = launch(Dispatchers.IO) { stream.controlIncoming.collect { received.add(it) } }
        // Real-time gate so the (replay=0) collector is subscribed before the
        // server emits — then trigger the server reply.
        delay(200)
        stream.sendEnumerate()

        assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo(targetsFrame)
        collector.cancel()
        stream.close()
    }

    @Test
    fun `control-frame wire constants are stable`() {
        // Pin the subprotocol so an Android/server drift surfaces here.
        assertThat(StreamClient.SUBPROTOCOL).isEqualTo("ai-sandbox.v1")
    }
}
