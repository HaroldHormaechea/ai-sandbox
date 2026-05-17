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

    /** Decoded UTF-8 view of the wire bytes — comparing strings sidesteps
     *  every nullable-/array-/iterable-related AssertJ dispatch ambiguity. */
    private fun decoded(e: KeyEvent): String? =
        KeyEncoding.bytesFor(e)?.toString(Charsets.UTF_8)

    @Test
    fun `arrow keys emit CSI sequences A B C D`() {
        // xterm CSI: ESC[A/B/C/D — the leading 0x1B is conventionally
        // prepended by the screen on emit (or by tmux); the bar emits
        // the "[A" suffix only.
        assertThat(decoded(KeyEvent.ArrowUp)).isEqualTo("[A")
        assertThat(decoded(KeyEvent.ArrowDown)).isEqualTo("[B")
        assertThat(decoded(KeyEvent.ArrowRight)).isEqualTo("[C")
        assertThat(decoded(KeyEvent.ArrowLeft)).isEqualTo("[D")
    }

    @Test
    fun `function keys F1 through F4 use SS3 form OP OQ OR OS`() {
        assertThat(decoded(KeyEvent.Function(1))).isEqualTo("OP")
        assertThat(decoded(KeyEvent.Function(2))).isEqualTo("OQ")
        assertThat(decoded(KeyEvent.Function(3))).isEqualTo("OR")
        assertThat(decoded(KeyEvent.Function(4))).isEqualTo("OS")
    }

    @Test
    fun `function keys F5 through F12 use CSI tilde form`() {
        assertThat(decoded(KeyEvent.Function(5))).isEqualTo("[15~")
        assertThat(decoded(KeyEvent.Function(6))).isEqualTo("[17~")
        assertThat(decoded(KeyEvent.Function(7))).isEqualTo("[18~")
        assertThat(decoded(KeyEvent.Function(8))).isEqualTo("[19~")
        assertThat(decoded(KeyEvent.Function(9))).isEqualTo("[20~")
        assertThat(decoded(KeyEvent.Function(10))).isEqualTo("[21~")
        assertThat(decoded(KeyEvent.Function(11))).isEqualTo("[23~")
        assertThat(decoded(KeyEvent.Function(12))).isEqualTo("[24~")
    }

    @Test
    fun `out of range function key falls back to F12 encoding`() {
        // Defensive default for the screen's `Fn` row — anything > 12
        // collapses to F12's CSI[24~ rather than producing no output.
        assertThat(decoded(KeyEvent.Function(99))).isEqualTo("[24~")
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
