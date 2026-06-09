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
 * UC-37 — the Android [ConversationClient] wire contract, mirrored on the
 * server's `ConversationClientMessage` / `ConversationServerMessage` vocabulary.
 * Covers the outbound frames the structured view emits (composer / answer /
 * enumerate / select-target / interrupt — AC8/AC9/AC11/AC17) and the inbound
 * server frames it surfaces on [ConversationClient.incoming] (AC3–AC6/AC19).
 *
 * <p>Uses the same robust harness as [StreamClientControlFrameTest]: a loopback
 * TLS MockWebServer, real-time `runBlocking` (so the real-thread WS callbacks
 * resume), a server-side recorder queue, a subscription gate before an inbound
 * emit, bounded polls, and explicit collector teardown.
 */
class ConversationClientControlFrameTest {

    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile
    private var http: AiSandboxHttpClient? = null

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
        http?.client?.dispatcher?.executorService?.shutdown()
        http?.client?.connectionPool?.evictAll()
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // best-effort cleanup
        }
    }

    private fun newClient(n: Int): ConversationClient {
        val h = AiSandboxHttpClient(profile, fakeIdentity())
        http = h
        return ConversationClient(h, sessionN = n)
    }

    private fun fakeIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        val m = mock(KeyStoreIdentityManager::class.java)
        `when`(m.keyManagerFactory()).thenReturn(factory)
        return m
    }

    private fun recordingUpgrade(received: LinkedBlockingQueue<String>) =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
        })

    // ── wire constants ───────────────────────────────────────────────────────

    @Test
    fun `conversation subprotocol constant is stable`() {
        assertThat(ConversationClient.SUBPROTOCOL).isEqualTo("ai-sandbox.conv.v1")
    }

    // ── before connect: no socket → false ─────────────────────────────────────

    @Test
    fun `sends are no-ops before the socket is open`() {
        val c = newClient(7)
        assertThat(c.sendComposer("hi")).isFalse
        assertThat(c.sendEnumerate()).isFalse
        assertThat(c.sendInterrupt()).isFalse
        assertThat(c.sendSelectTarget("main")).isFalse
        assertThat(c.sendAnswer("uq", 0, listOf(0), "")).isFalse
        assertThat(c.sendAnswerBatch("uq", listOf(com.aisandbox.android.conversation.AnswerItem(0, listOf(0), "")))).isFalse
        assertThat(c.sendFetchDetail("tu1", "u1")).isFalse
    }

    // ── outbound frames (captured server-side) ─────────────────────────────────

    @Test
    fun `sendComposer emits a composer-input frame`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(7)
        c.connect()

        assertThat(c.sendComposer("hello")).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo("""{"type":"composer-input","text":"hello"}""")
        c.close()
    }

    @Test
    fun `sendComposer escapes newlines so multiline survives the wire`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(7)
        c.connect()

        // AC9 — a multiline composer body must serialize as a single JSON frame
        // with the newline escaped (the server maps \n to the session's C-j).
        c.sendComposer("line a\nline b")
        val frame = received.poll(2, TimeUnit.SECONDS)
        assertThat(frame).isEqualTo("""{"type":"composer-input","text":"line a\nline b"}""")
        c.close()
    }

    @Test
    fun `sendAnswer emits an answer frame with selections and free text`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(3)
        c.connect()

        assertThat(c.sendAnswer("tuQ", 0, listOf(0, 2), "custom")).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS))
            .isEqualTo(
                """{"type":"answer","questionUuid":"tuQ","questionIndex":0,"selections":[0,2],"freeText":"custom"}""",
            )
        c.close()
    }

    @Test
    fun `sendAnswer json-escapes the free text and question id`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(3)
        c.connect()

        c.sendAnswer("u\"q", 0, emptyList(), "a\"b\\c")
        val frame = received.poll(2, TimeUnit.SECONDS)
        assertThat(frame).contains(""""questionUuid":"u\"q"""")
        assertThat(frame).contains(""""freeText":"a\"b\\c"""")
        assertThat(frame).contains(""""selections":[]""")
        c.close()
    }

    @Test
    fun `sendAnswerBatch emits one answer-batch frame with every item in index order`() = runBlocking {
        // UC-43 AC2/AC3 — a multi-question (N>1) submit goes out as a SINGLE answer-batch frame
        // carrying one entry per question, in the caller-supplied questionIndex order.
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(3)
        c.connect()

        assertThat(
            c.sendAnswerBatch(
                "tuQ",
                listOf(
                    com.aisandbox.android.conversation.AnswerItem(0, listOf(0, 2), ""),
                    com.aisandbox.android.conversation.AnswerItem(1, listOf(1), "x"),
                ),
            ),
        ).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS))
            .isEqualTo(
                """{"type":"answer-batch","questionUuid":"tuQ","answers":[""" +
                    """{"questionIndex":0,"selections":[0,2],"freeText":""},""" +
                    """{"questionIndex":1,"selections":[1],"freeText":"x"}]}""",
            )
        c.close()
    }

    @Test
    fun `sendAnswerBatch json-escapes the free text and question id`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(3)
        c.connect()

        c.sendAnswerBatch(
            "u\"q",
            listOf(com.aisandbox.android.conversation.AnswerItem(0, emptyList(), "a\"b\\c")),
        )
        val frame = received.poll(2, TimeUnit.SECONDS)
        assertThat(frame).contains(""""type":"answer-batch"""")
        assertThat(frame).contains(""""questionUuid":"u\"q"""")
        assertThat(frame).contains(""""freeText":"a\"b\\c"""")
        assertThat(frame).contains(""""selections":[]""")
        c.close()
    }

    @Test
    fun `sendEnumerate and sendInterrupt emit their control frames`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(7)
        c.connect()

        assertThat(c.sendEnumerate()).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo("""{"type":"enumerate-targets"}""")
        assertThat(c.sendInterrupt()).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo("""{"type":"interrupt"}""")
        c.close()
    }

    @Test
    fun `sendSelectTarget emits a select-target frame with the id`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(1)
        c.connect()

        c.sendSelectTarget("swarm:main:0.1")
        assertThat(received.poll(2, TimeUnit.SECONDS))
            .isEqualTo("""{"type":"select-target","targetId":"swarm:main:0.1"}""")
        c.close()
    }

    @Test
    fun `sendFetchDetail emits a fetch-detail frame with the tool id and uuid`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(7)
        c.connect()

        // UC-41 AC5 — the tap-to-expand request carries both the merge key (toolUseId)
        // and the originating tool_use line's uuid (server-side transcript scoping).
        assertThat(c.sendFetchDetail("tu9", "u-line")).isTrue
        assertThat(received.poll(2, TimeUnit.SECONDS))
            .isEqualTo("""{"type":"fetch-detail","toolUseId":"tu9","uuid":"u-line"}""")
        c.close()
    }

    @Test
    fun `sendFetchDetail json-escapes the ids`() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(recordingUpgrade(received))
        val c = newClient(7)
        c.connect()

        c.sendFetchDetail("t\"u", "u\\1")
        val frame = received.poll(2, TimeUnit.SECONDS)
        assertThat(frame).contains(""""toolUseId":"t\"u"""")
        assertThat(frame).contains(""""uuid":"u\\1"""")
        c.close()
    }

    // ── inbound frames → incoming ──────────────────────────────────────────────

    @Test
    fun `inbound server frame is surfaced on incoming`() = runBlocking {
        val assistantFrame = """{"type":"assistant-text","uuid":"u1","source":"main","isSidechain":false,"text":"hi"}"""
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // reply to the client's enumerate with a conversation frame
                    webSocket.send(assistantFrame)
                }
            }),
        )
        val c = newClient(7)
        c.connect()

        val received = LinkedBlockingQueue<String>()
        val collector = launch(Dispatchers.IO) { c.incoming.collect { received.add(it) } }
        delay(200) // gate: ensure the replay=0 collector is subscribed
        c.sendEnumerate()

        assertThat(received.poll(2, TimeUnit.SECONDS)).isEqualTo(assistantFrame)
        collector.cancel()
        c.close()
    }
}
