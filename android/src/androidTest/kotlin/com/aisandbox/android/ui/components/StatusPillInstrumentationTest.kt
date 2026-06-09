package com.aisandbox.android.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-28 AC10 — instrumented coverage for [StatusPill]'s label mapping across
 * the status value set, including the new `terminating` token and the graceful
 * fallback for unknown/future tokens.
 *
 * <p>HONESTY CAVEAT (recorded for the coverage summary): these assert the
 * rendered LABEL text (the deterministically queryable surface). The
 * destructive-red [com.aisandbox.android.ui.theme.ErrorTone] treatment and the
 * animated/indeterminate "spinner-dot" pulse are visual properties of the pill
 * — pinned by the JVM [com.aisandbox.android.ui.theme.ThemeTokensTest] (the
 * ErrorTone token) and the source (`pulse = true` only on the terminating
 * branch); the on-screen colour/animation remain a manual emulator check. This
 * file's job is to pin the label + unknown-token graceful render on a real
 * device.
 */
@RunWith(AndroidJUnit4::class)
class StatusPillInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun terminating_token_renders_terminating_label() {
        composeTestRule.setContent {
            AiSandboxTheme { StatusPill(state = "terminating") }
        }
        composeTestRule.onNodeWithText("terminating").assertIsDisplayed()
    }

    @Test
    fun running_token_renders_running_label() {
        composeTestRule.setContent {
            AiSandboxTheme { StatusPill(state = "running") }
        }
        composeTestRule.onNodeWithText("running").assertIsDisplayed()
    }

    @Test
    fun provisioning_token_renders_installing_label() {
        composeTestRule.setContent {
            AiSandboxTheme { StatusPill(state = "provisioning") }
        }
        // UC-27 — provisioning maps to the friendlier "installing…" label.
        composeTestRule.onNodeWithText("installing…").assertIsDisplayed()
    }

    /**
     * AC10 — an unknown/never-seen token still renders gracefully: the raw token
     * is shown verbatim (neutral treatment), so a future server state never
     * wedges the UI on a missing mapping.
     */
    @Test
    fun unknown_token_renders_raw_label() {
        composeTestRule.setContent {
            AiSandboxTheme { StatusPill(state = "frobnicate") }
        }
        composeTestRule.onNodeWithText("frobnicate").assertIsDisplayed()
    }

    /**
     * UC-46 AC5 — the new `paused` token renders the dedicated "paused" label
     * (a frozen, resumable container — distinct from `stopped`). Like the
     * UC-28 caveat above, this asserts the rendered LABEL; the subdued/hollow-
     * dot visual treatment is pinned by the source and remains a manual
     * emulator check.
     */
    @Test
    fun paused_token_renders_paused_label() {
        composeTestRule.setContent {
            AiSandboxTheme { StatusPill(state = "paused") }
        }
        composeTestRule.onNodeWithText("paused").assertIsDisplayed()
    }
}
