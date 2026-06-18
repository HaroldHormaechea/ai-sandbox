package com.aisandbox.android.ui.screens

import com.aisandbox.android.conversation.ConvOption
import com.aisandbox.android.conversation.ConvQuestion
import com.aisandbox.android.conversation.PendingSheet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UC-90 — JVM unit coverage for [questionShortLabel], the pure helper that derives the compact
 * header label shown on the collapsed [AnchoredQuestionBox] (AC4). It is a plain function over the
 * [PendingSheet] data model (no Compose), so it is exercised here on the JVM (fast, no emulator) as
 * the logic mirror of the instrumented collapse tests.
 *
 *  - A plan-approval sheet ⇒ "Plan approval".
 *  - A question group ⇒ the first question's title: header first, else the question text, else the
 *    caller-supplied pending fallback ("Question pending") when neither is concise.
 */
class ConversationQuestionShortLabelTest {

    private val pendingLabel = "Question pending"

    private fun q(header: String, question: String) =
        ConvQuestion(question = question, header = header, multiSelect = false, options = listOf(ConvOption("a", "")))

    private fun questions(vararg qs: ConvQuestion) =
        PendingSheet.Questions(questionUuid = "u", questions = qs.toList())

    @Test
    fun plan_sheet_label_is_plan_approval() {
        assertEquals("Plan approval", questionShortLabel(PendingSheet.Plan("u", "do a then b"), pendingLabel))
    }

    @Test
    fun question_group_uses_first_question_header_when_present() {
        assertEquals("Color", questionShortLabel(questions(q("Color", "Pick a color")), pendingLabel))
    }

    @Test
    fun question_group_falls_back_to_question_text_when_header_blank() {
        assertEquals("Pick a color", questionShortLabel(questions(q("", "Pick a color")), pendingLabel))
    }

    @Test
    fun question_group_falls_back_to_pending_label_when_header_and_question_blank() {
        assertEquals(pendingLabel, questionShortLabel(questions(q("", "")), pendingLabel))
    }

    @Test
    fun empty_question_group_falls_back_to_pending_label() {
        assertEquals(pendingLabel, questionShortLabel(questions(), pendingLabel))
    }

    @Test
    fun first_question_governs_even_when_later_questions_have_titles() {
        // The label is derived from the FIRST question only; a blank first question yields the
        // fallback even though a later question carries a header.
        assertEquals(
            pendingLabel,
            questionShortLabel(questions(q("", ""), q("Letters", "Pick letters")), pendingLabel),
        )
    }
}
