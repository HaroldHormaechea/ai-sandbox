package com.aisandbox.android.terminal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-99 — pure-JVM coverage for [encodeComposerLine], the single shared byte
 * translation the decoupled terminal composer uses to deliver a finalized line
 * to the PTY over the existing stdin path
 * ([com.aisandbox.android.ui.screens.TerminalViewModel.submitComposerLine] →
 * [TerminalStreamController.sendStdin]).
 *
 * <p>This is the make-or-break contract for two coupled AC threads:
 * <ul>
 *   <li><b>AC#6 un-mangled delivery</b> — whatever the native IME finalized in
 *       the composer field must reach the PTY verbatim (no normalization, no
 *       autocorrect re-application, no lost bytes), terminated by exactly one
 *       carriage return so the line is submitted once.</li>
 *   <li><b>Bug 3 replace-on-send</b> — a leading [COMPOSER_KILL_LINE] (Ctrl-U,
 *       0x15) is prepended so Send clears the pending PTY input line before
 *       typing, making the composed buffer REPLACE (not append to) it. So the
 *       exact wire shape is {@code 0x15 + UTF-8(text) + 0x0D}.</li>
 * </ul>
 *
 * <p>The on-device [com.aisandbox.android.gate.TerminalComposerGateTest] drives
 * the SAME function through the real [com.aisandbox.android.ui.components.Composer],
 * so pinning it here pins the real wire bytes.
 *
 * <p>Pure logic (no Android APIs), so it runs on every JVM build as a JUnit 5
 * test — no Robolectric, no emulator — mirroring the pure-JVM convention of
 * {@code TerminalStreamControllerTest}.
 */
class TerminalComposerSubmitTest {

    @Test
    fun `ascii line is a leading Ctrl-U then UTF-8 bytes then a single trailing CR`() {
        val bytes = encodeComposerLine("grep foo")

        assertThat(bytes)
            .withFailMessage(
                "an ASCII line must be Ctrl-U (0x15) + its UTF-8 bytes + exactly one CR (0x0D)",
            )
            .isEqualTo(byteArrayOf(0x15) + "grep foo".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // Spelled-out to guard against a UTF-8 charset regression (0x15 kill, body, 0x0D CR).
        assertThat(bytes).isEqualTo(byteArrayOf(21, 103, 114, 101, 112, 32, 102, 111, 111, 13))
    }

    @Test
    fun `the FIRST byte is always the kill-line control byte (Bug 3 replace-on-send)`() {
        assertThat(encodeComposerLine("x").first()).isEqualTo(COMPOSER_KILL_LINE)
        assertThat(COMPOSER_KILL_LINE).isEqualTo(0x15.toByte())
        // Exactly ONE kill byte, always at the head — Send clears the pending line, once.
        assertThat(encodeComposerLine("echo hi").count { it == COMPOSER_KILL_LINE }).isEqualTo(1)
        assertThat(encodeComposerLine("echo hi").indexOf(COMPOSER_KILL_LINE)).isEqualTo(0)
    }

    @Test
    fun `the last byte is always the carriage return the terminal expects for Enter`() {
        assertThat(encodeComposerLine("x").last()).isEqualTo(COMPOSER_SUBMIT_CR)
        assertThat(COMPOSER_SUBMIT_CR).isEqualTo(0x0D.toByte())
        // Exactly ONE terminator — a composed line is submitted once (no double Enter).
        assertThat(encodeComposerLine("echo hi").count { it == COMPOSER_SUBMIT_CR }).isEqualTo(1)
    }

    @Test
    fun `non-ASCII UTF-8 text is preserved verbatim between the kill byte and the CR`() {
        // Accented Latin — the exact case a mangling autocorrect would corrupt.
        assertThat(encodeComposerLine("café"))
            .isEqualTo(byteArrayOf(0x15) + "café".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // Multi-byte CJK.
        assertThat(encodeComposerLine("日本語"))
            .isEqualTo(byteArrayOf(0x15) + "日本語".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
    }

    @Test
    fun `emoji (surrogate-pair codepoints) survive intact`() {
        val bytes = encodeComposerLine("🚀ok")

        assertThat(bytes).isEqualTo(byteArrayOf(0x15) + "🚀ok".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // The rocket is a 4-byte UTF-8 sequence starting right after the kill byte;
        // assert it is byte-exact so a surrogate-splitting regression is caught.
        assertThat(bytes.first()).isEqualTo(COMPOSER_KILL_LINE)
        assertThat(bytes.drop(1).take(4).toByteArray()).isEqualTo("🚀".toByteArray(Charsets.UTF_8))
        assertThat(bytes.last()).isEqualTo(COMPOSER_SUBMIT_CR)
    }

    @Test
    fun `embedded newlines from multiline input are preserved and only ONE CR is appended`() {
        val bytes = encodeComposerLine("line1\nline2")

        assertThat(bytes)
            .withFailMessage("multiline input keeps its interior LF(s); Ctrl-U leads and one CR terminates")
            .isEqualTo(byteArrayOf(0x15) + "line1\nline2".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // Interior LF (0x0A) retained, single leading kill (0x15), trailing CR (0x0D) appended once.
        assertThat(bytes.count { it == 0x0A.toByte() }).isEqualTo(1)
        assertThat(bytes.count { it == COMPOSER_KILL_LINE }).isEqualTo(1)
        assertThat(bytes.count { it == 0x0D.toByte() }).isEqualTo(1)
        assertThat(bytes.first()).isEqualTo(COMPOSER_KILL_LINE)
        assertThat(bytes.last()).isEqualTo(0x0D.toByte())
    }

    @Test
    fun `empty input still emits the kill byte harmlessly then the CR`() {
        // The function faithfully encodes whatever it is handed: "" -> kill byte + CR.
        // The leading Ctrl-U is a harmless no-op on an already-empty PTY line (Bug 3),
        // so an empty composed line clears-nothing then submits.
        assertThat(encodeComposerLine("")).isEqualTo(byteArrayOf(0x15, 0x0D))
        // Whitespace is NOT trimmed here (the IME already finalized the text);
        // blank-submit suppression lives in the Composer send guard and
        // TerminalViewModel.submitComposerLine, exercised by the gate test.
        assertThat(encodeComposerLine("   "))
            .isEqualTo(byteArrayOf(0x15) + "   ".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
    }
}
