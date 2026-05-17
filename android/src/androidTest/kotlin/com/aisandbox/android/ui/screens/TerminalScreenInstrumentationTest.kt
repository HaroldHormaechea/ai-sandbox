package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC04-3 — instrumented coverage for the Terminal screen:
 *
 * <ul>
 *   <li>The docked modifier bar renders Ctrl / Alt / Esc / Tab / Fn-row.</li>
 *   <li>The "Reconnecting…" toolbar indicator surfaces when the stream
 *       transitions to {@link TerminalState.Reconnecting}.</li>
 *   <li>The "Disconnected — tap to reconnect" terminal state (AC25)
 *       renders after the 5-min give-up cap fires.</li>
 * </ul>
 *
 * <p>As with the onboarding instrumentation tests, this runs on a
 * connected Android device. The bodies are stubs today; explicit
 * coverage gap is noted in the TEST SUMMARY so the team-lead knows
 * what the future device-CI run is expected to deliver.
 */
@RunWith(AndroidJUnit4::class)
class TerminalScreenInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun modifier_bar_renders_tmux_ctrl_alt_esc_tab() {
        // composeTestRule.setContent { TerminalScreen(...) }
        // for (label in listOf("⌘ tmux", "Ctrl", "Alt", "Esc", "Tab")) {
        //     composeTestRule.onNodeWithText(label).assertIsDisplayed()
        // }
        //
        // DEFERRED — see class comment.
    }

    @Test
    fun reconnecting_banner_surfaces_on_disconnect() {
        // composeTestRule.setContent { TerminalScreen(initialState = Reconnecting(2, 2_000L)) }
        // composeTestRule.onNodeWithText("Reconnecting").assertIsDisplayed()
        //
        // DEFERRED.
    }

    @Test
    fun give_up_state_shows_tap_to_reconnect_terminal_copy() {
        // composeTestRule.setContent { TerminalScreen(initialState = GaveUp) }
        // composeTestRule.onNodeWithText("Disconnected — tap to reconnect").assertIsDisplayed()
        //
        // DEFERRED.
    }

    @Test
    fun bel_byte_triggers_haptic_listener_attachment() {
        // composeTestRule.setContent { TerminalScreen(...) }
        // viewModel.emitForTest(byteArrayOf(0x07))
        // // Vibrator side-effect runs via HapticEventListener; visual
        // // assertion is "no crash" since the haptic is not visible.
        //
        // DEFERRED — also requires a test seam on the ViewModel that
        // production doesn't expose today.
    }
}
