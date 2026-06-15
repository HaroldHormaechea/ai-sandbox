package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.R
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-84 AC6/AC7 — instrumented coverage for the Settings "Server updates"
 * section, driving the `internal` [ServerUpdateContent] render seam directly
 * with a fabricated [ServerUpdateUiState] (the public sealed interface). The
 * seam is stateless, so each render is deterministic and the callbacks
 * ([onCheck]/[onUpdateClick]/[onConfirm]/[onDismiss]/[onChangelog]) are asserted
 * directly — no ViewModel, no network, server-free on the headless emulator.
 *
 * <p>These compile under {@code :android:compileDebugAndroidTestKotlin}; the
 * release functional gate exercises them live on an emulator.
 *
 * <h2>AC → method map</h2>
 *
 * <ul>
 *   <li>AC6 up-to-date — {@link #up_to_date_shows_version_and_no_update_button()}.</li>
 *   <li>AC6 update available + Changelog — {@link #update_available_shows_update_button_and_changelog_fires_intent()},
 *       {@link #update_available_without_changelog_url_hides_the_link()}.</li>
 *   <li>AC7 confirm dialog gates apply — {@link #confirm_dialog_fires_apply_only_on_confirm()},
 *       {@link #confirm_dialog_cancel_dismisses_without_applying()}.</li>
 *   <li>AC9/AC14 render states — {@link #updating_state_shows_progress_no_buttons()},
 *       {@link #done_state_reports_new_version()}, {@link #error_state_renders_the_failure()}.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class ServerUpdateContentInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Render the seam with capturing no-op callbacks (overridable per test). */
    private fun render(
        state: ServerUpdateUiState,
        onCheck: () -> Unit = {},
        onUpdateClick: () -> Unit = {},
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onChangelog: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            AiSandboxTheme {
                ServerUpdateContent(
                    state = state,
                    onCheck = onCheck,
                    onUpdateClick = onUpdateClick,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                    onChangelog = onChangelog,
                )
            }
        }
    }

    // ── AC6 — up-to-date ────────────────────────────────────────────────────

    @Test
    fun up_to_date_shows_version_and_no_update_button() {
        render(ServerUpdateUiState.UpToDate("9.9.9"))

        composeTestRule.onNodeWithText(ctx.getString(R.string.settings_update_up_to_date, "9.9.9"))
            .assertIsDisplayed()
        // No "Update to version X" button in the up-to-date state (AC6).
        composeTestRule.onNodeWithTag("settings_update_apply").assertDoesNotExist()
        // A re-check button is still offered.
        composeTestRule.onNodeWithTag("settings_update_check").assertIsDisplayed()
    }

    // ── AC6 — update available + Changelog ──────────────────────────────────

    @Test
    fun update_available_shows_update_button_and_changelog_fires_intent() {
        var updateClicked = false
        var changelogUrl: String? = null
        render(
            ServerUpdateUiState.UpdateAvailable("0.0.9", "9.9.9", "https://gh/server-v9.9.9"),
            onUpdateClick = { updateClicked = true },
            onChangelog = { changelogUrl = it },
        )

        // "Update to version 9.9.9" button (AC6) — tapping it requests the update.
        val apply = composeTestRule.onNodeWithTag("settings_update_apply")
        apply.assertIsDisplayed().assertHasClickAction()
        composeTestRule.onNodeWithText(ctx.getString(R.string.settings_update_available, "9.9.9"))
            .assertIsDisplayed()
        apply.performClick()
        assertTrue("tapping Update requests the apply flow", updateClicked)

        // Changelog link fires the external-browser intent callback with the URL (AC6).
        composeTestRule.onNodeWithTag("settings_update_changelog").assertIsDisplayed().performClick()
        assertEquals("Changelog opens the release HTML URL", "https://gh/server-v9.9.9", changelogUrl)
    }

    @Test
    fun update_available_without_changelog_url_hides_the_link() {
        render(ServerUpdateUiState.UpdateAvailable("0.0.9", "9.9.9", null))

        composeTestRule.onNodeWithTag("settings_update_apply").assertIsDisplayed()
        // No release URL -> no Changelog link.
        composeTestRule.onNodeWithTag("settings_update_changelog").assertDoesNotExist()
    }

    // ── AC7 — confirm dialog gates apply ────────────────────────────────────

    @Test
    fun confirm_dialog_fires_apply_only_on_confirm() {
        var confirmed = false
        var dismissed = false
        render(
            ServerUpdateUiState.Confirming("0.0.9", "9.9.9", "https://gh/server-v9.9.9"),
            onConfirm = { confirmed = true },
            onDismiss = { dismissed = true },
        )

        // The in-app confirm dialog is up (AC7).
        composeTestRule.onNodeWithText(ctx.getString(R.string.settings_update_confirm_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_update_cancel").assertIsDisplayed()

        // Apply is invoked ONLY on explicit confirm.
        composeTestRule.onNodeWithTag("settings_update_confirm").assertIsDisplayed().performClick()
        assertTrue("apply fires on confirm", confirmed)
        assertFalse("confirm must not also dismiss", dismissed)
    }

    @Test
    fun confirm_dialog_cancel_dismisses_without_applying() {
        var confirmed = false
        var dismissed = false
        render(
            ServerUpdateUiState.Confirming("0.0.9", "9.9.9", null),
            onConfirm = { confirmed = true },
            onDismiss = { dismissed = true },
        )

        composeTestRule.onNodeWithTag("settings_update_cancel").assertIsDisplayed().performClick()
        // Cancelling the dialog must NOT fire apply (AC7 — apply only on explicit confirm).
        assertTrue("cancel dismisses", dismissed)
        assertFalse("cancel must never apply", confirmed)
    }

    // ── AC9/AC14 — updating / done / error render ───────────────────────────

    @Test
    fun updating_state_shows_progress_no_buttons() {
        render(ServerUpdateUiState.Updating("9.9.9"))

        composeTestRule.onNodeWithText(ctx.getString(R.string.settings_update_updating)).assertIsDisplayed()
        // No actionable buttons while the install/restart is in flight.
        composeTestRule.onNodeWithTag("settings_update_apply").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings_update_check").assertDoesNotExist()
    }

    @Test
    fun done_state_reports_new_version() {
        render(ServerUpdateUiState.Done("9.9.9"))

        // AC9 — the now-running version is reported after the server returns.
        composeTestRule.onNodeWithText(ctx.getString(R.string.settings_update_done, "9.9.9")).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_update_check").assertIsDisplayed()
    }

    @Test
    fun error_state_renders_the_failure() {
        render(ServerUpdateUiState.Error("update_github_unreachable", "GitHub down"))

        // AC14 — failures surface in the UI; the section stays usable (re-check offered).
        composeTestRule.onNodeWithText(
            ctx.getString(R.string.settings_update_error, "update_github_unreachable: GitHub down"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_update_check").assertIsDisplayed()
    }
}
