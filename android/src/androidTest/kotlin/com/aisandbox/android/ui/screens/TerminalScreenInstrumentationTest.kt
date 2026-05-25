package com.aisandbox.android.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
}
