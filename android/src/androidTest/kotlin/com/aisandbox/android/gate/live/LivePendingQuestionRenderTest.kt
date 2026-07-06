package com.aisandbox.android.gate.live

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.gate.GateHarness
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.testtags.QuestionTestTags
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-97 C2 — the LIVE on-device in-view render leg (RELEASE-GATING).
 *
 * <p>Unlike the deterministic {@code com.aisandbox.android.gate} suite (fixture-backed {@code replay}
 * profile, no live Claude), this suite runs against a REAL, live Claude Code 2.1.169-pinned
 * session that {@code android/gate-live-claude.sh} has driven to a PENDING, awaiting-answer
 * {@code AskUserQuestion}. It proves the UC-97 core: a pending question raised in the live
 * session is DELIVERED to the client and RENDERS the in-view sheet on-device (AC3), independent of
 * entry path (AC6), and is in-app answerable (AC7) — i.e. the sheet-render/delivery regression is
 * gone against the real pinned TUI, not just against fixtures.
 *
 * <h3>Coordination contract with {@code gate-live-claude.sh} (harness → suite)</h3>
 *
 * The script owns raising the live question; this suite only observes render. The script MUST,
 * immediately before invoking this package:
 * <ol>
 *   <li>Raise a live {@code AskUserQuestion} in the session and LEAVE IT PENDING (do NOT send
 *       Escape/answer before the on-device leg — otherwise nothing is pending to render); and</li>
 *   <li>Pass the live session number + kind as instrumentation args:
 *       {@code am instrument … -e liveSessionN <N> -e liveQuestionKind <single|multi>}
 *       where {@code <N>} is the {@code ai-sandbox-<N>} the script selected (GATE_LIVE_SESSION).</li>
 * </ol>
 * When those args are absent (standalone / pre-wiring runs) the suite SKIPS cleanly via
 * {@link Assume} rather than failing — mirroring {@link GateHarness#assumeEnrolled}. For single +
 * multi coverage the script invokes this package once per kind (raise → run → dismiss).
 *
 * <p>NOTE (vs. the replay gate): there is NO {@code answer-echo} here — answering injects into the
 * real tmux Claude. This suite therefore asserts DELIVERY + RENDER + answerability (the UC-97
 * regression surface); the option-mapping-on-the-wire (UC-57/43) invariant is covered
 * deterministically by the replay {@code AskUserQuestionGateTest}. A follow-on may assert the live
 * answer resolves the turn, but that is not required to prove the UC-97 render fix.
 */
@RunWith(AndroidJUnit4::class)
class LivePendingQuestionRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun args() = InstrumentationRegistry.getArguments()

    private fun liveSessionN(): Int? = args().getString("liveSessionN")?.trim()?.toIntOrNull()

    private fun liveQuestionKind(): String = args().getString("liveQuestionKind")?.trim()?.lowercase() ?: "single"

    private fun hostSheet(session: GateHarness.GateSession, sheet: PendingSheet) {
        composeTestRule.setContent {
            AiSandboxTheme {
                QuestionSheet(
                    sheet = sheet,
                    onSubmit = { uuid, idx, sels, ft -> session.controller.submitAnswer(uuid, idx, sels, ft) },
                    onSubmitBatch = { uuid, items -> session.controller.submitAnswerBatch(uuid, items) },
                )
            }
        }
    }

    /**
     * AC3/AC6 — a live pending AskUserQuestion for the live session renders the in-view sheet
     * on-device, with NO action in any other view: the real controller attaches over mTLS/WS, the
     * live pane signal is delivered as a populated {@code PendingSheet.Questions}, and the real
     * {@code QuestionSheet} composable shows it. Single AND multi are covered by re-invoking this
     * package with {@code -e liveQuestionKind single|multi}.
     */
    @Test
    fun livePendingQuestion_rendersInViewSheet_onDevice() {
        GateHarness.assumeEnrolled()
        val n = liveSessionN()
        Assume.assumeTrue(
            "live render suite requires -e liveSessionN <N> from gate-live-claude.sh (a live session with a PENDING question)",
            n != null,
        )

        val session = GateHarness.open(n!!)
        try {
            // Delivery: the live pane-signal pending-question reaches the client as a populated,
            // answerable sheet (the UC-97 delivery layer). For a MULTI wizard the server recovers
            // per-tab options progressively (recoverWizardOptions tab-steps the live pane), so the
            // sheet may first arrive header-only / single-tab and then settle to the full ≥2-question
            // wizard — await the STABLE shape (not the first emit), unlike the atomic replay fixture.
            val kind = liveQuestionKind()
            val sheet: PendingSheet.Questions = runBlocking {
                withTimeout(90_000) {
                    session.controller.pendingSheet.first {
                        it is PendingSheet.Questions &&
                            (kind != "multi" || it.questions.size >= 2)
                    } as PendingSheet.Questions
                }
            }

            when (kind) {
                "multi" -> assertTrue(
                    "expected a multi-question wizard for liveQuestionKind=multi",
                    sheet.questions.size >= 2,
                )
                else -> assertTrue("expected at least one question", sheet.questions.isNotEmpty())
            }
            assertTrue("AC7 — a live AskUserQuestion must be in-app answerable", sheet.answerable)

            // Render: the real sheet composable shows on-device (in-view), driven by stable testTag.
            hostSheet(session, sheet)
            composeTestRule.waitUntil(10_000) {
                composeTestRule.onAllNodesWithTag(QuestionTestTags.SHEET).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag(QuestionTestTags.SHEET).assertIsDisplayed()
            // The in-app-answer path is live (not the "answer in tmux" fallback).
            assertFalse(
                "AC7 — an answerable live question must NOT show the not-in-app-answerable fallback",
                composeTestRule.onAllNodesWithTag(QuestionTestTags.NOT_ANSWERABLE).fetchSemanticsNodes().isNotEmpty(),
            )
        } finally {
            session.close()
        }
    }

    /**
     * AC7 — in-app answering a LIVE question resolves the turn. Submits the first option through the
     * REAL controller (server → InputInjectionService keystroke walk → the live tmux pane on the
     * pinned 2.1.169 TUI), then asserts the live session accepts it and advances: the pending sheet
     * CLEARS with NO further user action (the pane's question chrome disappears → the streaming tail
     * emits pending-clear/turn-end → _pendingSheet → null). This exercises the version-sensitive
     * injection walk against live Claude, which the deterministic replay gate (answer-echo) cannot.
     *
     * Guarded behind `-e liveAnswer true` so the render-only harness invocation does not consume the
     * pending question; run standalone against a freshly-raised single question.
     */
    @Test
    fun liveInAppAnswer_resolvesTheLiveTurn() {
        Assume.assumeTrue("run with -e liveAnswer true", args().getString("liveAnswer")?.trim() == "true")
        GateHarness.assumeEnrolled()
        val n = liveSessionN()
        Assume.assumeTrue("requires -e liveSessionN <N>", n != null)

        val session = GateHarness.open(n!!)
        try {
            val sheet = GateHarness.awaitQuestionSheet(session, timeoutMs = 90_000)
            assertTrue("AC7 — must be in-app answerable", sheet.answerable)
            // Answer the first question with its first option, in-app (no tmux round-trip).
            session.controller.submitAnswer(sheet.questionUuid, 0, listOf(0), "")
            // The live session accepts the injected answer and advances → the sheet clears itself.
            val cleared = runBlocking {
                withTimeout(60_000) { session.controller.pendingSheet.first { it == null }; true }
            }
            assertTrue("AC7 — the live turn resolved and the pending sheet cleared with no exit/re-enter", cleared)
        } finally {
            session.close()
        }
    }
}
