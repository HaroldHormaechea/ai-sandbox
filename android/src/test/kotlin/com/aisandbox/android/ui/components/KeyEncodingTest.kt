package com.aisandbox.android.ui.components

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 AC15 — the on-screen modifier bar emits xterm-conventional byte
 * sequences for keys the soft keyboard can't reach (Esc, Tab, arrows,
 * F1-F12), plus Ctrl-modified printables via the 0x1F mask.
 *
 * <p>Pure-data lookup — no Robolectric needed. Pins the wire bytes so
 * a future tweak that, say, swaps SS3 for CSI on F1-F4 surfaces with
 * the right blame line.
 */
class KeyEncodingTest {

    @Test
    fun `escape and tab are single control bytes`() {
        assertThat(KeyEncoding.bytesFor(KeyEvent.Escape)).containsExactly(0x1b.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Tab)).containsExactly(0x09.toByte())
    }

    /** Hex string of the wire bytes, lowercase, space-separated.
     *  e.g. byteArrayOf(0x5B, 0x41) → "5b 41". null becomes "null". */
    private fun hex(e: KeyEvent): String =
        KeyEncoding.bytesFor(e)
            ?.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
            ?: "null"

    private fun checkHex(e: KeyEvent, expected: String) {
        val got = hex(e)
        if (got != expected) {
            throw RuntimeException(
                "KeyEncoding wire-byte mismatch: event=$e expected=[$expected] got=[$got]"
            )
        }
    }

    @Test
    fun `arrow keys emit CSI sequences A B C D`() {
        // xterm CSI: '[' A/B/C/D bytes (0x5B 0x41..0x44). The leading
        // ESC (0x1B) is prepended on emit; this layer outputs the
        // suffix only.
        checkHex(KeyEvent.ArrowUp, "5b 41")
        checkHex(KeyEvent.ArrowDown, "5b 42")
        checkHex(KeyEvent.ArrowRight, "5b 43")
        checkHex(KeyEvent.ArrowLeft, "5b 44")
    }

    @Test
    fun `function keys F1 through F4 use SS3 form OP OQ OR OS`() {
        // 'O' P/Q/R/S → 0x4F 0x50..0x53
        assertThat(hex(KeyEvent.Function(1))).isEqualTo("4f 50")
        assertThat(hex(KeyEvent.Function(2))).isEqualTo("4f 51")
        assertThat(hex(KeyEvent.Function(3))).isEqualTo("4f 52")
        assertThat(hex(KeyEvent.Function(4))).isEqualTo("4f 53")
    }

    @Test
    fun `function keys F5 through F12 use CSI tilde form`() {
        // '[' <digits> '~' — F5..F12 = 15, 17, 18, 19, 20, 21, 23, 24
        assertThat(hex(KeyEvent.Function(5))).isEqualTo("5b 31 35 7e")
        assertThat(hex(KeyEvent.Function(6))).isEqualTo("5b 31 37 7e")
        assertThat(hex(KeyEvent.Function(7))).isEqualTo("5b 31 38 7e")
        assertThat(hex(KeyEvent.Function(8))).isEqualTo("5b 31 39 7e")
        assertThat(hex(KeyEvent.Function(9))).isEqualTo("5b 32 30 7e")
        assertThat(hex(KeyEvent.Function(10))).isEqualTo("5b 32 31 7e")
        assertThat(hex(KeyEvent.Function(11))).isEqualTo("5b 32 33 7e")
        assertThat(hex(KeyEvent.Function(12))).isEqualTo("5b 32 34 7e")
    }

    @Test
    fun `out of range function key falls back to F12 encoding`() {
        assertThat(hex(KeyEvent.Function(99))).isEqualTo("5b 32 34 7e")
    }

    @Test
    fun `arming events do not emit bytes`() {
        // Modifier-arming events are state transitions for the bar, not
        // bytes on the wire. The screen consumes them separately.
        val nonByteEvents = listOf(
            KeyEvent.CtrlArmed,
            KeyEvent.CtrlDisarmed,
            KeyEvent.AltArmed,
            KeyEvent.AltDisarmed,
            KeyEvent.TmuxArmed,
            KeyEvent.TmuxDisarmed,
        )
        for (e in nonByteEvents) {
            val actual = KeyEncoding.bytesFor(e)
            assertThat(actual).withFailMessage { "event=$e should not emit a byte sequence (got=$actual)" }.isNull()
        }
    }

    @Test
    fun `ctrl masking with 0x1F produces the expected codepoint`() {
        // Ctrl-A → 0x01, Ctrl-C → 0x03, Ctrl-Z → 0x1A. Pin the classic
        // xterm convention; nothing else in the codebase implements this.
        assertThat(KeyEncoding.ctrlByte('A')).isEqualTo(0x01.toByte())
        assertThat(KeyEncoding.ctrlByte('a')).isEqualTo(0x01.toByte())
        assertThat(KeyEncoding.ctrlByte('C')).isEqualTo(0x03.toByte())
        assertThat(KeyEncoding.ctrlByte('Z')).isEqualTo(0x1A.toByte())
        assertThat(KeyEncoding.ctrlByte('[')).isEqualTo(0x1B.toByte())
    }

    @Test
    fun `tmux prefix is Ctrl-A by default`() {
        // Server-side claude-sandbox image uses Ctrl-A as the tmux
        // prefix (default). The bar arms the prefix tile as a one-shot,
        // then emits TMUX_PREFIX on the next key.
        assertThat(KeyEncoding.TMUX_PREFIX).containsExactly(0x01.toByte())
    }
}
