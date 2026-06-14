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
        enqueueCapture(wsRef)
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
    fun `a tool-result matching the pending sheet toolUseId dismisses the sheet`() {
        // UC-44 AC3a — the stuck-popup safety net. When the underlying ask is resolved/aborted
        // server-side (e.g. an "Other" answer declined the ask, the turn proceeded), the resolving
        // `tool-result` carries the SAME toolUseId as the sheet's questionUuid. The controller must
        // dismiss the sheet on that matched frame so it can never linger after the ask is resolved.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuQ",""" +
                    """"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""},{"label":"B","description":""}]}]}""",
            )
            assertThat(
                awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questionUuid == "tuQ" },
            ).isTrue
            // The matching tool-result (toolUseId == the sheet's questionUuid) resolves the ask …
            wsRef.get()!!.send(
                """{"type":"tool-result","uuid":"ur","source":"main","isSidechain":false,""" +
                    """"toolUseId":"tuQ","isError":false,"summary":"done"}""",
            )
            // … so the sheet dismisses cleanly (AC3a) — it never lingers once the ask is resolved.
            assertThat(awaitUntil { c.pendingSheet.value == null }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a tool-result with a non-matching toolUseId does not dismiss an unrelated sheet`() {
        // UC-44 AC3a (negative) — only the tool-result that RESOLVES the pending ask dismisses it.
        // A tool-result for a DIFFERENT tool call (any other Bash/Read/etc. that finishes while the
        // ask is still open) must NOT tear down the unrelated sheet.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuQ",""" +
                    """"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""},{"label":"B","description":""}]}]}""",
            )
            assertThat(
                awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questionUuid == "tuQ" },
            ).isTrue
            // An UNRELATED tool-result (different toolUseId) lands while the ask is still pending.
            wsRef.get()!!.send(
                """{"type":"tool-result","uuid":"ur2","source":"main","isSidechain":false,""" +
                    """"toolUseId":"tuOTHER","isError":false,"summary":"unrelated"}""",
            )
            // Wait until that unrelated result is observably processed (its merged row appears) …
            assertThat(
                awaitUntil { c.items.value.any { it is ConversationItem.ToolActivity && it.toolUseId == "tuOTHER" } },
            ).isTrue
            // … and the sheet is STILL up: a non-matching result must not dismiss it.
            assertThat(c.pendingSheet.value).isInstanceOf(PendingSheet.Questions::class.java)
            assertThat((c.pendingSheet.value as PendingSheet.Questions).questionUuid).isEqualTo("tuQ")
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

    // ──────────────────────── UC-50 — pane-signal pending prompt ─────────────
    // A LIVE, pane-delivered pending prompt (the transcript carried nothing for the
    // blocking turn on claude 2.1.169). The controller raises the sheet ONLY — it adds
    // NO inline item (so the later transcript write owns the single bubble, AC5) — and
    // clears the perpetual "Working…" spinner (a pending prompt is at-rest waiting).

    private fun questionItemCount(c: ConversationController): Int =
        c.items.value.count { it is ConversationItem.Question }

    @Test
    fun `a pane pending-question raises the sheet, adds no inline item, and idles the spinner`() {
        enqueuePush(
            listOf(
                """{"type":"pending-question","promptKey":"pane-k1","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Which database?","header":"Database",""" +
                    """"multiSelect":false,"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue // AC1
            val sheet = c.pendingSheet.value as PendingSheet.Questions
            assertThat(sheet.questionUuid).isEqualTo("pane-k1")
            assertThat(sheet.answerable).isTrue
            assertThat(sheet.questions.first().options).hasSize(2)
            // AC5 — the pane frame adds NO inline bubble (the transcript copy owns it).
            assertThat(questionItemCount(c)).isEqualTo(0)
            // The pending prompt is at-rest → the spinner must be idle, not "Working…".
            assertThat(awaitUntil { c.turnPhase.value == TurnPhase.IDLE }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a pane pending-question with answerable=false carries the not-answerable flag (multi batch)`() {
        // AC2 — a multi-question batch is visible but not in-app answerable; the controller
        // faithfully carries the server-decided flag (NEVER inferred client-side).
        enqueuePush(
            listOf(
                """{"type":"pending-question","promptKey":"pane-multi","kind":"questions","plan":"",""" +
                    """"answerable":false,"questions":[{"question":"","header":"Color","multiSelect":false,"options":[]},""" +
                    """{"question":"","header":"Size","multiSelect":false,"options":[]}]}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questions?.size == 2 }).isTrue
            assertThat((c.pendingSheet.value as PendingSheet.Questions).answerable).isFalse
        } finally {
            c.close()
        }
    }

    @Test
    fun `a pane multi-question batch with answerable=true routes to the in-app paged answerable sheet`() {
        // UC-55 AC2/AC10 — the server now recovers every tab's options and delivers the
        // multi-question batch answerable=true. The controller faithfully carries the flag
        // and the full per-tab options, so the UI renders the in-app PagedQuestionBody (the
        // answerable path), NOT the read-only "Answer in tmux" NotAnswerableBody. Android is
        // doc-only for this UC; this guards that the existing answerable path holds for the
        // now-answerable N>1 case.
        enqueuePush(
            listOf(
                """{"type":"pending-question","promptKey":"pane-multi","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[""" +
                    """{"question":"Pick a color","header":"Color","multiSelect":false,""" +
                    """"options":[{"label":"Red","description":""},{"label":"Blue","description":""}]},""" +
                    """{"question":"Pick a size","header":"Size","multiSelect":true,""" +
                    """"options":[{"label":"Small","description":""},{"label":"Large","description":""}]}]}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questions?.size == 2 }).isTrue
            val sheet = c.pendingSheet.value as PendingSheet.Questions
            // The flagship invariant: a multi-question batch is in-app answerable (not tmux).
            assertThat(sheet.answerable).isTrue
            assertThat(sheet.questions).hasSize(2) // N>1 → the paged sheet path
            assertThat(sheet.questions[0].options).hasSize(2)
            assertThat(sheet.questions[1].multiSelect).isTrue // multiSelect tab round-trips
            assertThat(sheet.questions[1].options.map { it.label }).containsExactly("Small", "Large")
        } finally {
            c.close()
        }
    }

    @Test
    fun `submitting a pane-derived multi-question batch sends one answer-batch frame and resets sheet state`() {
        // UC-55 AC3/AC7 — submitting the now-answerable pane multi-question batch goes out as a
        // SINGLE answer-batch frame (identical shape to the transcript-derived path), and the
        // sheet is optimistically dismissed with the working spinner. Reuses the shared
        // deriveAnswerSpec/answer-batch path — no parallel pane-specific injection path.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Thread {
                        Thread.sleep(300)
                        wsRef.set(webSocket)
                    }.start()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"pending-question","promptKey":"pane-multi","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[""" +
                    """{"question":"Pick a color","header":"Color","multiSelect":false,""" +
                    """"options":[{"label":"Red","description":""},{"label":"Blue","description":""}]},""" +
                    """{"question":"Pick a size","header":"Size","multiSelect":true,""" +
                    """"options":[{"label":"Small","description":""},{"label":"Large","description":""}]}]}""",
            )
            assertThat(awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questionUuid == "pane-multi" })
                .isTrue
            // Submit all answers as one batch (questionIndex order), echoing the pane promptKey.
            c.submitAnswerBatch(
                "pane-multi",
                listOf(
                    AnswerItem(questionIndex = 0, selections = listOf(1), freeText = ""),
                    AnswerItem(questionIndex = 1, selections = listOf(0, 1), freeText = ""),
                ),
            )
            // Optimistic local state transition (AC4 parity with the transcript path).
            assertThat(c.pendingSheet.value).isNull()
            assertThat(c.turnPhase.value).isEqualTo(TurnPhase.WORKING)
            // Exactly one answer-batch frame, echoing the promptKey and both answers in order.
            assertThat(awaitUntil { received.any { it.contains(""""type":"answer-batch"""") } }).isTrue
            val batch = received.single { it.contains(""""type":"answer-batch"""") }
            assertThat(batch)
                .contains(""""questionUuid":"pane-multi"""")
                .contains(""""questionIndex":0""")
                .contains(""""selections":[1]""")
                .contains(""""questionIndex":1""")
                .contains(""""selections":[0,1]""")
        } finally {
            c.close()
        }
    }

    @Test
    fun `a pane plan pending-question raises a Plan sheet`() {
        // AC6 — an ExitPlanMode plan approval delivered live from the pane.
        enqueuePush(
            listOf(
                """{"type":"pending-question","promptKey":"pane-plan","kind":"plan",""" +
                    """"plan":"1. step a\n2. step b","answerable":true,"questions":[]}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Plan }).isTrue
            val sheet = c.pendingSheet.value as PendingSheet.Plan
            assertThat(sheet.questionUuid).isEqualTo("pane-plan")
            assertThat(sheet.answerable).isTrue
            // No inline plan-approval bubble from the pane path.
            assertThat(c.items.value.none { it is ConversationItem.PlanApproval }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a pending-clear with a matching key clears the pane sheet`() {
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"pending-question","promptKey":"pane-k1","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Q","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""}]}]}""",
            )
            assertThat(awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questionUuid == "pane-k1" }).isTrue
            // The pane chrome disappeared (answered/dismissed in tmux) → key-matched clear.
            wsRef.get()!!.send("""{"type":"pending-clear","promptKey":"pane-k1"}""")
            assertThat(awaitUntil { c.pendingSheet.value == null }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a pending-clear with a non-matching key leaves the sheet up`() {
        // The clear must never clobber a sheet it doesn't own (e.g. a transcript-delivered
        // sheet, which carries a different key).
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"pending-question","promptKey":"pane-k1","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Q","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""}]}]}""",
            )
            assertThat(awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questionUuid == "pane-k1" }).isTrue
            wsRef.get()!!.send("""{"type":"pending-clear","promptKey":"pane-OTHER"}""")
            // Give the frame time to be processed, then assert the sheet is STILL up.
            Thread.sleep(150)
            assertThat(c.pendingSheet.value).isInstanceOf(PendingSheet.Questions::class.java)
            assertThat((c.pendingSheet.value as PendingSheet.Questions).questionUuid).isEqualTo("pane-k1")
        } finally {
            c.close()
        }
    }

    @Test
    fun `AC9 - a pane pending-question then a transcript question yields exactly one inline bubble`() {
        // AC9 (critical) — reproduces the current-claude order: the pane delivers the
        // pending question while the session blocks (transcript has NO assistant line for
        // it), then claude later writes the resolved turn as a transcript `question`. The
        // pane frame added NO inline item, so the transcript write contributes the ONE and
        // only inline Question bubble — no double render / phantom collapsed `❓ question`.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            // 1) Pane delivers the pending question (sheet only, no bubble).
            wsRef.get()!!.send(
                """{"type":"pending-question","promptKey":"pane-k1","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""},{"label":"B","description":""}]}]}""",
            )
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue
            assertThat(questionItemCount(c)).isEqualTo(0)
            // 2) Later, claude writes the resolved turn → a transcript `question` frame.
            wsRef.get()!!.send(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuQ",""" +
                    """"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""},{"label":"B","description":""}]}]}""",
            )
            // Exactly ONE inline Question bubble total (the transcript copy) — no double render.
            assertThat(awaitUntil { questionItemCount(c) == 1 }).isTrue
            Thread.sleep(150)
            assertThat(questionItemCount(c)).isEqualTo(1)
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

    /**
     * Capture the server WebSocket so the test can push frames at a deterministic moment.
     *
     * De-flake (test-only): the capture is gated ~300ms (the file's enqueuePush convention)
     * before [wsRef] is set, so the controller's pump has subscribed to the `replay=0`
     * `incoming` SharedFlow (ConversationController.kt:630) before any test's first send.
     * A frame sent pre-subscription is dropped, not queued. Every caller waits on
     * `wsRef.get() != null` before sending, so this gates exactly the first send. No
     * production change (do NOT make `_incoming` replay=1; that is a production decision).
     */
    private fun enqueueCapture(wsRef: java.util.concurrent.atomic.AtomicReference<WebSocket?>) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Thread {
                        Thread.sleep(300)
                        wsRef.set(webSocket)
                    }.start()
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

    // ──────────────────── Part F — UC-65 Clear (send /clear + wipe in place) ──
    // [ConversationController.clear] resets the conversation in place: it wipes the
    // locally-rendered transcript AND sends `/clear` to the session's Claude, WITHOUT
    // disconnecting or navigating away. These tests mirror AC1–AC7 at the unit level
    // (the menu/UI surface — AC1/AC7 — is covered device-realistically by
    // ConversationOverflowMenuInstrumentationTest); the live end-to-end gate (AC2/AC8)
    // is QA's runbook verification against a real server + emulator.

    /** Record every inbound client→server frame AND capture the socket (300 ms subscription gate). */
    private fun enqueueRecorder(
        received: java.util.concurrent.CopyOnWriteArrayList<String>,
        wsRef: java.util.concurrent.atomic.AtomicReference<WebSocket?>,
    ) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Thread {
                        Thread.sleep(300)
                        wsRef.set(webSocket)
                    }.start()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            }),
        )
    }

    private fun composerInputs(received: List<String>): List<String> =
        received.filter { it.contains(""""type":"composer-input"""") }

    @Test
    fun `clear with no pending sheet wipes the transcript, nulls the sheet, idles, and leaves no clear bubble`() {
        // AC3 — the in-app transcript is emptied. AC4 — any sheet is nulled. The turn goes IDLE
        // (composer stays enabled, AC5/AC6). Pitfall — the `/clear` send must NOT go through the
        // optimistic-echo path, so no stray `/clear` user bubble is left in the wiped transcript.
        val c = offlineController()
        c.submitComposer("hello") // a populated transcript + WORKING spinner
        assertThat(c.items.value).isNotEmpty
        c.clear()
        assertThat(c.items.value).isEmpty() // AC3
        assertThat(c.pendingSheet.value).isNull() // AC4
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.IDLE)
        assertThat(userMessages(c)).isEmpty() // no `/clear` optimistic bubble (pitfall)
    }

    @Test
    fun `clear with no pending sheet sends exactly one composer-input slash-clear and no interrupt`() {
        // AC2 (wire-level) — the happy path delivers `/clear` as a single composer-input frame,
        // byte-for-byte, with NO preceding interrupt (the interrupt is only for the sheet path).
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.clear()
            assertThat(awaitUntil { composerInputs(received).isNotEmpty() }).isTrue
            Thread.sleep(250) // give any erroneous extra/interrupt frame a chance to land
            assertThat(composerInputs(received)).hasSize(1)
            assertThat(composerInputs(received).single())
                .isEqualTo("""{"type":"composer-input","text":"/clear"}""")
            // Happy path → no interrupt frame is sent.
            assertThat(received.none { it.contains(""""type":"interrupt"""") }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `clear with a pending sheet dismisses it and interrupts BEFORE sending slash-clear`() {
        // AC4 — a pending question sheet at clear time is dismissed immediately. The session is
        // mid-blocking-turn, so the controller interrupts FIRST, then sends `/clear` (assert the
        // interrupt precedes the composer-input on the wire).
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { wsRef.get() != null }).isTrue
            wsRef.get()!!.send(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuQ",""" +
                    """"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""},{"label":"B","description":""}]}]}""",
            )
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue
            c.clear()
            assertThat(c.pendingSheet.value).isNull() // AC4 — dismissed immediately
            // The `/clear` lands (after the interrupt + settle gap) …
            assertThat(awaitUntil { composerInputs(received).isNotEmpty() }).isTrue
            val interruptIdx = received.indexOfFirst { it.contains(""""type":"interrupt"""") }
            val clearIdx = received.indexOfFirst {
                it.contains(""""type":"composer-input"""") && it.contains("/clear")
            }
            assertThat(interruptIdx).withFailMessage("expected an interrupt frame, got $received")
                .isGreaterThanOrEqualTo(0)
            assertThat(clearIdx).withFailMessage("interrupt must precede /clear, got $received")
                .isGreaterThan(interruptIdx)
            // Exactly one `/clear` send (no duplicate from the sheet path).
            assertThat(composerInputs(received)).hasSize(1)
        } finally {
            c.close()
        }
    }

    @Test
    fun `the clear guard drops late pre-clear frames so the wiped transcript is not resurrected`() {
        // AC3 (the race) — after clear() the suppression guard is armed; a late assistant-text /
        // turn-start echo / `/clear` command echo (system-note) / re-raised pending-question
        // belonging to the pre-clear epoch are ALL dropped, so none repopulate the empty view.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.clear() // arms the guard (CLEAR_SUPPRESS_MS = 1500 ms)
            listOf(
                """{"type":"turn-start","uuid":"s1","source":"main","isSidechain":false,"text":"stale"}""",
                """{"type":"assistant-text","uuid":"a1","source":"main","isSidechain":false,"text":"ghost"}""",
                """{"type":"system-note","uuid":"n1","source":"main","isSidechain":false,""" +
                    """"label":"Command: /clear","detail":"<command-name>/clear</command-name>"}""",
                """{"type":"pending-question","promptKey":"k1","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Q","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":""}]}]}""",
            ).forEach { wsRef.get()!!.send(it) }
            Thread.sleep(400) // well within CLEAR_SUPPRESS_MS — guard is still active
            assertThat(c.items.value).isEmpty() // nothing resurrected (AC3)
            assertThat(c.pendingSheet.value).isNull() // re-raised pending-question dropped (AC4)
        } finally {
            c.close()
        }
    }

    @Test
    fun `a submit after clear lifts the guard so the new line and its reply render`() {
        // AC5 — after Clear the composer is usable: a new submit deterministically lifts the guard,
        // its optimistic bubble renders, the server turn-start reconciles it, and the following
        // assistant-text now renders (proving the guard is truly lifted, not just for the user line).
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.clear() // guard armed
            c.submitComposer("after clear") // lifts the guard + optimistic bubble
            assertThat(awaitUntil { userMessages(c).size == 1 }).isTrue
            wsRef.get()!!.send(
                """{"type":"turn-start","uuid":"u1","source":"main","isSidechain":false,"text":"after clear"}""",
            )
            assertThat(awaitUntil { userMessages(c).singleOrNull()?.uuid == "u1" }).isTrue
            wsRef.get()!!.send(
                """{"type":"assistant-text","uuid":"r1","source":"main","isSidechain":false,"text":"reply"}""",
            )
            assertThat(awaitUntil { c.items.value.any { it is ConversationItem.AssistantMessage } }).isTrue
        } finally {
            c.close()
        }
    }

    // ──────────────── Part G — UC-66 model selection ─────────────────────────

    @Test
    fun `selectModel publishes the chosen model id for the current target`() {
        // AC5 (best-effort highlight) — picking a model records it as the
        // current target's selected model so the picker can highlight the row.
        val c = offlineController()
        assertThat(c.selectedModelId.value).isNull() // nothing chosen yet
        c.selectModel("opus")
        assertThat(c.selectedModelId.value).isEqualTo("opus")
    }

    @Test
    fun `selectModel ignores a blank id`() {
        val c = offlineController()
        c.selectModel("   ")
        assertThat(c.selectedModelId.value).isNull()
    }

    @Test
    fun `selectModel is not a content turn - no spinner and no user bubble`() {
        // The model switch reuses the RAW composer path (sendComposer), NOT
        // submitComposer: it arms no working spinner and leaves no `/model …`
        // optimistic user bubble in the transcript.
        val c = offlineController()
        c.selectModel("sonnet")
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.IDLE)
        assertThat(userMessages(c)).isEmpty()
    }

    @Test
    fun `a model choice survives a target switch and round-trip (no wipe on selectTarget)`() {
        // AC5 — selectedModelByTarget is per-target and is deliberately NOT
        // wiped by selectTarget()/clearItems(). Switching to a target with no
        // prior choice publishes null; switching back republishes the original.
        val c = offlineController()
        c.selectModel("opus") // for the default "main" target
        assertThat(c.selectedModelId.value).isEqualTo("opus")

        c.selectTarget("swarm:main:0.1") // a target with no model chosen yet
        assertThat(c.selectedTargetId.value).isEqualTo("swarm:main:0.1")
        assertThat(c.selectedModelId.value).isNull()

        c.selectModel("haiku") // choice for the swarm target
        assertThat(c.selectedModelId.value).isEqualTo("haiku")

        // Back to main → the original choice is republished, not lost.
        c.selectTarget(TerminalStreamController.MAIN_TARGET_ID)
        assertThat(c.selectedModelId.value).isEqualTo("opus")

        // And forward to the swarm target again → its own choice.
        c.selectTarget("swarm:main:0.1")
        assertThat(c.selectedModelId.value).isEqualTo("haiku")
    }

    @Test
    fun `selectModel sends exactly one composer-input slash-model frame for the id`() {
        // AC4 (wire-level) — the model change is delivered as a single
        // composer-input `/model <id>` frame down the SAME path the server
        // already routes to the selected target. No interrupt, byte-for-byte.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.selectModel("opus")
            assertThat(awaitUntil { composerInputs(received).isNotEmpty() }).isTrue
            Thread.sleep(250) // give any erroneous extra/interrupt frame a chance to land
            assertThat(composerInputs(received)).hasSize(1)
            assertThat(composerInputs(received).single())
                .isEqualTo("""{"type":"composer-input","text":"/model opus"}""")
            // A model switch is not a blocking turn → no interrupt frame.
            assertThat(received.none { it.contains(""""type":"interrupt"""") }).isTrue
            // And it leaves no optimistic user bubble in the transcript.
            assertThat(userMessages(c)).isEmpty()
        } finally {
            c.close()
        }
    }

    // ──────────────────────── UC-75 — free-text normalization + spinner safety-net ─────────────
    //
    // THE bug: answering via the "Other" field could pin the spinner on WORKING forever — the
    // server injected the answer free-text raw, so an embedded newline committed/declined the ask
    // early and no forward frame arrived to flip WORKING→IDLE. The client-side fixes: (1)
    // normalizeFreeText folds CRLF/CR→LF and trims surrounding whitespace/newlines so a stray
    // edge newline can never commit prematurely, while interior newlines survive end-to-end; and
    // (2) a spinner safety-net (awaitingAnswerKey + a conservative watchdog) recovers to IDLE on a
    // `pending-clear` while awaiting, or as a last-resort timeout — but is DISARMED by any
    // forward-progress frame so a slow-but-valid answer is never flipped early.

    /** An offline controller with an injected (short) watchdog so the safety-net is testable. */
    private fun offlineController(watchdogMs: Long): ConversationController =
        ConversationController(
            sessionN = 7,
            profileStore = mock(ServerProfileStore::class.java),
            httpClientFactory = { error("not used") },
            clientFactory = { _, _ -> error("not used") },
            onClosed = {},
            answerWatchdogMs = watchdogMs,
        )

    /** A networked controller with an injected (short) watchdog. */
    private fun networkedController(watchdogMs: Long): ConversationController {
        val store = mock(ServerProfileStore::class.java)
        runBlocking { doReturn(profile).`when`(store).current() }
        return ConversationController(
            sessionN = 7,
            profileStore = store,
            httpClientFactory = { AiSandboxHttpClient(profile, fakeIdentity()) },
            clientFactory = { http, n -> ConversationClient(http, n) },
            onClosed = {},
            answerWatchdogMs = watchdogMs,
        )
    }

    @Test
    fun `normalizeFreeText trims surrounding whitespace and newlines but preserves interior newlines`() {
        // AC4 — the defensive normalization contract. A stray leading/trailing newline (the exact
        // thing that committed/declined the ask early) is trimmed; CRLF/CR fold to LF; and a genuine
        // INTERIOR newline is preserved so a real multi-line answer survives end-to-end (the server
        // delivers it newline-safely with C-j).
        val c = offlineController()
        // Edge whitespace + edge newlines are stripped …
        assertThat(c.normalizeFreeText("   hello   ")).isEqualTo("hello")
        assertThat(c.normalizeFreeText("\n\nhello\n\n")).isEqualTo("hello")
        assertThat(c.normalizeFreeText("  \n hello \n  ")).isEqualTo("hello")
        // … interior newlines are PRESERVED …
        assertThat(c.normalizeFreeText("line a\nline b")).isEqualTo("line a\nline b")
        // … CRLF and lone CR fold to LF …
        assertThat(c.normalizeFreeText("line a\r\nline b")).isEqualTo("line a\nline b")
        assertThat(c.normalizeFreeText("line a\rline b")).isEqualTo("line a\nline b")
        // … combined: trimmed edges, folded + preserved interior.
        assertThat(c.normalizeFreeText("\r\n  line a\r\nline b  \r\n")).isEqualTo("line a\nline b")
        // An empty / whitespace-only answer normalizes to empty (treated as no free text downstream).
        assertThat(c.normalizeFreeText("   \n  ")).isEmpty()
    }

    @Test
    fun `the answer watchdog recovers a stuck WORKING spinner to IDLE`() {
        // AC5 — last-resort safety-net: an answer was submitted (spinner WORKING) but NO forward
        // frame ever arrives (the ask was declined/failed in the pane). After the conservative
        // watchdog window, the spinner recovers to IDLE (a usable state) — it is NEVER left pinned
        // on WORKING forever. Recovery is to IDLE, never an abort.
        val c = offlineController(watchdogMs = 150L)
        c.submitAnswer("tuQ", 0, listOf(0), "answer text")
        assertThat(c.turnPhase.value).isEqualTo(TurnPhase.WORKING) // armed, awaiting
        // No forward frame is fed → the watchdog fires and recovers the spinner.
        assertThat(awaitUntil(timeoutMs = 3000) { c.turnPhase.value == TurnPhase.IDLE })
            .withFailMessage("watchdog must recover a stuck WORKING spinner to IDLE")
            .isTrue
    }

    @Test
    fun `a pending-clear while awaiting an answer recovers the spinner to IDLE`() {
        // AC5 — event-driven recovery (preferred over the blind timer). A `pending-clear` arriving
        // while we are still awaiting a submitted answer AND pinned WORKING means the ask was
        // resolved/declined in the pane with no forward frame. The controller recovers to IDLE.
        // Uses the DEFAULT (45 s) watchdog, so the IDLE flip is provably from the pending-clear, not
        // the timer.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.submitAnswer("pane-k1", 0, listOf(0), "my answer") // arms the safety-net, spinner WORKING
            assertThat(c.turnPhase.value).isEqualTo(TurnPhase.WORKING)
            // The pane prompt's chrome vanished with no resolving frame → recover to IDLE.
            wsRef.get()!!.send("""{"type":"pending-clear","promptKey":"pane-k1"}""")
            assertThat(awaitUntil { c.turnPhase.value == TurnPhase.IDLE })
                .withFailMessage("pending-clear while awaiting must recover the spinner to IDLE")
                .isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a forward-progress frame disarms the watchdog so a slow-but-valid answer is never flipped early`() {
        // AC5 (the must-not-regress half) — the safety-net MUST NOT mask a genuinely-delivered, slow
        // answer. Any forward-progress frame (here assistant-text) proves the answer landed and the
        // turn is advancing, so it disarms the watchdog. Even after the (short, injected) watchdog
        // window elapses, the spinner stays WORKING — the watchdog never flips a legitimately-working
        // turn to IDLE.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController(watchdogMs = 500L)
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.submitAnswer("tuQ", 0, listOf(0), "a valid answer") // arms a 500 ms watchdog, WORKING
            // A forward frame arrives (the answer landed, the turn is progressing) BEFORE the watchdog.
            wsRef.get()!!.send(
                """{"type":"assistant-text","uuid":"a1","source":"main","isSidechain":false,"text":"on it"}""",
            )
            // Observe the frame is processed (its bubble appears) → the watchdog is disarmed.
            assertThat(
                awaitUntil { c.items.value.any { it is ConversationItem.AssistantMessage } },
            ).isTrue
            assertThat(c.turnPhase.value).isEqualTo(TurnPhase.WORKING) // still working — answer is processing
            // Wait well past the 500 ms watchdog window: a DISARMED watchdog must not fire.
            Thread.sleep(900)
            assertThat(c.turnPhase.value)
                .withFailMessage("a forward frame must disarm the watchdog — no spurious WORKING→IDLE flip")
                .isEqualTo(TurnPhase.WORKING)
        } finally {
            c.close()
        }
    }

    @Test
    fun `submitAnswer sends normalized free text on the wire — edges trimmed, interior newline preserved`() {
        // AC4 (wire-level) — the client never sends a raw leading/trailing newline that could commit
        // the ask early. The submitted "Other" text has its surrounding whitespace/newlines stripped
        // before it goes on the wire, while a genuine interior newline survives (the server injects it
        // newline-safely with C-j).
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.submitAnswer("tuQ", 0, listOf(2), "\n  line a\nline b  \n")
            assertThat(awaitUntil { received.any { it.contains(""""type":"answer"""") } }).isTrue
            val frame = received.first { it.contains(""""type":"answer"""") }
            // Edges trimmed, interior newline preserved → JSON-escaped as line a\nline b on the wire.
            assertThat(frame).contains("\"freeText\":\"line a\\nline b\"")
            // And NO stray leading/trailing escaped newline that would have committed the ask early.
            assertThat(frame).doesNotContain("\"freeText\":\"\\n")
        } finally {
            c.close()
        }
    }
}
