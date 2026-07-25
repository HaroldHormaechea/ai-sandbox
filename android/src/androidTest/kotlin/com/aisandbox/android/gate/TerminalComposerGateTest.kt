package com.aisandbox.android.gate

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.terminal.encodeComposerLine
import com.aisandbox.android.ui.components.Composer
import com.aisandbox.android.ui.components.ModifierBar
import com.aisandbox.android.ui.components.TerminalSurface
import com.aisandbox.android.ui.testtags.TerminalComposerTestTags
import com.aisandbox.android.ui.theme.AiSandboxTheme
import com.termux.view.TerminalView
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-99 — MANDATORY on-device (instrumented) functional gate for the decoupled
 * terminal composer (winner B1). Lives in the {@code com.aisandbox.android.gate}
 * package so {@code android/gate.sh} runs it as part of the deterministic UC-85
 * gate ({@code am instrument -e package com.aisandbox.android.gate}); it is
 * self-contained ({@code createAndroidComposeRule}, no {@code GateHarness} /
 * enrollment / server) so it runs unconditionally on a booted emulator.
 *
 * <p><b>What it proves, on a real window, using the REAL production components</b>
 * (the shipped [Composer], the real [encodeComposerLine], and the real
 * [TerminalSurface] focus-gate — nothing re-implemented):
 *
 * <ul>
 *   <li><b>(a) lag fix / local echo</b> — typing into the composer is held
 *       locally; NOTHING is delivered to the PTY byte sink before Send (no
 *       per-keystroke round-trip). AC#6 (lag), AC#1 assumption.</li>
 *   <li><b>(b) un-mangled delivery</b> — tapping Send emits EXACTLY the finalized
 *       line's UTF-8 bytes + CR (via the real encoder) and clears the field.
 *       AC#6 (autocorrect mangling), AC#5.</li>
 *   <li><b>(c) coexistence + focus-gate</b> — the composer, the [ModifierBar],
 *       and the [TerminalSurface] (in composer mode, {@code inputEnabled=false})
 *       are all displayed together, AND the raw Termux [TerminalView] is NOT
 *       focusable/focused (a tap can't steal focus back to the laggy/mangling
 *       raw IME path). AC#6 focus hole, AC#8 (UC-21/23/36 coexistence).</li>
 *   <li><b>(d) raw passthrough preserved</b> — flipping the MODE_TOGGLE hands
 *       focus back: the Termux view regains focusability + focus and the composer
 *       is withdrawn. AC#5 (raw path stays for power users).</li>
 * </ul>
 *
 * <p><b>NOT RUN without a booted AVD.</b> Like the other gate tests, this needs
 * the headless emulator (see the {@code android-testing} skill). The pure byte
 * contract of [encodeComposerLine] is additionally pinned on every JVM build by
 * {@code com.aisandbox.android.terminal.TerminalComposerSubmitTest}.
 */
