package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.ui.settings.ConversationFontSize
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * UC-53 — device-realistic (emulator-tier) coverage of the conversation-view
 * appearance preferences, driving the REAL routing + bubble composables through
 * the [ConversationContent] seam (the same seam UC-39's alignment test uses).
 *
 * Acceptance-criteria mapping:
 *  - AC2     font scale grows transcript text across bubbles AND tool/meta rows
 *            ([fontScale_growsTranscriptText_acrossBubblesAndToolRows])
 *  - AC3/AC4 agent-color toggle ON tints a subagent assistant bubble; OFF keeps
 *            the neutral background ([agentColorToggle_tintsSubagentBubble_onlyWhenOn])
 *  - AC4     a main-session source stays neutral even with the toggle ON
 *            ([mainSource_staysNeutral_evenWhenToggleOn]); user bubbles are
 *            unaffected ([userBubble_unaffectedByAgentColorToggle])
 *  - AC7     XL + a long message stays within the ~80% width cap, no clip
 *            ([xlFont_longMessage_staysWithinEightyPercent_noClip])
 *
 * Background-color assertions sample the rendered bitmap inside the bubble's
 * 12dp padding (a guaranteed background, not a glyph), because the production
 * [Bubble] exposes no semantics for its resolved tint.
 */
@RunWith(AndroidJUnit4::class)
class ConversationAppearanceInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assistant(text: String, source: String = "main", sidechain: Boolean = false) =
        ConversationItem.AssistantMessage(uuid = "a", source = source, isSidechain = sidechain, text = text)

    private fun user(text: String) =
        ConversationItem.UserMessage(uuid = "u", source = "user", isSidechain = false, text = text)

    private fun bashTool(snippet: String) =
        ConversationItem.ToolActivity(
            uuid = "t",
            source = "main",
            isSidechain = false,
            toolName = "Bash",
            toolUseId = "tu1",
            inputSummary = snippet,
            primaryText = snippet,
            result = null,
        )

    private fun heightOf(text: String): Float =
        composeTestRule.onNodeWithText(text).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    private fun rootWidthDp(): Float =
        composeTestRule.onRoot().getUnclippedBoundsInRoot().let { (it.right - it.left).value }

    /** Sample a background pixel inside the bubble's 12dp padding, just left of the text. */
    private fun bubbleBackgroundArgb(text: String): Int {
        composeTestRule.waitForIdle()
        val bmp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val b = composeTestRule.onNodeWithText(text).getUnclippedBoundsInRoot()
        val density = composeTestRule.density.density
        val x = ((b.left.value - 6f) * density).toInt().coerceIn(0, bmp.width - 1)
        val y = ((b.top.value + 4f) * density).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(x, y)
    }

    private fun channelsClose(a: Int, b: Int, tol: Int = 8): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tol &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol

    @Test
    fun fontScale_growsTranscriptText_acrossBubblesAndToolRows() {
        val bubble = "an assistant message used to measure font growth"
        val toolLabel = "Command used: build" // ToolBubble renders "Command used: <snippet>"
        var scale by mutableStateOf(ConversationFontSize.MEDIUM.scale)

        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(assistant(bubble), bashTool("build")),
                    modifier = Modifier.fillMaxSize(),
                    fontScale = scale,
                )
            }
        }

        composeTestRule.onNodeWithText(bubble).assertIsDisplayed()
        composeTestRule.onNodeWithText(toolLabel).assertIsDisplayed()
        val bubbleMedium = heightOf(bubble)
        val toolMedium = heightOf(toolLabel)

        composeTestRule.runOnUiThread { scale = ConversationFontSize.XLARGE.scale }
        composeTestRule.waitForIdle()
        val bubbleXl = heightOf(bubble)
        val toolXl = heightOf(toolLabel)

        composeTestRule.runOnUiThread { scale = ConversationFontSize.SMALL.scale }
        composeTestRule.waitForIdle()
        val bubbleSmall = heightOf(bubble)
        val toolSmall = heightOf(toolLabel)

        assertTrue("bubble text should grow MEDIUM→XL ($bubbleMedium → $bubbleXl)", bubbleXl > bubbleMedium)
        assertTrue("bubble text should shrink MEDIUM→SMALL ($bubbleMedium → $bubbleSmall)", bubbleSmall < bubbleMedium)
        assertTrue("tool row should grow MEDIUM→XL ($toolMedium → $toolXl)", toolXl > toolMedium)
        assertTrue("tool row should shrink MEDIUM→SMALL ($toolMedium → $toolSmall)", toolSmall < toolMedium)
    }

    @Test
    fun agentColorToggle_tintsSubagentBubble_onlyWhenOn() {
        val msg = "delegated subagent output line"
        var useColor by mutableStateOf(false)
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(assistant(msg, source = "subagent:worker", sidechain = true)),
                    modifier = Modifier.fillMaxSize(),
                    useAgentColor = useColor,
                )
            }
        }

        val offArgb = bubbleBackgroundArgb(msg)
        composeTestRule.runOnUiThread { useColor = true }
        val onArgb = bubbleBackgroundArgb(msg)

        // AC3/AC4 — a real subagent source gets a (subtle) tint when ON; OFF keeps
        // the neutral background, so the two sampled backgrounds must differ.
        assertTrue(
            "subagent bubble background should change when the agent-color toggle flips ON " +
                "(off=${Integer.toHexString(offArgb)} on=${Integer.toHexString(onArgb)})",
            !channelsClose(offArgb, onArgb),
        )
    }

    @Test
    fun mainSource_staysNeutral_evenWhenToggleOn() {
        val msg = "main session assistant line"
        var useColor by mutableStateOf(false)
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(assistant(msg, source = "main")),
                    modifier = Modifier.fillMaxSize(),
                    useAgentColor = useColor,
                )
            }
        }

        val offArgb = bubbleBackgroundArgb(msg)
        composeTestRule.runOnUiThread { useColor = true }
        val onArgb = bubbleBackgroundArgb(msg)

        // AC4 — the main session has no agent color, so the background stays neutral
        // regardless of the toggle.
        assertTrue(
            "main-source bubble must stay neutral when the toggle is ON " +
                "(off=${Integer.toHexString(offArgb)} on=${Integer.toHexString(onArgb)})",
            channelsClose(offArgb, onArgb),
        )
    }

    @Test
    fun userBubble_unaffectedByAgentColorToggle() {
        val msg = "a user message that must keep its bubble"
        var useColor by mutableStateOf(false)
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(user(msg)),
                    modifier = Modifier.fillMaxSize(),
                    useAgentColor = useColor,
                )
            }
        }

        val offArgb = bubbleBackgroundArgb(msg)
        composeTestRule.runOnUiThread { useColor = true }
        val onArgb = bubbleBackgroundArgb(msg)

        // AC4 — user (right-aligned) bubbles are never tinted.
        assertTrue(
            "user bubble background must not change with the agent-color toggle " +
                "(off=${Integer.toHexString(offArgb)} on=${Integer.toHexString(onArgb)})",
            channelsClose(offArgb, onArgb),
        )
    }

    @Test
    fun xlFont_longMessage_staysWithinEightyPercent_noClip() {
        val msg = ("This is a deliberately long assistant message rendered at the XL font step so we can " +
            "confirm that even the largest discrete size keeps the bubble within the eighty-percent width " +
            "cap and never clips or overflows the conversation list horizontally.")
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(assistant(msg)),
                    modifier = Modifier.fillMaxSize(),
                    fontScale = ConversationFontSize.XLARGE.scale,
                )
            }
        }

        composeTestRule.onNodeWithText(msg).assertIsDisplayed()
        val w = rootWidthDp()
        val b = composeTestRule.onNodeWithText(msg).getUnclippedBoundsInRoot()
        val textWidth = (b.right - b.left).value
        // No horizontal clip past the container (AC7) …
        assertTrue("right edge ${b.right.value} must stay within container width $w", b.right.value <= w + 1f)
        // … and still capped at ~80% even at the largest font step.
        assertTrue("text width $textWidth must stay <= ~80% of $w at XL", textWidth <= w * 0.82f)
    }
}
