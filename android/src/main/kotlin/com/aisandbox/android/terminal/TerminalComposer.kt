package com.aisandbox.android.terminal

/**
 * UC-99 — the carriage return byte a composed line ends with when submitted to
 * the PTY. `0x0D` (CR) is what the terminal expects as the "Enter" key (the same
 * byte the vendored `TerminalView` sends for a hardware/soft Enter), so a
 * composed line is delivered and executed exactly as if it had been typed and
 * Enter pressed.
 */
const val COMPOSER_SUBMIT_CR: Byte = 0x0D

/**
 * UC-99 (Bug 3) — the kill-line control byte prepended to a composed line so
 * "Send" REPLACES (rather than appends to) whatever is already on the PTY input
 * line. `Ctrl-U` (0x15) is readline/emacs "kill line to start", which clears the
 * current input line before the buffer is typed; paired with UC-99 (Bug 2)'s
 * prefill the composer becomes an editable mirror of the pending line — the
 * prefill shows it, and send clears-then-replaces-then-submits it as ONE message
 * (no `pending + buffer` duplication). On an already-empty line the kill is a
 * harmless no-op.
 *
 * <p><b>Pinned to Claude Code {@link ClaudeTuiPin#PINNED_CLAUDE_VERSION}; QA must
 * live-verify</b> that 0x15 actually clears the Claude Ink input line on the
 * pinned build. If it proves a no-op there, the documented fallbacks are: (a)
 * Backspace × prefill-length, or (b) `Ctrl-A` (0x01) + `Ctrl-K` (0x0B). Fallback
 * (a) is fragile — it assumes the pane line still holds exactly the prefilled
 * characters, so if the user edits the raw passthrough view after prefill the
 * backspace count drifts and over/under-deletes; `Ctrl-U` has no such issue (it
 * kills the whole line-to-start regardless of content). Fallback (b) shares
 * `Ctrl-U`'s content-independence. The pane line may also change between the
 * prefill read and send — that is fine, because `Ctrl-U` clears whatever is
 * actually there; do NOT add any send-time logic that assumes the prefilled text
 * still matches the pane.
 */
const val COMPOSER_KILL_LINE: Byte = 0x15

/**
 * UC-99 — encode one finalized composer line for delivery to the PTY over the
 * existing stdin path ([TerminalStreamController.sendStdin]).
 *
 * <p>This is the single, pure translation used by both the runtime
 * ([com.aisandbox.android.ui.screens.TerminalViewModel.submitComposerLine]) and
 * the tests, so the on-device gate exercises the real encoder rather than a
 * re-implementation. The rules are deliberately minimal:
 *
 * <ul>
 *   <li><b>Leading kill-line</b> — a single [COMPOSER_KILL_LINE] (Ctrl-U, 0x15)
 *       is prepended so the PTY input line is cleared before the buffer lands,
 *       making send REPLACE the pending line rather than append to it (Bug 3).
 *       On an empty line it is a no-op.</li>
 *   <li><b>UTF-8</b> — the text (including non-ASCII / emoji and any embedded
 *       newlines from multiline input) is encoded as UTF-8 verbatim; no
 *       normalization, trimming, or autocorrect happens here — the IME already
 *       finalized the text in the native composer field.</li>
 *   <li><b>Trailing CR</b> — a single [COMPOSER_SUBMIT_CR] (0x0D) is appended so
 *       the line is submitted (Enter) once, one-shot, avoiding the
 *       per-keystroke round-trip that causes the raw-view lag.</li>
 * </ul>
 *
 * <p>The function is total: it faithfully encodes whatever it is given. Blank /
 * empty handling (no-op) is a caller concern — [Composer] and the ViewModel both
 * guard against submitting blank text before this is ever reached.
 */
fun encodeComposerLine(text: String): ByteArray =
    byteArrayOf(COMPOSER_KILL_LINE) + text.toByteArray(Charsets.UTF_8) + COMPOSER_SUBMIT_CR
