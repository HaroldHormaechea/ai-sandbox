package com.aisandbox.android.gate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.testtags.QuestionTestTags
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-85 — the single-{@code AskUserQuestion} leg of the deterministic on-device gate (AC-4/5).
 *
 * <p>Each test opens the corresponding synthetic replay session over the REAL mTLS/WebSocket
 * stack, lets the committed fixture raise the question (proving the deterministic, no-live-Claude
 * raise of an {@code AskUserQuestion}, AC-1), renders the REAL {@code QuestionSheet}, drives it by
 * stable {@code testTag} ONLY (no coordinate taps), and then asserts UC-57 ON THE WIRE: the
 * option(s) the user actually tapped are exactly what the server received, read back off the
 * {@code answer-echo} frame — NOT the first-visible option, NOT just the UI state.
 *
 * <ul>
 *   <li>single-select (UC-55) — a NON-first option is chosen; the echo carries only that index.</li>
 *   <li>multi-select (UC-55) — two non-adjacent options; the echo carries both indices.</li>
 *   <li>"Other" free-text (UC-75) — a typed custom answer; the echo carries the Other index
 *       (= option count) and the exact free text.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class AskUserQuestionGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun hostSheet(session: GateHarness.GateSession, sheet: com.aisandbox.android.conversation.PendingSheet) {
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
    fun singleSelect_sendsTheSelectedOption_notFirstVisible() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_SINGLE_SELECT)
        try {
            val sheet = GateHarness.awaitQuestionSheet(session)
            assertEquals("single-select fixture has exactly one question", 1, sheet.questions.size)
            assertTrue("single-select fixture is NOT multiSelect", !sheet.questions[0].multiSelect)
            assertTrue("fixture must offer ≥3 options so 'not first' is meaningful", sheet.questions[0].options.size >= 3)

            val collector = GateHarness.EchoCollector(session)
            hostSheet(session, sheet)

            // Tap the SECOND option (index 1) — deliberately NOT the first-visible one.
            composeTestRule.onNodeWithTag(QuestionTestTags.option(1)).performClick()
            composeTestRule.onNodeWithTag(QuestionTestTags.SUBMIT).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.waitUntil(90_000) { collector.received().isNotEmpty() }
            val echo = collector.received().first()
            assertEquals("UC-57 — only the tapped option index is transmitted", listOf(1), echo.selections)
            assertEquals("single-select carries no free text", "", echo.freeText)
            assertEquals("echo correlates to the raised question", sheet.questionUuid, echo.questionUuid)
            assertEquals(0, echo.questionIndex)
            collector.stop()
        } finally {
            session.close()
        }
    }

    @Test
    fun multiSelect_sendsExactlyTheCheckedOptions() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_MULTI_SELECT)
        try {
            val sheet = GateHarness.awaitQuestionSheet(session)
            assertTrue("multi-select fixture is multiSelect", sheet.questions[0].multiSelect)
            assertTrue("fixture must offer ≥3 options", sheet.questions[0].options.size >= 3)

            val collector = GateHarness.EchoCollector(session)
            hostSheet(session, sheet)

            // Check the first and third options (0 and 2) — skip the middle one.
            composeTestRule.onNodeWithTag(QuestionTestTags.option(0)).performClick()
            composeTestRule.onNodeWithTag(QuestionTestTags.option(2)).performClick()
            composeTestRule.onNodeWithTag(QuestionTestTags.SUBMIT).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.waitUntil(90_000) { collector.received().isNotEmpty() }
            val echo = collector.received().first()
            assertEquals("UC-57 — exactly the checked indices are transmitted", listOf(0, 2), echo.selections.sorted())
            assertEquals("", echo.freeText)
            assertEquals(sheet.questionUuid, echo.questionUuid)
            collector.stop()
        } finally {
            session.close()
        }
    }

    @Test
    fun otherFreeText_sendsTheOtherIndexAndTypedText() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_OTHER_FREE_TEXT)
        try {
            val sheet = GateHarness.awaitQuestionSheet(session)
            val optionCount = sheet.questions[0].options.size
            assertTrue("other fixture must offer ≥1 option", optionCount >= 1)

            val collector = GateHarness.EchoCollector(session)
            hostSheet(session, sheet)

            val custom = "ap-southeast-2"
            composeTestRule.onNodeWithTag(QuestionTestTags.OTHER_FIELD).performTextInput(custom)
            composeTestRule.onNodeWithTag(QuestionTestTags.SUBMIT).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.waitUntil(90_000) { collector.received().isNotEmpty() }
            val echo = collector.received().first()
            // The "Other" choice occupies the index equal to the option count (UC-75).
            assertEquals("UC-75 — Other selection index is the option count", listOf(optionCount), echo.selections)
            assertEquals("UC-75 — the exact typed free text is transmitted", custom, echo.freeText)
            assertEquals(sheet.questionUuid, echo.questionUuid)
            collector.stop()
        } finally {
            session.close()
        }
    }
}
