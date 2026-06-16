package com.aisandbox.android.gate

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.ui.screens.ConversationContent
import com.aisandbox.android.ui.testtags.ConversationTestTags
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-85 — the conversation-view invariants leg of the deterministic gate, driven from the LIVE
 * replayed transcript fixture (synthetic session 5) over the REAL mTLS/WebSocket stack and
 * rendered through the production {@code ConversationContent} composable. Every assertion is by
 * stable {@code testTag} / rendered node — no screenshot eyeballing.
 *
 * <p>Coverage (all exercised against the replayed transcript):
 * <ul>
 *   <li>UC-58 — teammate/subagent messages render as distinct, left-aligned, sender-attributed
 *       bubbles (NOT the user's own right-aligned bubble).</li>
 *   <li>UC-78 — the view anchors to the bottom (newest message) on load.</li>
 *   <li>UC-79 — an in-window scroll-up keeps its anchor and does not jump. (Loading a page OLDER
 *       than the backfill window is NOT exercisable under replay — the fixture IS the whole
 *       window — so only the in-window behaviour is asserted; the older-page paging path is
 *       covered by the UC-79 JVM/seam tests.)</li>
 *   <li>UC-80 — a long assistant message renders in full (its tail survives, uncropped).</li>
 *   <li>UC-81 — long-press on a bubble copies its full body.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class ConversationViewGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Volatile
    private var copied: String? = null
    private lateinit var listState: LazyListState

    /** Render the live transcript through the real ConversationContent and wait until it backfills. */
    private fun renderTranscript(session: GateHarness.GateSession) {
        composeTestRule.setContent {
            AiSandboxTheme {
                val items by session.controller.items.collectAsState()
                val backfilling by session.controller.backfilling.collectAsState()
                val s = rememberLazyListState()
                listState = s
                ConversationContent(
                    items = items,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = backfilling,
                    onCopy = { copied = it },
                )
            }
        }
        // The transcript fixture carries the long (UC-80) message; wait until it has streamed in.
        composeTestRule.waitUntil(90_000) {
            session.controller.items.value.any { it is ConversationItem.AssistantMessage && it.text.contains("magna aliqua") }
        }
        composeTestRule.waitForIdle()
    }

    private fun items(session: GateHarness.GateSession) = session.controller.items.value

    private fun scrollTo(index: Int) {
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(index.coerceAtLeast(0)) } }
        composeTestRule.waitForIdle()
    }

    private fun lastVisibleIndex(): Int =
        composeTestRule.runOnIdle { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }

    private fun firstVisibleIndex(): Int = composeTestRule.runOnIdle { listState.firstVisibleItemIndex }

    @Test
    fun teammateBubbles_renderDistinctFromUser() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_TRANSCRIPT)
        try {
            renderTranscript(session)
            // The fixture carries ≥2 teammate lines (qa, researcher) — bring the top into view.
            scrollTo(0)
            val teammateBubbles = composeTestRule.onAllNodesWithTag(ConversationTestTags.BUBBLE_TEAMMATE).fetchSemanticsNodes()
            assertTrue(
                "UC-58 — teammate/subagent lines render as dedicated teammate bubbles (found ${teammateBubbles.size})",
                teammateBubbles.size >= 2,
            )
            // Each teammate bubble is attributed to its sender and is left-aligned (distinct from a user bubble).
            composeTestRule.onNodeWithText("qa").assertExists()
            composeTestRule.onNodeWithText("researcher").assertExists()

            val w = composeTestRule.onRoot().getUnclippedBoundsInRoot().let { (it.right - it.left).value }
            val teammate = composeTestRule.onNodeWithText("all tests pass", substring = true).getUnclippedBoundsInRoot()
            assertTrue(
                "UC-58 — teammate bubble is left-aligned (right gap > left gap)",
                (w - teammate.right.value) > teammate.left.value,
            )
            // The user's own line stays right-aligned.
            val user = composeTestRule.onNodeWithText("Summarize the team progress", substring = true).getUnclippedBoundsInRoot()
            assertTrue("UC-58 — the user's own line stays right-aligned", user.left.value > (w - user.right.value))
        } finally {
            session.close()
        }
    }

    @Test
    fun anchorsToBottomOnLoad() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_TRANSCRIPT)
        try {
            renderTranscript(session)
            val lastIndex = items(session).size - 1
            assertTrue("transcript must have loaded items", lastIndex >= 0)
            // UC-78 — the freshly-loaded view sits at the bottom: the last item is the last visible one.
            assertEquals("UC-78 — conversation opens anchored at the newest message", lastIndex, lastVisibleIndex())
        } finally {
            session.close()
        }
    }

    @Test
    fun inWindowScrollUp_keepsAnchor_noJump() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_TRANSCRIPT)
        try {
            renderTranscript(session)
            // Scroll to the very top of the loaded window …
            scrollTo(0)
            assertEquals("UC-79 — an in-window scroll-up reaches the top", 0, firstVisibleIndex())
            // … and the offset is stable (no teleport): a second settle keeps us at the top.
            composeTestRule.waitForIdle()
            assertEquals("UC-79 — the scroll position does not jump after settling", 0, firstVisibleIndex())
            // Scrolling back down returns to the newest message without a crash.
            scrollTo(items(session).size - 1)
            assertEquals("UC-79 — scrolling back lands at the newest message", items(session).size - 1, lastVisibleIndex())
        } finally {
            session.close()
        }
    }

    @Test
    fun longMessage_rendersUncropped() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_TRANSCRIPT)
        try {
            renderTranscript(session)
            val longIndex = items(session).indexOfFirst {
                it is ConversationItem.AssistantMessage && it.text.contains("magna aliqua")
            }
            assertTrue("the long message must be present in the transcript", longIndex >= 0)
            scrollTo(longIndex)
            // UC-80 — both the head and the TAIL of the long body are present → not cropped/ellipsised.
            composeTestRule.onNodeWithText("deliberately long assistant message", substring = true).assertExists()
            composeTestRule.onNodeWithText("magna aliqua", substring = true).assertExists()
        } finally {
            session.close()
        }
    }

    @Test
    fun longPress_copiesBubbleBody() {
        GateHarness.assumeEnrolled()
        val session = GateHarness.open(GateHarness.N_TRANSCRIPT)
        try {
            renderTranscript(session)
            copied = null
            // The last assistant line is at the bottom (anchored). Long-press it to copy.
            val target = "Anything else you want me to dig into?"
            scrollTo(items(session).size - 1)
            composeTestRule.onNodeWithText(target, substring = true).performTouchInput { longClick() }
            composeTestRule.waitForIdle()
            composeTestRule.waitUntil(30_000) { copied != null }
            assertTrue("UC-81 — long-press copies the FULL bubble body", copied!!.contains(target))
        } finally {
            session.close()
        }
    }
}
