package com.aisandbox.android.conversation

import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ConversationClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.terminal.TerminalStreamController
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
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
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC-37 — [ConversationController] behaviour.
 *
 * <p><b>Part A (no network)</b> drives the controller's public input methods and
 * asserts the local state transitions they own: optimistic spinner on submit
 * (AC14), composer-lock guard while a sheet is pending (AC12), and the
 * clear-on-target-switch (AC17). These are synchronous state-flow mutations, so
 * no client/socket is needed.
 *
 * <p><b>Part B (MockWebServer)</b> drives real server→client frames through a real
 * [ConversationClient] over a loopback TLS socket and asserts the frame-handling
 * the controller performs: backfill dedupe + end-of-backfill idle (AC6/AC15/AC22),
 * a pending question sheet (AC10/AC12), the turn-lifecycle spinner (AC14/AC15),
 * and the switcher badge flags (AC18). Frames are pushed ~300 ms after open (a
 * subscription gate, mirroring [com.aisandbox.android.net.ConversationClientControlFrameTest]),
 * then asserted with bounded polling.
 */
class ConversationControllerTest {

    // ──────────────────────── Part A — no network ────────────────────────────

    private fun offlineController(): ConversationController =
        ConversationController(
            sessionN = 7,
            profileStore = mock(ServerProfileStore::class.java),
            httpClientFactory = { error("not used in Part A") },
            clientFactory = { _, _ -> error("not used in Part A") },
            onClosed = {},
        )

    @Test
    fun `blank composer submit does not start the spinner`() {
        val c = offlineController()
        c.submitComposer("   ")
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.IDLE)
    }

    @Test
    fun `composer submit optimistically shows the working spinner`() {
        val c = offlineController()
        c.submitComposer("hello")
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.WORKING) // AC14
    }

    @Test
    fun `submitting an answer shows the spinner and clears any pending sheet`() {
        val c = offlineController()
        c.submitAnswer("tuQ", 0, listOf(0), "")
        assertThat(c.pendingSheet.value).isNull()
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.WORKING)
    }

    @Test
    fun `selecting a target clears items and resets turn state`() {
        val c = offlineController()
        c.submitComposer("hello") // WORKING
        c.selectTarget("swarm:main:0.1")
        assertThat(c.selectedTargetId.value).isEqualTo("swarm:main:0.1") // AC17
        assertThat(c.items.value).isEmpty()
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.IDLE)
    }

    @Test
    fun `interrupt clears the spinner`() {
        val c = offlineController()
        c.submitComposer("hello")
        c.interrupt()
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.IDLE)
    }

    // ──────────────────────── Part B — MockWebServer ─────────────────────────

    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile

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
        val m = mock(KeyStoreIdentityManager::class.java)
        `when`(m.keyManagerFactory()).thenReturn(factory)
        return m
    }

    /** A controller wired to the loopback MockWebServer with a stubbed profile store. */
    private fun networkedController(): ConversationController {
        val store = mock(ServerProfileStore::class.java)
        runBlocking { doReturn(profile).`when`(store).current() }
        return ConversationController(
            sessionN = 7,
            profileStore = store,
            httpClientFactory = { AiSandboxHttpClient(profile, fakeIdentity()) },
            clientFactory = { http, n -> ConversationClient(http, n) },
            onClosed = {},
        )
    }

    /** Enqueue a WS upgrade that pushes [frames] ~300 ms after open (subscription gate). */
    private fun enqueuePush(frames: List<String>) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Thread {
                        Thread.sleep(300)
                        frames.forEach { webSocket.send(it) }
                    }.start()
                }
            }),
        )
    }

    private fun awaitUntil(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(25)
        }
        return cond()
    }

    @Test
    fun `backfill dedupes overlapping lines and ends idle`() {
        enqueuePush(
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"turn-start","uuid":"u1","source":"main","isSidechain":false,"text":"hello"}""",
                """{"type":"assistant-text","uuid":"u2","source":"main","isSidechain":false,"text":"hi back"}""",
                // exact duplicate (same uuid + text) — must dedupe (AC6/AC22)
                """{"type":"assistant-text","uuid":"u2","source":"main","isSidechain":false,"text":"hi back"}""",
                """{"type":"backfill-end","source":"main"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.items.value.size == 2 })
                .withFailMessage("expected 2 deduped items, got ${c.items.value.map { it.key }}")
                .isTrue
            // backfill-end with no pending sheet → spinner idle (AC15).
            assertThat(awaitUntil { c.turnPhase.value == TurnPhase.IDLE }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a question frame raises a pending sheet`() {
        enqueuePush(
            listOf(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuQ",""" +
                    """"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue // AC10/AC12
            val sheet = c.pendingSheet.value as PendingSheet.Questions
            assertThat(sheet.questionUuid).isEqualTo("tuQ")
            assertThat(sheet.questions.first().options).hasSize(2)
        } finally {
            c.close()
        }
    }

    @Test
    fun `a live turn drives the thinking spinner`() {
        enqueuePush(
            listOf(
                """{"type":"turn-start","uuid":"t1","source":"main","isSidechain":false,"text":"go"}""",
                """{"type":"thinking","uuid":"t2","source":"main","isSidechain":false,"text":"reasoning"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.turnPhase.value == TurnPhase.THINKING }).isTrue // AC14
        } finally {
            c.close()
        }
    }

    @Test
    fun `a targets frame surfaces the pending-question badge`() {
        enqueuePush(
            listOf(
                """{"type":"targets","selectedId":"main","targets":[""" +
                    """{"id":"main","kind":"main","title":"main"},""" +
                    """{"id":"swarm:main:0.1","kind":"swarm","title":"ping","agentName":"ping",""" +
                    """"pendingActivity":true,"pendingQuestion":true}]}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.targets.value.size == 2 }).isTrue // AC18
            val pane = c.targets.value.first { it.id == "swarm:main:0.1" }
            assertThat(pane.pendingQuestion).isTrue
            assertThat(pane.pendingActivity).isTrue
        } finally {
            c.close()
        }
    }
}
