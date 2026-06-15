package com.aisandbox.android.ui.screens

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
 * UC-80 — device-realistic (emulator-tier) contract test that long message bodies render in
 * FULL, height-unbounded, with no cropping/trimming. Drives the REAL routing + [Bubble]
 * composables through the [ConversationContent] seam with real [ConversationItem] data, so the
 * rendered geometry is exactly what appears on screen, then captures screenshot evidence.
 *
 * <p>This is the insurance the QA gate calls for: a future regression that re-introduces a
 * `maxLines = N`, a `Modifier.heightIn(max = …)`, or a `TextOverflow.Ellipsis` on the message
 * `Bubble` body would CAP the laid-out height of a long bubble to a few lines. Each test asserts
 * a ~60-line body's unclipped bounds height far exceeds a single-line baseline (≥ 15×), which no
 * small line cap could satisfy — so the test fails loudly if the body is clipped again. It also
 * asserts the message TAIL (the last line's marker) is present in the rendered node — proving the
 * full body survived the server→client path — and that there is no horizontal overflow (AC6).
 *
 * <p>Coverage:
 *  - AC1 user body full          → [userLongMessage_rendersFullHeight_notCropped]
 *  - AC2 assistant body full     → [assistantLongMessage_rendersFullHeight_notCropped]
 *  - AC3 teammate body full      → [teammateLongMessage_rendersFullHeight_notCropped]
 *  - AC4 no height/line cap      → the height ratio assertion in every test above
 *  - AC6 no horizontal overflow + long unbroken token wraps inside the cap →
 *        [longUnbrokenToken_staysWithinContainer]
 *
 * <p>The complementary UC-41 intentional collapse (a long Bash command truncating with an
 * ellipsis on the COLLAPSED TOOL ROW) is pinned by the separate
 * `ConversationToolBubbleInstrumentationTest.bashBubble_longCommand_truncatesWithEllipsis`, which
 * MUST keep passing — that deliberate truncation is out of UC-80's scope (AC5).
 */
@RunWith(AndroidJUnit4::class)
class ConversationLongMessageInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun saveScreenshot(tag: String) {
        composeTestRule.waitForIdle()
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/uc80_$tag.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // drain → blocks until done
    }

    private fun rootWidthPx(): Float =
        composeTestRule.onRoot().getUnclippedBoundsInRoot().let { (it.right - it.left).value }

    private fun heightOf(textFragment: String): Float =
        composeTestRule.onNodeWithText(textFragment, substring = true)
            .getUnclippedBoundsInRoot()
            .let { (it.bottom - it.top).value }

    private fun rightEdgeOf(textFragment: String): Float =
        composeTestRule.onNodeWithText(textFragment, substring = true)
            .getUnclippedBoundsInRoot()
            .right.value

    private val baselineText = "BASELINE_SHORT one line"

    /** A ~[lines]-line body (well past any plausible maxLines cap), tagged for lookup. */
    private fun longBody(tag: String, lines: Int = 60): String =
        (1..lines).joinToString("\n") { i ->
            when (i) {
                1 -> "${tag}_START long message that must render in full"
                lines -> "${tag}_END tail line that must NOT be cropped away"
                else -> "$tag line $i — a deliberately long message body that must render in full, " +
                    "wrapping across as many lines as needed with no maxLines or fixed-height cap."
            }
        }

    private fun user(text: String, uuid: String) =
        ConversationItem.UserMessage(uuid = uuid, source = "user", isSidechain = false, text = text)

    private fun assistant(text: String, uuid: String) =
        ConversationItem.AssistantMessage(uuid = uuid, source = "main", isSidechain = false, text = text)

    private fun teammate(text: String, uuid: String) = ConversationItem.TeammateMessage(
        uuid = uuid,
        source = "main",
        isSidechain = false,
        teammateId = "analyst",
        color = null,
        text = text,
    )

    private fun assertFullHeightNotCropped(tag: String) {
        val baseline = heightOf("BASELINE_SHORT")
        val longHeight = heightOf("${tag}_START")
        assertTrue("baseline bubble height should be a small positive value, was $baseline", baseline in 1f..400f)
        // No small line/height cap could let a ~60-line body stay within 15× a single line.
        assertTrue(
            "long $tag bubble height $longHeight must far exceed baseline $baseline (≥15×) — proves no maxLines/heightIn cap",
            longHeight > baseline * 15f,
        )
        // The TAIL of the body is present in the rendered node → full body reached the bubble (no crop).
        composeTestRule.onNodeWithText("${tag}_END", substring = true).assertExists()
        // AC6 — no horizontal overflow past the container.
        val w = rootWidthPx()
        assertTrue("$tag bubble right edge ${rightEdgeOf("${tag}_START")} must stay within width $w",
            rightEdgeOf("${tag}_START") <= w + 1f)
    }

    @Test
    fun userLongMessage_rendersFullHeight_notCropped() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(user(baselineText, "b"), user(longBody("USERLONG"), "u")),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        assertFullHeightNotCropped("USERLONG")
        saveScreenshot("user_full")
    }

    @Test
    fun assistantLongMessage_rendersFullHeight_notCropped() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(user(baselineText, "b"), assistant(longBody("ASSTLONG"), "a")),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        assertFullHeightNotCropped("ASSTLONG")
        saveScreenshot("assistant_full")
    }

    @Test
    fun teammateLongMessage_rendersFullHeight_notCropped() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(user(baselineText, "b"), teammate(longBody("TEAMLONG"), "t")),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        assertFullHeightNotCropped("TEAMLONG")
        saveScreenshot("teammate_full")
    }

    @Test
    fun longUnbrokenToken_staysWithinContainer() {
        // AC6 — a ~300-char unbroken token (URL/base64/code) must wrap/stay inside the bubble
        // cap, never clip or overflow horizontally — for both user and assistant bodies.
        val userToken = "https://example.com/u/" + "a".repeat(300) + "/end"
        val asstToken = "data:image/png;base64," + "Z".repeat(300) + "==END"
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(user(userToken, "ut"), assistant(asstToken, "at")),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val w = rootWidthPx()
        listOf(userToken, asstToken).forEach { token ->
            val b = composeTestRule.onNodeWithText(token).getUnclippedBoundsInRoot()
            val width = (b.right - b.left).value
            assertTrue("right edge ${b.right.value} must stay within container width $w", b.right.value <= w + 1f)
            assertTrue("token bubble width $width must stay <= ~80% of $w (wrapped, not overflowing)", width <= w * 0.82f)
        }
        saveScreenshot("long_token")
    }
}
