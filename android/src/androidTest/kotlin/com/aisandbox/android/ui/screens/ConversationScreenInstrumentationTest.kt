package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.conversation.ConvOption
import com.aisandbox.android.conversation.ConvQuestion
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.ui.components.Composer
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-37 — instrumented (emulator-tier) coverage for the structured conversation
 * view's interactive surfaces: the local [Composer] (AC7/AC9/AC12 — submit + the
 * lock while a question is pending) and the [QuestionSheet] (AC10/AC11 — option
 * selection and the always-present free-text "Other"). Drives the public
 * composables directly (the full [ConversationScreen] resolves a
 * {@code ConversationViewModel} from the live {@code AppContainer}, which is not
 * a unit/instrumentation seam) on a real headless emulator.
 *
 * <p>The single-tap → conversation vs long-press → terminal routing (AC1/AC2) is
 * covered by {@code SessionsScreenInstrumentationTest}; the turn-lifecycle
 * spinner and frame handling (AC14/AC15/AC18/AC22) are covered by the JVM
 * {@code ConversationControllerTest}. This class is the device-realistic mirror
 * of the JVM {@code ConversationUiStateTest}; if the emulator cannot be brought
 * up, the JVM tests still execute these behaviours.
 */
@RunWith(AndroidJUnit4::class)
class ConversationScreenInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun composer_submit_fires_onSubmit_with_typed_text() {
        var submitted: String? = null
        composeTestRule.setContent {
            AiSandboxTheme { Composer(enabled = true, onSubmit = { submitted = it }) }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("ship it")
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("ship it", submitted)
    }

    @Test
    fun composer_is_locked_while_a_question_is_pending() {
        var submitted: String? = null
        composeTestRule.setContent {
            AiSandboxTheme { Composer(enabled = false, onSubmit = { submitted = it }) }
        }

        composeTestRule.onNodeWithText("Answer the question above to continue").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        assertNull(submitted)
    }

    @Test
    fun question_sheet_single_select_submits_chosen_index() {
        var selections: List<Int>? = null
        val sheet = PendingSheet.Questions(
            questionUuid = "tuQ",
            questions = listOf(
                ConvQuestion(
                    question = "Pick a color",
                    header = "Color",
                    multiSelect = false,
                    options = listOf(ConvOption("Red", "warm"), ConvOption("Blue", "cool")),
                ),
            ),
        )
        composeTestRule.setContent {
            AiSandboxTheme { QuestionSheet(sheet = sheet, onSubmit = { _, _, sel, _ -> selections = sel }) }
        }

        composeTestRule.onNodeWithText("Blue").performClick()
        composeTestRule.onNodeWithText("Send answer").performClick()

        assertEquals(listOf(1), selections)
    }

    @Test
    fun question_sheet_free_text_other_submits_other_index_and_text() {
        var selections: List<Int>? = null
        var freeText: String? = null
        val sheet = PendingSheet.Questions(
            questionUuid = "tuQ",
            questions = listOf(
                ConvQuestion(
                    question = "Pick a color",
                    header = "Color",
                    multiSelect = false,
                    options = listOf(ConvOption("Red", ""), ConvOption("Blue", "")),
                ),
            ),
        )
        composeTestRule.setContent {
            AiSandboxTheme {
                QuestionSheet(sheet = sheet, onSubmit = { _, _, sel, free ->
                    selections = sel
                    freeText = free
                })
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("teal")
        composeTestRule.onNodeWithText("Send answer").performClick()

        // Other index = optionCount (2).
        assertEquals(listOf(2), selections)
        assertEquals("teal", freeText)
    }

    @Test
    fun plan_sheet_approve_submits_selection_zero() {
        var selections: List<Int>? = null
        val sheet = PendingSheet.Plan(questionUuid = "tuP", plan = "1. do a\n2. do b")
        composeTestRule.setContent {
            AiSandboxTheme { QuestionSheet(sheet = sheet, onSubmit = { _, _, sel, _ -> selections = sel }) }
        }

        composeTestRule.onNodeWithText("Approve").performClick()
        assertTrue(selections == listOf(0))
    }
}
