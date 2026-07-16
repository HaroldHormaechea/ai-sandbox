package com.aisandbox.android.terminal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-99 (Bug 2) — pure-JVM coverage for [PendingInputReader.extractPendingLine],
 * the cursor-row decoration stripper that lets the decoupled terminal composer
 * pre-populate with whatever is already sitting on Claude's input line (rather
 * than always starting empty and appending to it on send).
 *
 * <p>The extraction is deliberately a single captured row (the {@code
 * getCursorRow()} row), so these tests feed it captured-row strings directly and
 * assert three things:
 * <ul>
 *   <li><b>strip</b> — the composer box decoration (leading {@code │} border, the
 *       {@code >} / {@code ❯} prompt marker, one following space, and the trailing
 *       {@code │} border) is removed, leaving the pending text verbatim;</li>
 *   <li><b>empty-fallback</b> — ANY row that is not a recognizable composer input
 *       line (a menu/option row, a bordered-but-marker-less status row, a blank
 *       row, a not-yet-rendered null) yields {@code ""} — <b>never a wrong
 *       prefill</b> (the safety priority);</li>
 *   <li><b>content fidelity</b> — non-ASCII / emoji pending text and interior
 *       {@code >} characters survive the strip intact.</li>
 * </ul>
 *
 * <p>Pinned to Claude Code {@link ClaudeTuiPin#PINNED_CLAUDE_VERSION}; the chrome
 * glyphs are the TUI-pin surface QA live-verifies against the pinned build.
 */
class PendingInputReaderTest {

    // ── strip: a recognizable composer input row yields its pending text ──

    @Test
    fun `plain prompt marker row is stripped to the pending text`() {
        assertThat(PendingInputReader.extractPendingLine("│ > grep foo │")).isEqualTo("grep foo")
    }

    @Test
    fun `pointed prompt marker row is stripped to the pending text`() {
        assertThat(PendingInputReader.extractPendingLine("│ ❯ deploy now │")).isEqualTo("deploy now")
    }

    @Test
    fun `leading whitespace before the box border is tolerated`() {
        assertThat(PendingInputReader.extractPendingLine("    │ > cmd │")).isEqualTo("cmd")
    }

    @Test
    fun `a row with no trailing border still yields the pending text`() {
        // The cursor-row read may not include the right border; trimEnd handles it.
        assertThat(PendingInputReader.extractPendingLine("│ > grep foo")).isEqualTo("grep foo")
    }

    @Test
    fun `interior prompt-marker characters in the pending text are preserved`() {
        // Only the LEADING prompt marker is stripped — a piped command survives.
        assertThat(PendingInputReader.extractPendingLine("│ > echo a > b │")).isEqualTo("echo a > b")
    }

    @Test
    fun `non-ASCII and emoji pending text survives the strip verbatim`() {
        assertThat(PendingInputReader.extractPendingLine("│ > café 🚀 日本語 │")).isEqualTo("café 🚀 日本語")
    }

    @Test
    fun `pending text with interior double spaces is preserved (only edges trimmed)`() {
        assertThat(PendingInputReader.extractPendingLine("│ > a  b │")).isEqualTo("a  b")
    }

    // ── empty-fallback: never a wrong prefill for a non-composer row ──

    @Test
    fun `an empty composer input row yields empty (no phantom prefill)`() {
        assertThat(PendingInputReader.extractPendingLine("│ > │")).isEmpty()
        assertThat(PendingInputReader.extractPendingLine("│ >      │")).isEmpty()
    }

    @Test
    fun `an unbordered menu or option cursor row is NOT mistaken for pending input`() {
        // Claude's option rows look like "❯ 1. Red" with NO composer box border —
        // requiring the leading │ is exactly what keeps a parked option cursor from
        // becoming a wrong prefill.
        assertThat(PendingInputReader.extractPendingLine("❯ 1. Red")).isEmpty()
        assertThat(PendingInputReader.extractPendingLine("> 2. Blue")).isEmpty()
    }

    @Test
    fun `a bordered row without a prompt marker yields empty`() {
        // A status/decoration row inside the box but not the input row.
        assertThat(PendingInputReader.extractPendingLine("│ Thinking… │")).isEmpty()
    }

    @Test
    fun `a blank or whitespace-only row yields empty`() {
        assertThat(PendingInputReader.extractPendingLine("")).isEmpty()
        assertThat(PendingInputReader.extractPendingLine("            ")).isEmpty()
    }

    @Test
    fun `a null captured row yields empty`() {
        assertThat(PendingInputReader.extractPendingLine(null)).isEmpty()
    }

    // ── null-safety of the emulator-facing entry point (no emulator instance) ──

    @Test
    fun `readPendingInputLine on a null emulator yields empty and never throws`() {
        assertThat(PendingInputReader.readPendingInputLine(null)).isEmpty()
    }
}
