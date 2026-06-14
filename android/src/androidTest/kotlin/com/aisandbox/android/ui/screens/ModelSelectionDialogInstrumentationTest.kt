package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.net.ModelInfo
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-66 — instrumented (emulator-tier) coverage for the internal
 * [ModelSelectionDialog] seam. Drives the composable directly with each
 * [ModelMenuState] so the dialog's rendering + interaction are exercised
 * deterministically without resolving the live {@code ConversationViewModel}
 * from {@code AppContainer} (same extraction pattern as
 * [ConversationOverflowMenuInstrumentationTest]).
 *
 * <p>Covers AC2 (lists the server-reported models with their labels), AC5 (the
 * current/selected model is highlighted with a ✓), the select callback (sends
 * `/model <id>`), and AC6 (dismiss without choosing changes nothing), plus the
 * loading / empty / error states. The wire-level `/model <id>` send is covered
 * by the JVM {@code ConversationControllerTest}; the live end-to-end gate (AC8)
 * is QA's runbook verification against a real server + emulator.
 */
@RunWith(AndroidJUnit4::class)
class ModelSelectionDialogInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val catalogue = listOf(
        ModelInfo(id = "opus", label = "Opus 4.8"),
        ModelInfo(id = "sonnet", label = "Sonnet 4.5"),
        ModelInfo(id = "haiku", label = "Haiku (latest)"),
    )

    @Test
    fun loaded_state_lists_every_server_model_by_label() {
        // AC2 — one selectable row per server-reported model, each showing its label.
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Loaded(catalogue),
                    selectedModelId = null,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Opus 4.8").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sonnet 4.5").assertIsDisplayed()
        composeTestRule.onNodeWithText("Haiku (latest)").assertIsDisplayed()
    }

    @Test
    fun a_blank_label_falls_back_to_the_model_id() {
        // The wire DTO defaults label to "" — the row must still be selectable, showing the id.
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Loaded(listOf(ModelInfo(id = "claude-opus-4-8", label = ""))),
                    selectedModelId = null,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("claude-opus-4-8").assertIsDisplayed()
    }

    @Test
    fun the_selected_model_row_is_highlighted_with_a_check() {
        // AC5 — the row whose id matches selectedModelId shows the ✓ highlight (best-effort
        // client-side last-selection).
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Loaded(catalogue),
                    selectedModelId = "sonnet",
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        // The checkmark is present exactly once (for the single selected row).
        composeTestRule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun tapping_a_model_invokes_onSelect_with_its_id() {
        // AC4 (UI seam) — tapping a row reports the model's id so the caller sends `/model <id>`.
        var selected: String? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Loaded(catalogue),
                    selectedModelId = null,
                    onSelect = { selected = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Sonnet 4.5").performClick()

        assertEquals("sonnet", selected)
    }

    @Test
    fun dismissing_via_close_changes_nothing() {
        // AC6 — dismissing the dialog without choosing leaves the model unchanged: onSelect is
        // never called, only onDismiss.
        var selected: String? = null
        var dismissed = false
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Loaded(catalogue),
                    selectedModelId = "opus",
                    onSelect = { selected = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Close").performClick()

        assertNull("AC6 — no model is selected on dismiss", selected)
        assertTrue("dismiss fired", dismissed)
    }

    @Test
    fun loading_state_shows_a_spinner_label() {
        // AC2 — while GET /v1/models is in flight the dialog shows a loading affordance.
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Loading,
                    selectedModelId = null,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed()
    }

    @Test
    fun empty_state_shows_no_models_message() {
        // AC3 — a server with no configured models surfaces a clear empty message.
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Empty,
                    selectedModelId = null,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No models available").assertIsDisplayed()
    }

    @Test
    fun error_state_shows_the_failure_message() {
        // A transport / HTTP / not-enrolled failure renders a readable error rather than spinning.
        composeTestRule.setContent {
            AiSandboxTheme {
                ModelSelectionDialog(
                    state = ModelMenuState.Error("Not enrolled"),
                    selectedModelId = null,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Could not load models: Not enrolled").assertIsDisplayed()
    }
}
