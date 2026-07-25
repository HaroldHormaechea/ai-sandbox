package com.aisandbox.android.terminal

import com.termux.terminal.TerminalEmulator

/**
 * UC-99 (Bug 2) — extract the pane's PENDING input line from the live
 * `TerminalEmulator` screen buffer, so the decoupled terminal composer can
 * pre-populate with whatever is already sitting on Claude's input row (rather
 * than always starting empty and appending to it on send).
 *
 * <p>The controller pumps every WS stdout byte into the emulator regardless of
 * focus ([WsTerminalSession.feed]), so the client already holds the live pane
 * buffer — no server round-trip / new endpoint is needed. Claude parks the
 * cursor inside its input box, so [TerminalEmulator.getCursorRow] is the input
 * row; we read exactly that ONE row (a cursor-anchored single-row read, NOT a
 * whole-screen box scan), strip the box decoration + prompt marker, and return
 * the pending text.
 *
 * <p><b>Safety priority: never a WRONG prefill.</b> When the cursor row is not a
 * recognizable composer input line (the cursor is parked on a menu/option row, a
 * dialog, a wrapped continuation, or the emulator hasn't rendered yet) the reader
 * returns {@code ""} — an empty prefill, never a guessed one. The recognizer
 * therefore requires BOTH the composer box's left vertical border AND a prompt
 * marker, which is exactly what distinguishes the bordered input row from an
 * unbordered menu/option row (e.g. {@code ❯ 1. Red}).
 *
 * <p><b>TUI-pinned to Claude Code {@code 2.1.169}</b> (kept in lock-step with the
 * server pin — see
 * {@link com.aisandbox.android.terminal.ClaudeTuiPin#PINNED_CLAUDE_VERSION}). The
 * chrome glyphs below are the pin surface; a version bump re-verifies them.
 * <b>QA live-verifies this against the pinned build</b> (top TUI-pin risk).
 */
object PendingInputReader {

    /** The composer box's vertical border glyph (`│`, U+2502). */
    private const val BOX_VERTICAL: Char = '│'

    /**
     * Prompt markers Claude renders at the start of the composer input row: the
     * plain `>` and the pointed `❯` (U+276F). Both are accepted since the exact
     * glyph is TUI-version dependent.
     */
    private const val PROMPT_MARKERS: String = ">❯"

    /**
     * Read the pending composer line from [emulator]'s cursor row, or {@code ""}
     * when there is nothing recognizable to prefill. Null-safe (a not-yet-rendered
     * emulator or an out-of-range cursor yields {@code ""}); never throws.
     */
    fun readPendingInputLine(emulator: TerminalEmulator?): String {
        if (emulator == null) return ""
        val screen = emulator.screen ?: return ""
        val cursorRow = emulator.cursorRow
        val columns = emulator.mColumns
        if (cursorRow < 0 || columns <= 0) return ""
        val rowText =
            try {
                // Whole cursor row (single-row selection): selX2 = columns-1 → the
                // buffer reads to the last used column of that row.
                screen.getSelectedText(0, cursorRow, columns - 1, cursorRow)
            } catch (t: Throwable) {
                return ""
            }
        return extractPendingLine(rowText)
    }

    /**
     * PURE — strip the composer box decoration and prompt marker from one captured
     * cursor-row string, returning the pending text (or {@code ""} when the row is
     * not a recognizable composer input line). Exposed for unit testing without an
     * emulator instance.
     */
    fun extractPendingLine(rowText: String?): String {
        if (rowText.isNullOrEmpty()) return ""
        var s = rowText.trimStart()
        // Must be inside the composer box (leading vertical border). This is what
        // distinguishes the input row from an unbordered menu/option row, so a
        // parked option cursor is never mistaken for pending input.
        if (s.isEmpty() || s[0] != BOX_VERTICAL) return ""
        s = s.substring(1).trimStart()
        // Must then start with the composer prompt marker to be an input row.
        if (s.isEmpty() || s[0] !in PROMPT_MARKERS) return ""
        s = s.substring(1)
        if (s.startsWith(" ")) s = s.substring(1)
        // Strip the trailing box border (and any whitespace around it).
        s = s.trimEnd()
        if (s.isNotEmpty() && s[s.length - 1] == BOX_VERTICAL) {
            s = s.substring(0, s.length - 1).trimEnd()
        }
        return s.trim()
    }
}
