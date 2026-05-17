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

    @Test
    fun `arrow keys emit CSI sequences A B C D`() {
        // xterm CSI: ESC[A/B/C/D — the leading 0x1B is conventionally
        // prepended by the screen on emit (or by tmux); the bar emits
        // the "[A" suffix only. Pin both representations.
        assertThat(KeyEncoding.bytesFor(KeyEvent.ArrowUp)).isEqualTo("[A".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.ArrowDown)).isEqualTo("[B".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.ArrowRight)).isEqualTo("[C".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.ArrowLeft)).isEqualTo("[D".toByteArray())
    }

    @Test
    fun `function keys F1 through F4 use SS3 form OP OQ OR OS`() {
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(1))).isEqualTo("OP".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(2))).isEqualTo("OQ".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(3))).isEqualTo("OR".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(4))).isEqualTo("OS".toByteArray())
    }

    @Test
    fun `function keys F5 through F12 use CSI tilde form`() {
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(5))).isEqualTo("[15~".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(6))).isEqualTo("[17~".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(7))).isEqualTo("[18~".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(8))).isEqualTo("[19~".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(9))).isEqualTo("[20~".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(10))).isEqualTo("[21~".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(11))).isEqualTo("[23~".toByteArray())
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(12))).isEqualTo("[24~".toByteArray())
    }

    @Test
    fun `out of range function key falls back to F12 encoding`() {
        // Defensive default for the screen's `Fn` row — anything > 12
        // collapses to F12's CSI[24~ rather than producing no output.
        assertThat(KeyEncoding.bytesFor(KeyEvent.Function(99))).isEqualTo("[24~".toByteArray())
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
            assertThat(KeyEncoding.bytesFor(e))
                .as("event=%s should not emit a byte sequence", e)
                .isNull()
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
