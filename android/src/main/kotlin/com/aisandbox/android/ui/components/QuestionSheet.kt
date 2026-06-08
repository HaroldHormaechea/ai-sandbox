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
 * <p>An `AskUserQuestion` may carry several questions; the TUI resolves them one
 * at a time and the server's answer frame carries a single `questionIndex`, so
 * this sheet drives the first unanswered question (index 0). The plan-approval
 * variant renders the plan text with Approve / Reject.
 */
@Composable
fun QuestionSheet(
    sheet: PendingSheet,
    onSubmit: (questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        color = SurfaceLow,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (sheet) {
                is PendingSheet.Questions -> QuestionBody(sheet, onSubmit)
                is PendingSheet.Plan -> PlanBody(sheet, onSubmit)
            }
        }
    }
}

@Composable
private fun QuestionBody(
    sheet: PendingSheet.Questions,
    onSubmit: (String, Int, List<Int>, String) -> Unit,
) {
    val q: ConvQuestion = sheet.questions.firstOrNull() ?: run {
        Text("Question", color = OnSurface)
        return
    }
    val selected = remember(sheet.questionUuid) { mutableStateMapOf<Int, Boolean>() }
    var otherText by remember(sheet.questionUuid) { mutableStateOf("") }

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
                checked = selected[idx] == true,
                multiSelect = q.multiSelect,
                onToggle = {
                    if (q.multiSelect) {
                        selected[idx] = !(selected[idx] ?: false)
                    } else {
                        selected.clear()
                        selected[idx] = true
                    }
                },
            )
        }
        // The always-present "Other" free-text option (AC10).
        OutlinedTextField(
            value = otherText,
            onValueChange = { otherText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Other", color = OnSurfaceMuted) },
            placeholder = { Text("Type a custom answer", color = OnSurfaceMuted) },
            singleLine = false,
            maxLines = 3,
        )
    }

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
