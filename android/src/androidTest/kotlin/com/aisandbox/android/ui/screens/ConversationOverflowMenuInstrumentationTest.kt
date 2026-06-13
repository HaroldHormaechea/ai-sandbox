package com.aisandbox.android.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-65 — instrumented (emulator-tier) coverage for the conversation overflow menu's
 * new **Clear** action. Drives the `internal` [ConversationOverflowMenu] seam directly
 * (same pattern as the [ConversationContent] / [ConversationScreenInstrumentationTest]
 * extraction) so the menu can be exercised deterministically without resolving the live
 * {@code ConversationViewModel} from {@code AppContainer}.
 *
 * <p>Covers AC1 (Clear shown ABOVE Disconnect; both reachable), the Clear/Disconnect
 * click routing, and AC7 (the menu closes after Clear is chosen). The controller-level
 * effects of Clear — wipe + `/clear` send + post-clear suppression — are covered by the
 * JVM {@code ConversationControllerTest} (Part F); the live end-to-end gate (AC2/AC8) is
 * QA's runbook verification against a real server + emulator.
 */
@RunWith(AndroidJUnit4::class)
class ConversationOverflowMenuInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun overflow_menu_shows_clear_above_disconnect_and_both_are_reachable() {
        // AC1 — the menu exposes a Clear item positioned ABOVE Disconnect; both remain reachable.
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationOverflowMenu(expanded = true, onClear = {}, onDisconnect = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disconnect").assertIsDisplayed()

        val clearTop = composeTestRule.onNodeWithText("Clear").getBoundsInRoot().top
        val disconnectTop = composeTestRule.onNodeWithText("Disconnect").getBoundsInRoot().top
        assertTrue("Clear must render above Disconnect", clearTop < disconnectTop)
    }

    @Test
    fun tapping_clear_invokes_onClear_only() {
        // Tapping Clear fires onClear and never the Disconnect action.
        var clearCalled = false
        var disconnectCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationOverflowMenu(
                    expanded = true,
                    onClear = { clearCalled = true },
                    onDisconnect = { disconnectCalled = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Clear").performClick()

        assertTrue("Clear must invoke onClear", clearCalled)
        assertFalse("Clear must not invoke onDisconnect", disconnectCalled)
    }

    @Test
    fun tapping_disconnect_still_invokes_onDisconnect_only() {
        // Regression — the pre-existing Disconnect action is unchanged by adding Clear.
        var clearCalled = false
        var disconnectCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationOverflowMenu(
                    expanded = true,
                    onClear = { clearCalled = true },
                    onDisconnect = { disconnectCalled = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Disconnect").performClick()

        assertTrue("Disconnect must invoke onDisconnect", disconnectCalled)
        assertFalse("Disconnect must not invoke onClear", clearCalled)
    }

    @Test
    fun menu_closes_after_clear_is_chosen() {
        // AC7 — choosing Clear closes the menu. Mirrors the screen wiring (onClear flips the
        // expanded state to false), so the dropdown's items leave the composition.
        composeTestRule.setContent {
            AiSandboxTheme {
                var expanded by remember { mutableStateOf(true) }
                ConversationOverflowMenu(
                    expanded = expanded,
                    onClear = { expanded = false },
                    onDisconnect = { expanded = false },
                    onDismiss = { expanded = false },
                )
            }
        }

        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").performClick()
        // The menu is dismissed → its items are no longer present.
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
        composeTestRule.onNodeWithText("Disconnect").assertDoesNotExist()
    }
}
