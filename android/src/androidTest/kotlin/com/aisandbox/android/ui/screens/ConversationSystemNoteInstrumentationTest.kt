package com.aisandbox.android.ui.screens

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-42 (AC1/AC4/AC10) — device-realistic (emulator-tier) coverage for harness-injected
 * user lines in the structured conversation view. Drives the REAL routing + row
 * composables through the [ConversationContent] seam (the same seam UC-39/UC-41 use),
 * so the system-note row's alignment/collapse/expand behaviour is exercised exactly as
 * it renders on screen.
 *
 * Acceptance-criteria mapping:
 *  - AC1  a skill load shows NO right-aligned bubble carrying the SKILL.md body
 *         ([skillLoad_showsNoRightAlignedUserBubble])
 *  - AC4  the system note renders collapsed + left-aligned and expands on tap
 *         ([systemNote_isLeftAligned_collapsed_andExpandsOnTap])
 *
 * <p>The JVM/Robolectric mirror of this behaviour lives in {@code ConversationUiStateTest}
 * (collapse/expand + left-margin) and {@code ConversationControllerTest} Part D
 * (`system-note` frame → SystemNote item, skill-load → single tool row, no user bubble),
 * so the UC-42 render contract is fully covered even when the emulator is unavailable.
 */
@RunWith(AndroidJUnit4::class)
class ConversationSystemNoteInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun rootWidthPx(): Float =
        composeTestRule.onRoot().getUnclippedBoundsInRoot().let { (it.right - it.left).value }

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun saveScreenshot(tag: String) {
        composeTestRule.waitForIdle()
        shell("screencap -p /data/local/tmp/uc42_$tag.png")
    }

    private fun note(
        label: String,
        detail: String,
        uuid: String = "n",
        source: String = "main",
        sidechain: Boolean = false,
    ) = ConversationItem.SystemNote(uuid = uuid, source = source, isSidechain = sidechain, label = label, detail = detail)

    @Test
    fun systemNote_isLeftAligned_collapsed_andExpandsOnTap() {
        val label = "Command: /clear"
        val detail = "<command-name>/clear</command-name> full injected command body"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = listOf(note(label, detail)), modifier = Modifier.fillMaxSize())
            }
        }

        // Collapsed by default: label visible, body hidden until tapped (AC4).
        composeTestRule.onNodeWithText(label).assertIsDisplayed()
        composeTestRule.onNodeWithText(detail).assertDoesNotExist()

        // Left-aligned, non-user: the row starts near the left margin, NOT a right bubble (AC4).
        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        assertTrue("system note should start near the left margin (left=${b.left.value}, w=$w)", b.left.value < w * 0.2f)

        // Tap expands the inline detail.
        composeTestRule.onNodeWithText(label).performClick()
        composeTestRule.onNodeWithText(detail).assertIsDisplayed()

        saveScreenshot("system_note_left_expand")
    }

    @Test
    fun skillLoad_showsNoRightAlignedUserBubble() {
        // AC1 — the server FOLDS the SKILL.md body, so the client only ever renders the
        // Skill tool row (left/full-width). There must be NO second right-aligned bubble.
        val items = listOf(
            ConversationItem.ToolActivity(
                uuid = "t1",
                source = "main",
                isSidechain = false,
                toolName = "Skill",
                toolUseId = "tuS",
                inputSummary = "deep-research",
                primaryText = "deep-research",
                result = null,
            ),
        )
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = items, modifier = Modifier.fillMaxSize())
            }
        }

        // The Skill row renders ("Skill loaded …") near the left margin — full-width, not a bubble.
        val node = composeTestRule.onNodeWithText("Skill loaded deep-research")
        node.assertIsDisplayed()
        val w = rootWidthPx()
        val b = node.getUnclippedBoundsInRoot()
        assertTrue("skill row should start near the left margin (left=${b.left.value}, w=$w)", b.left.value < w * 0.2f)

        saveScreenshot("skill_load_no_user_bubble")
    }
}
