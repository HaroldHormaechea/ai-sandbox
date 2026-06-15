package com.aisandbox.android.ui.screens

import com.aisandbox.android.conversation.ToolDetailState
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * UC-81 (AC3/AC4) — pure JVM unit coverage for [toolDetailCopyText], the `internal`
 * helper that produces the EXACT string the tool/skill detail popup's **Copy** button
 * places on the clipboard. This is the contract behind AC3 ("copy the tool popup
 * information") and AC4 ("the FULL content, not a cropped version"): the function must
 * emit the complete, untruncated input AND output, labelled, with blank fields rendered
 * as `(empty)` to mirror the dialog's on-screen presentation.
 *
 * <p>Runs in the normal `:android:test` JVM run (no emulator, no Robolectric) because the
 * helper is pure string formatting with no Android/Compose dependency. The end-to-end
 * "click Copy → onCopy receives this string" wiring is pinned by the emulator-tier
 * `ConversationCopyInstrumentationTest.toolDetailDialog_copyButton_copiesFullInputAndOutput`.
 */
class ToolDetailCopyTextTest {

    @Test
    fun formatsFullInputAndOutputWithLabels() {
        val text = toolDetailCopyText(
            ToolDetailState.Loaded(input = "ls -la /tmp", result = "total 0\ndrwxr-xr-x", isError = false),
        )
        assertThat(text).isEqualTo("Input:\nls -la /tmp\n\nOutput:\ntotal 0\ndrwxr-xr-x")
    }

    @Test
    fun blankInputRendersEmptyPlaceholder() {
        val text = toolDetailCopyText(
            ToolDetailState.Loaded(input = "", result = "done", isError = false),
        )
        assertThat(text).isEqualTo("Input:\n(empty)\n\nOutput:\ndone")
    }

    @Test
    fun blankOutputRendersEmptyPlaceholder() {
        val text = toolDetailCopyText(
            ToolDetailState.Loaded(input = "echo hi", result = "", isError = false),
        )
        assertThat(text).isEqualTo("Input:\necho hi\n\nOutput:\n(empty)")
    }

    @Test
    fun whitespaceOnlyFieldsTreatedAsBlank() {
        // .ifBlank covers all-whitespace, not just empty (AC4 — nothing meaningful to copy).
        val text = toolDetailCopyText(
            ToolDetailState.Loaded(input = "   ", result = "\n\t ", isError = false),
        )
        assertThat(text).isEqualTo("Input:\n(empty)\n\nOutput:\n(empty)")
    }

    @Test
    fun errorFlagDoesNotChangeCopiedText() {
        // The error styling is a dialog concern; the copied payload is identical regardless.
        val ok = toolDetailCopyText(ToolDetailState.Loaded(input = "false", result = "exit 1", isError = false))
        val err = toolDetailCopyText(ToolDetailState.Loaded(input = "false", result = "exit 1", isError = true))
        assertThat(err).isEqualTo(ok)
    }

    @Test
    fun veryLongInputAndOutputAreCopiedInFull() {
        // AC4 — a payload far past UC-41's 600-char streaming cap and UC-80's old user cap must
        // survive verbatim, head AND tail, proving no truncation in the copy path.
        val input = "kubectl apply -f " + "x".repeat(5000) + " #END_INPUT"
        val output = "deployment.apps/web created\n" + "y".repeat(5000) + " #END_OUTPUT"
        val text = toolDetailCopyText(ToolDetailState.Loaded(input = input, result = output, isError = false))

        assertThat(text).isEqualTo("Input:\n$input\n\nOutput:\n$output")
        // Explicit head+tail survival checks (independent of the equality assertion above).
        assertThat(text).contains("kubectl apply -f")
        assertThat(text).contains("#END_INPUT")
        assertThat(text).contains("deployment.apps/web created")
        assertThat(text).contains("#END_OUTPUT")
        // No character was dropped: full input + full output + the fixed scaffolding length.
        val scaffolding = "Input:\n\n\nOutput:\n".length
        assertThat(text.length).isEqualTo(input.length + output.length + scaffolding)
    }

    @Test
    fun internalNewlinesAndStructurePreserved() {
        // AC4 — multi-line, structured output (JSON/log) keeps its exact shape.
        val output = "{\n  \"ok\": true,\n  \"items\": [1, 2, 3]\n}"
        val text = toolDetailCopyText(ToolDetailState.Loaded(input = "GET /api", result = output, isError = false))
        assertThat(text).isEqualTo("Input:\nGET /api\n\nOutput:\n$output")
        assertThat(text.lines()).contains("  \"ok\": true,")
    }
}
