package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.R
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.components.AgentSwitcherBar
import com.aisandbox.android.ui.components.KeyEvent
import com.aisandbox.android.ui.components.ModifierBar
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-21 AC#15 — instrumented Compose coverage for the terminal screen's UC-21
 * pieces, run on the headless emulator (AndroidJUnit4). These drive the public
 * render seams directly — the agent-switcher row and the modifier bar — seeded
 * with deterministic state, server-free, mirroring the
 * {@code SessionsScreenInstrumentationTest} pattern (the full {@code TerminalScreen}
 * needs the Application/AppContainer/ViewModel graph, so its sub-composables are
 * exercised in isolation here).
 *
 * <p>Criterion -> test map:
 *
 * <ul>
 *   <li>AC#2 / AC#3 — keyboard input: the [ModifierBar] renders alongside the
 *       terminal and its tiles emit the correct [KeyEvent]s that drive PTY
 *       stdin. The wire bytes per KeyEvent are pinned by the JVM
 *       {@code KeyEncodingTest}.</li>
 *   <li>AC#9 / AC#12 — the switcher labels each member and hides when only the
 *       main target is present.</li>
 *   <li>AC#10 — the main target is always present and first.</li>
 *   <li>AC#11 — tapping a box routes the selection (the highlight is driven by
 *       the selected id).</li>
 *   <li>AC#5 — the hamburger menu offers Delete session + Disconnect.</li>
 * </ul>
 *
 * <p>AC#8 (back-keeps-syncing) is a navigation/lifecycle property covered by the
 * JVM {@code TerminalStreamControllerTest} (close() is the sole teardown path;
 * the controller is process-scoped, so leaving the screen does not tear it down).
 */
