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
import com.aisandbox.android.conversation.AnswerItem
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

    // ──────────────────────── UC-43 — multi-question paged sheet (AC9) ────────────────────────

    private fun threeQuestionSheet() = PendingSheet.Questions(
        questionUuid = "tuQ",
        questions = listOf(
            ConvQuestion(
                question = "Pick a color",
                header = "Color",
                multiSelect = false,
                options = listOf(ConvOption("Red", ""), ConvOption("Blue", "")),
            ),
            ConvQuestion(
                question = "Pick letters",
                header = "Letters",
                multiSelect = true,
                options = listOf(ConvOption("Xray", ""), ConvOption("Yankee", "")),
            ),
            ConvQuestion(
                question = "Pick a city",
                header = "City",
                multiSelect = false,
                options = listOf(ConvOption("Papa", ""), ConvOption("Quebec", "")),
            ),
        ),
    )

    @Test
    fun multi_question_sheet_pages_navigates_gates_submit_and_batches_in_index_order() {
        // AC2/AC3/AC4 — a 3-question ask renders paged ("X of N"); Back/Next navigate; each
        // question is answerable (single-select, multiSelect); submit is gated until ALL are
        // answered; the batch resolves with one item per question in questionIndex order.
        var batchItems: List<AnswerItem>? = null
        var singleCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                QuestionSheet(
                    sheet = threeQuestionSheet(),
                    onSubmit = { _, _, _, _ -> singleCalled = true },
                    onSubmitBatch = { _, items -> batchItems = items },
                )
            }
        }

        // Page 1 of 3 — single-select. "Next" is shown (not "Submit all").
        composeTestRule.onNodeWithText("Question 1 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Blue").performClick() // Q0 → index 1
        composeTestRule.onNodeWithText("Next").performClick()

        // Page 2 of 3 — multiSelect, choose both.
        composeTestRule.onNodeWithText("Question 2 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Xray").performClick() // Q1 → index 0
        composeTestRule.onNodeWithText("Yankee").performClick() // Q1 → index 1
        composeTestRule.onNodeWithText("Next").performClick()

        // Page 3 of 3 — submit is GATED (Q2 unanswered) …
        composeTestRule.onNodeWithText("Question 3 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit all").assertIsNotEnabled()

        // … Back/Next round-trip must not lose the buffered answers …
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithText("Question 2 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Question 3 of 3").assertIsDisplayed()

        // … answer Q2 → submit enabled → submit.
        composeTestRule.onNodeWithText("Papa").performClick() // Q2 → index 0
        composeTestRule.onNodeWithText("Submit all").performClick()

        assertTrue("single onSubmit must not fire for a multi-question sheet", !singleCalled)
        val items = batchItems
        assertTrue("batch must be submitted", items != null)
        assertEquals(3, items!!.size)
        assertEquals(listOf(0, 1, 2), items.map { it.questionIndex })
        assertEquals(listOf(1), items[0].selections)
        assertEquals(listOf(0, 1), items[1].selections)
        assertEquals(listOf(0), items[2].selections)
        items.forEach { assertEquals("", it.freeText) }
    }

    @Test
    fun single_question_sheet_uses_the_single_answer_path_not_the_batch_path() {
        // AC5 regression — a single-question ask submits via the existing single `answer` path
        // (onSubmit, questionIndex 0) and NEVER the batch path.
        var singleSelections: List<Int>? = null
        var singleIndex: Int? = null
        var batchCalled = false
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
                QuestionSheet(
                    sheet = sheet,
                    onSubmit = { _, idx, sel, _ ->
                        singleIndex = idx
                        singleSelections = sel
                    },
                    onSubmitBatch = { _, _ -> batchCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Blue").performClick()
        composeTestRule.onNodeWithText("Send answer").performClick()

        assertEquals(listOf(1), singleSelections)
        assertEquals(0, singleIndex)
        assertTrue("single-question sheet must not use the batch path", !batchCalled)
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

    // ──────────────────────── UC-44 — single-select radio/"Other" mutual exclusivity (AC5) ────────────────────────

    @Test
    fun single_select_typing_other_after_a_radio_choice_clears_the_radio_so_other_wins() {
        // AC5 — on a single-select question a radio choice and a custom "Other" answer are mutually
        // exclusive. Choosing a radio THEN typing an "Other" must clear the radio, so exactly the
        // "Other" answer is submitted (selections == [otherIndex], the radio index is gone).
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

        composeTestRule.onNodeWithText("Blue").performClick() // radio index 1
        composeTestRule.onNode(hasSetTextAction()).performTextInput("teal") // clears the radio (UC-44)
        composeTestRule.onNodeWithText("Send answer").performClick()

        // Other won: only the Other index (== optionCount == 2) is submitted, never alongside the radio.
        assertEquals(listOf(2), selections)
        assertEquals("teal", freeText)
    }

    @Test
    fun single_select_choosing_a_radio_after_typing_other_clears_the_other_so_radio_wins() {
        // AC5 (reverse direction) — typing "Other" THEN choosing a radio clears the free text, so the
        // radio choice is submitted alone (selections == [radioIndex], freeText == "").
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
        composeTestRule.onNodeWithText("Blue").performClick() // clears the typed Other (UC-44)
        composeTestRule.onNodeWithText("Send answer").performClick()

        assertEquals(listOf(1), selections) // radio index 1, no Other index appended
        assertEquals("", freeText)
    }

    @Test
    fun multi_select_radio_and_other_combine_rather_than_excluding() {
        // AC5 — the mutual-exclusivity rule is single-select ONLY. A multiSelect question still
        // COMBINES a checked option with a custom "Other" (both are submitted together).
        var selections: List<Int>? = null
        var freeText: String? = null
        val sheet = PendingSheet.Questions(
            questionUuid = "tuQ",
            questions = listOf(
                ConvQuestion(
                    question = "Pick letters",
                    header = "Letters",
                    multiSelect = true,
                    options = listOf(ConvOption("Xray", ""), ConvOption("Yankee", "")),
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

        composeTestRule.onNodeWithText("Xray").performClick() // index 0
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Zulu")
        composeTestRule.onNodeWithText("Send answer").performClick()

        // multiSelect keeps BOTH: the checked option AND the Other index (== optionCount == 2).
        assertEquals(listOf(0, 2), selections)
        assertEquals("Zulu", freeText)
    }

    // ──────────────────────── UC-44 — "Other" inside a multi-question batch (AC9) ────────────────────────

    @Test
    fun multi_question_batch_carries_other_free_text_for_single_and_multi_select_questions() {
        // AC9 — an instrumented exercise of an "Other" answer WITHIN a multi-question batch (the gap
        // that let the bug ship). Q0 single-select answered via "Other"; Q1 multiSelect answered with
        // a checked option PLUS "Other". The submitted batch must carry each question's free text and
        // append the Other index (== that question's option count) to its selections, in index order.
        var batchItems: List<AnswerItem>? = null
        val sheet = PendingSheet.Questions(
            questionUuid = "tuQ",
            questions = listOf(
                ConvQuestion(
                    question = "Pick a color",
                    header = "Color",
                    multiSelect = false,
                    options = listOf(ConvOption("Red", ""), ConvOption("Blue", "")),
                ),
                ConvQuestion(
                    question = "Pick letters",
                    header = "Letters",
                    multiSelect = true,
                    options = listOf(ConvOption("Xray", ""), ConvOption("Yankee", "")),
                ),
            ),
        )
        composeTestRule.setContent {
            AiSandboxTheme {
                QuestionSheet(
                    sheet = sheet,
                    onSubmit = { _, _, _, _ -> },
                    onSubmitBatch = { _, items -> batchItems = items },
                )
            }
        }

        // Page 1 of 2 — single-select answered via "Other".
        composeTestRule.onNodeWithText("Question 1 of 2").assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("mauve")
        composeTestRule.onNodeWithText("Next").performClick()

        // Page 2 of 2 — multiSelect: a checked option PLUS "Other".
        composeTestRule.onNodeWithText("Question 2 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Xray").performClick() // index 0
        composeTestRule.onNode(hasSetTextAction()).performTextInput("lime")
        composeTestRule.onNodeWithText("Submit all").performClick()

        val items = batchItems
        assertTrue("batch must be submitted", items != null)
        assertEquals(2, items!!.size)
        assertEquals(listOf(0, 1), items.map { it.questionIndex })
        // Q0 single-select via Other → only the Other index (== optionCount == 2); free text carried.
        assertEquals(listOf(2), items[0].selections)
        assertEquals("mauve", items[0].freeText)
        // Q1 multiSelect → the checked option AND the Other index; free text carried.
        assertEquals(listOf(0, 2), items[1].selections)
        assertEquals("lime", items[1].freeText)
    }
}