@RunWith(AndroidJUnit4::class)
class TerminalComposerGateTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var controller: TerminalStreamController? = null

    @After
    fun tearDown() {
        controller?.close("uc99-gate-teardown")
        controller = null
    }

    /**
     * Build a process-scoped [TerminalStreamController] with never-invoked
     * network factories: this gate is offline — it only uses the eagerly-created
     * {@code wsSession.session} / emulator that [TerminalSurface] binds to, never
     * {@code start()}, so the http/stream factories are never called.
     */
    private fun newController(): TerminalStreamController =
        TerminalStreamController(
            appContext = ctx.applicationContext,
            sessionN = 0,
            profileStore = ServerProfileStore(ctx.applicationContext),
            httpClientFactory = { error("UC-99 gate is offline: httpClientFactory must not be called") },
            streamClientFactory = { _, _ -> error("UC-99 gate is offline: streamClientFactory must not be called") },
            onClosed = {},
        ).also { controller = it }

    /**
     * Mirror of production {@code TerminalBody}'s exact wiring: one persisted
     * {@code composerMode} state drives BOTH the input surface selection and the
     * {@code inputEnabled = !composerMode} focus-gate handed to [TerminalSurface]
     * — so flipping the real MODE_TOGGLE exercises the real focus hand-off.
     * ({@code TerminalInputBar} is {@code private} to the screen, so its trivial
     * toggle+Composer layout is reproduced here; every underlying component —
     * Composer, ModifierBar, TerminalSurface, encodeComposerLine — is the real
     * production one.)
     */
    @Composable
    private fun TerminalInputHarness(
        composerMode: MutableState<Boolean>,
        ctrl: TerminalStreamController,
        initialText: String = "",
        onEncoded: (ByteArray) -> Unit,
    ) {
        AiSandboxTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                TextButton(
                    onClick = { composerMode.value = !composerMode.value },
                    modifier = Modifier.testTag(TerminalComposerTestTags.MODE_TOGGLE),
                ) {
                    Text(if (composerMode.value) "Switch to raw input" else "Switch to composer")
                }
                if (composerMode.value) {
                    Composer(
                        enabled = true,
                        // The REAL encoder — this is the byte path the ViewModel uses.
                        onSubmit = { onEncoded(encodeComposerLine(it)) },
                        inputTestTag = TerminalComposerTestTags.INPUT,
                        sendTestTag = TerminalComposerTestTags.SEND,
                        // UC-99 (Bug 2) — the pane's pending input line, seeded so the
                        // composer opens as an editable mirror of what is already typed.
                        initialText = initialText,
                    )
                }
                Box(modifier = Modifier.testTag(MODIFIER_BAR_TAG)) {
                    ModifierBar(onKey = {})
                }
                TerminalSurface(
                    controller = ctrl,
                    conversational = true,
                    inputEnabled = !composerMode.value,
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag(SURFACE_TAG),
                )
            }
        }
    }

    // ── (a)+(b) — local echo (no per-keystroke round-trip) + un-mangled send ──

    @Test
    fun typing_is_held_locally_then_send_delivers_exact_bytes_and_clears() {
        val ctrl = newController()
        var delivered: ByteArray? = null
        composeTestRule.setContent {
            val composerMode = remember { mutableStateOf(true) }
            TerminalInputHarness(composerMode, ctrl) { delivered = it }
        }
        composeTestRule.waitForIdle()

        // (a) type a whole line — held in the native field, NOTHING sent yet.
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.INPUT).performTextInput("grep foo")
        composeTestRule.waitForIdle()
        assertTrue(
            "AC#6 lag fix — no bytes may reach the PTY before Send (no per-keystroke round-trip)",
            delivered == null,
        )

        // (b) tap Send — a leading Ctrl-U (0x15, Bug 3 replace-on-send) + the finalized
        // line's UTF-8 bytes + one CR, via the real encoder.
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.SEND).performClick()
        composeTestRule.waitUntil(5_000) { delivered != null }
        assertArrayEquals(
            "AC#6 un-mangled delivery — Send must emit Ctrl-U + the exact finalized bytes + CR",
            byteArrayOf(0x15) + "grep foo".toByteArray(Charsets.UTF_8) + 0x0D.toByte(),
            delivered,
        )
        // The field clears: with blank text the Send control disables again.
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.SEND).assertIsNotEnabled()
    }

    // ── (b2) — Bug 2 prefill + Bug 3 replace-on-send, verified together ──

    /**
     * UC-99 Bugs 2 & 3 coupled — the composer opens PRE-POPULATED with the pane's
     * pending input line (Bug 2), and tapping Send REPLACES it on the PTY: the wire
     * bytes are the leading kill-line (Ctrl-U, 0x15) + the buffer's UTF-8 + one CR,
     * so {@code pending + buffer} can never be submitted (Bug 3). This drives the
     * REAL [Composer] with a non-empty {@code initialText} and the REAL
     * [encodeComposerLine].
     */
    @Test
    fun composer_opens_prefilled_with_the_pending_line_and_send_replaces_it() {
        val ctrl = newController()
        var delivered: ByteArray? = null
        composeTestRule.setContent {
            val composerMode = remember { mutableStateOf(true) }
            TerminalInputHarness(composerMode, ctrl, initialText = "grep pending") { delivered = it }
        }
        composeTestRule.waitForIdle()

        // Bug 2 — the field opens showing the pending line (editable mirror), not empty.
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.INPUT).assertTextContains("grep pending")
        // Nothing is delivered to the PTY merely by prefilling (no round-trip on open).
        assertTrue("prefill must not deliver anything to the PTY on its own", delivered == null)

        // Bug 3 — Send delivers Ctrl-U + the FULL buffer + CR (replace, not append).
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.SEND).performClick()
        composeTestRule.waitUntil(5_000) { delivered != null }
        assertArrayEquals(
            "Bug 3 replace-on-send — Send must kill the pending line (Ctrl-U) then submit the full buffer",
            byteArrayOf(0x15) + "grep pending".toByteArray(Charsets.UTF_8) + 0x0D.toByte(),
            delivered,
        )
    }

    // ── (c) — composer + modifier bar + surface coexist; raw view focus-gated ──

    @Test
    fun composer_modifierbar_and_surface_coexist_and_raw_view_is_not_focused() {
        val ctrl = newController()
        composeTestRule.setContent {
            val composerMode = remember { mutableStateOf(true) }
            TerminalInputHarness(composerMode, ctrl) {}
        }
        composeTestRule.waitForIdle()

        // All three surfaces are on screen together (UC-21/23/36 coexistence).
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.INPUT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.SEND).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MODIFIER_BAR_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SURFACE_TAG).assertIsDisplayed()

        // AC#6 focus hole — in composer mode the raw Termux view must NOT own
        // focus: it is non-focusable, so a tap can't re-summon the laggy/mangling
        // raw IME path.
        val tv = composeTestRule.runOnUiThread { findTerminalView() }
        assertNotNull("the vendored TerminalView must be present in the tree", tv)
        assertFalse("composer mode: raw view must be non-focusable", tv!!.isFocusable)
        assertFalse("composer mode: raw view must be non-focusable-in-touch", tv.isFocusableInTouchMode)
        assertFalse("composer mode: raw view must not be focused", tv.isFocused)
    }

    // ── (d) — flipping to raw mode hands focus back to the Termux view ─────────

    @Test
    fun flipping_mode_toggle_restores_raw_termux_focus() {
        val ctrl = newController()
        composeTestRule.setContent {
            val composerMode = remember { mutableStateOf(true) }
            TerminalInputHarness(composerMode, ctrl) {}
        }
        composeTestRule.waitForIdle()

        // Precondition: composer mode, raw view gated off.
        val tv = composeTestRule.runOnUiThread { findTerminalView() }
        assertNotNull(tv)
        assertFalse("precondition: composer mode → raw view non-focusable", tv!!.isFocusable)
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.INPUT).assertIsDisplayed()

        // Flip to raw passthrough via the real MODE_TOGGLE.
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.MODE_TOGGLE).performClick()
        composeTestRule.waitForIdle()

        // The same TerminalView instance regains focusability (TerminalSurface.update
        // sets isFocusable=true + requestFocus). AC#5 — raw path preserved.
        composeTestRule.waitUntil(5_000) { tv.isFocusable }
        assertTrue("raw mode: view must be focusable again", tv.isFocusable)
        assertTrue("raw mode: view must be focusable-in-touch again", tv.isFocusableInTouchMode)
        composeTestRule.waitUntil(5_000) { tv.isFocused }
        assertTrue("raw mode: view must regain focus (requestFocus)", tv.isFocused)
        // The composer is withdrawn when the raw path owns input.
        composeTestRule.onNodeWithTag(TerminalComposerTestTags.INPUT).assertDoesNotExist()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Depth-first search for the vendored Termux [TerminalView] in the window. */
    private fun findTerminalView(): TerminalView? =
        findTerminalView(composeTestRule.activity.window.decorView)

    private fun findTerminalView(v: View): TerminalView? = when (v) {
        is TerminalView -> v
        is ViewGroup -> {
            var found: TerminalView? = null
            var i = 0
            while (i < v.childCount && found == null) {
                found = findTerminalView(v.getChildAt(i))
                i++
            }
            found
        }
        else -> null
    }

    private companion object {
        const val MODIFIER_BAR_TAG = "uc99_modifier_bar"
        const val SURFACE_TAG = "uc99_terminal_surface"
    }
}
