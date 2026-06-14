package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-53 AC1/AC5 — the restructured Settings screen: a top **Appearance** group
 * (font size + agent-color toggle + the UC-36 keyboard toggle kept top-level)
 * sitting above an **Info** group that now holds the previously-top-level
 * read-only sections (Server, Client identity, WebSocket, Diagnostics) and the
 * version footer.
 *
 * <p>{@link SettingsScreen} builds its own dependencies via {@code requireContainer}
 * off the real {@code AiSandboxApplication}, so the screen renders on-device with
 * a null server profile / no imported identity — exactly the fresh-install state.
 *
 * <p>As of UC-68 the content {@code Column} carries a {@code verticalScroll}
 * modifier (inside the Scaffold {@code innerPadding}, so the top app bar stays
 * pinned). A scrolling Column still composes and positions every child even
 * when it falls below the fold, so the ordering assertions below remain valid:
 * they read {@code getUnclippedBoundsInRoot} (defined off-screen) and use
 * {@code assertExists} rather than {@code assertIsDisplayed} for lower items.
 *
 * Acceptance-criteria mapping:
 *  - AC1  Appearance group above Info; read-only sections + footer under Info
 *  - AC1  (UC-68) the footer is reachable by scrolling on a short viewport
 *  - AC5  UC-36 keyboard toggle reachable as a top-level (Appearance) preference
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SettingsScreen(onBack = {})
            }
        }
    }

    private fun topOf(text: String, substring: Boolean = false): Float =
        composeTestRule.onNodeWithText(text, substring = substring)
            .getUnclippedBoundsInRoot().top.value

    /**
     * UC-68 AC1 — the regression target. Settings used to be a plain,
     * non-scrolling Column, so on a viewport shorter than the full content the
     * version footer was clipped off the bottom and unreachable. We render the
     * screen inside a deliberately short ({@code requiredHeight = 360.dp}) box
     * to force overflow, then prove the footer is brought on-screen by
     * scrolling. {@code performScrollTo()} succeeds only because UC-68 added the
     * {@code verticalScroll}; on the pre-fix Column it would throw (no scroll
     * ancestor), which is exactly the bug this guards against.
     */
    @Test
    fun footer_isReachableByScrolling_onAShortViewport() {
        composeTestRule.setContent {
            AiSandboxTheme {
                Box(Modifier.fillMaxWidth().requiredHeight(360.dp)) {
                    SettingsScreen(onBack = {})
                }
            }
        }

        // The footer starts below the fold on this short viewport; scrolling
        // must reveal it (and the TopAppBar title stays pinned — AC4).
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("ai-sandbox-android", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // App bar title is still displayed after the scroll → chrome is pinned,
        // only the content region scrolled.
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun appearanceGroup_sitsAboveInfoGroup_withReadOnlySectionsAndFooterUnderInfo() {
        render()

        // Both group headers exist; Appearance is at the very top of the surface.
        composeTestRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Info").assertExists()

        val appearanceTop = topOf("Appearance")
        val infoTop = topOf("Info")
        assertTrue("Appearance group must sit above Info ($appearanceTop < $infoTop)", appearanceTop < infoTop)

        // AC1 — the previously top-level read-only sections are now demoted under
        // Info (Section titles render uppercased).
        for (section in listOf("SERVER", "CLIENT IDENTITY", "WEBSOCKET", "DIAGNOSTICS")) {
            composeTestRule.onNodeWithText(section).assertExists()
            assertTrue("$section must live below the Info header", topOf(section) > infoTop)
        }

        // AC1 — the version footer is under Info too.
        composeTestRule.onNodeWithText("ai-sandbox-android", substring = true).assertExists()
        assertTrue("footer must live below the Info header", topOf("ai-sandbox-android", substring = true) > infoTop)
    }

    @Test
    fun appearanceControls_andKeyboardToggle_areTopLevelAboveInfo() {
        render()

        val infoTop = topOf("Info")

        // AC2 — the font-size control + its discrete steps are in Appearance.
        composeTestRule.onNodeWithText("Font size").assertExists()
        assertTrue("font-size control must be in the Appearance group", topOf("Font size") < infoTop)
        composeTestRule.onNodeWithText("S").assertExists()
        composeTestRule.onNodeWithText("XL").assertExists()

        // AC3 — the agent-color toggle is in Appearance.
        composeTestRule.onNodeWithText("Use agent color in bubbles").assertExists()
        assertTrue(
            "agent-color toggle must be in the Appearance group",
            topOf("Use agent color in bubbles") < infoTop,
        )

        // AC5 — the UC-36 keyboard conversational toggle stays a TOP-LEVEL
        // preference (above Info), not buried in the read-only Info group.
        composeTestRule.onNodeWithText("Conversational keyboard").assertExists()
        assertTrue(
            "the UC-36 keyboard toggle must remain a top-level Appearance preference",
            topOf("Conversational keyboard") < infoTop,
        )
    }
}
