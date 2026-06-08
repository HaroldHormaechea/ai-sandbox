package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.aisandbox.android.conversation.ConvOption
import com.aisandbox.android.conversation.ConvQuestion
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.ui.components.Composer
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UC-37 — Compose UI-state coverage for the structured view's two interactive
 * surfaces, the [Composer] (AC7/AC9/AC12) and the [QuestionSheet]
 * (AC10/AC11/AC13). Runs as a Robolectric JVM test (no emulator), mirroring
 * {@code ServerIdentityChangedScreenTest}, so it executes in the normal
 * {@code :android:test} run. The full-screen wiring (single-tap/long-press
 * routing, end-to-end submit) is additionally authored as an instrumented test
 * for the emulator tier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp")
class ConversationUiStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ──────────────────────── Composer (AC7/AC9/AC12) ────────────────────────

    @Test
    fun composer_submit_fires_onSubmit_with_the_typed_text() {
        var submitted: String? = null
        composeRule.setContent {
            AiSandboxTheme { Composer(enabled = true, onSubmit = { submitted = it }) }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("hello world")
        composeRule.onNodeWithContentDescription("Send").performClick()

        assertThat(submitted).isEqualTo("hello world")
    }

    @Test
    fun composer_locked_while_a_question_is_pending_does_not_submit() {
        // AC12 — enabled=false models "a sheet is pending"; the composer is locked.
        var submitted: String? = null
        composeRule.setContent {
            AiSandboxTheme { Composer(enabled = false, onSubmit = { submitted = it }) }
        }

        // The lock hint is shown and the Send button is not enabled.
        composeRule.onNodeWithText("Answer the question above to continue").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        assertThat(submitted).isNull()
    }

    // ──────────────────────── QuestionSheet single-select (AC10/AC11) ─────────

    @Test
    fun question_sheet_single_select_submits_the_chosen_option_index() {
        var captured: Triple<List<Int>, String, String>? = null
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
        composeRule.setContent {
            AiSandboxTheme {
                QuestionSheet(sheet = sheet, onSubmit = { uuid, _, sel, free -> captured = Triple(sel, free, uuid) })
            }
        }

        composeRule.onNodeWithText("Blue").performClick()
        composeRule.onNodeWithText("Send answer").performClick()

        assertThat(captured).isNotNull
        assertThat(captured!!.first).containsExactly(1) // index of "Blue"
        assertThat(captured!!.second).isEmpty() // no free text
        assertThat(captured!!.third).isEqualTo("tuQ")
    }

    // ──────────────────────── QuestionSheet multi-select (AC10/AC11) ──────────

    @Test
    fun question_sheet_multi_select_submits_every_toggled_option() {
        var selections: List<Int>? = null
        val sheet = PendingSheet.Questions(
            questionUuid = "tuQ",
            questions = listOf(
                ConvQuestion(
                    question = "Pick toppings",
                    header = "Toppings",
                    multiSelect = true,
                    options = listOf(ConvOption("Cheese", ""), ConvOption("Ham", ""), ConvOption("Egg", "")),
                ),
            ),
        )
        composeRule.setContent {
            AiSandboxTheme {
                QuestionSheet(sheet = sheet, onSubmit = { _, _, sel, _ -> selections = sel })
            }
        }

        composeRule.onNodeWithText("Cheese").performClick()
        composeRule.onNodeWithText("Egg").performClick()
        composeRule.onNodeWithText("Send answer").performClick()

        assertThat(selections).containsExactlyInAnyOrder(0, 2)
    }

    // ──────────────────────── QuestionSheet free-text "Other" (AC10) ──────────

    @Test
    fun question_sheet_free_text_other_submits_with_the_other_index_and_text() {
        var captured: Pair<List<Int>, String>? = null
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
        composeRule.setContent {
            AiSandboxTheme {
                QuestionSheet(sheet = sheet, onSubmit = { _, _, sel, free -> captured = sel to free })
            }
        }

        // Only the always-present "Other" free-text field is filled (no option tapped).
        composeRule.onNode(hasSetTextAction()).performTextInput("teal")
        composeRule.onNodeWithText("Send answer").performClick()

        assertThat(captured).isNotNull
        // Other index = optionCount (2), per the sheet's contract with the server.
        assertThat(captured!!.first).containsExactly(2)
        assertThat(captured!!.second).isEqualTo("teal")
    }

    // ──────────────────────── QuestionSheet plan approval (AC13) ──────────────

    @Test
    fun plan_sheet_approve_submits_selection_zero() {
        var selections: List<Int>? = null
        val sheet = PendingSheet.Plan(questionUuid = "tuP", plan = "1. do a\n2. do b")
        composeRule.setContent {
            AiSandboxTheme {
                QuestionSheet(sheet = sheet, onSubmit = { _, _, sel, _ -> selections = sel })
            }
        }

        composeRule.onNodeWithText("Approve").performClick()
        assertThat(selections).containsExactly(0)
    }

    @Test
    fun plan_sheet_keep_planning_submits_selection_one() {
        var selections: List<Int>? = null
        val sheet = PendingSheet.Plan(questionUuid = "tuP", plan = "the plan")
        composeRule.setContent {
            AiSandboxTheme {
                QuestionSheet(sheet = sheet, onSubmit = { _, _, sel, _ -> selections = sel })
            }
        }

        composeRule.onNodeWithText("Keep planning").performClick()
        assertThat(selections).containsExactly(1)
    }
}
