package com.aisandbox.android.ui.screens

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-39 — device-realistic (emulator-tier) coverage for the chat-style aligned
 * message bubbles in the structured conversation view. Drives the REAL routing
 * + bubble composables through the [ConversationContent] seam with real
 * [ConversationItem] data, so alignment / max-width / label / overflow behaviour
 * is exercised exactly as it renders on screen — then captures screenshot
 * evidence under the app's external files dir for the gate report.
 *
 * Acceptance-criteria mapping:
 *  - AC1/AC4   assistant + subagent bubbles left-aligned, label retained ([assistantBubble_isLeftAligned_withClaudeLabel], [subagentBubble_showsAgentSubagentLabel_leftAligned])
 *  - AC2/AC3   user bubble right-aligned, "You" dropped ([userBubble_isRightAligned_withoutYouLabel])
 *  - AC3       ~80% max width + wrap ([longMessage_capsAtAboutEightyPercent_andWraps])
 *  - AC9       long unbroken token stays inside the container ([longUnbrokenToken_staysWithinContainer])
 *  - AC5/AC6/AC7 colors + meta full-width + spacing — visual, captured in [allScenarios_render_and_capture_screenshot]
 */
@RunWith(AndroidJUnit4::class)
class ConversationBubbleAlignmentInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun rootWidthPx(): Float = composeTestRule.onRoot().getUnclippedBoundsInRoot().let { (it.right - it.left).value }

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // drain → blocks until done
    }

    private fun saveScreenshot(tag: String) {
        composeTestRule.waitForIdle()
        // Capture as the shell uid straight into a shell-owned path: it survives the post-test app
        // uninstall (so it can be `adb pull`ed) and sidesteps scoped-storage limits on the app dir.
        // The composable under test is in the foreground, so screencap records exactly what's rendered.
        shell("screencap -p /data/local/tmp/uc39_$tag.png")
    }

    private fun user(text: String, uuid: String = "u") =
        ConversationItem.UserMessage(uuid = uuid, source = "user", isSidechain = false, text = text)

    private fun assistant(text: String, uuid: String = "a", source: String = "main", sidechain: Boolean = false) =
        ConversationItem.AssistantMessage(uuid = uuid, source = source, isSidechain = sidechain, text = text)

    @Test
    fun userBubble_isRightAligned_withoutYouLabel() {
        val msg = "ack got it"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = listOf(user(msg)), modifier = Modifier.fillMaxSize())
            }
        }

        composeTestRule.onNodeWithText(msg).assertIsDisplayed()
        // "You" label is dropped for user messages (AC4).
        composeTestRule.onNodeWithText("You").assertDoesNotExist()

        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(msg).getUnclippedBoundsInRoot()
        val leftGap = b.left.value
        val rightGap = w - b.right.value
        // Right-aligned: the empty space on the LEFT is larger than on the right (AC2).
        assertTrue("expected right alignment: leftGap=$leftGap rightGap=$rightGap width=$w", leftGap > rightGap + w * 0.2f)

        saveScreenshot("user_right")
    }

    @Test
    fun assistantBubble_isLeftAligned_withClaudeLabel() {
        val msg = "Hello from the assistant side"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = listOf(assistant(msg)), modifier = Modifier.fillMaxSize())
            }
        }

        composeTestRule.onNodeWithText(msg).assertIsDisplayed()
        // Assistant keeps its "Claude" label (AC4).
        composeTestRule.onNodeWithText("Claude").assertIsDisplayed()

        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(msg).getUnclippedBoundsInRoot()
        val leftGap = b.left.value
        val rightGap = w - b.right.value
        // Left-aligned: the empty space on the RIGHT is larger than on the left (AC1).
        assertTrue("expected left alignment: leftGap=$leftGap rightGap=$rightGap width=$w", rightGap > leftGap + w * 0.2f)

        saveScreenshot("assistant_left")
    }

    @Test
    fun subagentBubble_showsAgentSubagentLabel_leftAligned() {
        val msg = "delegated work output"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(assistant(msg, source = "subagent:worker", sidechain = true)),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // source starts with "subagent:" -> "Agent"; isSidechain -> " · subagent" (AC4).
        composeTestRule.onNodeWithText("Agent · subagent").assertIsDisplayed()
        composeTestRule.onNodeWithText(msg).assertIsDisplayed()

        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(msg).getUnclippedBoundsInRoot()
        assertTrue("subagent bubble should be left-aligned", (w - b.right.value) > b.left.value)

        saveScreenshot("subagent_left")
    }

    @Test
    fun longMessage_capsAtAboutEightyPercent_andWraps() {
        // Long, naturally-wrapping prose well beyond 80% of any phone width.
        val msg = ("This is a deliberately long assistant message that should grow up to about eighty " +
            "percent of the conversation list width and then wrap onto additional lines instead of " +
            "filling the full container width like the old full-width blocks used to do before UC-39.")
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = listOf(assistant(msg)), modifier = Modifier.fillMaxSize())
            }
        }

        composeTestRule.onNodeWithText(msg).assertIsDisplayed()
        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(msg).getUnclippedBoundsInRoot()
        val textWidth = (b.right - b.left).value
        // Hit the ~80% cap (so it grew wide and wrapped), but never full-width.
        assertTrue("text width $textWidth should exceed 50% of $w (wrapped at cap)", textWidth > w * 0.5f)
        assertTrue("text width $textWidth should stay <= ~80% of $w (not full-width)", textWidth <= w * 0.82f)

        saveScreenshot("long_wrap_80pct")
    }

    @Test
    fun longUnbrokenToken_staysWithinContainer() {
        // A ~200-char unbroken token (URL/code) — must wrap/stay inside the bubble cap (AC9).
        val token = "https://example.com/" + "a".repeat(180) + "/end"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = listOf(user(token)), modifier = Modifier.fillMaxSize())
            }
        }

        composeTestRule.onNodeWithText(token).assertIsDisplayed()
        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(token).getUnclippedBoundsInRoot()
        // No horizontal overflow past the container, and capped at ~80%.
        val tokenWidth = (b.right - b.left).value
        assertTrue("right edge ${b.right.value} must stay within container width $w", b.right.value <= w + 1f)
        assertTrue("token bubble width $tokenWidth must stay <= ~80% of $w", tokenWidth <= w * 0.82f)

        saveScreenshot("long_token_nooverflow")
    }

    @Test
    fun allScenarios_render_and_capture_screenshot() {
        val items = listOf(
            user("Short user message", uuid = "u1"),
            assistant("Short Claude reply", uuid = "a1"),
            assistant("Subagent reply line", uuid = "a2", source = "subagent:worker", sidechain = true),
            assistant(
                "A long assistant message that wraps at roughly eighty percent of the list width " +
                    "rather than spanning the entire container as before.",
                uuid = "a3",
            ),
            user("https://example.com/" + "z".repeat(160) + "/x", uuid = "u2"),
            ConversationItem.ToolUse(
                uuid = "t1",
                source = "main",
                isSidechain = false,
                toolName = "Bash",
                toolUseId = "tu1",
                inputSummary = "ls -la /workspace",
            ),
            ConversationItem.Thinking(uuid = "th1", source = "main", isSidechain = false, text = "considering options"),
        )
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = items, modifier = Modifier.fillMaxSize())
            }
        }

        // Meta line renders unchanged, left/full-width (AC6) — its prefix sits at the left margin.
        composeTestRule.onNodeWithText("▸ Bash").assertIsDisplayed()
        val w = rootWidthPx()
        val meta = composeTestRule.onNodeWithText("▸ Bash").getUnclippedBoundsInRoot()
        assertTrue("meta prefix should start near the left margin (full-width row)", meta.left.value < w * 0.2f)

        saveScreenshot("all_scenarios")
    }
}
