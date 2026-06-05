package com.termux.view

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * UC-36 — the make-or-break test for AC#2/#3/#4/#5: bytes the IME hands the
 * terminal's {@link android.view.inputmethod.InputConnection} reach the PTY in
 * full, once, and in order — and a word still being composed locally is flushed
 * to the PTY before any out-of-band control key, so the byte stream is never
 * reordered or corrupted.
 *
 * <h2>Why this test exists / what it pins</h2>
 *
 * The ai-sandbox terminal is <b>remote</b>: {@link TerminalView} renders only
 * the server-echoed emulator buffer, never the IME's local editable. So the
 * contract that makes conversational typing safe is entirely about <em>what
 * bytes leave the device and in what order</em>. We assert exactly that by
 * draining {@link TerminalSession}'s {@link TerminalSession.OutputListener}
 * (the same sink the production {@code WsTerminalSession} routes to the
 * WebSocket as PTY stdin) into a byte buffer.
 *
 * <h2>Robolectric fidelity notes</h2>
 *
 * <ul>
 *   <li>The production {@code InputConnection} is the anonymous
 *       {@code BaseInputConnection} subclass built inside
 *       {@link TerminalView#onCreateInputConnection}; we obtain the real one and
 *       drive its {@code setComposingText/commitText/finishComposingText}.</li>
 *   <li>Key-event delivery (Enter, arrows, Backspace) is asserted through
 *       {@link TerminalView#onKeyDown} directly. The IME's own
 *       {@code BaseInputConnection.sendKeyEvent} dispatches via the window's
 *       {@code ViewRootImpl}, which does not exist in a non-attached Robolectric
 *       view — so a key sent purely through the {@code InputConnection} would be
 *       a no-op. Driving {@code onKeyDown} exercises the exact code path a
 *       dispatched key reaches on-device (including the UC-36 flush-first guard
 *       added at the top of {@code onKeyDown}), with deterministic bytes.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TerminalInputConnectionDeliveryTest {

    /** Bytes the terminal wrote toward the PTY during one scenario. */
    private lateinit var sink: ByteArrayOutputStream
    private lateinit var view: TerminalView

    private fun setUp(attachEmulator: Boolean = true): android.view.inputmethod.InputConnection {
        sink = ByteArrayOutputStream()
        val ctx = RuntimeEnvironment.getApplication()
        view = TerminalView(ctx, null)

        val viewClient = Mockito.mock(TerminalViewClient::class.java)
        // Only override what steers the branches we care about; Mockito's
        // defaults (false / null / 0) cover the read*/onKeyDown/onCodePoint
        // surface, which is exactly "no modifiers held, view handles the key".
        Mockito.`when`(viewClient.isTerminalViewSelected()).thenReturn(true)
        view.setTerminalViewClient(viewClient)

        val sessionClient = Mockito.mock(TerminalSessionClient::class.java)
        val session = TerminalSession(
            2000,
            sessionClient,
            TerminalSession.OutputListener { data, offset, count -> sink.write(data, offset, count) },
        )
        session.initializeEmulator(80, 24, 10, 10)
        view.mTermSession = session
        view.mEmulator = if (attachEmulator) session.getEmulator() else null

        return view.onCreateInputConnection(EditorInfo())
    }

    private fun captured(): String = sink.toString("UTF-8")
    private fun capturedBytes(): List<Int> = sink.toByteArray().map { it.toInt() and 0xff }

    // ── AC#3 — composing text is NOT echoed per-character to the PTY ──────────

    @Test
    fun `setComposingText sends zero bytes to the PTY`() {
        val ic = setUp()
        ic.setComposingText("foo", 1)
        ic.setComposingText("foobar", 1) // keystroke-by-keystroke composing update
        // Nothing is committed yet — the PTY must have received nothing, so the
        // server echo stays the single source of truth (no double-typing).
        assertThat(captured()).isEmpty()
    }

    // ── AC#2 — committed text reaches the PTY in full, exactly once ───────────

    @Test
    fun `commitText delivers the exact bytes once`() {
        val ic = setUp()
        ic.commitText("ls -la /tmp", 1)
        assertThat(captured()).isEqualTo("ls -la /tmp")
    }

    @Test
    fun `successive commits preserve order with no dropped characters`() {
        val ic = setUp()
        // Simulate rapid word-by-word commits (suggestion taps / spaces).
        ic.commitText("ls", 1)
        ic.commitText(" -la", 1)
        ic.commitText(" /tmp", 1)
        assertThat(captured()).isEqualTo("ls -la /tmp")
    }

    @Test
    fun `composing then commit sends only the committed word once`() {
        val ic = setUp()
        ic.setComposingText("gre", 1)
        ic.setComposingText("grep", 1)
        ic.commitText("grep", 1) // commit replaces the composing region
        assertThat(captured()).isEqualTo("grep")
    }

    // ── AC#3/#5 — the flush primitive commits a pending word ─────────────────

    @Test
    fun `finishComposingText flushes the pending composing word to the PTY`() {
        val ic = setUp()
        ic.setComposingText("hello", 1)
        assertThat(captured()).isEmpty() // still composing
        ic.finishComposingText()
        assertThat(captured()).isEqualTo("hello")
    }

    // ── AC#2/#5 — control keys flush the composing word FIRST, then emit ──────

    @Test
    fun `Enter flushes the composing word before sending CR`() {
        val ic = setUp()
        ic.setComposingText("foo", 1)
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        // 'f','o','o' then CR (\r, 13) — word committed and echoed before the
        // command is run. Order is the whole point of AC#5.
        assertThat(capturedBytes()).containsExactly(102, 111, 111, 13)
        assertThat(captured()).isEqualTo("foo\r")
    }

    @Test
    fun `a ModifierBar-style arrow key flushes the composing word before the control sequence`() {
        val ic = setUp()
        ic.setComposingText("foo", 1)
        view.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        // 'f','o','o' then ESC '[' 'C' (the xterm cursor-right CSI). The word
        // must precede the control bytes — never interleaved or dropped.
        assertThat(capturedBytes()).containsExactly(102, 111, 111, 27, 91, 67)
    }

    // ── AC#5 — Backspace deletes exactly one char on the PTY ──────────────────

    @Test
    fun `Backspace key emits a single DEL byte`() {
        setUp()
        view.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertThat(capturedBytes()).containsExactly(127)
    }

    // ── AC#2 — detached (no emulator): commit/finish DROP, never retain ───────

    @Test
    fun `commitText while detached drops the text instead of buffering it`() {
        val ic = setUp(attachEmulator = false)
        ic.setComposingText("stale", 1)
        ic.commitText("dropme", 1)
        assertThat(captured()).isEmpty()
    }

    @Test
    fun `finishComposingText while detached drops the buffer (no merge into next session)`() {
        val ic = setUp(attachEmulator = false)
        ic.setComposingText("stale", 1)
        ic.finishComposingText()
        // Re-attach and commit a fresh word: the stale buffer must NOT be
        // prepended to it.
        view.mEmulator = view.mTermSession.getEmulator()
        ic.commitText("fresh", 1)
        assertThat(captured()).isEqualTo("fresh")
    }

    // ── AC#4 — autocorrect must not leak the pre-correction token ─────────────

    @Test
    fun `autocorrect via composing replacement sends only the corrected word`() {
        val ic = setUp()
        // Gboard with autocorrect surfaced as a suggestion: the mistyped word is
        // held in the composing region, then replaced by the correction before
        // it is committed. Because composing text is never forwarded per-char
        // (AC#3) and commit replaces the composing region, the PTY must receive
        // ONLY "grep" — never the stray pre-correction "grpe".
        ic.setComposingText("grpe", 1)
        ic.setComposingText("grep", 1) // IME swaps the composing word to the correction
        ic.finishComposingText()
        assertThat(captured()).isEqualTo("grep")
        assertThat(captured()).doesNotContain("grpe")
    }
}
