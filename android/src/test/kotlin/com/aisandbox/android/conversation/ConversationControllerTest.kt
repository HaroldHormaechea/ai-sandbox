package com.aisandbox.android.conversation

import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ConversationClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.screens.TerminalState
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
    fun `submitting an answer batch shows the spinner and clears the pending sheet`() {
        // UC-43 AC4 — a multi-question batch submit behaves like the single submit: it
        // optimistically dismisses the sheet and shows the working spinner.
        val c = offlineController()
        c.submitAnswerBatch(
            "tuQ",
            listOf(
                AnswerItem(questionIndex = 0, selections = listOf(1), freeText = ""),
                AnswerItem(questionIndex = 1, selections = listOf(0, 2), freeText = ""),
            ),
        )
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
    fun `a multi-question sheet carries all questions and dismisses when the transcript advances`() {
        // UC-43 AC2 — the pending sheet preserves the FULL questions[] (not just the first), and
        // AC7 — when the ask is resolved/aborted externally (the transcript advances past it, e.g.
        // answered in tmux → turn-end), the in-app sheet dismisses cleanly instead of lingering.
        // Two-phase delivery (capture the server socket, send the question, ASSERT, then send the
        // turn-end) so the dismissal is observed deterministically rather than racing the sheet.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsRef.set(webSocket)
                }
            }),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuQ",""" +
                    """"questions":[""" +
                    """{"question":"Q0","header":"H0","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""},{"label":"B","description":""}]},""" +
                    """{"question":"Q1","header":"H1","multiSelect":true,""" +
                    """"options":[{"label":"X","description":""},{"label":"Y","description":""}]},""" +
                    """{"question":"Q2","header":"H2","multiSelect":false,""" +
                    """"options":[{"label":"P","description":""},{"label":"Q","description":""}]}]}""",
            )
            // The sheet surfaces with ALL three questions reachable in-app (AC2) …
            assertThat(
                awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questions?.size == 3 },
            ).isTrue
            // … then a transcript advance (turn-end) dismisses it cleanly (AC7).
            wsRef.get()!!.send(
                """{"type":"turn-end","uuid":"ue","source":"main","isSidechain":false,"durationMs":10,"messageCount":1}""",
            )
            assertThat(awaitUntil { c.pendingSheet.value == null }).isTrue
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

    // ──────────────────────── Part C — UC-41 merged tool rows + detail dialog ─

    /** Enqueue a WS upgrade that replies with [reply] to any inbound frame containing [trigger]. */
    private fun enqueueAutoReply(trigger: String, reply: String) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains(trigger)) webSocket.send(reply)
                }
            }),
        )
    }

    private fun activityOf(c: ConversationController): ConversationItem.ToolActivity? =
        c.items.value.singleOrNull() as? ConversationItem.ToolActivity

    @Test
    fun `tool use then tool result merge into one activity row`() {
        enqueuePush(
            listOf(
                """{"type":"tool-use","uuid":"u1","source":"main","isSidechain":false,"toolName":"Bash",""" +
                    """"toolUseId":"tu1","inputSummary":"ls -la","primaryText":"ls -la"}""",
                """{"type":"tool-result","uuid":"u2","source":"main","isSidechain":false,"toolUseId":"tu1",""" +
                    """"isError":false,"summary":"ok output"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            // AC4 — one merged row (not two), result folded in.
            assertThat(awaitUntil { activityOf(c)?.result != null }).isTrue
            val act = activityOf(c)!!
            assertThat(act.toolUseId).isEqualTo("tu1")
            assertThat(act.primaryText).isEqualTo("ls -la")
            assertThat(act.result?.summary).isEqualTo("ok output")
        } finally {
            c.close()
        }
    }

    @Test
    fun `tool result arriving before tool use still merges into one row`() {
        // Backfill-boundary split: result first, use second (AC4 pitfall).
        enqueuePush(
            listOf(
                """{"type":"tool-result","uuid":"u2","source":"main","isSidechain":false,"toolUseId":"tu1",""" +
                    """"isError":true,"summary":"boom"}""",
                """{"type":"tool-use","uuid":"u1","source":"main","isSidechain":false,"toolName":"Bash",""" +
                    """"toolUseId":"tu1","inputSummary":"false","primaryText":"false"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { activityOf(c)?.toolName == "Bash" }).isTrue
            val act = activityOf(c)!!
            assertThat(act.result?.isError).isTrue // AC7 — error preserved across the ordering
            assertThat(act.primaryText).isEqualTo("false")
        } finally {
            c.close()
        }
    }

    @Test
    fun `a tool use without a result shows the awaiting state`() {
        enqueuePush(
            listOf(
                """{"type":"tool-use","uuid":"u1","source":"main","isSidechain":false,"toolName":"Skill",""" +
                    """"toolUseId":"tuS","inputSummary":"verify","primaryText":"verify"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { activityOf(c) != null }).isTrue
            assertThat(activityOf(c)!!.result).isNull() // AC8 — awaiting result
        } finally {
            c.close()
        }
    }

    @Test
    fun `tapping a tool bubble loads the on-demand detail`() {
        enqueueAutoReply(
            "fetch-detail",
            """{"type":"tool-detail","toolUseId":"tu1","toolName":"Bash","input":"ls -la /workspace",""" +
                """"result":"drwxr-xr-x","isError":false,"available":true}""",
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open }).isTrue
            c.openDetail("tu1", "u1")
            // AC5/AC6 — the full untruncated input + output arrive into a Loaded dialog state.
            assertThat(awaitUntil { c.toolDetail.value is ToolDetailState.Loaded }).isTrue
            val loaded = c.toolDetail.value as ToolDetailState.Loaded
            assertThat(loaded.input).isEqualTo("ls -la /workspace")
            assertThat(loaded.result).isEqualTo("drwxr-xr-x")
            assertThat(loaded.isError).isFalse
        } finally {
            c.close()
        }
    }

    @Test
    fun `an unavailable tool-detail reply shows the unavailable state`() {
        enqueueAutoReply(
            "fetch-detail",
            """{"type":"tool-detail","toolUseId":"gone","available":false}""",
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open }).isTrue
            c.openDetail("gone", "u1")
            assertThat(awaitUntil { c.toolDetail.value == ToolDetailState.Unavailable }).isTrue // AC9
        } finally {
            c.close()
        }
    }

    @Test
    fun `a disconnect while a detail fetch is in flight degrades to unavailable`() {
        // The server receives the fetch-detail then closes WITHOUT replying (AC9).
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("fetch-detail")) webSocket.close(1000, "bye")
                }
            }),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open }).isTrue
            c.openDetail("tu1", "u1")
            assertThat(awaitUntil { c.toolDetail.value == ToolDetailState.Unavailable }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `closing the detail dialog cancels the in-flight fetch and does not leak`() {
        // No reply ever comes; closeDetail must cancel+prune so nothing publishes later.
        enqueueAutoReply("never-match-this", "")
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open }).isTrue
            c.openDetail("tu1", "u1")
            assertThat(awaitUntil { c.toolDetail.value == ToolDetailState.Loading }).isTrue
            c.closeDetail()
            assertThat(c.toolDetail.value).isNull()
            // The cancelled await must NOT later resurrect the dialog.
            Thread.sleep(250)
            assertThat(c.toolDetail.value).isNull()
        } finally {
            c.close()
        }
    }

    @Test
    fun `a detail fetch with no reply times out to unavailable`() {
        // Fully offline: openDetail works without a socket (the fetch frame is a no-op),
        // and the client-side 8 s timeout resolves the dialog to Unavailable (AC9).
        val c = offlineController()
        c.openDetail("tu1", "u1")
        assertThat(c.toolDetail.value).isEqualTo(ToolDetailState.Loading)
        assertThat(awaitUntil(timeoutMs = 12_000) { c.toolDetail.value == ToolDetailState.Unavailable }).isTrue
    }

    // ──────────────────── Part D — UC-42 injected-line frames ─────────────────

    @Test
    fun `a system-note frame becomes a left-aligned SystemNote item`() {
        // AC4 — an injected line with no host bubble arrives as a `system-note` frame
        // carrying its label + inline detail; the controller renders it as a SystemNote
        // item (NOT a right-aligned user bubble) and does NOT advance the turn phase.
        enqueuePush(
            listOf(
                """{"type":"system-note","uuid":"u1","source":"main","isSidechain":false,""" +
                    """"label":"Command: /clear","detail":"<command-name>/clear</command-name>"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.items.value.any { it is ConversationItem.SystemNote } }).isTrue
            val note = c.items.value.single() as ConversationItem.SystemNote
            assertThat(note.label).isEqualTo("Command: /clear")
            assertThat(note.detail).isEqualTo("<command-name>/clear</command-name>")
            // No real user prompt was injected → nothing renders as a right-aligned bubble.
            assertThat(c.items.value.none { it is ConversationItem.UserMessage }).isTrue
            // A render-only note must not start the spinner.
            assertThat(c.turnPhase.value).isEqualTo(TurnPhase.IDLE)
        } finally {
            c.close()
        }
    }

    @Test
    fun `a sidechain system-note frame is stamped with its subagent source`() {
        // AC9 — the teammate's injected note folds under its own source, not the main pane.
        enqueuePush(
            listOf(
                """{"type":"system-note","uuid":"u2","source":"subagent:agent-3","isSidechain":true,""" +
                    """"label":"System note","detail":"teammate housekeeping"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.items.value.any { it is ConversationItem.SystemNote } }).isTrue
            val note = c.items.value.single { it is ConversationItem.SystemNote } as ConversationItem.SystemNote
            assertThat(note.source).isEqualTo("subagent:agent-3")
            assertThat(note.isSidechain).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a folded skill load yields only the tool row and no user bubble`() {
        // AC1 — the server FOLDS the injected SKILL.md body (emits nothing for it), so the
        // client only ever sees the Skill tool-use frame: exactly one bubble (the tool
        // row), never a second right-aligned bubble carrying the skill body.
        enqueuePush(
            listOf(
                """{"type":"tool-use","uuid":"u1","source":"main","isSidechain":false,"toolName":"Skill",""" +
                    """"toolUseId":"tuS","inputSummary":"deep-research","primaryText":"deep-research"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.items.value.size == 1 }).isTrue
            assertThat(c.items.value.single()).isInstanceOf(ConversationItem.ToolActivity::class.java)
            assertThat(c.items.value.none { it is ConversationItem.UserMessage }).isTrue
        } finally {
            c.close()
        }
    }
}
