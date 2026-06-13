package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * <p>The screen is a non-scrolling {@code Column}: every child is composed and
 * positioned even when it falls below the fold, so ordering is asserted from
 * {@code getUnclippedBoundsInRoot} (valid off-screen) and lower items use
 * {@code assertExists} rather than {@code assertIsDisplayed}.
 *
 * Acceptance-criteria mapping:
 *  - AC1  Appearance group above Info; read-only sections + footer under Info
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
