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
 * UC-65 / UC-66 — instrumented (emulator-tier) coverage for the conversation
 * overflow menu. Drives the `internal` [ConversationOverflowMenu] seam directly
 * (same pattern as the [ConversationContent] / [ConversationScreenInstrumentationTest]
 * extraction) so the menu can be exercised deterministically without resolving the
 * live {@code ConversationViewModel} from {@code AppContainer}.
 *
 * <p>UC-65 covers the **Clear** action (shown above Disconnect, click routing,
 * menu-closes-after). UC-66 adds the **Model** item (AC1): it is present, it
 * renders above Clear, and tapping it fires `onModel` only (the dialog open +
 * `/model <id>` send are covered by [ModelSelectionDialogInstrumentationTest]
 * and the JVM {@code ConversationControllerTest}; the live end-to-end gate
 * (AC8) is QA's runbook verification against a real server + emulator).
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
                ConversationOverflowMenu(
                    expanded = true,
                    onModel = {},
                    onClear = {},
                    onDisconnect = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
        composeTestRule.onNodeWithText("Disconnect").assertIsDisplayed()

        val clearTop = composeTestRule.onNodeWithText("Clear").getBoundsInRoot().top
        val disconnectTop = composeTestRule.onNodeWithText("Disconnect").getBoundsInRoot().top
        assertTrue("Clear must render above Disconnect", clearTop < disconnectTop)
    }

    @Test
    fun overflow_menu_shows_model_item_above_clear() {
        // UC-66 AC1 — the menu exposes a Model item; it renders at the top, above Clear.
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationOverflowMenu(
                    expanded = true,
                    onModel = {},
                    onClear = {},
                    onDisconnect = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Model").assertIsDisplayed()
        val modelTop = composeTestRule.onNodeWithText("Model").getBoundsInRoot().top
        val clearTop = composeTestRule.onNodeWithText("Clear").getBoundsInRoot().top
        assertTrue("Model must render above Clear", modelTop < clearTop)
    }

    @Test
    fun tapping_model_invokes_onModel_only() {
        // UC-66 AC1/AC2 — tapping Model fires onModel (which opens the dialog) and
        // neither the Clear nor the Disconnect action.
        var modelCalled = false
        var clearCalled = false
        var disconnectCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationOverflowMenu(
                    expanded = true,
                    onModel = { modelCalled = true },
                    onClear = { clearCalled = true },
                    onDisconnect = { disconnectCalled = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Model").performClick()

        assertTrue("Model must invoke onModel", modelCalled)
        assertFalse("Model must not invoke onClear", clearCalled)
        assertFalse("Model must not invoke onDisconnect", disconnectCalled)
    }

    @Test
    fun tapping_clear_invokes_onClear_only() {
        // Tapping Clear fires onClear and never the Model or Disconnect action.
        var modelCalled = false
        var clearCalled = false
        var disconnectCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationOverflowMenu(
                    expanded = true,
                    onModel = { modelCalled = true },
                    onClear = { clearCalled = true },
                    onDisconnect = { disconnectCalled = true },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Clear").performClick()

        assertTrue("Clear must invoke onClear", clearCalled)
        assertFalse("Clear must not invoke onModel", modelCalled)
        assertFalse("Clear must not invoke onDisconnect", disconnectCalled)
    }

    @Test
    fun tapping_disconnect_still_invokes_onDisconnect_only() {
        // Regression — the pre-existing Disconnect action is unchanged by adding Clear/Model.
        var clearCalled = false
        var disconnectCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationOverflowMenu(
                    expanded = true,
                    onModel = {},
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
                    onModel = { expanded = false },
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

    @Test
    fun menu_closes_after_model_is_chosen() {
        // UC-66 AC2 — choosing Model closes the menu (the screen then opens the dialog).
        composeTestRule.setContent {
            AiSandboxTheme {
                var expanded by remember { mutableStateOf(true) }
                ConversationOverflowMenu(
                    expanded = expanded,
                    onModel = { expanded = false },
                    onClear = { expanded = false },
                    onDisconnect = { expanded = false },
                    onDismiss = { expanded = false },
                )
            }
        }

        composeTestRule.onNodeWithText("Model").assertIsDisplayed()
        composeTestRule.onNodeWithText("Model").performClick()
        composeTestRule.onNodeWithText("Model").assertDoesNotExist()
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
    }
}
