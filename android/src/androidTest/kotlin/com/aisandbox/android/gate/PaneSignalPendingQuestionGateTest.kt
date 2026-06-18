package com.aisandbox.android.gate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.conversation.TurnPhase
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.testtags.QuestionTestTags
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-85 — the PANE-SIGNAL pending-question leg of the deterministic on-device gate.
 *
 * <p>Scenario 6 differs from {@link AskUserQuestionGateTest} in HOW the question is raised: it is
 * NOT an in-transcript {@code AskUserQuestion} tool call, but the production
 * {@code __ctrl__ pending-question} control frame the server emits when it derives a prompt by
 * stepping the live pane (promptKey {@code "pq-single"}). This locks the fidelity path the
 * developer added — the same one the real app hits when Claude is mid-turn at an
 * {@code AskUserQuestion} that never reached the transcript as a tool call.
 *
 * <p>The test proves, against the REAL mTLS/WebSocket replay stack (no live Claude, no LLM):
 * <ol>
 *   <li><b>Pending-question indicator + in-app answerable.</b> The pane signal surfaces a
 *       {@link PendingSheet.Questions} whose {@code answerable} is {@code true} (server-declared,
 *       UC-55) — the standard in-app wizard, NOT the read-only "answer in tmux" fallback — keyed to
 *       the pane signal's {@code promptKey}.</li>
 *   <li><b>UC-75 spinner machinery.</b> A pending prompt parks the turn at {@code IDLE} (at-rest,
 *       not a perpetual "Working…" spinner); submitting an answer immediately flips the spinner to
 *       {@code WORKING} (and arms the answer watchdog); the forward turn then dismisses the sheet
 *       and recovers the spinner to {@code IDLE}.</li>
 *   <li><b>UC-57 selected==sent.</b> The option the user actually tapped (a NON-first option) is
 *       exactly what the server received, read back off the {@code answer-echo} frame on the wire.</li>
 * </ol>
 */
@RunWith(AndroidJUnit4::class)
class PaneSignalPendingQuestionGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun hostSheet(session: GateHarness.GateSession, sheet: PendingSheet.Questions) {
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

    @Test
    fun paneSignalPendingQuestion_isInAppAnswerable_andSendsSelected() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_PENDING_QUESTION)
        try {
            // Raised purely by the production `__ctrl__ pending-question` pane signal (no tool call).
            val sheet = GateHarness.awaitQuestionSheet(session)

            // (1) Pending-question indicator + in-app answerable (NOT the "answer in tmux" fallback),
            //     keyed to the pane signal's promptKey "pq-single".
            assertTrue("pane-signal question is in-app answerable, not the tmux read-only fallback", sheet.answerable)
            assertEquals("the sheet is keyed to the pane-signal promptKey", "pq-single", sheet.questionUuid)
            assertEquals("the pane signal raises exactly one question", 1, sheet.questions.size)
            assertTrue("the question is single-select", !sheet.questions[0].multiSelect)
            assertTrue("fixture offers ≥3 options so 'not first' is meaningful", sheet.questions[0].options.size >= 3)

            // (1b) UC-75 — a pending prompt is AT REST: the pane signal parks the spinner at IDLE
            //      rather than leaving a perpetual "Working…".
            assertEquals(
                "UC-75 — a pending pane prompt parks the spinner at IDLE (at-rest, answerable)",
                TurnPhase.IDLE,
                session.controller.turnPhase.value,
            )

            val collector = GateHarness.EchoCollector(session)
            hostSheet(session, sheet)

            // Tap the SECOND option (index 1) — deliberately NOT the first-visible one.
            composeTestRule.onNodeWithTag(QuestionTestTags.option(1)).performClick()
            composeTestRule.onNodeWithTag(QuestionTestTags.SUBMIT).performClick()

            // (2) UC-75 spinner machinery: submitting shows the working spinner immediately —
            //     synchronous on the main thread, before any network round-trip can flip it back.
            assertEquals(
                "UC-75 — submitting the answer flips the spinner to WORKING",
                TurnPhase.WORKING,
                session.controller.turnPhase.value,
            )
            composeTestRule.waitForIdle()

            // (3) UC-57 — the option actually tapped is exactly what the server received on the wire.
            composeTestRule.waitUntil(90_000) { collector.received().isNotEmpty() }
            val echo = collector.received().first()
            assertEquals("UC-57 — only the tapped option index is transmitted", listOf(1), echo.selections)
            assertEquals("single-select carries no free text", "", echo.freeText)
            assertEquals("echo correlates to the pane-signal question", sheet.questionUuid, echo.questionUuid)
            assertEquals(0, echo.questionIndex)
            collector.stop()

            // (4) The forward turn completes: the tool_result for "pq-single" dismisses the sheet and
            //     the UC-75 spinner recovers to IDLE — full at-rest → working → idle lifecycle.
            composeTestRule.waitUntil(90_000) { session.controller.pendingSheet.value == null }
            composeTestRule.waitUntil(90_000) { session.controller.turnPhase.value == TurnPhase.IDLE }
        } finally {
            session.close()
        }
    }
}
