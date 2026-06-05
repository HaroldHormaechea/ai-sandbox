package com.termux.view

import android.text.InputType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-36 AC#1/#4 — the IME {@code inputType} policy, pinned in isolation.
 *
 * <p>{@link TerminalView#computeInputType} is the single seam that decides
 * whether the on-screen keyboard runs in the new <b>conversational</b> mode
 * (word prediction + suggestion strip + autocomplete, the UC-36 default) or the
 * <b>raw/char</b> escape hatch (today's historical Termux behaviour). This test
 * exists because the difference is a handful of bit-flags that are easy to
 * regress silently — flip the wrong one and either suggestions vanish (AC#1
 * regression) or autocorrect starts mangling CLI tokens (AC#4 regression).
 *
 * <p>Pure-JVM: {@code InputType.*} are compile-time {@code static final int}
 * constants inlined by the compiler, so no Android runtime is needed.
 */
class TerminalInputTypeTest {

    private val raw = TerminalView.computeInputType(true)
    private val conversational = TerminalView.computeInputType(false)

    private fun has(type: Int, flag: Int) = (type and flag) == flag

    // ── AC#1 — conversational mode turns suggestions/prediction ON ────────────

    @Test
    fun `conversational mode is a normal text field`() {
        assertThat(has(conversational, InputType.TYPE_CLASS_TEXT))
            .withFailMessage("conversational input must declare TYPE_CLASS_TEXT so the IME runs a real text field")
            .isTrue()
        assertThat(conversational)
            .isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
    }

    @Test
    fun `conversational mode does NOT suppress suggestions`() {
        // NO_SUGGESTIONS is what kills the suggestion strip / prediction — it
        // must be absent for the conversational experience (AC#1).
        assertThat(has(conversational, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
            .withFailMessage("conversational input must NOT set NO_SUGGESTIONS (it would disable prediction — AC#1)")
            .isFalse()
    }

    // ── AC#4 — conversational mode must NOT auto-mangle CLI tokens ─────────────

    @Test
    fun `conversational mode leaves autocorrect OFF so commands are never silently rewritten`() {
        assertThat(has(conversational, InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
            .withFailMessage("AUTO_CORRECT must stay OFF — the IME must not silently rewrite e.g. `grep` (AC#4)")
            .isFalse()
    }

    @Test
    fun `conversational mode leaves auto-capitalisation OFF`() {
        // Auto-cap would turn the first letter of a line into a different token.
        assertThat(has(conversational, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)).isFalse()
        assertThat(has(conversational, InputType.TYPE_TEXT_FLAG_CAP_WORDS)).isFalse()
        assertThat(has(conversational, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)).isFalse()
    }

    // ── AC#1/#4 — raw mode reproduces the historical no-suggestions behaviour ──

    @Test
    fun `raw mode is VISIBLE_PASSWORD plus NO_SUGGESTIONS`() {
        assertThat(raw)
            .isEqualTo(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertThat(has(raw, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)).isTrue()
    }

    @Test
    fun `the two modes are distinct`() {
        assertThat(raw).isNotEqualTo(conversational)
    }
}
