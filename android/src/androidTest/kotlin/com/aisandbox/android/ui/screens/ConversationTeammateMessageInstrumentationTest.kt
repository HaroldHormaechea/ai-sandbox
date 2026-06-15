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
 * UC-58 (AC1/AC2/AC3) — device-realistic (emulator-tier) coverage for the inbound
 * teammate/subagent message bubble in the structured conversation view. Drives the REAL
 * routing + bubble composables through the [ConversationContent] seam (the same seam
 * UC-39/UC-41/UC-42/UC-53 use) with real [ConversationItem.TeammateMessage] data, so the
 * teammate bubble's alignment + sender label is exercised exactly as it renders on screen.
 *
 * Acceptance-criteria mapping:
 *  - AC1  a teammate message renders as a distinct, NON-user (LEFT-aligned) bubble
 *         ([teammateBubble_isLeftAligned_distinctFromUser])
 *  - AC2  the bubble is labelled with the sender (teammate_id), with colour present AND
 *         absent ([teammateBubble_isLabelledWithSender], [teammateBubble_withoutColor_stillLabelledAndLeftAligned])
 *  - AC3  the user's own message stays right-aligned alongside it
 *         ([teammateBubble_isLeftAligned_distinctFromUser])
 *
 * The JVM mirror of the render contract (frame → item, render-only, no sheet/turn-phase
 * disturbance, UC-65 guard drop) lives in `ConversationControllerTest` Part D2, so the
 * UC-58 contract is covered even when the emulator is unavailable.
 */
@RunWith(AndroidJUnit4::class)
class ConversationTeammateMessageInstrumentationTest {

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
        shell("screencap -p /data/local/tmp/uc58_$tag.png")
    }

    private fun teammate(
        text: String,
        teammateId: String = "analyst",
        color: String? = "blue",
        uuid: String = "tm",
        sidechain: Boolean = false,
    ) = ConversationItem.TeammateMessage(
        uuid = uuid,
        source = "main",
        isSidechain = sidechain,
        teammateId = teammateId,
        color = color,
        text = text,
    )

    private fun user(text: String, uuid: String = "u") =
        ConversationItem.UserMessage(uuid = uuid, source = "user", isSidechain = false, text = text)

    @Test
    fun teammateBubble_isLabelledWithSender() {
        val msg = "Analysis complete, handing off to the developer"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(items = listOf(teammate(msg, teammateId = "analyst")), modifier = Modifier.fillMaxSize())
            }
        }

        // AC2 — the bubble is attributed to the sending teammate by name (teammate_id).
        composeTestRule.onNodeWithText("analyst").assertIsDisplayed()
        composeTestRule.onNodeWithText(msg).assertIsDisplayed()

        // AC1 — left-aligned, NON-user: the empty space on the RIGHT exceeds the left.
        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(msg).getUnclippedBoundsInRoot()
        val leftGap = b.left.value
        val rightGap = w - b.right.value
        assertTrue("teammate bubble should be left-aligned: leftGap=$leftGap rightGap=$rightGap w=$w", rightGap > leftGap + w * 0.2f)

        saveScreenshot("teammate_left_labelled")
    }

    @Test
    fun teammateBubble_isLeftAligned_distinctFromUser() {
        val teammateMsg = "Reviewing your change now"
        val userMsg = "thanks, go ahead"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(
                        teammate(teammateMsg, teammateId = "challenger", uuid = "tm1"),
                        user(userMsg, uuid = "u1"),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeTestRule.onNodeWithText(teammateMsg).assertIsDisplayed()
        composeTestRule.onNodeWithText(userMsg).assertIsDisplayed()

        val w = rootWidthPx()
        val tm = composeTestRule.onNodeWithText(teammateMsg).getUnclippedBoundsInRoot()
        val usr = composeTestRule.onNodeWithText(userMsg).getUnclippedBoundsInRoot()
        // AC1 — teammate is LEFT-aligned (right gap larger).
        assertTrue("teammate left-aligned", (w - tm.right.value) > tm.left.value)
        // AC3 — the user's own message stays RIGHT-aligned (left gap larger) and is NOT flipped.
        assertTrue("user right-aligned", usr.left.value > (w - usr.right.value))

        saveScreenshot("teammate_vs_user")
    }

    @Test
    fun teammateBubble_withoutColor_stillLabelledAndLeftAligned() {
        // AC2 — colour is optional; attribution off the teammate_id alone, default tint.
        val msg = "no colour supplied here"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(teammate(msg, teammateId = "qa", color = null)),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeTestRule.onNodeWithText("qa").assertIsDisplayed()
        composeTestRule.onNodeWithText(msg).assertIsDisplayed()
        val w = rootWidthPx()
        val b = composeTestRule.onNodeWithText(msg).getUnclippedBoundsInRoot()
        assertTrue("teammate bubble (no colour) should be left-aligned", (w - b.right.value) > b.left.value)

        saveScreenshot("teammate_no_color")
    }
}
