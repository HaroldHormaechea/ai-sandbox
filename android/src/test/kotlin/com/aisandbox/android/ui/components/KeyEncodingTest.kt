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

    private fun bytesOf(s: String): List<Byte> = s.toByteArray().toList()
    private fun actual(e: KeyEvent): List<Byte>? = KeyEncoding.bytesFor(e)?.toList()

    @Test
    fun `arrow keys emit CSI sequences A B C D`() {
        // xterm CSI: ESC[A/B/C/D — the leading 0x1B is conventionally
        // prepended by the screen on emit (or by tmux); the bar emits
        // the "[A" suffix only. Pin both representations.
        // Compare as List<Byte> — Kotlin's ByteArray.equals is identity-based,
        // and AssertJ's nullable-receiver overload doesn't dispatch to the
        // content-comparing byte[] overload.
        assertThat(actual(KeyEvent.ArrowUp)).isEqualTo(bytesOf("[A"))
        assertThat(actual(KeyEvent.ArrowDown)).isEqualTo(bytesOf("[B"))
        assertThat(actual(KeyEvent.ArrowRight)).isEqualTo(bytesOf("[C"))
        assertThat(actual(KeyEvent.ArrowLeft)).isEqualTo(bytesOf("[D"))
    }

    @Test
    fun `function keys F1 through F4 use SS3 form OP OQ OR OS`() {
        assertThat(actual(KeyEvent.Function(1))).isEqualTo(bytesOf("OP"))
        assertThat(actual(KeyEvent.Function(2))).isEqualTo(bytesOf("OQ"))
        assertThat(actual(KeyEvent.Function(3))).isEqualTo(bytesOf("OR"))
        assertThat(actual(KeyEvent.Function(4))).isEqualTo(bytesOf("OS"))
    }

    @Test
    fun `function keys F5 through F12 use CSI tilde form`() {
        assertThat(actual(KeyEvent.Function(5))).isEqualTo(bytesOf("[15~"))
        assertThat(actual(KeyEvent.Function(6))).isEqualTo(bytesOf("[17~"))
        assertThat(actual(KeyEvent.Function(7))).isEqualTo(bytesOf("[18~"))
        assertThat(actual(KeyEvent.Function(8))).isEqualTo(bytesOf("[19~"))
        assertThat(actual(KeyEvent.Function(9))).isEqualTo(bytesOf("[20~"))
        assertThat(actual(KeyEvent.Function(10))).isEqualTo(bytesOf("[21~"))
        assertThat(actual(KeyEvent.Function(11))).isEqualTo(bytesOf("[23~"))
        assertThat(actual(KeyEvent.Function(12))).isEqualTo(bytesOf("[24~"))
    }

    @Test
    fun `out of range function key falls back to F12 encoding`() {
        // Defensive default for the screen's `Fn` row — anything > 12
        // collapses to F12's CSI[24~ rather than producing no output.
        assertThat(actual(KeyEvent.Function(99))).isEqualTo(bytesOf("[24~"))
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