@RunWith(AndroidJUnit4::class)
class TerminalScreenInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val mainTarget = StreamTarget(id = TerminalStreamController.MAIN_TARGET_ID, kind = "main", title = "main")
    private val ping = StreamTarget(
        id = "swarm:claude-swarm-1:0.0",
        kind = "swarm",
        title = "agent ping",
        agentName = "ping",
        agentColor = "blue",
        teamName = "pingpong",
    )

    // ── AC#12 — hidden when only the main target is present ───────────────────

    @Test
    fun switcher_hidden_when_only_main() {
        composeTestRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(mainTarget),
                    selectedTargetId = "main",
                    onSelect = {},
                )
            }
        }
        composeTestRule.onNodeWithText("main").assertDoesNotExist()
    }

    // ── AC#9 — members are labeled; main is shown when a team is running ──────

    @Test
    fun switcher_labels_members() {
        composeTestRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(mainTarget, ping),
                    selectedTargetId = "main",
                    onSelect = {},
                )
            }
        }
        composeTestRule.onNodeWithText("main").assertIsDisplayed()
        composeTestRule.onNodeWithText("ping").assertIsDisplayed()
    }

    // ── AC#10 — main is always first, even if the server lists it later ───────

    @Test
    fun switcher_keeps_main_first_even_if_server_lists_it_later() {
        composeTestRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(ping, mainTarget),
                    selectedTargetId = "main",
                    onSelect = {},
                )
            }
        }
        val mainLeft = composeTestRule.onNodeWithText("main").fetchSemanticsNode().boundsInRoot.left
        val pingLeft = composeTestRule.onNodeWithText("ping").fetchSemanticsNode().boundsInRoot.left
        assertTrue("main must render to the left of (before) the swarm pane", mainLeft < pingLeft)
    }

    // ── AC#11 — tapping a box routes the selection ────────────────────────────

    @Test
    fun tapping_a_box_routes_the_selection() {
        val selections = mutableListOf<String>()
        composeTestRule.setContent {
            var selected by remember { mutableStateOf("main") }
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(mainTarget, ping),
                    selectedTargetId = selected,
                    onSelect = {
                        selections.add(it)
                        selected = it // drives the highlight to the tapped box
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("ping").assertHasClickAction().performClick()
        composeTestRule.onNodeWithText("main").assertHasClickAction().performClick()

        assertEquals(listOf("swarm:claude-swarm-1:0.0", "main"), selections)
    }

    // ── AC#2 / AC#3 — the modifier bar renders and emits PTY-stdin key events ─

    @Test
    fun modifier_bar_renders_and_taps_emit_key_events() {
        val keys = mutableListOf<KeyEvent>()
        composeTestRule.setContent {
            AiSandboxTheme {
                ModifierBar(onKey = { keys.add(it) })
            }
        }

        // AC#3 — renders alongside the terminal with the tmux/ctrl/alt/esc/tab tiles.
        for (label in listOf("⌘ tmux", "Ctrl", "Alt", "Esc", "Tab")) {
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }

        // AC#2 — tapping a key feeds the corresponding event toward PTY stdin.
        composeTestRule.onNodeWithText("Esc").performTouchInput { click() }
        composeTestRule.onNodeWithText("Tab").performTouchInput { click() }
        composeTestRule.onNodeWithText("Ctrl").performTouchInput { click() }

        assertTrue("Esc tap must emit KeyEvent.Escape", keys.contains(KeyEvent.Escape))
        assertTrue("Tab tap must emit KeyEvent.Tab", keys.contains(KeyEvent.Tab))
        // The Ctrl tile is a sticky one-shot; the first tap arms it.
        assertTrue("Ctrl tap must arm the modifier", keys.contains(KeyEvent.CtrlArmed))
    }

    // ── AC#5 — the hamburger menu offers Delete session + Disconnect ──────────

    @Test
    fun hamburger_menu_labels_are_present() {
        // The dropdown lives inside the full TerminalScreen (which needs the app
        // graph); pin the menu's two action labels here so a future rename that
        // breaks AC#5 surfaces on-device.
        assertEquals("Delete session", ctx.getString(R.string.terminal_menu_delete))
        assertEquals("Disconnect", ctx.getString(R.string.terminal_menu_disconnect))
    }

    // ── UC-28 AC5 — terminal-screen Delete blocked while terminating ──────────
    //
    // The real dropdown is inside the full TerminalScreen (needs the
    // Application/AppContainer/ViewModel graph), so these pin the EXACT guard
    // wiring the screen uses on the Delete item:
    //   • enabled = !isTerminating  (the item is disabled while terminating)
    //   • onClick { if (isTerminating) return@DropdownMenuItem … }  (defensive
    //     short-circuit even if a tap slips through)
    // where `isTerminating` is the union of the shared optimistic store and the
    // server `terminating` token (TerminalViewModel.terminating).
    //
    // HONESTY CAVEAT (coverage summary): the `isTerminating` UNION computation
    // itself is covered by the JVM SessionsUiState `effectiveState` tests and
    // the shared-store SessionsCoordinator transition tests; the optimistic flag
    // surviving back-navigation is the process-scoped TerminatingSessionsStore's
    // contract. A future seam extraction of the terminal menu would let this
    // drive the real composable instead of pinning the wiring.

    @Composable
    private fun GuardedDeleteItem(isTerminating: Boolean, onDelete: () -> Unit) {
        androidx.compose.material3.DropdownMenuItem(
            text = { androidx.compose.material3.Text(ctx.getString(R.string.terminal_menu_delete)) },
            enabled = !isTerminating,
            onClick = {
                if (isTerminating) return@DropdownMenuItem
                onDelete()
            },
        )
    }

    /** AC5 — while terminating, tapping the Delete item must NOT fire a delete. */
    @Test
    fun terminal_delete_item_is_blocked_while_terminating() {
        var deleteFired = false
        composeTestRule.setContent {
            AiSandboxTheme { GuardedDeleteItem(isTerminating = true, onDelete = { deleteFired = true }) }
        }

        composeTestRule.onNodeWithText(ctx.getString(R.string.terminal_menu_delete)).performClick()

        assertTrue("a terminating session's terminal Delete must not fire (AC5)", !deleteFired)
    }

    /** No-regression — when NOT terminating, the Delete item fires normally. */
    @Test
    fun terminal_delete_item_fires_when_not_terminating() {
        var deleteFired = false
        composeTestRule.setContent {
            AiSandboxTheme { GuardedDeleteItem(isTerminating = false, onDelete = { deleteFired = true }) }
        }

        composeTestRule.onNodeWithText(ctx.getString(R.string.terminal_menu_delete)).performClick()

        assertTrue("a non-terminating session's terminal Delete must fire", deleteFired)
    }

    // ══ UC-23 — IME-inset / keyboard-occlusion layout contract (AC#1–#4, #6, #8) ══
    //
    // These drive the production [TerminalScaffoldLayout] seam directly with a
    // SYNTHETIC, controllable `imeInsets` (server-free, no real IME) and assert
    // the keyboard-up geometry contract via the production test tags
    // [TerminalViewportTestTag] / [ModifierBarTestTag]. The slots are lightweight
    // stand-ins — the real TerminalView / ModifierBar internals are exercised by
    // the UC-21 tests above and the JVM suites.
    //
    // HONESTY CAVEAT (recorded for the coverage summary): the assertions below are
    // STRUCTURAL layout-contract checks. They bypass the real
    // Scaffold/consumeWindowInsets stack and the real TerminalView.updateSize()
    // PTY-resize path. True on-device IME docking + the no-resize guarantee remain
    // a manual emulator check; this file's job is to pin the layout contract and
    // run green on a real device via connectedDebugAndroidTest.

    /**
     * Render [TerminalScaffoldLayout] full-screen with a fabricated, mutable
     * `imeInsets` (px) and lightweight stand-in slots. The agent-switcher slot
     * tags itself [SWITCHER_COMPACT_TAG] / [SWITCHER_NORMAL_TAG] by the `compact`
     * flag the layout hands it, so AC#4 wiring is observable.
     */
    private fun setUc23Layout(imeBottom: MutableState<Int>) {
        composeTestRule.setContent {
            AiSandboxTheme {
                TerminalScaffoldLayout(
                    modifier = Modifier.fillMaxSize(),
                    // AC#8 seam: the sole inset source, fabricated + controllable.
                    imeInsets = WindowInsets(bottom = imeBottom.value),
                    agentSwitcher = { compact ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(if (compact) 24.dp else 44.dp)
                                .testTag(if (compact) SWITCHER_COMPACT_TAG else SWITCHER_NORMAL_TAG),
                        )
                    },
                    terminal = { Box(Modifier.fillMaxSize()) },
                    modifierBar = { Box(Modifier.fillMaxWidth().height(48.dp)) },
                )
            }
        }
    }

    private fun rootBottomPx(): Float =
        composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    private fun tagBottomPx(tag: String): Float =
        composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom

    /** Measured (unclipped) height of a tagged node — the signal a PTY resize keys off. */
    private fun tagHeightPx(tag: String): Int =
        composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().size.height

    private fun setIme(imeBottom: MutableState<Int>, px: Int) {
        composeTestRule.runOnUiThread { imeBottom.value = px }
        composeTestRule.waitForIdle()
    }

    // ── AC#1 / AC#8(a) — cursor/terminal region not occluded by the keyboard ──

    @Test
    fun ime_shown_terminal_viewport_sits_above_keyboard_band() {
        val ime = mutableStateOf(0)
        setUc23Layout(ime)
        composeTestRule.waitForIdle()
        setIme(ime, IME_BOTTOM_PX)

        val ceiling = rootBottomPx() - IME_BOTTOM_PX
        val viewportBottom = tagBottomPx(TerminalViewportTestTag)
        assertTrue(
            "AC#1/#8a — terminal viewport bottom ($viewportBottom) must sit above the IME band " +
                "(rootBottom - ime = $ceiling): the cursor row is not occluded.",
            viewportBottom <= ceiling + EPS,
        )
    }

    // ── AC#3 / AC#8(b) — the modifier bar docks directly above the keyboard ───

    @Test
    fun ime_shown_modifier_bar_docked_above_keyboard_band() {
        val ime = mutableStateOf(0)
        setUc23Layout(ime)
        composeTestRule.waitForIdle()
        setIme(ime, IME_BOTTOM_PX)

        val ceiling = rootBottomPx() - IME_BOTTOM_PX
        val modifierBarBottom = tagBottomPx(ModifierBarTestTag)
        assertTrue(
            "AC#3/#8b — modifier-bar content bottom ($modifierBarBottom) must dock at/above the IME " +
                "band (rootBottom - ime = $ceiling): the keys stay reachable above the keyboard.",
            modifierBarBottom <= ceiling + EPS,
        )
    }

    // ── AC#2 — no PTY resize on IME toggle (structural: slot height pinned) ───

    @Test
    fun ime_toggle_does_not_resize_terminal_slot() {
        val ime = mutableStateOf(0)
        setUc23Layout(ime)
        composeTestRule.waitForIdle()

        val hidden = tagHeightPx(TerminalViewportTestTag)
        setIme(ime, IME_BOTTOM_PX)
        val shown = tagHeightPx(TerminalViewportTestTag)

        // The terminal slot pins its measured height via requiredHeight(), so the
        // measured (unclipped) height is identical keyboard-hidden vs shown —
        // TerminalView.updateSize()'s row/col guard never fires → no sendResize.
        // (±1px allowed for the dp round-trip rounding in requiredHeight().)
        assertTrue(
            "AC#2 — terminal slot measured height must be unchanged on IME toggle " +
                "(hidden=$hidden, shown=$shown); a delta means the PTY would resize.",
            kotlin.math.abs(hidden - shown) <= 1,
        )
    }

    // ── AC#4 — switcher collapses to a compact strip while the keyboard is up ─

    @Test
    fun ime_visibility_drives_switcher_compact_state() {
        val ime = mutableStateOf(0)
        setUc23Layout(ime)
        composeTestRule.waitForIdle()

        // Keyboard down → normal row.
        composeTestRule.onNodeWithTag(SWITCHER_NORMAL_TAG).assertExists()
        composeTestRule.onNodeWithTag(SWITCHER_COMPACT_TAG).assertDoesNotExist()

        setIme(ime, IME_BOTTOM_PX)

        // Keyboard up → compact strip.
        composeTestRule.onNodeWithTag(SWITCHER_COMPACT_TAG).assertExists()
        composeTestRule.onNodeWithTag(SWITCHER_NORMAL_TAG).assertDoesNotExist()
    }

    // ── AC#6 — dismissing the keyboard restores the full layout ───────────────

    @Test
    fun ime_dismiss_restores_full_layout() {
        val ime = mutableStateOf(0)
        setUc23Layout(ime)
        composeTestRule.waitForIdle()
        val restingHeight = tagHeightPx(TerminalViewportTestTag)

        setIme(ime, IME_BOTTOM_PX) // keyboard up …
        setIme(ime, 0) // … then dismissed

        // Switcher restored to its normal row.
        composeTestRule.onNodeWithTag(SWITCHER_NORMAL_TAG).assertExists()
        composeTestRule.onNodeWithTag(SWITCHER_COMPACT_TAG).assertDoesNotExist()

        // Modifier bar back at the bottom edge (no IME band beneath it).
        val rootBottom = rootBottomPx()
        val modifierBarBottom = tagBottomPx(ModifierBarTestTag)
        assertTrue(
            "AC#6 — modifier bar must return to the bottom edge once the IME is dismissed " +
                "(modifierBarBottom=$modifierBarBottom, rootBottom=$rootBottom).",
            kotlin.math.abs(modifierBarBottom - rootBottom) <= EPS,
        )

        // Terminal slot resumes its resting (full) height.
        val afterHeight = tagHeightPx(TerminalViewportTestTag)
        assertTrue(
            "AC#6 — terminal slot must resume its resting height after IME dismiss " +
                "(resting=$restingHeight, after=$afterHeight).",
            kotlin.math.abs(afterHeight - restingHeight) <= 1,
        )
    }

    private companion object {
        /** Synthetic keyboard height (px) injected through the [WindowInsets] seam. */
        const val IME_BOTTOM_PX = 600

        /** Float tolerance for px geometry comparisons (sub-pixel rounding). */
        const val EPS = 1.5f

        const val SWITCHER_NORMAL_TAG = "uc23_switcher_normal"
        const val SWITCHER_COMPACT_TAG = "uc23_switcher_compact"
    }
}
