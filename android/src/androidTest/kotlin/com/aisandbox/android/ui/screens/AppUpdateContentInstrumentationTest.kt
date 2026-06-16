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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-87 — instrumented coverage for the dedicated app-update screen, driving the
 * `internal` [AppUpdateContent] render seam directly with a fabricated
 * [AppUpdateUiState] (the public sealed interface). The seam is stateless, so
 * each render is deterministic and the callbacks ([onUpdate]/[onRetry]/[onChangelog])
 * are asserted directly — no ViewModel, no network, server-free on the headless
 * emulator (mirrors [ServerUpdateContentInstrumentationTest]).
 *
 * <p>These compile under {@code :android:compileDebugAndroidTestKotlin}; the
 * release functional gate exercises the full flow live on an emulator.
 *
 * <h2>AC → test map</h2>
 *
 * <ul>
 *   <li>AC2 (loader) — {@link #checking_state_shows_the_loader()}.</li>
 *   <li>AC3 (up to date + version, no action) — {@link #up_to_date_shows_version_and_no_update_button()}.</li>
 *   <li>AC4 (old→new transition + Update + Changelog) —
 *       {@link #update_available_shows_transition_update_button_and_changelog()},
 *       {@link #update_available_without_changelog_url_hides_the_link()}.</li>
 *   <li>AC5 (download progress) — {@link #downloading_state_shows_progress()}.</li>
 *   <li>AC6 (installing) — {@link #installing_state_shows_progress_indicator()}.</li>
 *   <li>AC8 (error + retry) — {@link #error_state_renders_failure_and_retry_fires()}.</li>
 *   <li>AC9 (debug notice, no action) — {@link #debug_build_shows_release_only_notice()}.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateContentInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Render the seam with capturing no-op callbacks (overridable per test). */
    private fun render(
        state: AppUpdateUiState,
        onUpdate: () -> Unit = {},
        onRetry: () -> Unit = {},
        onChangelog: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            AiSandboxTheme {
                AppUpdateContent(
                    state = state,
                    onUpdate = onUpdate,
                    onRetry = onRetry,
                    onChangelog = onChangelog,
                )
            }
        }
    }

    // ── AC2 — loader ────────────────────────────────────────────────────────

    @Test
    fun checking_state_shows_the_loader() {
        render(AppUpdateUiState.Checking)
        composeTestRule.onNodeWithTag("app_update_checking").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.app_update_checking)).assertIsDisplayed()
    }

    // ── AC3 — up to date ────────────────────────────────────────────────────

    @Test
    fun up_to_date_shows_version_and_no_update_button() {
        render(AppUpdateUiState.UpToDate("0.4.15"))
        composeTestRule.onNodeWithTag("app_update_up_to_date").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.app_update_up_to_date, "0.4.15"))
            .assertIsDisplayed()
        // AC3 — no Update action when already current.
        composeTestRule.onNodeWithTag("app_update_action").assertDoesNotExist()
    }

    // ── AC4 — update available: transition + Update + Changelog ─────────────

    @Test
    fun update_available_shows_transition_update_button_and_changelog() {
        var updateClicked = false
        var changelogUrl: String? = null
        render(
            AppUpdateUiState.UpdateAvailable("0.4.15", "0.5.0", "https://gh/android-v0.5.0"),
            onUpdate = { updateClicked = true },
            onChangelog = { changelogUrl = it },
        )
        // AC4 — old → new transition copy.
        composeTestRule.onNodeWithText(ctx.getString(R.string.app_update_transition, "0.4.15", "0.5.0"))
            .assertIsDisplayed()
        // AC4 — the Update action fires onUpdate.
        val action = composeTestRule.onNodeWithTag("app_update_action")
        action.assertIsDisplayed().assertHasClickAction()
        action.performClick()
        assertTrue("tapping Update fires onUpdate", updateClicked)
        // Changelog link opens the release HTML URL.
        composeTestRule.onNodeWithTag("app_update_changelog").assertIsDisplayed().performClick()
        assertEquals("Changelog opens the release HTML URL", "https://gh/android-v0.5.0", changelogUrl)
    }

    @Test
    fun update_available_without_changelog_url_hides_the_link() {
        render(AppUpdateUiState.UpdateAvailable("0.4.15", "0.5.0", null))
        composeTestRule.onNodeWithTag("app_update_action").assertIsDisplayed()
        // No release URL → no Changelog link.
        composeTestRule.onNodeWithTag("app_update_changelog").assertDoesNotExist()
    }

    // ── AC5 — download progress ─────────────────────────────────────────────

    @Test
    fun downloading_state_shows_progress() {
        render(AppUpdateUiState.Downloading(42))
        composeTestRule.onNodeWithTag("app_update_progress").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.app_update_downloading, 42))
            .assertIsDisplayed()
        // No retry/Update affordance while a download is in flight.
        composeTestRule.onNodeWithTag("app_update_retry").assertDoesNotExist()
        composeTestRule.onNodeWithTag("app_update_action").assertDoesNotExist()
    }

    // ── AC6 — installing ────────────────────────────────────────────────────

    @Test
    fun installing_state_shows_progress_indicator() {
        render(AppUpdateUiState.Installing)
        composeTestRule.onNodeWithText(ctx.getString(R.string.app_update_installing)).assertIsDisplayed()
    }

    // ── AC8 — error + retry ─────────────────────────────────────────────────

    @Test
    fun error_state_renders_failure_and_retry_fires() {
        var retried = false
        render(
            AppUpdateUiState.Error("unreachable", "GitHub is unreachable"),
            onRetry = { retried = true },
        )
        // AC8 — the failure is surfaced (never a crash) and is retryable.
        composeTestRule.onNodeWithText(ctx.getString(R.string.app_update_error, "GitHub is unreachable"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("app_update_retry").assertIsDisplayed().performClick()
        assertTrue("Retry fires onRetry", retried)
    }

    // ── AC9 — debug build notice ────────────────────────────────────────────

    @Test
    fun debug_build_shows_release_only_notice() {
        render(AppUpdateUiState.DebugBuild("0.4.15"))
        composeTestRule.onNodeWithTag("app_update_debug_notice").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.app_update_debug_notice, "0.4.15"))
            .assertIsDisplayed()
        // AC9 — a debug build offers no Update action and no download progress.
        composeTestRule.onNodeWithTag("app_update_action").assertDoesNotExist()
        composeTestRule.onNodeWithTag("app_update_progress").assertDoesNotExist()
    }
}
