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

    private fun hex(b: ByteArray?): String =
        b?.joinToString(" ") { "%02x".format(it.toInt() and 0xff) } ?: "null"

    @Test
    fun `arrow keys emit CSI sequences A B C D`() {
        // xterm CSI: ESC[A/B/C/D — the leading 0x1B is conventionally
        // prepended by the screen on emit (or by tmux); the bar emits
        // the "[A" suffix only.
        val up = KeyEncoding.bytesFor(KeyEvent.ArrowUp)
        assertThat(up)
            .withFailMessage { "ArrowUp got=${hex(up)} (size=${up?.size})" }
            .containsExactly(0x5b.toByte(), 0x41.toByte())
        val down = KeyEncoding.bytesFor(KeyEvent.ArrowDown)
        assertThat(down)
            .withFailMessage { "ArrowDown got=${hex(down)} (size=${down?.size})" }
            .containsExactly(0x5b.toByte(), 0x42.toByte())
        val right = KeyEncoding.bytesFor(KeyEvent.ArrowRight)
        assertThat(right)
            .withFailMessage { "ArrowRight got=${hex(right)} (size=${right?.size})" }
            .containsExactly(0x5b.toByte(), 0x43.toByte())
        val left = KeyEncoding.bytesFor(KeyEvent.ArrowLeft)
        assertThat(left)
            .withFailMessage { "ArrowLeft got=${hex(left)} (size=${left?.size})" }
            .containsExactly(0x5b.toByte(), 0x44.toByte())
    }

    @Test
    fun `function keys F1 through F4 use SS3 form OP OQ OR OS`() {
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(1))).containsExactly(0x4f.toByte(), 0x50.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(2))).containsExactly(0x4f.toByte(), 0x51.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(3))).containsExactly(0x4f.toByte(), 0x52.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(4))).containsExactly(0x4f.toByte(), 0x53.toByte())
    }

    @Test
    fun `function keys F5 through F12 use CSI tilde form`() {
        // CSI[<n>~ — 0x5B '[', digits, 0x7E '~'.
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(5)))
            .containsExactly(0x5b.toByte(), 0x31.toByte(), 0x35.toByte(), 0x7e.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(6)))
            .containsExactly(0x5b.toByte(), 0x31.toByte(), 0x37.toByte(), 0x7e.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(7)))
            .containsExactly(0x5b.toByte(), 0x31.toByte(), 0x38.toByte(), 0x7e.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(8)))
            .containsExactly(0x5b.toByte(), 0x31.toByte(), 0x39.toByte(), 0x7e.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(9)))
            .containsExactly(0x5b.toByte(), 0x32.toByte(), 0x30.toByte(), 0x7e.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(10)))
            .containsExactly(0x5b.toByte(), 0x32.toByte(), 0x31.toByte(), 0x7e.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(11)))
            .containsExactly(0x5b.toByte(), 0x32.toByte(), 0x33.toByte(), 0x7e.toByte())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(12)))
            .containsExactly(0x5b.toByte(), 0x32.toByte(), 0x34.toByte(), 0x7e.toByte())
    }

    @Test
    fun `out of range function key falls back to F12 encoding`() {
        // Defensive default for the screen's `Fn` row — anything > 12
        // collapses to F12's CSI[24~ rather than producing no output.
        // [24~ → 0x5B, 0x32, 0x34, 0x7E
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(99)))
            .containsExactly(0x5b.toByte(), 0x32.toByte(), 0x34.toByte(), 0x7e.toByte())
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
