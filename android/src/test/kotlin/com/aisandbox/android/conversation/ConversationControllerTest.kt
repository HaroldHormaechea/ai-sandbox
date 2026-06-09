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

    // ──────────────── Part E — UC-45 optimistic local echo ───────────────────

    /**
     * AC3/AC5/AC7 server stub — records every inbound frame, and echoes a DISTINCT
     * `turn-start` per inbound `composer-input` (a per-message uuid, reflecting back the
     * exact typed text). The distinct uuid is load-bearing for AC5: a FIXED echo frame
     * (same uuid+text) would dedupe via `reconciledServerKeys` and MASK a double-reconcile
     * / cross-match bug (challenger note), so the stub must mint one per submission.
     */
    private fun enqueueComposerEchoer(received: java.util.concurrent.CopyOnWriteArrayList<String>) {
        val seq = java.util.concurrent.atomic.AtomicInteger(0)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    if (text.contains(""""type":"composer-input"""")) {
                        val typed = Regex(""""text":"(.*?)"""").find(text)?.groupValues?.get(1) ?: ""
                        val n = seq.incrementAndGet()
                        webSocket.send(
                            """{"type":"turn-start","uuid":"srv$n","source":"main",""" +
                                """"isSidechain":false,"text":"$typed"}""",
                        )
                    }
                }
            }),
        )
    }

    /** Capture the server WebSocket so the test can push frames at a deterministic moment. */
    private fun enqueueCapture(wsRef: java.util.concurrent.atomic.AtomicReference<WebSocket?>) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsRef.set(webSocket)
                }
            }),
        )
    }

    private fun userMessages(c: ConversationController): List<ConversationItem.UserMessage> =
        c.items.value.filterIsInstance<ConversationItem.UserMessage>()

    @Test
    fun `submit echoes an optimistic user bubble immediately with no network`() {
        // AC1 — the bubble is present synchronously after submit, before any round-trip, and
        // renders as a user's own line (source=main, non-sidechain → right-aligned).
        val c = offlineController()
        c.submitComposer("hello there")
        val bubbles = userMessages(c)
        assertThat(bubbles).hasSize(1)
        assertThat(bubbles.single().text).isEqualTo("hello there")
        assertThat(bubbles.single().localSeq).isNotNull // optimistic
        assertThat(bubbles.single().source).isEqualTo("main")
        assertThat(bubbles.single().isSidechain).isFalse
    }

    @Test
    fun `a blank submit adds no optimistic bubble`() {
        // AC2 — a blank submit neither starts the spinner (existing test) nor echoes a bubble.
        val c = offlineController()
        c.submitComposer("   ")
        assertThat(userMessages(c)).isEmpty()
    }

    @Test
    fun `a submit blocked by a pending sheet adds no optimistic bubble`() {
        // AC2/AC12 — the composer is locked while a question sheet is pending, so submit is a
        // no-op: no echo bubble appears.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuQ",""" +
                    """"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":"a"}]}]}""",
            )
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue
            c.submitComposer("blocked")
            Thread.sleep(150) // give any erroneous async insert a chance to land
            assertThat(userMessages(c)).isEmpty()
        } finally {
            c.close()
        }
    }

    @Test
    fun `a server echo reconciles the optimistic bubble in place into exactly one bubble`() {
        // AC3 — submit, observe the optimistic bubble, then the authoritative turn-start echo
        // reconciles it IN PLACE: exactly one bubble, key stable (localSeq kept), uuid backfilled
        // — no duplicate, no remove+re-add flicker.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.submitComposer("hello")
            assertThat(awaitUntil { userMessages(c).size == 1 }).isTrue
            val optimistic = userMessages(c).single()
            assertThat(optimistic.localSeq).isNotNull
            assertThat(optimistic.uuid).isEmpty() // uuid not yet backfilled
            val keyBefore = optimistic.key
            wsRef.get()!!.send(
                """{"type":"turn-start","uuid":"u1","source":"main","isSidechain":false,"text":"hello"}""",
            )
            assertThat(awaitUntil { userMessages(c).singleOrNull()?.uuid == "u1" }).isTrue
            val reconciled = userMessages(c).single()
            assertThat(userMessages(c)).hasSize(1) // no duplicate
            assertThat(reconciled.key).isEqualTo(keyBefore) // stable → Compose updates the row
            assertThat(reconciled.localSeq).isEqualTo(optimistic.localSeq)
            assertThat(reconciled.text).isEqualTo("hello")
        } finally {
            c.close()
        }
    }

    @Test
    fun `with no server echo the optimistic bubble persists`() {
        // AC4 — the echo never arrives (delayed/dropped, UC-40); the user must never lose sight
        // of what they sent, so the optimistic bubble remains visible.
        val c = offlineController()
        c.submitComposer("durable")
        assertThat(userMessages(c)).hasSize(1)
        Thread.sleep(300) // no echo will ever come
        val bubbles = userMessages(c)
        assertThat(bubbles).hasSize(1)
        assertThat(bubbles.single().text).isEqualTo("durable")
        assertThat(bubbles.single().localSeq).isNotNull // still the optimistic bubble
    }

    @Test
    fun `two rapid submits yield two ordered bubbles with no duplicates`() {
        // AC5 — each submit echoes and reconciles to ITS OWN server frame, in submission order,
        // without cross-matching. The echoer mints a DISTINCT uuid per inbound composer-input
        // (challenger note): a fixed echo would dedupe and mask the bug this test guards.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        enqueueComposerEchoer(received)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open }).isTrue
            c.submitComposer("first")
            c.submitComposer("second")
            assertThat(
                awaitUntil { userMessages(c).size == 2 && userMessages(c).all { it.uuid.startsWith("srv") } },
            ).withFailMessage("bubbles=${userMessages(c).map { it.text to it.uuid }}").isTrue
            val bubbles = userMessages(c)
            assertThat(bubbles).hasSize(2) // no duplicate
            assertThat(bubbles.map { it.text }).containsExactly("first", "second") // ordered
            assertThat(bubbles.map { it.key }).containsExactly("localuser|0", "localuser|1") // stable keys
            assertThat(bubbles.map { it.uuid }.toSet()).hasSize(2) // distinct frames, no cross-match
        } finally {
            c.close()
        }
    }

    @Test
    fun `the bytes injected into the session are unchanged by the local echo`() {
        // AC7 — local echo is display-only: the composer-input frame the server receives carries
        // the typed text byte-for-byte, exactly as before this feature.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        enqueueComposerEchoer(received)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open }).isTrue
            c.submitComposer("hello world")
            assertThat(awaitUntil { received.any { it.contains(""""type":"composer-input"""") } }).isTrue
            val frame = received.first { it.contains(""""type":"composer-input"""") }
            assertThat(frame).isEqualTo("""{"type":"composer-input","text":"hello world"}""")
        } finally {
            c.close()
        }
    }

    @Test
    fun `a backfill replay of an already-reconciled line stays one bubble`() {
        // AC8 — after a live reconcile, a reconnect/backfill replay of the SAME server line is
        // deduped via reconciledServerKeys: no second (server-keyed) bubble appears.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.submitComposer("hello")
            assertThat(awaitUntil { userMessages(c).size == 1 }).isTrue
            wsRef.get()!!.send(
                """{"type":"turn-start","uuid":"u1","source":"main","isSidechain":false,"text":"hello"}""",
            )
            assertThat(awaitUntil { userMessages(c).singleOrNull()?.uuid == "u1" }).isTrue
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"turn-start","uuid":"u1","source":"main","isSidechain":false,"text":"hello"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { wsRef.get()!!.send(it) }
            Thread.sleep(200)
            assertThat(userMessages(c)).hasSize(1) // still ONE — no phantom replay bubble
        } finally {
            c.close()
        }
    }

    @Test
    fun `a backfill-only turn-start with no prior submit yields one non-optimistic bubble`() {
        // AC8 phantom guard — re-entering a conversation (pure history replay, no submit) must
        // NOT mint an optimistic bubble; the line is added once, server-keyed (localSeq == null).
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"turn-start","uuid":"u1","source":"main","isSidechain":false,"text":"replayed"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { wsRef.get()!!.send(it) }
            assertThat(awaitUntil { userMessages(c).size == 1 }).isTrue
            val bubble = userMessages(c).single()
            assertThat(bubble.localSeq).isNull() // non-optimistic, server-origin
            assertThat(bubble.uuid).isEqualTo("u1")
            assertThat(bubble.text).isEqualTo("replayed")
        } finally {
            c.close()
        }
    }

    @Test
    fun `switching target clears pending echoes so a later echo is not cross-matched`() {
        // Guards the developer's clearItems() dropping pendingEchoes in lockstep: after a target
        // switch, a turn-start echo finds no pending bubble and is added fresh (no stale match).
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.submitComposer("stale")
            assertThat(awaitUntil { userMessages(c).size == 1 }).isTrue
            c.selectTarget("swarm:main:0.1") // clears items + pendingEchoes + reconciledServerKeys
            assertThat(c.items.value).isEmpty()
            wsRef.get()!!.send(
                """{"type":"turn-start","uuid":"u9","source":"main","isSidechain":false,"text":"fresh"}""",
            )
            assertThat(awaitUntil { userMessages(c).size == 1 }).isTrue
            val bubble = userMessages(c).single()
            assertThat(bubble.uuid).isEqualTo("u9")
            assertThat(bubble.localSeq).isNull() // added fresh, not reconciled against the cleared bubble
        } finally {
            c.close()
        }
    }
}
