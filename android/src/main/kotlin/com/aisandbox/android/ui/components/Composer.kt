package com.aisandbox.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.aisandbox.android.ui.testtags.ComposerTestTags
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.SurfaceLow

/**
 * UC-37 — the local native composer (AC7). A real Compose text field with full
 * IME/autocorrect/prediction and NO per-keystroke round-trip (the raw PTY is
 * never rendered in this mode), so there is no echo lag. Supports multiline
 * input (AC9); a tap on Send submits + clears.
 *
 * <p>[enabled] is wired to "no pending question" — while a [QuestionSheet] is up
 * the composer is locked (AC12) so a competing/double submit can't race the
 * answer.
 *
 * <p>UC-60 — [readOnly] is set when a background-subagent pill is the selected
 * target. A subagent is a read-only view (it has no pane to inject into; the
 * server hard-blocks any input to a `subagent:` id), so the composer is disabled
 * with an explanatory placeholder rather than the "answer the question" one.
 */
@Composable
fun Composer(
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    // UC-99 — testTags are parametrized (defaults = the conversation composer's
    // [ComposerTestTags]) so the SAME component can be reused as the terminal
    // (tmux phone-view) input surface with its own stable tags
    // ([com.aisandbox.android.ui.testtags.TerminalComposerTestTags]) without the
    // two surfaces colliding on a single tag when both are in the tree.
    inputTestTag: String = ComposerTestTags.INPUT,
    sendTestTag: String = ComposerTestTags.SEND,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val placeholder = remember(enabled, readOnly) {
        when {
            readOnly -> "Viewing a subagent — read-only"
            enabled -> "Message"
            else -> "Answer the question above to continue"
        }
    }

    Surface(modifier = modifier.fillMaxWidth(), color = SurfaceLow) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp).testTag(inputTestTag),
                enabled = enabled,
                placeholder = { Text(placeholder, color = OnSurfaceMuted) },
                // Multiline (AC9): allow newlines; Send is an explicit button so the
                // Enter key inserts a newline rather than submitting.
                singleLine = false,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
            )
            IconButton(
                onClick = {
                    val toSend = text
                    if (enabled && toSend.isNotBlank()) {
                        onSubmit(toSend)
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.size(48.dp).testTag(sendTestTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send",
                    tint = if (enabled && text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        OnSurfaceMuted
                    },
                )
            }
        }
    }
}
