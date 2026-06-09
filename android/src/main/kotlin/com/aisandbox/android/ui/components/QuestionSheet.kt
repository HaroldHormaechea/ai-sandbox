package com.aisandbox.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aisandbox.android.conversation.AnswerItem
import com.aisandbox.android.conversation.ConvQuestion
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow

/**
 * UC-37 — the question / plan-approval sheet (AC10/AC13), rendered as a distinct
 * pinned card above the composer (NOT swipe-dismissible: while it is up the
 * composer is locked, AC12). Renders one control per option, multi-select where
 * `multiSelect`, and a free-text field for the always-present "Other" option.
 * Submitting sends a structured answer; the parent clears the sheet (AC11).
 *
 * <p>UC-43 — an `AskUserQuestion` may carry several questions. A SINGLE-question
 * sheet (the common case) renders one question and submits the existing single
 * `answer` frame (index 0), unchanged. A MULTI-question sheet (N&gt;1) is rendered
 * **paged one-at-a-time** with a "X of N" progress indicator and Back/Next
 * navigation: each question's answer is buffered locally (keyed by `questionIndex`)
 * and the whole batch is submitted together via [onSubmitBatch] only once every
 * question is answered. The plan-approval variant renders the plan text with
 * Approve / Reject.
 */
@Composable
fun QuestionSheet(
    sheet: PendingSheet,
    onSubmit: (questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String) -> Unit,
    modifier: Modifier = Modifier,
    onSubmitBatch: (questionUuid: String, items: List<AnswerItem>) -> Unit = { _, _ -> },
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        color = SurfaceLow,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (sheet) {
                is PendingSheet.Questions ->
                    if (sheet.questions.size > 1) {
                        PagedQuestionBody(sheet, onSubmitBatch)
                    } else {
                        SingleQuestionBody(sheet, onSubmit)
                    }
                is PendingSheet.Plan -> PlanBody(sheet, onSubmit)
            }
        }
    }
}

/**
 * The shared rendering of ONE question — header, prompt, the option list (radio /
 * checkbox per [ConvQuestion.multiSelect]), and the always-present "Other"
 * free-text field (AC10). Used by both the single-question and paged
 * (multi-question) bodies so they stay visually identical. The caller owns the
 * selection/free-text state; this composable is pure rendering.
 */
@Composable
private fun QuestionContent(
    q: ConvQuestion,
    isChecked: (Int) -> Boolean,
    onToggle: (Int) -> Unit,
    otherText: String,
    onOtherChange: (String) -> Unit,
) {
    if (q.header.isNotBlank()) {
        Text(q.header, style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
        Spacer(Modifier.height(2.dp))
    }
    Text(q.question, style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))

    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        q.options.forEachIndexed { idx, opt ->
            OptionRow(
                label = opt.label,
                description = opt.description,
                checked = isChecked(idx),
                multiSelect = q.multiSelect,
                onToggle = { onToggle(idx) },
            )
        }
        // The always-present "Other" free-text option (AC10).
        OutlinedTextField(
            value = otherText,
            onValueChange = onOtherChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Other", color = OnSurfaceMuted) },
            placeholder = { Text("Type a custom answer", color = OnSurfaceMuted) },
            singleLine = false,
            maxLines = 3,
        )
    }
}

/**
 * The common single-question sheet (AC5 — unchanged behavior). Submits the
 * existing single `answer` frame with `questionIndex = 0`; the "Other" free-text,
 * when used, occupies the option index equal to the option count.
 */
@Composable
private fun SingleQuestionBody(
    sheet: PendingSheet.Questions,
    onSubmit: (String, Int, List<Int>, String) -> Unit,
) {
    val q: ConvQuestion = sheet.questions.firstOrNull() ?: run {
        Text("Question", color = OnSurface)
        return
    }
    val selected = remember(sheet.questionUuid) { mutableStateMapOf<Int, Boolean>() }
    var otherText by remember(sheet.questionUuid) { mutableStateOf("") }

    QuestionContent(
        q = q,
        isChecked = { selected[it] == true },
        onToggle = { idx ->
            if (q.multiSelect) {
                selected[idx] = !(selected[idx] ?: false)
            } else {
                selected.clear()
                selected[idx] = true
                otherText = "" // UC-44 — single-select: a radio choice and "Other" are mutually exclusive
            }
        },
        otherText = otherText,
        onOtherChange = {
            otherText = it
            // UC-44 — single-select: typing a non-blank "Other" clears the radio choice.
            if (!q.multiSelect && it.isNotBlank()) selected.clear()
        },
    )

    Spacer(Modifier.height(12.dp))
    val selectionIndices = selected.filterValues { it }.keys.sorted().toMutableList()
    val otherSelected = otherText.isNotBlank()
    val canSubmit = selectionIndices.isNotEmpty() || otherSelected
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(
            enabled = canSubmit,
            onClick = {
                val finalSelections = selectionIndices.toMutableList()
                if (otherSelected) finalSelections.add(q.options.size) // Other index = optionCount
                onSubmit(sheet.questionUuid, 0, finalSelections, if (otherSelected) otherText else "")
            },
        ) {
            Text("Send answer")
        }
    }
}

