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
 * UC-99 — encode one finalized composer line for delivery to the PTY over the
 * existing stdin path ([TerminalStreamController.sendStdin]).
 *
 * <p>This is the single, pure translation used by both the runtime
 * ([com.aisandbox.android.ui.screens.TerminalViewModel.submitComposerLine]) and
 * the tests, so the on-device gate exercises the real encoder rather than a
 * re-implementation. The rules are deliberately minimal:
 *
 * <ul>
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
    text.toByteArray(Charsets.UTF_8) + COMPOSER_SUBMIT_CR
