package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-78 — device-realistic (emulator-tier) coverage of the chat anchor-to-bottom
 * behaviour, driving the REAL growth-gated anchor that lives in the
 * [ConversationContent] seam (the same `internal` seam the UC-37/UC-53 tests use).
 *
 * <p>The seam is fed a {@code MutableState<List<ConversationItem>>} (the transcript)
 * plus a {@code MutableState<Boolean>} backfilling flag, and a captured
 * [LazyListState] so the test can assert the rendered scroll position directly
 * (visible item indices / first-visible index / scroll-in-progress). Items are
 * deliberately tall so a 24-item list overflows the viewport — only then does the
 * anchor have to actually scroll, which is what makes "lands at the bottom" a
 * meaningful assertion rather than a tautology.
 *
 * <p>Acceptance-criteria mapping:
 *  - AC1/AC6  initial replay (backfilling=true) lands at the bottom INSTANTLY at
 *             every growth, with no mid-flight animation through intermediate
 *             indices ([backfillStreaming_anchorsInstantlyAtEachGrowth_noMidFlightAnimation]).
 *             Asserted under a frozen clock (autoAdvance=false): an instant
 *             {@code scrollToItem} reaches the last index within a couple of frames
 *             and leaves no scroll in progress, whereas an animated scroll would be
 *             mid-flight with the clock frozen.
 *  - AC4      after the initial anchor, a live append (backfilling=false) keeps the
 *             view pinned to the newest item ([liveAppend_afterInitialAnchor_sticksToBottom]).
 *  - AC2/AC3  switching targets (empty → stream A → empty → stream B) re-anchors to
 *             the NEW stream's bottom ([targetSwitch_reAnchorsToNewStreamBottom]).
 *  - AC3      a reconnect that re-delivers the SAME (deduped, no-growth) items while
 *             the user is scrolled up must NOT yank the view to the bottom
 *             ([reconnect_withStableSize_doesNotYankScrollPosition]).
 *  - AC5      empty and single-item transcripts render without crashing and without a
 *             spurious scroll ([emptyAndSingleItem_renderWithoutCrashOrSpuriousScroll]).
 *
 * <p>The {@code backfilling} field→StateFlow conversion's turn-phase no-regression
 * (the spinner must not flip to WORKING/THINKING while history replays) is covered
 * on the JVM by {@code ConversationControllerTest}.
 */