/**
 * UC-43 (AC2/AC3/AC4) — the multi-question (N>1) paged sheet. Shows ONE question
 * at a time with an "X of N" progress indicator and Back/Next navigation; each
 * question's selections + free text are buffered locally keyed by `questionIndex`,
 * and the batch is submitted (via [onSubmitBatch]) only once EVERY question is
 * answered. Each question's "Other" free-text, when used, occupies that question's
 * option-count index, exactly like the single-question path.
 */
@Composable
private fun PagedQuestionBody(
    sheet: PendingSheet.Questions,
    onSubmitBatch: (String, List<AnswerItem>) -> Unit,
) {
    val questions = sheet.questions
    val n = questions.size
    var current by remember(sheet.questionUuid) { mutableStateOf(0) }
    // Per-question buffered state, keyed by questionIndex.
    val selectionsByQ = remember(sheet.questionUuid) { mutableStateMapOf<Int, Set<Int>>() }
    val freeTextByQ = remember(sheet.questionUuid) { mutableStateMapOf<Int, String>() }

    val q = questions[current]
    val curSelections = selectionsByQ[current] ?: emptySet()
    val curOther = freeTextByQ[current] ?: ""

    fun answered(i: Int): Boolean =
        (selectionsByQ[i]?.isNotEmpty() == true) || (freeTextByQ[i]?.isNotBlank() == true)

    val allAnswered = (0 until n).all { answered(it) }

    Text(
        "Question ${current + 1} of $n",
        style = MaterialTheme.typography.labelMedium,
        color = OnSurfaceMuted,
    )
    Spacer(Modifier.height(6.dp))

    QuestionContent(
        q = q,
        isChecked = { curSelections.contains(it) },
        onToggle = { idx ->
            val existing = selectionsByQ[current] ?: emptySet()
            selectionsByQ[current] = if (q.multiSelect) {
                if (existing.contains(idx)) existing - idx else existing + idx
            } else {
                freeTextByQ[current] = "" // UC-44 — single-select: radio choice and "Other" are mutually exclusive
                setOf(idx)
            }
        },
        otherText = curOther,
        onOtherChange = { text ->
            freeTextByQ[current] = text
            // UC-44 — single-select: typing a non-blank "Other" clears the radio choice.
            if (!q.multiSelect && text.isNotBlank()) selectionsByQ[current] = emptySet()
        },
    )

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(enabled = current > 0, onClick = { current -= 1 }) {
            Text("Back")
        }
        if (current < n - 1) {
            Button(onClick = { current += 1 }) {
                Text("Next")
            }
        } else {
            Button(
                enabled = allAnswered,
                onClick = {
                    val items = (0 until n).map { i ->
                        val sels = (selectionsByQ[i] ?: emptySet()).sorted().toMutableList()
                        val ft = freeTextByQ[i] ?: ""
                        if (ft.isNotBlank()) sels.add(questions[i].options.size) // Other index = optionCount
                        AnswerItem(
                            questionIndex = i,
                            selections = sels,
                            freeText = if (ft.isNotBlank()) ft else "",
                        )
                    }
                    onSubmitBatch(sheet.questionUuid, items)
                },
            ) {
                Text("Submit all")
            }
        }
    }
}

@Composable
private fun PlanBody(
    sheet: PendingSheet.Plan,
    onSubmit: (String, Int, List<Int>, String) -> Unit,
) {
    Text("Plan approval", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
    Spacer(Modifier.height(6.dp))
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
        Text(
            sheet.plan.ifBlank { "Claude is asking to proceed with its plan." },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        OutlinedButton(onClick = { onSubmit(sheet.questionUuid, 0, listOf(1), "") }) {
            Text("Keep planning")
        }
        Button(onClick = { onSubmit(sheet.questionUuid, 0, listOf(0), "") }) {
            Text("Approve")
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String,
    checked: Boolean,
    multiSelect: Boolean,
    onToggle: () -> Unit,
) {
    val rowModifier = if (multiSelect) {
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
    } else {
        Modifier.fillMaxWidth().selectable(selected = checked, role = Role.RadioButton, onClick = onToggle)
    }
    Row(modifier = rowModifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (multiSelect) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        } else {
            RadioButton(selected = checked, onClick = onToggle)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
            }
        }
    }
}
