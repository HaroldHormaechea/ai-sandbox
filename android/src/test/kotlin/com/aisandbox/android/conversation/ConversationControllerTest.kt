package com.aisandbox.android.conversation

import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ConversationClient
import com.aisandbox.android.net.MuxConnection
import com.aisandbox.android.net.MuxConnectionManager
import com.aisandbox.android.net.MuxEnvelope
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.screens.TerminalState
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
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

    // ──────────────────────── Part B — shared mux connection (mocked manager) ─
    //
    // UC-100 — the three legacy per-endpoint sockets collapsed onto ONE mux
    // connection, so [ConversationClient] is now a thin adapter over a
    // [MuxConnectionManager] (no socket of its own). Part B therefore drives a
    // REAL ConversationClient over a MOCKED manager: the client's real send*
    // methods produce the real wire JSON (captured in [ConvMuxRig.outbound]) and
    // its `incoming` is a per-session replay flow the test pushes onto. This
    // keeps every test body unchanged — the shared helpers (enqueuePush /
    // enqueueCapture / enqueueAutoReply / enqueueComposerEchoer) and the
    // `wsRef.get()!!.send(...)` idiom are re-expressed over the rig — while
    // eliminating the loopback-socket collector flakiness (the same reason the
    // old runTest-based StreamClientTest was disabled). Transport concerns
    // (reconnect, 4401/4426, single-socket) are covered centrally in the
    // net/Mux* tests + the server-side mux suite.

    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile
    private lateinit var rig: ConvMuxRig

    /**
     * A mocked [MuxConnectionManager] that a real [ConversationClient] rides:
     * per-session replay `incoming` flows the test pushes onto, an outbound
     * capture list (the client's real wire JSON), an optional auto-reply hook
     * (server-echo simulation), and a fake server [WebSocket] whose `send()`
     * feeds the matching session's `incoming`.
     */
    private inner class ConvMuxRig {
        val manager: MuxConnectionManager = mock(MuxConnectionManager::class.java)
        val stateFlow = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Open)
        val outbound = CopyOnWriteArrayList<String>()

        @Volatile
        var autoReply: ((String) -> Unit)? = null

        private val inbound = ConcurrentHashMap<Int, MutableSharedFlow<String>>()

        init {
            // NB: use anyString() (not eq(CONVERSATION)) for the channel — eq() returns
            // null and trips Kotlin's non-null check on the mocked Kotlin method. A real
            // ConversationClient only ever touches the conversation channel, so anyString()
            // is exact enough here.
            `when`(manager.state).thenReturn(stateFlow)
            `when`(manager.textFrames(anyString(), any())).thenAnswer { inv ->
                flowFor(inv.getArgument(1) as Int?)
            }
            `when`(manager.sendText(anyString(), any(), anyString())).thenAnswer { inv ->
                val json = inv.getArgument<String>(2)
                outbound.add(json)
                autoReply?.invoke(json)
                true
            }
        }

        fun flowFor(sessionId: Int?): MutableSharedFlow<String> =
            inbound.computeIfAbsent(sessionId ?: -1) {
                MutableSharedFlow(replay = 256, extraBufferCapacity = 256)
            }

        /** Push server→client conversation frames onto a session's incoming flow. */
        fun push(sessionId: Int, frames: List<String>) {
            frames.forEach { flowFor(sessionId).tryEmit(it) }
        }

        /** A fake server WebSocket whose send(text) feeds the session's incoming flow. */
        fun serverWs(sessionId: Int): WebSocket {
            val ws = mock(WebSocket::class.java)
            `when`(ws.send(anyString())).thenAnswer { inv ->
                flowFor(sessionId).tryEmit(inv.getArgument(0) as String)
                true
            }
            return ws
        }
    }

    @BeforeEach
    fun setUp() {
        rig = ConvMuxRig()
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

    /** A controller wired to the shared mux [rig] with a stubbed profile store. */
    private fun networkedController(): ConversationController {
        val store = mock(ServerProfileStore::class.java)
        runBlocking { doReturn(profile).`when`(store).current() }
        return ConversationController(
            sessionN = 7,
            profileStore = store,
            httpClientFactory = { AiSandboxHttpClient(profile, fakeIdentity()) },
            clientFactory = { _, n -> ConversationClient(rig.manager, n) },
            onClosed = {},
        )
    }

    /**
     * Push [frames] onto session 7's shared `incoming` flow (replay-buffered, so
     * a frame pushed before the controller subscribes is still delivered — the
     * mux analogue of the old ~300 ms post-open subscription gate).
     */
    private fun enqueuePush(frames: List<String>) {
        rig.push(7, frames)
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
    fun `backfill replay does not flip the spinner to working or thinking (UC-78 no-regression)`() {
        // UC-78 turn-phase no-regression — converting `backfilling` from a plain @Volatile var to a
        // MutableStateFlow must NOT change the gating: while history replays (between backfill-start
        // and backfill-end) the turn-start / thinking / assistant-text frames that would otherwise
        // drive WORKING/THINKING are suppressed, so the spinner stays IDLE. No backfill-end is sent,
        // so any flip would be observable.
        enqueuePush(
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                // These would set WORKING / THINKING / WORKING respectively if NOT gated.
                """{"type":"turn-start","uuid":"r1","source":"main","isSidechain":false,"text":"replayed user"}""",
                """{"type":"thinking","uuid":"r2","source":"main","isSidechain":false,"text":"replayed reasoning"}""",
                """{"type":"assistant-text","uuid":"r3","source":"main","isSidechain":false,"text":"replayed answer"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            // The replayed items land …
            assertThat(awaitUntil { c.items.value.size >= 2 })
                .withFailMessage("expected replayed items, got ${c.items.value.map { it.key }}")
                .isTrue
            // … and the spinner never flips out of IDLE during the replay (gated by _backfilling).
            assertThat(awaitUntil(500) { c.turnPhase.value != TurnPhase.IDLE })
                .withFailMessage("spinner must stay IDLE during backfill, was ${c.turnPhase.value}")
                .isFalse
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
        // Capture the controller's outbound frames + a fake server socket to push with.
        rig.autoReply = { received.add(it) }
        wsRef.set(rig.serverWs(7))
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

    /** Reply with [reply] whenever the controller sends a frame containing [trigger]. */
    private fun enqueueAutoReply(trigger: String, reply: String) {
        rig.autoReply = { json ->
            if (json.contains(trigger)) rig.push(7, listOf(reply))
        }
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
        // The shared connection drops right as the fetch-detail goes out, with no
        // reply (AC9). Over the mux, a drop is a state transition to Disconnected —
        // the controller must degrade the in-flight detail to Unavailable.
        rig.autoReply = { json ->
            if (json.contains("fetch-detail")) rig.stateFlow.value = MuxConnection.State.Disconnected
        }
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

    // ──────────────────── Part D2 — UC-58 teammate-message frames ─────────────
    // A `teammate-message` frame (the server's reclassification of a `<teammate-message …>`
    // envelope delivered to a team-lead session as a user line) renders as a distinct,
    // NON-user TeammateMessage item. It is RENDER-ONLY: it must NOT advance the turn phase
    // or touch the pending sheet — it is an inbound teammate line, not the lead's own activity.
    // It is also a content-producing frame, so a teammate line arriving under the UC-65
    // post-`/clear` suppression guard must be DROPPED (no item added), just like assistant-text.

    private fun teammateItems(c: ConversationController): List<ConversationItem.TeammateMessage> =
        c.items.value.filterIsInstance<ConversationItem.TeammateMessage>()

    @Test
    fun `a teammate-message frame becomes a non-user TeammateMessage and does not advance the turn phase`() {
        // AC1/AC2 — the frame's teammateId/color/text land on a distinct non-user item, and
        // the render-only frame leaves the spinner idle (it is not the lead's own work).
        enqueuePush(
            listOf(
                """{"type":"teammate-message","uuid":"u1","source":"main","isSidechain":false,""" +
                    """"teammateId":"analyst","color":"blue","text":"Proposal looks good."}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { teammateItems(c).isNotEmpty() }).isTrue
            val tm = teammateItems(c).single()
            assertThat(tm.teammateId).isEqualTo("analyst")
            assertThat(tm.color).isEqualTo("blue")
            assertThat(tm.text).isEqualTo("Proposal looks good.")
            // NON-user: never a right-aligned user bubble (AC1/AC3).
            assertThat(c.items.value.none { it is ConversationItem.UserMessage }).isTrue
            // Render-only: the spinner stays idle (does NOT drive turn phase).
            assertThat(c.turnPhase.value).isEqualTo(TurnPhase.IDLE)
        } finally {
            c.close()
        }
    }

    @Test
    fun `a teammate-message with no color carries a null color and still attributes the sender`() {
        // AC2 — color is optional on the wire; the item carries a null color and the client
        // falls back to its default label tint, attribution still keyed off teammateId.
        enqueuePush(
            listOf(
                """{"type":"teammate-message","uuid":"u2","source":"main","isSidechain":false,""" +
                    """"teammateId":"qa","text":"running the tests"}""",
            ),
        )
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { teammateItems(c).isNotEmpty() }).isTrue
            val tm = teammateItems(c).single()
            assertThat(tm.teammateId).isEqualTo("qa")
            assertThat(tm.color).isNull()
            assertThat(tm.text).isEqualTo("running the tests")
        } finally {
            c.close()
        }
    }

    @Test
    fun `a teammate-message does not clear or disturb a pending question sheet`() {
        // AC1 (render-only contract) — a teammate line that arrives while a question sheet
        // is pending must add its bubble WITHOUT dismissing the sheet (it does not resolve
        // the lead's ask). Two-phase delivery so the sheet is observed up first.
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
            // A teammate line lands while the ask is still open.
            wsRef.get()!!.send(
                """{"type":"teammate-message","uuid":"u3","source":"main","isSidechain":false,""" +
                    """"teammateId":"developer","color":"green","text":"heads up while you decide"}""",
            )
            assertThat(awaitUntil { teammateItems(c).isNotEmpty() }).isTrue
            // … and the sheet is STILL up: a render-only teammate line must not dismiss it.
            assertThat(c.pendingSheet.value).isInstanceOf(PendingSheet.Questions::class.java)
            assertThat((c.pendingSheet.value as PendingSheet.Questions).questionUuid).isEqualTo("tuQ")
        } finally {
            c.close()
        }
    }

    @Test
    fun `a teammate-message arriving under the post-clear suppression guard is dropped`() {
        // UC-65 regression (AC3 of UC-65) — `teammate-message` is a content-producing frame,
        // so while the post-`/clear` suppression guard is armed a late pre-clear teammate line
        // must be DROPPED: no TeammateMessage item is added, the wiped transcript stays empty.
        // (The drop-list explicitly includes "teammate-message" alongside assistant-text.)
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            c.clear() // arms the guard (CLEAR_SUPPRESS_MS)
            wsRef.get()!!.send(
                """{"type":"teammate-message","uuid":"tmg","source":"main","isSidechain":false,""" +
                    """"teammateId":"analyst","color":"blue","text":"stale teammate line"}""",
            )
            Thread.sleep(400) // well within CLEAR_SUPPRESS_MS — guard is still active
            assertThat(teammateItems(c)).isEmpty() // dropped: nothing resurrected (AC3)
            assertThat(c.items.value).isEmpty()
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
        rig.autoReply = { text ->
            received.add(text)
            if (text.contains(""""type":"composer-input"""")) {
                val typed = Regex(""""text":"(.*?)"""").find(text)?.groupValues?.get(1) ?: ""
                val n = seq.incrementAndGet()
                rig.push(
                    7,
                    listOf(
                        """{"type":"turn-start","uuid":"srv$n","source":"main",""" +
                            """"isSidechain":false,"text":"$typed"}""",
                    ),
                )
            }
        }
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
        // The shared incoming flow is replay-buffered, so a fake server socket can
        // be handed over immediately — a frame sent before the controller subscribes
        // is retained and replayed (no post-open gate needed).
        wsRef.set(rig.serverWs(7))
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
            // UC-86 — the switch-suppress guard is now active; lift it via backfill-start,
            // exactly as the real server always does immediately after processing select-target.
            // Only after the guard lifts can the new-target turn-start be processed (the guard
            // deliberately suppresses all content frames before this, to prevent bleed).
            val ws = wsRef.get()!!
            ws.send("""{"type":"backfill-start","source":"main"}""")
            ws.send("""{"type":"backfill-end","source":"main"}""")
            assertThat(awaitUntil { !c.backfilling.value }).isTrue
            ws.send(
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

    /** Record every client→server frame the controller emits AND hand over a fake server socket. */
    private fun enqueueRecorder(
        received: java.util.concurrent.CopyOnWriteArrayList<String>,
        wsRef: java.util.concurrent.atomic.AtomicReference<WebSocket?>,
    ) {
        rig.autoReply = { received.add(it) }
        wsRef.set(rig.serverWs(7))
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

    /** A networked controller with an injected (short) watchdog, over the shared mux [rig]. */
    private fun networkedController(watchdogMs: Long): ConversationController {
        val store = mock(ServerProfileStore::class.java)
        runBlocking { doReturn(profile).`when`(store).current() }
        return ConversationController(
            sessionN = 7,
            profileStore = store,
            httpClientFactory = { AiSandboxHttpClient(profile, fakeIdentity()) },
            clientFactory = { _, n -> ConversationClient(rig.manager, n) },
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

    // ──────────────────────── UC-79 — infinite-scroll older pages ─────────────

    private fun assistantTexts(c: ConversationController): List<String> =
        c.items.value.filterIsInstance<ConversationItem.AssistantMessage>().map { it.text }

    @Test
    fun `an older page prepends history in transcript order and dedupes the boundary`() {
        // AC2/AC6 — the loaded window holds [win-a, win-b]; an older page brings [old-1, old-2]
        // plus an OVERLAP line (same key as win-a). At page-end the page's items are prepended in
        // arrival (oldest→newest) order ABOVE the window, and the overlap is deduped (one render).
        // Agent-switcher fix — the page is requested via loadOlder() so it carries the live
        // window's transcriptEpoch (the server emits page-start ONLY in response to load-older —
        // SessionConversationHandler.loadOlder), exactly as in production. A two-phase capture
        // delivers the backfill window first, then the requested page, so the epoch matches.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"wa","source":"main","isSidechain":false,"text":"win-a"}""",
                """{"type":"assistant-text","uuid":"wb","source":"main","isSidechain":false,"text":"win-b"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("win-a", "win-b") }).isTrue
            // User scrolls up → request the older page (captures the live window's epoch).
            c.loadOlder()
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            listOf(
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"o1","source":"main","isSidechain":false,"text":"old-1"}""",
                """{"type":"assistant-text","uuid":"o2","source":"main","isSidechain":false,"text":"old-2"}""",
                // Overlap with the window's win-a (same uuid+text → same key) — must dedupe (AC6).
                """{"type":"assistant-text","uuid":"wa","source":"main","isSidechain":false,"text":"win-a"}""",
                """{"type":"page-end","atStart":false}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("old-1", "old-2", "win-a", "win-b") })
                .withFailMessage("expected prepended+deduped order, got ${assistantTexts(c)}")
                .isTrue
            // The half-built page is never published mid-assembly, and paging is not at the start.
            assertThat(awaitUntil { !c.loadingOlder.value }).isTrue
            assertThat(c.atTranscriptStart.value).isFalse
        } finally {
            c.close()
        }
    }

    @Test
    fun `a tool pair merges across the page boundary into a single row at its older position`() {
        // AC6 — the window already holds the tool_result for X (a result-first placeholder row);
        // an older page brings the matching tool_use for X. They must merge into ONE ToolActivity
        // (keyed on toolUseId), and that merged row moves to its correct OLDER position in the page.
        // Agent-switcher fix — the page is requested via loadOlder() so it carries the live
        // window's epoch (server emits page-start only in response to load-older), as in production.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"tool-result","uuid":"tr","source":"main","isSidechain":false,"toolUseId":"X","isError":false,"summary":"RESULT"}""",
                """{"type":"assistant-text","uuid":"wb","source":"main","isSidechain":false,"text":"win-b"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { c.items.value.size == 2 }).isTrue
            c.loadOlder()
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            listOf(
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"o1","source":"main","isSidechain":false,"text":"old-1"}""",
                """{"type":"tool-use","uuid":"tu","source":"main","isSidechain":false,"toolName":"Bash","toolUseId":"X","inputSummary":"ls -la","primaryText":""}""",
                """{"type":"page-end","atStart":false}""",
            ).forEach { ws.send(it) }
            // 3 rows total: old-1, the single merged tool row, win-b (no duplicate tool row).
            assertThat(awaitUntil { c.items.value.size == 3 })
                .withFailMessage("expected 3 rows, got ${c.items.value.map { it.key }}")
                .isTrue
            val tools = c.items.value.filterIsInstance<ConversationItem.ToolActivity>()
            assertThat(tools).hasSize(1)
            // Merged in place: BOTH halves present (tool_use input + the result that arrived first).
            assertThat(tools.single().toolUseId).isEqualTo("X")
            assertThat(tools.single().inputSummary).isEqualTo("ls -la")
            assertThat(tools.single().result?.summary).isEqualTo("RESULT")
            // Positioned at its older (page) slot — after old-1, before the window's win-b.
            val keys = c.items.value.map { it.key }
            assertThat(keys.indexOf("toolactivity|X")).isEqualTo(1)
        } finally {
            c.close()
        }
    }

    @Test
    fun `a page-end with atStart true stops further paging`() {
        // AC4 — once the transcript start is reached the controller flags atTranscriptStart and a
        // subsequent loadOlder() is a no-op (the client stops requesting older pages).
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            wsRef.get()!!.send("""{"type":"page-start"}""")
            wsRef.get()!!.send("""{"type":"page-end","atStart":true}""")
            assertThat(awaitUntil { c.atTranscriptStart.value }).isTrue
            assertThat(awaitUntil { !c.loadingOlder.value }).isTrue
            // loadOlder() must NOT send a load-older frame once at the start.
            c.loadOlder()
            Thread.sleep(150)
            assertThat(received.none { it.contains(""""type":"load-older"""") }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a zero-yield page still clears loading and can reach the transcript start`() {
        // AC4 — a page that prepends NOTHING (cursor already at 0 server-side) is delivered as a
        // page-start immediately followed by page-end(atStart=true) with no frames. The loading
        // affordance still clears and the controller reaches atTranscriptStart (no hang).
        // Agent-switcher fix — the page is requested via loadOlder() so it carries the live
        // window's epoch (server emits page-start only in response to load-older), as in production.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"wa","source":"main","isSidechain":false,"text":"only"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("only") }).isTrue
            c.loadOlder()
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            listOf(
                """{"type":"page-start"}""",
                """{"type":"page-end","atStart":true}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { c.atTranscriptStart.value }).isTrue
            assertThat(awaitUntil { !c.loadingOlder.value }).isTrue
            // The existing window is untouched by an empty page.
            assertThat(assistantTexts(c)).containsExactly("only")
        } finally {
            c.close()
        }
    }

    @Test
    fun `loadOlder is single-in-flight and re-enabled by page-end`() {
        // AC2/AC3 — loadOlder() optimistically raises loadingOlder and sends ONE load-older frame;
        // a second call while in flight is dropped (a fast scroll-up fling fires no overlap). The
        // server's page-end clears the flag so a later scroll can page again.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            // Two rapid calls → exactly one frame on the wire (single-in-flight).
            c.loadOlder()
            c.loadOlder()
            assertThat(awaitUntil { received.count { it.contains(""""type":"load-older"""") } == 1 })
                .withFailMessage("expected exactly 1 load-older, got ${received.count { it.contains("load-older") }}")
                .isTrue
            assertThat(c.loadingOlder.value).isTrue
            Thread.sleep(150)
            assertThat(received.count { it.contains(""""type":"load-older"""") }).isEqualTo(1)
            // page-end clears the in-flight guard …
            wsRef.get()!!.send("""{"type":"page-end","atStart":false}""")
            assertThat(awaitUntil { !c.loadingOlder.value }).isTrue
            // … so a fresh loadOlder() pages again (second frame).
            c.loadOlder()
            assertThat(awaitUntil { received.count { it.contains(""""type":"load-older"""") } == 2 }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a fresh backfill window re-enables paging after the transcript start was reached`() {
        // AC4 fresh-window — after reaching the start, a re-baselined / re-seeded window (a new
        // backfill-start, e.g. on reconnect or target switch) re-enables paging: atTranscriptStart
        // and loadingOlder reset so older history can be paged again.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            wsRef.get()!!.send("""{"type":"page-start"}""")
            wsRef.get()!!.send("""{"type":"page-end","atStart":true}""")
            assertThat(awaitUntil { c.atTranscriptStart.value }).isTrue
            // A fresh backfill window resets the paging flags (AC4 fresh-window).
            wsRef.get()!!.send("""{"type":"backfill-start","source":"main"}""")
            assertThat(awaitUntil { !c.atTranscriptStart.value }).isTrue
            assertThat(c.loadingOlder.value).isFalse
        } finally {
            c.close()
        }
    }

    // ──────────── Part H — agent-switcher selection fix: stale-page epoch drain ─────────────
    //
    // THE regression: "every member shows the same conversation". The UC-79 older-page machinery
    // was not target/epoch-aware — a `load-older` page requested for target A could land AFTER a
    // switch to B (or a reconnect/`backfill-start`), and the burst then either grafted A's history
    // into B's store or swallowed B's own `backfill-start`. The fix adds a monotonic
    // [transcriptEpoch] (bumped on selectTarget / clear / every `backfill-start`), captured at
    // `load-older` send-time and re-checked at `page-start`: a mismatch marks the page STALE and
    // drains its whole burst (dropping CONTENT only — `targets`/`target-selected`/`backfill-end`
    // pass through), while `backfill-start` is handled BEFORE the drain gate so a fresh window can
    // never be swallowed. These guards pin that behaviour; the private page-state flags
    // (pageMode/pageDiscarding) are asserted BEHAVIOURALLY — a later live line must render — since
    // they are not exposed.

    @Test
    fun `a stale older page for the prior target is fully discarded after switching, including its content`() {
        // Guard 1 — a load-older page requested on target A lands AFTER the user switched to B and
        // B's backfill rendered. The whole stale burst (page-start, the page's assistant-text +
        // tool-use CONTENT, page-end) is drained and dropped, so A's history never leaks into B's
        // transcript; B's window + selection stay intact and a later live B line still renders
        // (proving the drain and page-assembly state were both cleared).
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            // On target A the user scrolls up → an older page is requested (captures the epoch).
            c.loadOlder()
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            // The user switches to B before the page arrives → fresh window (epoch bumps).
            c.selectTarget("swarm:main:0.1")
            // B's backfill renders its own two lines (its backfill-start bumps the epoch again).
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"b1","source":"main","isSidechain":false,"text":"b1"}""",
                """{"type":"assistant-text","uuid":"b2","source":"main","isSidechain":false,"text":"b2"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("b1", "b2") })
                .withFailMessage("B backfill did not render; got ${assistantTexts(c)}")
                .isTrue
            // Now the STALE page for A lands (requested for the prior window): page-start, CONTENT
            // (assistant-text + tool-use), page-end — every frame must be drained/dropped.
            listOf(
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"a0","source":"main","isSidechain":false,"text":"a0"}""",
                """{"type":"tool-use","uuid":"at","source":"main","isSidechain":false,"toolName":"Bash",""" +
                    """"toolUseId":"aT","inputSummary":"ls","primaryText":"ls"}""",
                """{"type":"page-end","atStart":false}""",
            ).forEach { ws.send(it) }
            // A subsequent LIVE line for B proves the drain ended cleanly (pageDiscarding cleared)
            // and the live path is active again (pageMode false → it publishes immediately).
            ws.send("""{"type":"assistant-text","uuid":"b3","source":"main","isSidechain":false,"text":"b3"}""")
            assertThat(awaitUntil { assistantTexts(c) == listOf("b1", "b2", "b3") })
                .withFailMessage("stale A page leaked / live B frame lost; items=${c.items.value.map { it.key }}")
                .isTrue
            // No A content survived: the stale tool-use never created a row, and B is still selected.
            assertThat(c.items.value.none { it is ConversationItem.ToolActivity }).isTrue
            assertThat(c.selectedTargetId.value).isEqualTo("swarm:main:0.1")
            assertThat(c.loadingOlder.value).isFalse
        } finally {
            c.close()
        }
    }

    @Test
    fun `B's backfill-start clears a stale-page drain even with interleaved stale content`() {
        // Guard 2 — the page for A begins draining (stale), an interleaved stale CONTENT line is
        // dropped, then B's backfill-start arrives BEFORE the stale page's page-end. backfill-start
        // is handled (epoch bump + full page-state reset) BEFORE the discard gate, so it can never
        // be swallowed by the drain: B's window renders cleanly and the drain is cleared.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            c.loadOlder() // on A — captures the current epoch
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            c.selectTarget("swarm:main:0.1") // epoch bump → the in-flight page is now stale
            listOf(
                // Stale page for A starts draining; the interleaved stale CONTENT line is dropped …
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"a0","source":"main","isSidechain":false,"text":"stale-a0"}""",
                // … then B's backfill-start lands (NO page-end for the stale page) and must NOT be swallowed.
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"b1","source":"main","isSidechain":false,"text":"b1"}""",
                """{"type":"assistant-text","uuid":"b2","source":"main","isSidechain":false,"text":"b2"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("b1", "b2") })
                .withFailMessage("B backfill swallowed or stale A content leaked; got ${assistantTexts(c)}")
                .isTrue
            // The drain was cleared by backfill-start: a later live line renders normally.
            ws.send("""{"type":"assistant-text","uuid":"b3","source":"main","isSidechain":false,"text":"b3"}""")
            assertThat(awaitUntil { assistantTexts(c) == listOf("b1", "b2", "b3") })
                .withFailMessage("stale drain not cleared by backfill-start; got ${assistantTexts(c)}")
                .isTrue
            assertThat(c.selectedTargetId.value).isEqualTo("swarm:main:0.1")
        } finally {
            c.close()
        }
    }

    @Test
    fun `bare stale content frames during a page drain are dropped from the store`() {
        // Guard 3 — while a stale page is draining (pageDiscarding), the discard gate drops every
        // CONTENT frame (assistant-text / thinking / tool-use / tool-result) so the prior window's
        // history can never leak into the freshly-switched transcript. A later live line is then
        // the FIRST and only item, proving the burst added nothing.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            c.loadOlder() // on A — captures the current epoch
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            c.selectTarget("swarm:main:0.1") // epoch bump → the in-flight page is now stale
            assertThat(c.items.value).isEmpty()
            listOf(
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"c1","source":"main","isSidechain":false,"text":"ghost-text"}""",
                """{"type":"thinking","uuid":"c2","source":"main","isSidechain":false,"text":"ghost-think"}""",
                """{"type":"tool-use","uuid":"c3","source":"main","isSidechain":false,"toolName":"Bash",""" +
                    """"toolUseId":"gT","inputSummary":"x","primaryText":"x"}""",
                """{"type":"tool-result","uuid":"c4","source":"main","isSidechain":false,""" +
                    """"toolUseId":"gT","isError":false,"summary":"y"}""",
                """{"type":"page-end","atStart":false}""",
            ).forEach { ws.send(it) }
            // Assert the epoch/pageDiscarding gate dropped all stale page content FIRST.
            assertThat(awaitUntil { !c.loadingOlder.value }).isTrue // page-end processed
            assertThat(c.items.value).isEmpty() // stale burst all dropped by the epoch/pageDiscarding gate
            // UC-86 — switchSuppressActive is still armed at this point (endPageDiscard() does not
            // lift it by design; only backfill-start does). Deliver backfill-start/end to lift the
            // guard exactly as the real server does after processing the target switch.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { !c.backfilling.value }).isTrue
            // NOW the live line arrives — only then does it pass the (now-lifted) switch guard.
            ws.send("""{"type":"assistant-text","uuid":"live","source":"main","isSidechain":false,"text":"live"}""")
            assertThat(awaitUntil { assistantTexts(c) == listOf("live") })
                .withFailMessage("stale content leaked; items=${c.items.value.map { it.key }}")
                .isTrue
            assertThat(c.items.value.none { it is ConversationItem.ToolActivity }).isTrue // gT dropped
            assertThat(c.items.value).hasSize(1)
        } finally {
            c.close()
        }
    }

    @Test
    fun `legit same-window paging still prepends older history in order (epoch match, no regression)`() {
        // Guard 4 (UC-79 no-regression) — a load-older page requested AND delivered within the SAME
        // transcript window (no target switch / clear / backfill between the request and the page)
        // matches the captured epoch, so it is ASSEMBLED (not discarded) and prepended in order.
        // Drives a real loadOlder() so the epoch-match path is genuinely exercised.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            // Render the live window first.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"wa","source":"main","isSidechain":false,"text":"win-a"}""",
                """{"type":"assistant-text","uuid":"wb","source":"main","isSidechain":false,"text":"win-b"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("win-a", "win-b") }).isTrue
            // User scrolls up → request an older page (captures the CURRENT epoch).
            c.loadOlder()
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            // The page arrives in the SAME window (epoch unchanged) → assembled, not discarded.
            listOf(
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"o1","source":"main","isSidechain":false,"text":"old-1"}""",
                """{"type":"assistant-text","uuid":"o2","source":"main","isSidechain":false,"text":"old-2"}""",
                """{"type":"page-end","atStart":false}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("old-1", "old-2", "win-a", "win-b") })
                .withFailMessage("legit same-window paging broke; got ${assistantTexts(c)}")
                .isTrue
            assertThat(awaitUntil { !c.loadingOlder.value }).isTrue
            assertThat(c.atTranscriptStart.value).isFalse
        } finally {
            c.close()
        }
    }

    // ── Part H (optional edge cases, per the developer's change notes) ──────────────────────────
    // These pin CURRENT behaviour for two orderings the approved design treats as out-of-scope
    // because real server flows do not produce them. They are documentation guards: if the contract
    // is ever tightened around these orderings, they will flag the change for review.

    @Test
    fun `a server target-selected frame is a selection echo, not a window reset, so a same-epoch page still assembles`() {
        // Edge case (a) — a server-pushed `target-selected` frame only updates the selected target
        // label; it does NOT bump transcriptEpoch (it is not a transcript-window reset, and it does
        // not wipe the store). So an in-flight load-older page requested in the SAME epoch still
        // matches and assembles onto the still-displayed window. In real flows a `backfill-start`
        // ALWAYS follows the selection and IS the window reset (it bumps the epoch + re-renders).
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            // Render the current window (A).
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"a1","source":"main","isSidechain":false,"text":"a1"}""",
                """{"type":"assistant-text","uuid":"a2","source":"main","isSidechain":false,"text":"a2"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("a1", "a2") }).isTrue
            c.loadOlder() // captures the live epoch
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            // A server `target-selected` echo arrives (no trailing backfill) — selection updates,
            // but the epoch is NOT bumped, so the in-flight page is NOT stale.
            ws.send("""{"type":"target-selected","targetId":"swarm:main:0.1"}""")
            assertThat(awaitUntil { c.selectedTargetId.value == "swarm:main:0.1" }).isTrue
            listOf(
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"o1","source":"main","isSidechain":false,"text":"old-1"}""",
                """{"type":"assistant-text","uuid":"o2","source":"main","isSidechain":false,"text":"old-2"}""",
                """{"type":"page-end","atStart":false}""",
            ).forEach { ws.send(it) }
            // The page assembled (epoch matched) — pinning that target-selected alone is not a reset.
            assertThat(awaitUntil { assistantTexts(c) == listOf("old-1", "old-2", "a1", "a2") })
                .withFailMessage("target-selected wrongly treated as a window reset; got ${assistantTexts(c)}")
                .isTrue
            assertThat(awaitUntil { !c.loadingOlder.value }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `a mid-burst backfill-start clears the drain and B's window survives a trailing stale page-end`() {
        // Edge case (b) — a `backfill-start` arriving BETWEEN a stale `page-start` and its `page-end`
        // clears `pageDiscarding` (it is handled before the discard gate), so the trailing stale
        // `page-end` then takes the normal endPage() path. The load-bearing invariant: B's freshly
        // backfilled window is NEVER corrupted — the stale page's content does not leak and B renders
        // cleanly. (Server bursts are contiguous, so this interleaving is not expected live; the
        // atTranscriptStart side-effect of the trailing page-end is the documented out-of-scope quirk.)
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!
            c.loadOlder() // on A — captures the current epoch
            assertThat(awaitUntil { c.loadingOlder.value }).isTrue
            c.selectTarget("swarm:main:0.1") // epoch bump → the in-flight page is now stale
            listOf(
                // Stale page begins draining; an interleaved stale CONTENT line is dropped …
                """{"type":"page-start"}""",
                """{"type":"assistant-text","uuid":"sx","source":"main","isSidechain":false,"text":"stale-x"}""",
                // … then B's backfill-start lands mid-burst (clears the drain) and renders B …
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"b1","source":"main","isSidechain":false,"text":"b1"}""",
                """{"type":"assistant-text","uuid":"b2","source":"main","isSidechain":false,"text":"b2"}""",
                """{"type":"backfill-end","source":"main"}""",
                // … and only NOW the stale page-end arrives (drain already cleared → endPage path).
                """{"type":"page-end","atStart":true}""",
            ).forEach { ws.send(it) }
            // Load-bearing invariant: B's window is intact and the stale page's content never leaked.
            assertThat(awaitUntil { assistantTexts(c) == listOf("b1", "b2") })
                .withFailMessage("B window corrupted by the interleaving; got ${assistantTexts(c)}")
                .isTrue
            assertThat(c.selectedTargetId.value).isEqualTo("swarm:main:0.1")
            // A later live line still renders (the controller is not wedged in a drain).
            ws.send("""{"type":"assistant-text","uuid":"b3","source":"main","isSidechain":false,"text":"b3"}""")
            assertThat(awaitUntil { assistantTexts(c) == listOf("b1", "b2", "b3") }).isTrue
            // Documented out-of-scope quirk: the trailing stale page-end(atStart=true) took the
            // endPage path and flipped atTranscriptStart on B's window. Pinned so any future change
            // to make page-end epoch-aware surfaces here for review.
            assertThat(c.atTranscriptStart.value)
                .withFailMessage("known quirk changed: trailing stale page-end no longer sets atTranscriptStart")
                .isTrue
        } finally {
            c.close()
        }
    }

    // ──────────── Part I — UC-86 reproduction: conversation bleed and broken pills ─────────────
    //
    // THE BUG (android-v0.4.15 regression, both symptoms):
    //
    // (1) VIEW BLEED — after the user taps a different agent pill, live content frames from
    //     the OLD target that the server had already buffered arrive at the client AFTER
    //     selectTarget() called clearItems(). Because clearItems() does NOT arm any
    //     suppression guard, these "in-flight" frames are added to the now-empty store,
    //     contaminating the new target's transcript with the old target's content.
    //
    // (2) PILL NAVIGATION BROKEN — this same unsuppressed arrival of stale content is
    //     exactly what makes a pill-tap appear to "not work": the view switches from A
    //     to B, clearItems empties the list, but A's still-buffered line(s) immediately
    //     re-populate it.  The user sees content that belongs to A in B's window.
    //
    // ROOT CAUSE: selectTarget() calls clearItems() and sendSelectTarget(), but does NOT
    // arm a "switch suppress" guard analogous to the clearSuppressActive guard that
    // UC-65's /clear uses.  The /clear handler sets clearSuppressActive = true, which
    // drops every content frame until backfill-start re-clears the store; selectTarget()
    // has no equivalent, so any live frame from the prior target that arrives in the
    // window between clearItems() and the server's backfill-start lands in the store.
    //
    // Fix (not yet applied — this is the Phase-0 failing repro):
    // selectTarget() must arm a per-switch suppression flag (or reuse/generalise
    // clearSuppressActive) so that "turn-start", "assistant-text", "thinking",
    // "teammate-message", "tool-use", "tool-result", "system-note" frames arriving
    // before the next backfill-start are dropped, and backfill-start re-clears the store
    // (already done for the clearSuppressActive path at onFrame line ~606).

    @Test
    fun `UC-86 repro (1) - live frame from old target that arrives after selectTarget contaminates new target transcript`() {
        // Scenario: agent A has a live assistant-text frame that the server buffers just
        // before processing the client's select-target.  That frame arrives at the client
        // AFTER selectTarget("B") called clearItems().  It must be DROPPED (not added to
        // B's now-empty store).  Without a switch-suppress guard the test fails because
        // the stale frame is kept, and B's backfill is then appended below it.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            // Agent A's initial backfill renders.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"a1","source":"main","isSidechain":false,"text":"A-history"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("A-history") }).isTrue

            // User taps agent B pill → clearItems() runs client-side; select-target sent to server.
            c.selectTarget("swarm:main:0.1")
            assertThat(c.items.value).isEmpty()

            // A stale live frame from A arrives BEFORE the server's backfill-start for B
            // (server had buffered it before processing the select-target command).
            ws.send(
                """{"type":"assistant-text","uuid":"a-stale","source":"main","isSidechain":false,"text":"A-stale-leak"}""",
            )

            // B's backfill arrives.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"b1","source":"main","isSidechain":false,"text":"B-content"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }

            // B's transcript must contain ONLY B's content — A's stale frame must be absent.
            // This FAILS before the fix: stale frame lands in the empty store before backfill-start,
            // then B's content is appended below it → items = [A-stale-leak, B-content].
            assertThat(awaitUntil { assistantTexts(c) == listOf("B-content") })
                .withFailMessage(
                    "A's stale live frame contaminated B's transcript (UC-86 bleed not fixed); " +
                        "got: ${assistantTexts(c)}",
                )
                .isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `UC-86 repro (2) - multiple stale live frames from old target are all suppressed across the switch`() {
        // Variant of repro (1): the server had MULTIPLE buffered frames from agent A
        // (e.g. a tool-use + tool-result + assistant-text mid-turn) all arriving before
        // B's backfill-start.  None must appear in B's transcript.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            // A's backfill.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"a1","source":"main","isSidechain":false,"text":"A-init"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("A-init") }).isTrue

            c.selectTarget("swarm:main:0.1")

            // Three stale frames from A's in-progress turn arrive before B's backfill-start.
            listOf(
                """{"type":"thinking","uuid":"a-th","source":"main","isSidechain":false,"text":"A-thinking"}""",
                """{"type":"tool-use","uuid":"a-tu","source":"main","isSidechain":false,""" +
                    """"toolName":"Bash","toolUseId":"aTU","inputSummary":"ls","primaryText":"ls"}""",
                """{"type":"assistant-text","uuid":"a-stale2","source":"main","isSidechain":false,"text":"A-stale-2"}""",
            ).forEach { ws.send(it) }

            // B's backfill.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"b1","source":"main","isSidechain":false,"text":"B-only"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }

            // Only B's item must appear; no A thinking/tool/text must survive.
            // FAILS before the fix: all three A frames contaminate B's store.
            assertThat(awaitUntil { assistantTexts(c) == listOf("B-only") })
                .withFailMessage(
                    "Stale A frames not suppressed after selectTarget; got: ${c.items.value.map { it.key }}",
                )
                .isTrue
            assertThat(c.items.value.none { it is ConversationItem.ToolActivity }).isTrue
            assertThat(c.items.value.none { it is ConversationItem.Thinking }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `UC-86 guard - basic agent-pill navigation without stale pages works (smoke)`() {
        // Non-failing guard: selectTarget with NO prior loadOlder (no stale page in flight)
        // and no stale live frames → the clean path.  B's backfill renders exactly.
        // This must PASS before and after the fix so a future over-correction is caught.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            // A's initial backfill.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"a1","source":"main","isSidechain":false,"text":"A-content"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("A-content") }).isTrue
            assertThat(c.selectedTargetId.value).isEqualTo(TerminalStreamController.MAIN_TARGET_ID)

            // Tap B — clearItems fires; items go empty.
            c.selectTarget("swarm:main:0.1")
            assertThat(c.items.value).isEmpty()
            assertThat(c.selectedTargetId.value).isEqualTo("swarm:main:0.1")

            // B's clean backfill (no stale frames in flight).
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"b1","source":"main","isSidechain":false,"text":"B-content"}""",
                """{"type":"assistant-text","uuid":"b2","source":"main","isSidechain":false,"text":"B-content-2"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }

            assertThat(awaitUntil { assistantTexts(c) == listOf("B-content", "B-content-2") })
                .withFailMessage("clean selectTarget + backfill failed; got: ${assistantTexts(c)}")
                .isTrue
            // No A content survived.
            assertThat(c.items.value.none { (it as? ConversationItem.AssistantMessage)?.text == "A-content" }).isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `UC-86 guard - rapid consecutive agent switches show only the final target's content`() {
        // AC6 guard: rapidly switching A → B → C; only C's backfill must show.  Each switch
        // clears the store and bumps the epoch; C's clean backfill fills the final view.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            // Establish initial window.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"a1","source":"main","isSidechain":false,"text":"A-init"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { assistantTexts(c) == listOf("A-init") }).isTrue

            // Rapid A → B → C switches (each bumps epoch again).
            c.selectTarget("swarm:b:0.1")
            c.selectTarget("swarm:c:0.1")

            assertThat(c.items.value).isEmpty()
            assertThat(c.selectedTargetId.value).isEqualTo("swarm:c:0.1")

            // C's backfill arrives with no stale frames.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"c1","source":"main","isSidechain":false,"text":"C-content"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }

            assertThat(awaitUntil { assistantTexts(c) == listOf("C-content") })
                .withFailMessage("after rapid A→B→C switches, got: ${assistantTexts(c)}")
                .isTrue
        } finally {
            c.close()
        }
    }

    // ──────────── Part II — UC-86 fix: guard correctness and boundary conditions ─────────────

    @Test
    fun `UC-86 Q5 — clear() while switch is pending lifts both guards and content renders after backfill-start`() {
        // Q5 edge case: the user taps a pill (selectTarget) and then immediately issues /clear
        // before the new target's backfill-start arrives, arming BOTH switchSuppressActive AND
        // clearSuppressActive simultaneously.  The next backfill-start must lift BOTH via
        // liftAllSuppressGuards() in the clearSuppressActive guard-block arm — leaving neither
        // guard stranded.  A subsequent live assistant-text must render to prove this.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            // Arm both guards simultaneously: pill-tap then /clear, no backfill between them.
            c.selectTarget("swarm:main:0.1") // switchSuppressActive = true
            c.clear()                         // clearSuppressActive = true (both now armed)

            // Deliver backfill-start: hits the clearSuppressActive guard-block arm first
            // (clearItems() + liftAllSuppressGuards()), so BOTH flags are cleared atomically.
            ws.send("""{"type":"backfill-start","source":"main"}""")
            ws.send("""{"type":"assistant-text","uuid":"b-ac","source":"main","isSidechain":false,"text":"B-after-clear"}""")
            ws.send("""{"type":"backfill-end","source":"main"}""")

            // The live content must render — proves switchSuppressActive is NOT permanently stranded
            // (if it were, the assistant-text above would have been dropped too).
            assertThat(awaitUntil { assistantTexts(c) == listOf("B-after-clear") })
                .withFailMessage(
                    "switchSuppressActive stranded after overlapping clear(); got: ${assistantTexts(c)}",
                )
                .isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `UC-86 — optimistic echo submitted after selectTarget is preserved through backfill-start and reconciles`() {
        // submit-survives-backfill: the suppress window allows the UC-45 optimistic echo through
        // (submitComposer writes directly to itemMap, not via onFrame), while still dropping a stale
        // A-frame.  After backfill-start (Path B — no clearItems, echo survives), the server's
        // turn-start echo reconciles against the pending bubble in place.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            // Switch to B (arms switchSuppressActive) then immediately submit (echo goes in directly).
            c.selectTarget("swarm:main:0.1")
            c.submitComposer("hello")

            // (a) Optimistic echo appears immediately — submit bypasses onFrame.
            assertThat(awaitUntil { userMessages(c).size == 1 }).isTrue
            assertThat(userMessages(c).single().uuid).isEmpty() // optimistic: blank uuid
            assertThat(userMessages(c).single().localSeq).isEqualTo(0L) // first localSeq

            // (b) A stale A frame arrives — must be dropped by the switch-suppress guard.
            ws.send(
                """{"type":"assistant-text","uuid":"stale-a","source":"main","isSidechain":false,"text":"A-stale-during-submit"}""",
            )

            // Deliver B's backfill (Path B: no clearItems — echo stays; guard lifts).
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { !c.backfilling.value }).isTrue

            // Stale A frame must not have appeared.
            assertThat(c.items.value.none { it is ConversationItem.AssistantMessage }).isTrue

            // (c) Server's turn-start echo reconciles against the pending echo in place.
            ws.send(
                """{"type":"turn-start","uuid":"srv-u1","source":"main","isSidechain":false,"text":"hello"}""",
            )
            // Wait specifically for the uuid to be updated (size == 1 was already true, so we must
            // poll for the reconcile to have fired and the server uuid to have been applied).
            assertThat(awaitUntil { userMessages(c).firstOrNull()?.uuid == "srv-u1" }).isTrue
            val reconciled = userMessages(c).single()
            assertThat(reconciled.uuid).isEqualTo("srv-u1") // server uuid applied
            assertThat(reconciled.localSeq).isEqualTo(0L)   // localSeq preserved (not a fresh add)
            assertThat(c.items.value.none { it is ConversationItem.AssistantMessage }).isTrue // A still absent
        } finally {
            c.close()
        }
    }

    @Test
    fun `UC-86 — switchSuppressActive falls back to timed lift when no backfill-start arrives`() {
        // Timeout backstop: if the server never delivers a backfill-start (network drop, unexpected
        // race), the CLEAR_SUPPRESS_MS timed fallback inside selectTarget() fires and lifts
        // switchSuppressActive so live content is not blocked permanently.
        // CLEAR_SUPPRESS_MS = 1_500L ms (companion constant); we sleep 2_000 ms to ensure it fired.
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            c.selectTarget("swarm:main:0.1") // arms guard + starts 1_500 ms timer

            // Deliberate: no backfill-start sent. Wait past CLEAR_SUPPRESS_MS so the timed
            // fallback fires and lifts the guard.
            Thread.sleep(2_000L)

            // A live frame arriving after the timer must render (guard is now down).
            ws.send(
                """{"type":"assistant-text","uuid":"post-timeout","source":"main","isSidechain":false,"text":"live-after-timeout"}""",
            )
            assertThat(awaitUntil { assistantTexts(c) == listOf("live-after-timeout") })
                .withFailMessage("timed fallback did not lift switchSuppressActive; items=${c.items.value.map { it.key }}")
                .isTrue
        } finally {
            c.close()
        }
    }

    @Test
    fun `AC1 — two controllers for different sessions carry independent transcript state`() {
        // AC1 guard (controller layer): session 1 and session 2 use independent ConversationController
        // instances whose stores never share state.  Receiving content for session 2 does not
        // contaminate session 1's transcript, and vice versa.
        // Note: ViewModel-level isolation (ConversationViewModel.attach() collector lifecycle under
        // Android framework) is outside JVM unit-test scope — it requires instrumented tests to verify
        // that the old controller's collector coroutines are cancelled when the session changes.
        // Per-session fake server sockets over the ONE shared connection — session 1
        // and 2 have independent `incoming` flows (rig keys by sessionId), so a frame
        // for session 2 can never contaminate session 1's transcript.
        val wsRef1 = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        val wsRef2 = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        wsRef1.set(rig.serverWs(1))
        wsRef2.set(rig.serverWs(2))

        val store = org.mockito.Mockito.mock(ServerProfileStore::class.java)
        kotlinx.coroutines.runBlocking { org.mockito.Mockito.doReturn(profile).`when`(store).current() }
        val makeCtrl = { n: Int ->
            ConversationController(
                sessionN = n,
                profileStore = store,
                httpClientFactory = { AiSandboxHttpClient(profile, fakeIdentity()) },
                clientFactory = { _, sn -> ConversationClient(rig.manager, sn) },
                onClosed = {},
            )
        }

        val c1 = makeCtrl(1)
        val c2 = makeCtrl(2)
        // Attach controllers one at a time so the MockWebServer serves them in a known order:
        // c1 gets the first enqueued response (wsRef1), c2 gets the second (wsRef2).
        c1.attach(1)
        assertThat(awaitUntil { c1.state.value == TerminalState.Open && wsRef1.get() != null }).isTrue
        c2.attach(2)
        try {
            assertThat(awaitUntil { c2.state.value == TerminalState.Open && wsRef2.get() != null }).isTrue

            // Session 1 receives content A.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"s1-a1","source":"main","isSidechain":false,"text":"session-1-content"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { wsRef1.get()!!.send(it) }
            assertThat(awaitUntil { assistantTexts(c1) == listOf("session-1-content") }).isTrue

            // Session 2 receives content B.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"assistant-text","uuid":"s2-b1","source":"main","isSidechain":false,"text":"session-2-content"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { wsRef2.get()!!.send(it) }
            assertThat(awaitUntil { assistantTexts(c2) == listOf("session-2-content") }).isTrue

            // Neither session sees the other's content (no cross-session bleed at controller layer).
            assertThat(assistantTexts(c1)).doesNotContain("session-2-content")
                .withFailMessage("session-2 content bled into session-1 controller: ${assistantTexts(c1)}")
            assertThat(assistantTexts(c2)).doesNotContain("session-1-content")
                .withFailMessage("session-1 content bled into session-2 controller: ${assistantTexts(c2)}")
        } finally {
            c1.close()
            c2.close()
        }
    }

    @Test
    fun `SUPPRESSED_CONTENT_FRAMES sync — all item-adding frame types are suppressed in the switch window`() {
        // Structural guard: every when(type) arm in onFrame() that adds a visible item (via
        // addItem / upsertToolUse / upsertToolResult / reconcileOrAddUserMessage) must be present
        // in SUPPRESSED_CONTENT_FRAMES so it is dropped while switchSuppressActive is armed.
        // This test sends ALL known item-adding frame types while the guard is active and asserts
        // none reached the store.  If a developer adds a new content frame type to onFrame() but
        // forgets to add it to SUPPRESSED_CONTENT_FRAMES, a variant of this test will catch the
        // resulting bleed.  Frame types enumerated from when(type) in ConversationController.onFrame().
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueCapture(wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            c.selectTarget("swarm:main:0.1") // arms switchSuppressActive
            assertThat(c.items.value).isEmpty()

            // Send every item-adding frame type known from onFrame()'s when(type) arms.
            listOf(
                // turn-start → reconcileOrAddUserMessage (if text non-blank)
                """{"type":"turn-start","uuid":"ts1","source":"main","isSidechain":false,"text":"stale-turn"}""",
                // thinking → addItem(Thinking)
                """{"type":"thinking","uuid":"th1","source":"main","isSidechain":false,"text":"stale-think"}""",
                // assistant-text → addItem(AssistantMessage)
                """{"type":"assistant-text","uuid":"at1","source":"main","isSidechain":false,"text":"stale-assistant"}""",
                // teammate-message (UC-58) → addItem(TeammateMessage)
                """{"type":"teammate-message","uuid":"tm1","source":"main","isSidechain":false,""" +
                    """"teammateId":"agent-x","color":"blue","text":"stale-teammate"}""",
                // tool-use → upsertToolUse
                """{"type":"tool-use","uuid":"tu1","source":"main","isSidechain":false,""" +
                    """"toolName":"Bash","toolUseId":"SYNC-TU","inputSummary":"ls","primaryText":"ls"}""",
                // tool-result → upsertToolResult
                """{"type":"tool-result","uuid":"tr1","source":"main","isSidechain":false,""" +
                    """"toolUseId":"SYNC-TU","isError":false,"summary":"done"}""",
                // system-note (UC-42) → addItem(SystemNote)
                """{"type":"system-note","uuid":"sn1","source":"main","isSidechain":false,"label":"cmd","detail":"body"}""",
                // question → addItem(Question)
                """{"type":"question","uuid":"q1","source":"main","isSidechain":false,"toolUseId":"SYNC-TU",""" +
                    """"questions":[{"question":"Q?","header":"Q","multiSelect":false,"options":[{"label":"Yes","description":""}]}]}""",
                // plan-approval → addItem(PlanApproval)
                """{"type":"plan-approval","uuid":"pa1","source":"main","isSidechain":false,"toolUseId":"SYNC-TU","plan":"1. do x"}""",
            ).forEach { ws.send(it) }

            // Lift the guard via backfill-start so a post-guard frame can land as sentinel.
            listOf(
                """{"type":"backfill-start","source":"main"}""",
                """{"type":"backfill-end","source":"main"}""",
            ).forEach { ws.send(it) }
            assertThat(awaitUntil { !c.backfilling.value }).isTrue

            // The store must be completely empty — every suppressed frame was dropped.
            assertThat(c.items.value)
                .withFailMessage(
                    "Not all item-adding frame types are in SUPPRESSED_CONTENT_FRAMES; " +
                        "leaked: ${c.items.value.map { it.key }}",
                )
                .isEmpty()
        } finally {
            c.close()
        }
    }

    // ──────────────────── Part Z — UC-93 deep-link re-focus (Case R) ──────────
    // The shipped fix: a warm push-notification deep-link can re-enter a process-cached
    // ConversationController that was left selecting a read-only `subagent:` pane. While
    // it stays on the subagent, the server tails the subagent pane (the main pending
    // question is never re-emitted) AND `ConversationScreen.readOnly` gates the sheet out
    // — the wedge. [ConversationController.focusAnswerableTargetForDeepLink] re-focuses the
    // answerable `main` pane via the existing [selectTarget] (epoch bump + switch-suppress +
    // `select-target main` on the live socket), so the server re-tails main and re-emits its
    // pending question, and `readOnly` clears. It is a strict no-op for `main`/`swarm:`.

    @Test
    fun `UC-93 - deep-link re-focus on a subagent selection sends select-target main and the re-emitted question raises the sheet`() {
        // The crux regression guard at the controller layer. Seed the selection the REAL way
        // (drive selectTarget down the pill path) onto a `subagent:` id, fire the deep-link
        // hook, and assert: the server receives `select-target main` (NOT left on the subagent
        // pane), selection flips to main, and main's re-emitted pending question then renders.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            // Left selecting a read-only background-subagent pill (as a prior pill tap would).
            c.selectTarget(TerminalStreamController.SUBAGENT_ID_PREFIX + "agent-3")
            assertThat(c.selectedTargetId.value).isEqualTo("subagent:agent-3")

            // Deep-link consume hook fires → because the selection is a subagent, re-focus main.
            c.focusAnswerableTargetForDeepLink()
            assertThat(c.selectedTargetId.value).isEqualTo(TerminalStreamController.MAIN_TARGET_ID)

            // `select-target main` is the LAST selection sent on the wire — the server is told to
            // re-tail the answerable main pane, never left tailing the subagent.
            assertThat(
                awaitUntil {
                    received.any {
                        it.contains(""""type":"select-target"""") && it.contains(""""targetId":"main"""")
                    }
                },
            ).isTrue
            val lastSelect = received.last { it.contains(""""type":"select-target"""") }
            assertThat(lastSelect).contains(""""targetId":"main"""")

            // The server re-tails main: backfill (lifts the UC-86 switch-suppress guard), then the
            // UC-50 per-connection re-emit of main's pending question.
            val ws = wsRef.get()!!
            ws.send("""{"type":"backfill-start","source":"main"}""")
            ws.send("""{"type":"backfill-end","source":"main"}""")
            assertThat(awaitUntil { !c.backfilling.value }).isTrue
            ws.send(
                """{"type":"pending-question","promptKey":"main-q","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}""",
            )
            // The pending question now renders (sheet populated) AND selection is the answerable main.
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue
            assertThat((c.pendingSheet.value as PendingSheet.Questions).answerable).isTrue
            assertThat(c.selectedTargetId.value).isEqualTo(TerminalStreamController.MAIN_TARGET_ID)
        } finally {
            c.close()
        }
    }

    @Test
    fun `UC-93 - deep-link re-focus is a strict no-op on a main selection and never wipes a populated sheet`() {
        // The negative guard: on a healthy `main` selection the hook must send NO `select-target`
        // frame, leave the selection on main, and NOT wipe an already-populated sheet (a blind
        // selectTarget(main) would have nulled it and dropped the question the user is answering).
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7)
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            // A pending question is already up on the main pane (the normal, healthy case).
            wsRef.get()!!.send(
                """{"type":"question","uuid":"uq","source":"main","isSidechain":false,"toolUseId":"tuMain",""" +
                    """"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}""",
            )
            assertThat(awaitUntil { (c.pendingSheet.value as? PendingSheet.Questions)?.questionUuid == "tuMain" }).isTrue

            // Hook fires on a main selection → strict no-op.
            c.focusAnswerableTargetForDeepLink()
            Thread.sleep(200) // give any erroneous select-target / sheet-wipe a chance to land

            // No select-target frame went out (connect with main sends none either), selection stays
            // main, and the populated sheet is untouched.
            assertThat(received.none { it.contains(""""type":"select-target"""") }).isTrue
            assertThat(c.selectedTargetId.value).isEqualTo(TerminalStreamController.MAIN_TARGET_ID)
            assertThat((c.pendingSheet.value as? PendingSheet.Questions)?.questionUuid).isEqualTo("tuMain")
        } finally {
            c.close()
        }
    }

    @Test
    fun `UC-93 - deep-link re-focus leaves a swarm selection untouched`() {
        // The subagent-only guard must not disturb an answerable team (`swarm:`) selection: a
        // swarm pane is writable, so it is left as-is (no re-focus to main).
        val c = offlineController()
        c.selectTarget("swarm:main:0.1")
        c.focusAnswerableTargetForDeepLink()
        assertThat(c.selectedTargetId.value).isEqualTo("swarm:main:0.1")
    }

    // ──────────────────── Part A1 — UC-97 resync-pending on warm re-attach ──────
    // The shipped A1 fix: a warm re-attach (connectJob still live) would otherwise no-op, and the
    // server's streaming re-emit won't re-send a still-blocked prompt (the helper's once-per-key
    // guard). So a pending sheet the client lost to a transient (racing pending-clear / turn-end)
    // while the ask is STILL live would only re-appear on a fresh connection — the user's "exit and
    // re-enter to see it" symptom (AC5). ConversationController.attach() now sends `resync-pending`
    // on the warm path; the server re-derives the live pane and re-emits the pending question, so
    // the sheet re-populates WITHOUT a reconnect.

    @Test
    fun `UC-97 A1 - a warm re-attach sends resync-pending and the re-emitted question re-populates a transient-cleared sheet`() {
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        enqueueRecorder(received, wsRef)
        val c = networkedController()
        c.attach(7) // cold attach → connect loop live
        try {
            assertThat(awaitUntil { c.state.value == TerminalState.Open && wsRef.get() != null }).isTrue
            val ws = wsRef.get()!!

            // A live pending question is up on the main pane…
            ws.send(
                """{"type":"pending-question","promptKey":"pane-k1","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}""",
            )
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue

            // …then a TRANSIENT clears it while the ask is still live in the pane (turn-end nulls the
            // sheet; the helper won't re-emit the same key on this warm socket).
            ws.send("""{"type":"turn-end"}""")
            assertThat(awaitUntil { c.pendingSheet.value == null }).isTrue

            // Warm re-attach (the ViewModel's re-entry): the connect loop is still live, so this must
            // send `resync-pending` rather than silently no-op.
            received.clear()
            c.attach(7)
            assertThat(
                awaitUntil { received.any { it.contains(""""type":"resync-pending"""") } },
            ).isTrue

            // The server re-derives the live pane and re-emits the pending question → the sheet
            // re-populates with NO reconnect (fail-before A1: warm attach no-op'd, sheet stayed null).
            ws.send(
                """{"type":"pending-question","promptKey":"pane-k1","kind":"questions","plan":"",""" +
                    """"answerable":true,"questions":[{"question":"Pick","header":"H","multiSelect":false,""" +
                    """"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]}]}""",
            )
            assertThat(awaitUntil { c.pendingSheet.value is PendingSheet.Questions }).isTrue
            assertThat((c.pendingSheet.value as PendingSheet.Questions).answerable).isTrue
        } finally {
            c.close()
        }
    }
}
