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
 * <p>This is the make-or-break "un-mangled delivery" contract for AC#6: whatever
 * the native IME finalized in the composer field must reach the PTY verbatim
 * (no normalization, no autocorrect re-application, no lost bytes), terminated
 * by exactly one carriage return so the line is submitted once — the same
 * one-shot path the on-device [com.aisandbox.android.gate.TerminalComposerGateTest]
 * exercises through the real [com.aisandbox.android.ui.components.Composer].
 * Because both the runtime and that gate test call THIS function, pinning it
 * here pins the real wire bytes.
 *
 * <p>Pure logic (no Android APIs), so it runs on every JVM build as a JUnit 5
 * test — no Robolectric, no emulator — mirroring the pure-JVM convention of
 * {@code TerminalStreamControllerTest}.
 */
class TerminalComposerSubmitTest {

    @Test
    fun `ascii line is UTF-8 bytes plus a single trailing CR`() {
        val bytes = encodeComposerLine("grep foo")

        assertThat(bytes)
            .withFailMessage("an ASCII line must be its UTF-8 bytes followed by exactly one CR (0x0D)")
            .isEqualTo("grep foo".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // Spelled-out to guard against a UTF-8 charset regression.
        assertThat(bytes).isEqualTo(byteArrayOf(103, 114, 101, 112, 32, 102, 111, 111, 13))
    }

    @Test
    fun `the last byte is always the carriage return the terminal expects for Enter`() {
        assertThat(encodeComposerLine("x").last()).isEqualTo(COMPOSER_SUBMIT_CR)
        assertThat(COMPOSER_SUBMIT_CR).isEqualTo(0x0D.toByte())
        // Exactly ONE terminator — a composed line is submitted once (no double Enter).
        assertThat(encodeComposerLine("echo hi").count { it == COMPOSER_SUBMIT_CR }).isEqualTo(1)
    }

    @Test
    fun `non-ASCII UTF-8 text is preserved verbatim`() {
        // Accented Latin — the exact case a mangling autocorrect would corrupt.
        assertThat(encodeComposerLine("café"))
            .isEqualTo("café".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // Multi-byte CJK.
        assertThat(encodeComposerLine("日本語"))
            .isEqualTo("日本語".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
    }

    @Test
    fun `emoji (surrogate-pair codepoints) survive intact`() {
        val bytes = encodeComposerLine("🚀ok")

        assertThat(bytes).isEqualTo("🚀ok".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // The rocket is a 4-byte UTF-8 sequence; assert it is byte-exact so a
        // surrogate-splitting regression is caught.
        assertThat(bytes.take(4).toByteArray()).isEqualTo("🚀".toByteArray(Charsets.UTF_8))
        assertThat(bytes.last()).isEqualTo(COMPOSER_SUBMIT_CR)
    }

    @Test
    fun `embedded newlines from multiline input are preserved and only ONE CR is appended`() {
        val bytes = encodeComposerLine("line1\nline2")

        assertThat(bytes)
            .withFailMessage("multiline input keeps its interior LF(s); a single CR terminates the whole line")
            .isEqualTo("line1\nline2".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
        // Interior LF (0x0A) retained, trailing CR (0x0D) appended once.
        assertThat(bytes.count { it == 0x0A.toByte() }).isEqualTo(1)
        assertThat(bytes.count { it == 0x0D.toByte() }).isEqualTo(1)
        assertThat(bytes.last()).isEqualTo(0x0D.toByte())
    }

    @Test
    fun `encoder is total - blank encodes to a lone CR while blank no-op is a caller concern`() {
        // The function faithfully encodes whatever it is handed: "" -> just CR.
        assertThat(encodeComposerLine("")).isEqualTo(byteArrayOf(0x0D))
        // Whitespace is NOT trimmed here (the IME already finalized the text);
        // blank-submit suppression lives in the Composer send guard and
        // TerminalViewModel.submitComposerLine, exercised by the gate test.
        assertThat(encodeComposerLine("   ")).isEqualTo("   ".toByteArray(Charsets.UTF_8) + 0x0D.toByte())
    }
}