@RunWith(AndroidJUnit4::class)
class ConversationAnchorInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Deliberately tall assistant rows so a modest list overflows the viewport. */
    private fun msg(tag: String, i: Int) = ConversationItem.AssistantMessage(
        uuid = "$tag-$i",
        source = "main",
        isSidechain = false,
        text = "[$tag] Message #$i — " + "lorem ipsum dolor sit amet consectetur ".repeat(5),
    )

    private fun msgs(tag: String, n: Int): List<ConversationItem> = (0 until n).map { msg(tag, it) }

    private fun pump(frames: Int = 4) = repeat(frames) { composeTestRule.mainClock.advanceTimeByFrame() }

    private fun <T> ui(block: () -> T): T = composeTestRule.runOnUiThread(block)

    private fun lastVisible(state: LazyListState): Int =
        ui { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }

    private fun firstVisible(state: LazyListState): Int = ui { state.firstVisibleItemIndex }

    @Test
    fun backfillStreaming_anchorsInstantlyAtEachGrowth_noMidFlightAnimation() {
        // AC1/AC6 — freeze the clock so an animated scroll would be observably stuck mid-flight.
        composeTestRule.mainClock.autoAdvance = false

        val items = mutableStateOf(emptyList<ConversationItem>())
        val backfilling = mutableStateOf(true)
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = backfilling.value,
                )
            }
        }
        pump() // settle the initial (empty) composition

        val full = msgs("S", 24)
        for (n in 1..full.size) {
            ui { items.value = full.subList(0, n) }
            pump() // recompose → relaunch the size-keyed effect → instant scroll → relayout

            // Instant anchor: the newest item is already the last VISIBLE item …
            assertEquals(
                "growth to $n items must land the last index instantly at the bottom",
                n - 1,
                lastVisible(state),
            )
            // … and with the clock frozen there is NO animation still in flight.
            assertFalse(
                "backfill anchor must be instant (no scroll animation in progress) at size $n",
                ui { state.isScrollInProgress },
            )
        }

        composeTestRule.mainClock.autoAdvance = true
    }

    @Test
    fun liveAppend_afterInitialAnchor_sticksToBottom() {
        // AC4 — after the initial replay anchors, live growth keeps the view pinned to the latest.
        val items = mutableStateOf(msgs("L", 20))
        val backfilling = mutableStateOf(true)
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = backfilling.value,
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("initial replay must anchor to the bottom", 19, lastVisible(state))

        // Go live, then a new message arrives.
        ui { backfilling.value = false }
        composeTestRule.waitForIdle()
        ui { items.value = items.value + msg("L", 20) }
        composeTestRule.waitForIdle()

        assertEquals("a live append must stick to the new bottom (AC4)", 20, lastVisible(state))
    }

    @Test
    fun targetSwitch_reAnchorsToNewStreamBottom() {
        // AC2/AC3 — empty → backfill stream A → settle → empty → backfill stream B re-anchors to B.
        val a = msgs("A", 20)
        val b = msgs("B", 12)
        val items = mutableStateOf(emptyList<ConversationItem>())
        val backfilling = mutableStateOf(true)
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = backfilling.value,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Stream A replays and anchors to A's bottom.
        ui { items.value = a }
        composeTestRule.waitForIdle()
        assertEquals("stream A must anchor to its bottom", 19, lastVisible(state))

        // Settle, then switch target: the screen clears (size 0 re-arms the anchor) …
        ui { backfilling.value = false }
        composeTestRule.waitForIdle()
        ui { items.value = emptyList() }
        composeTestRule.waitForIdle()

        // … then stream B replays and RE-anchors to B's (different) bottom.
        ui { backfilling.value = true }
        composeTestRule.waitForIdle()
        ui { items.value = b }
        composeTestRule.waitForIdle()

        assertEquals("stream B must re-anchor to ITS bottom", 11, lastVisible(state))
        composeTestRule.onNodeWithText("[B] Message #11", substring = true).assertIsDisplayed()
    }

    @Test
    fun reconnect_withStableSize_doesNotYankScrollPosition() {
        // AC3 — a deduped reconnect (backfilling false→true→false, SAME items, no growth) while the
        // user is scrolled up must leave the scroll position untouched (no pin to the bottom).
        val items = mutableStateOf(msgs("R", 24))
        val backfilling = mutableStateOf(true)
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = backfilling.value,
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("initial replay anchors to the bottom", 23, lastVisible(state))

        // The user scrolls all the way up to read history.
        composeTestRule.runOnIdle { runBlocking { state.scrollToItem(0) } }
        composeTestRule.waitForIdle()
        val before = firstVisible(state)
        assertEquals("precondition: the user is scrolled to the top", 0, before)

        // A reconnect re-delivers the SAME items (deduped → no growth), flipping backfilling.
        ui { backfilling.value = false }
        composeTestRule.waitForIdle()
        ui { backfilling.value = true }
        composeTestRule.waitForIdle()
        ui { backfilling.value = false }
        composeTestRule.waitForIdle()

        assertEquals(
            "a stable-size reconnect must NOT yank the scroll position to the bottom",
            before,
            firstVisible(state),
        )
    }

    @Test
    fun emptyAndSingleItem_renderWithoutCrashOrSpuriousScroll() {
        // AC5 — empty and single-item transcripts render cleanly (no crash, index 0, no spurious scroll).
        val items = mutableStateOf(emptyList<ConversationItem>())
        val backfilling = mutableStateOf(true)
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = backfilling.value,
                )
            }
        }
        // Empty list: no crash.
        composeTestRule.waitForIdle()
        assertEquals("empty list stays at index 0", 0, firstVisible(state))

        // Single item: renders, anchored at index 0, no spurious scroll.
        ui { items.value = listOf(msg("E", 0)) }
        composeTestRule.waitForIdle()
        assertEquals("single-item list is anchored at index 0", 0, firstVisible(state))
        composeTestRule.onNodeWithText("[E] Message #0", substring = true).assertIsDisplayed()
    }
}
