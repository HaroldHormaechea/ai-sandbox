package com.aisandbox.android.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * UC-89 — device-realistic (emulator-tier) coverage of the jump-to-bottom button +
 * unread-badge auto-follow state machine, driving the REAL state machine that lives in
 * the [ConversationContent] seam (the same `internal` seam the UC-37/UC-53/UC-78/UC-79
 * tests use).
 *
 * <p>The seam is fed a {@code MutableState<List<ConversationItem>>} (the transcript) plus
 * a {@code MutableState<Boolean>} backfilling flag and a captured [LazyListState], so the
 * test can assert the rendered scroll position directly (visible item indices). It also
 * captures the composition's [CoroutineScope] so it can drive ANIMATED scrolls
 * ([animateTo]) — an animated scroll spans multiple frames, so `isScrollInProgress`
 * latches true and then settles false, which is exactly the transition the seam's
 * settle-reconcile effect (Effect A) keys on. An instant {@code scrollToItem} can flip
 * `isScrollInProgress` true→false within a single snapshot and be conflated away, so it
 * would NOT reliably exercise the settle path; animated scrolls are the faithful proxy
 * for a real fling that comes to rest. Items are deliberately tall so a 24-item list
 * overflows the viewport — only then is "the latest message is offscreen" real.
 *
 * <p>The button is located by the content description "Scroll to latest message" (the
 * FAB icon's description, merged onto the FAB); the numeric badge by its
 * "<n> unread messages" content description. {@code atTranscriptStart = true} on every
 * render disables the UC-79 older-page prefetch trigger so these tests isolate the UC-89
 * follow/badge behaviour from paging.
 *
 * <p>Acceptance-criteria mapping (printed as a table in the QA summary):
 *  - AC1  [atBottom_buttonHidden_initially_andWithinTolerance]
 *  - AC2  [scrollUpSettle_showsButton]
 *  - AC3  [scrolledUp_appendIncrementsBadge_multiBlockReplyCountsOnce]
 *  - AC4  [tap_animatesToBottom_clearsBadge_hidesButton]
 *  - AC5  [afterTap_autoFollowReengaged_liveAppendFollows]
 *  - AC6  [scrolledUp_liveAppendAndStreamingGrowth_doNotMoveScroll_noStreamingInflation]
 *  - AC7  [atBottom_newMessageAndStreaming_followsNoButtonNoBadge]
 *  - AC8  [manualReturnToBottom_reengagesFollow_clearsBadge_hidesButton]
 *  - AC9  [entrySwitch_startsAnchoredAtBottom_buttonHidden_followOn]
 *  - AC10 [captureScreenshot_buttonVisibleWithBadge] (button overlays at BottomEnd, does
 *         not sit in the list flow; the composer lives in the Scaffold bottom bar OUTSIDE
 *         this seam, so full composer-occlusion/inset coverage is structural — see notes)
 *  - AC11 satisfied by this instrumented suite existing and passing.
 */
@RunWith(AndroidJUnit4::class)
class ConversationJumpToBottomInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fabDescription = "Scroll to latest message"

    /** Deliberately tall assistant rows so a modest list overflows the viewport. */
    private fun msg(tag: String, i: Int) = ConversationItem.AssistantMessage(
        uuid = "$tag-$i",
        source = "main",
        isSidechain = false,
        text = "[$tag] Message #$i — " + "lorem ipsum dolor sit amet consectetur ".repeat(5),
    )

    private fun msgs(tag: String, n: Int): List<ConversationItem> = (0 until n).map { msg(tag, it) }

    private fun thinking(tag: String, i: Int) = ConversationItem.Thinking(
        uuid = "$tag-think-$i",
        source = "main",
        isSidechain = false,
        text = "[$tag] thinking #$i",
    )

    private fun tool(tag: String, i: Int) = ConversationItem.ToolActivity(
        uuid = "$tag-tool-$i",
        source = "main",
        isSidechain = false,
        toolName = "Bash",
        toolUseId = "$tag-tooluse-$i",
        inputSummary = "echo hi",
        primaryText = "echo hi",
        result = null,
    )

    private fun <T> ui(block: () -> T): T = composeTestRule.runOnUiThread(block)

    private fun lastVisible(state: LazyListState): Int =
        ui { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }

    private fun firstVisible(state: LazyListState): Int = ui { state.firstVisibleItemIndex }

    /**
     * Drive an ANIMATED scroll on the composition scope and wait for it to settle. Animated
     * (not instant) so `isScrollInProgress` latches true across frames and then transitions to
     * false, which is the settle signal the seam's Effect A reconciles on — the faithful proxy
     * for a real user fling coming to rest.
     */
    private fun animateTo(scope: CoroutineScope, state: LazyListState, index: Int) {
        scope.launch { state.animateScrollToItem(index) }
        composeTestRule.waitForIdle()
    }

    // ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun atBottom_buttonHidden_initially_andWithinTolerance() {
        // AC1 — first content anchors to the bottom (within tolerance), so the button is hidden.
        val items = mutableStateOf(msgs("B", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("first content must anchor to the bottom", 23, lastVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()

        // A live append while at the bottom keeps us anchored — still within tolerance, button hidden.
        ui { items.value = items.value + msg("B", 24) }
        composeTestRule.waitForIdle()
        assertEquals("a live append at the bottom keeps us anchored", 24, lastVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
    }

    @Test
    fun scrollUpSettle_showsButton() {
        // AC2 — scrolling up beyond the tolerance (latest offscreen) and settling shows the button.
        val items = mutableStateOf(msgs("U", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()

        animateTo(scope, state, 0)
        assertEquals("precondition: scrolled to the top, latest is offscreen", 0, firstVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertIsDisplayed()
    }

    @Test
    fun scrolledUp_appendIncrementsBadge_multiBlockReplyCountsOnce() {
        // AC3 — while scrolled up, the badge increments per arriving message; a single assistant
        // reply made of several blocks (thinking + tool + assistant) counts as exactly ONE.
        val items = mutableStateOf(msgs("C", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        animateTo(scope, state, 0)
        composeTestRule.onNodeWithContentDescription(fabDescription).assertIsDisplayed()

        // One new user message → badge 1.
        ui {
            items.value = items.value + ConversationItem.UserMessage(
                uuid = "C-user", source = "main", isSidechain = false, text = "a question",
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertExists()

        // One assistant reply delivered as THREE blocks (thinking + tool + assistant) → +1 (total 2).
        ui {
            items.value = items.value + listOf(thinking("C", 0), tool("C", 0), msg("C", 24))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("2 unread messages").assertExists()
        // The earlier badge value is gone (it incremented, not duplicated).
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertDoesNotExist()
    }

    @Test
    fun tap_animatesToBottom_clearsBadge_hidesButton() {
        // AC4 — tapping the button animates to the last message, clears the badge, hides the button.
        val items = mutableStateOf(msgs("T", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        animateTo(scope, state, 0)
        ui { items.value = items.value + msg("T", 24) } // arrives while scrolled up → badge 1
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertExists()

        composeTestRule.onNodeWithContentDescription(fabDescription).performClick()
        composeTestRule.waitForIdle()

        assertEquals("tap must animate to the last message", 24, lastVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertDoesNotExist()
    }

    @Test
    fun afterTap_autoFollowReengaged_liveAppendFollows() {
        // AC5 — after a tap re-engages follow, subsequent live messages keep the view pinned.
        val items = mutableStateOf(msgs("F", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        animateTo(scope, state, 0)
        composeTestRule.onNodeWithContentDescription(fabDescription).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(fabDescription).performClick()
        composeTestRule.waitForIdle()
        assertEquals("tap lands at the bottom", 23, lastVisible(state))

        // A new live message must now be followed automatically (no button, pinned to newest).
        ui { items.value = items.value + msg("F", 24) }
        composeTestRule.waitForIdle()
        assertEquals("re-engaged follow must stick to the new bottom", 24, lastVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
    }

    @Test
    fun scrolledUp_liveAppendAndStreamingGrowth_doNotMoveScroll_noStreamingInflation() {
        // AC6 — while scrolled up: a live append and an in-place streaming growth must NOT move the
        // viewport; the new message bumps the badge ONCE, and token-by-token growth never inflates it.
        val items = mutableStateOf(msgs("S", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        animateTo(scope, state, 0)
        val anchored = firstVisible(state)
        assertEquals("precondition: scrolled to the top", 0, anchored)

        // A new streaming assistant message appears (first token) → badge 1, scroll unchanged.
        val streamingBase = ConversationItem.AssistantMessage(
            uuid = "S-stream", source = "main", isSidechain = false, text = "tok",
        )
        ui { items.value = items.value + streamingBase }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertExists()
        assertEquals("a live append must not move a scrolled-up viewport", anchored, firstVisible(state))

        // The SAME message grows in place token-by-token (key folds text.hashCode, so it changes) →
        // badge stays 1 (no inflation), scroll still unchanged.
        ui { items.value = items.value.dropLast(1) + streamingBase.copy(text = "tok tok") }
        composeTestRule.waitForIdle()
        ui { items.value = items.value.dropLast(1) + streamingBase.copy(text = "tok tok tok") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("1 unread messages").assertExists()
        composeTestRule.onNodeWithContentDescription("2 unread messages").assertDoesNotExist()
        assertEquals("streaming growth must not move a scrolled-up viewport", anchored, firstVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertIsDisplayed()
    }

    @Test
    fun atBottom_newMessageAndStreaming_followsNoButtonNoBadge() {
        // AC7 — at the bottom, a new message AND an in-place streaming growth both auto-follow; the
        // button stays hidden and no badge appears.
        val items = mutableStateOf(msgs("P", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("starts anchored at the bottom", 23, lastVisible(state))

        // New message at the bottom → follows.
        ui { items.value = items.value + msg("P", 24) }
        composeTestRule.waitForIdle()
        assertEquals("new message at the bottom is followed", 24, lastVisible(state))

        // Streaming growth in place at the bottom → follows.
        val streaming = ConversationItem.AssistantMessage(
            uuid = "P-stream", source = "main", isSidechain = false, text = "g",
        )
        ui { items.value = items.value + streaming }
        composeTestRule.waitForIdle()
        ui { items.value = items.value.dropLast(1) + streaming.copy(text = "g g g g g g") }
        composeTestRule.waitForIdle()
        assertEquals("streaming growth at the bottom is followed", 25, lastVisible(state))

        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertDoesNotExist()
    }

    @Test
    fun manualReturnToBottom_reengagesFollow_clearsBadge_hidesButton() {
        // AC8 — manually scrolling back to the bottom (without tapping) re-engages follow, clears
        // the badge, and hides the button — equivalent to tapping it.
        val items = mutableStateOf(msgs("M", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        animateTo(scope, state, 0)
        ui { items.value = items.value + msg("M", 24) } // badge 1 while scrolled up
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(fabDescription).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertExists()

        // Manually scroll back to the bottom (NOT via the button).
        animateTo(scope, state, ui { state.layoutInfo.totalItemsCount - 1 })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("1 unread messages").assertDoesNotExist()

        // And follow is re-engaged: a further message sticks to the bottom.
        ui { items.value = items.value + msg("M", 25) }
        composeTestRule.waitForIdle()
        assertEquals("manual return re-engaged follow", 26, lastVisible(state))
    }

    @Test
    fun entrySwitch_startsAnchoredAtBottom_buttonHidden_followOn() {
        // AC9 — entering/switching into a conversation (empty → backfill) starts anchored at the
        // bottom with the button hidden and follow on, regardless of any prior scroll position.
        val items = mutableStateOf(emptyList<ConversationItem>())
        val backfilling = mutableStateOf(true)
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = backfilling.value,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Backfill delivers the transcript: must open anchored at the bottom, button hidden.
        ui { items.value = msgs("E", 24) }
        composeTestRule.waitForIdle()
        assertEquals("entry must open anchored at the bottom", 23, lastVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()

        // Follow is on: a live message after going live sticks to the bottom.
        ui { backfilling.value = false }
        composeTestRule.waitForIdle()
        ui { items.value = items.value + msg("E", 24) }
        composeTestRule.waitForIdle()
        assertEquals("entry started with follow on", 24, lastVisible(state))
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
    }

    @Test
    fun captureScreenshot_buttonVisibleWithBadge() {
        // AC10 (visual) — render the scrolled-up state with a visible badge, assert the button is
        // displayed at the bottom-end overlay, and dump a screenshot so QA can confirm by eye that
        // the FAB sits at the bottom-end and does not overlap list content. NOTE: the message
        // composer/input lives in the Scaffold bottom bar in [ConversationScreen], OUTSIDE this
        // seam, so full composer-occlusion + safe-area inset coverage is structural (the button is
        // an overlay inside the list Box with 16.dp padding, above the composer sibling) rather
        // than asserted here.
        val items = mutableStateOf(msgs("V", 24))
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                scope = rememberCoroutineScope()
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    atTranscriptStart = true,
                )
            }
        }
        composeTestRule.waitForIdle()
        animateTo(scope, state, 0)
        ui { items.value = items.value + msg("V", 24) + msg("V", 25) } // badge 2 while scrolled up
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(fabDescription).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("2 unread messages").assertIsDisplayed()

        val bmp: Bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val dir: File = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(dir, "uc89_button_visible_with_badge.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
