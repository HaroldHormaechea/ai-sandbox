package com.aisandbox.android.gate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * UC-85 / UC-43 — the multi-question {@code AskUserQuestion} leg of the deterministic gate.
 *
 * <p>Opens the multi-question synthetic replay session, lets the fixture raise a 2-question sheet,
 * then drives the REAL paged {@code QuestionSheet} by stable {@code testTag} (answer Q1 → Next →
 * answer Q2 → Submit all). It asserts on the wire that EACH question maps to its OWN
 * {@code answer-echo} frame with the correct {@code questionIndex} and the exact selections tapped,
 * and that the conversation RESUMES cleanly afterwards (the pending sheet clears and the spinner
 * returns to idle as the recorded post-answer turn-end replays).
 */
@RunWith(AndroidJUnit4::class)
class MultiQuestionGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun multiQuestion_eachQuestionMapsToItsOwnAnswerFrame_andConversationResumes() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_MULTI_QUESTION)
        try {
            val sheet = GateHarness.awaitQuestionSheet(session)
            assertTrue("multi-question fixture must carry ≥2 questions", sheet.questions.size >= 2)
            val q2Multi = sheet.questions[1].multiSelect

            val collector = GateHarness.EchoCollector(session.client())
            composeTestRule.setContent {
                AiSandboxTheme {
                    QuestionSheet(
                        sheet = sheet,
                        onSubmit = { uuid, idx, sels, ft -> session.controller.submitAnswer(uuid, idx, sels, ft) },
                        onSubmitBatch = { uuid, items -> session.controller.submitAnswerBatch(uuid, items) },
                    )
                }
            }

            // Q1 (paged): choose the first option, then advance.
            composeTestRule.onNodeWithTag(QuestionTestTags.PROGRESS).assertExists()
            composeTestRule.onNodeWithTag(QuestionTestTags.option(0)).performClick()
            composeTestRule.onNodeWithTag(QuestionTestTags.NEXT).performClick()
            composeTestRule.waitForIdle()

            // Q2: choose option 1 (and option 0 too if it is multi-select), then submit the batch.
            composeTestRule.onNodeWithTag(QuestionTestTags.option(1)).performClick()
            if (q2Multi) composeTestRule.onNodeWithTag(QuestionTestTags.option(0)).performClick()
            composeTestRule.onNodeWithTag(QuestionTestTags.SUBMIT).performClick()
            composeTestRule.waitForIdle()

            // UC-43 — one answer-echo per question, each correlated by questionIndex.
            composeTestRule.waitUntil(25_000) { collector.received().size >= 2 }
            val byIndex = collector.received().associateBy { it.questionIndex }
            assertEquals("Q1 maps to its own frame at index 0", listOf(0), byIndex[0]?.selections)
            val expectedQ2 = if (q2Multi) listOf(0, 1) else listOf(1)
            assertEquals("Q2 maps to its own frame at index 1", expectedQ2, byIndex[1]?.selections?.sorted())
            assertEquals("both echoes share the question's uuid", sheet.questionUuid, byIndex[0]?.questionUuid)
            assertEquals(sheet.questionUuid, byIndex[1]?.questionUuid)
            collector.stop()

            // The conversation resumes: the recorded post-answer turn-end clears the sheet + spinner.
            composeTestRule.waitUntil(25_000) {
                session.controller.pendingSheet.value == null && session.controller.turnPhase.value == TurnPhase.IDLE
            }
            assertEquals(null, session.controller.pendingSheet.value)
        } finally {
            session.close()
        }
    }
}
