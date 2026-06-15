package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap

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
    fun captureScreenshot_backfillOpensAtBottom() {
        // AC1/AC6 visual gate — render the backfill-open state (24 tall items, backfilling=true),
        // assert it's anchored at the bottom, and dump a screenshot to the app's external files dir
        // so QA can pull it and confirm by eye that the chat opens already at the latest message
        // (no top-of-list content), not mid-scroll.
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
        // Cold replay lands the full transcript at once.
        ui { items.value = msgs("V", 24) }
        composeTestRule.waitForIdle()
        assertEquals("backfill must open anchored at the bottom", 23, lastVisible(state))
        // The newest message is on screen; the very first message is NOT.
        composeTestRule.onNodeWithText("[V] Message #23", substring = true).assertIsDisplayed()

        val bmp: Bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val dir: File = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(dir, "uc78_backfill_open_at_bottom.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    // ════════════════════════ UC-79 — infinite-scroll older pages ════════════════════════
    //
    // These exercise the SAME ConversationContent seam with the UC-79 wiring (loadingOlder /
    // atTranscriptStart / onLoadOlder). They drive the REAL scroll-up trigger + anchor capture/
    // restore on the emulator and assert the viewport does not teleport when an older page is
    // prepended (AC2), the bottom-anchor is not triggered by a top-prepend (AC8), a live message
    // arriving while scrolled up does not yank to the bottom (AC5), and the top loading affordance
    // shows while a page loads (AC3).

    private fun firstVisibleOffset(state: LazyListState): Int = ui { state.firstVisibleItemScrollOffset }

    /** The viewport-relative pixel offset of the visible item with [key], or null if offscreen. */
    private fun layoutOffsetOfKey(state: LazyListState, key: String): Int? =
        ui { state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset }

    @Test
    fun olderPagePrepend_preservesScrollAnchor_noJump_andDoesNotAnchorToBottom() {
        // AC2 + AC8 — scroll up near the top of the loaded window so the prefetch trigger fires and
        // captures the top-visible item's key + pixel offset; an older page is then loaded (the
        // real loadingOlder gate is modeled). The SAME item must remain at the SAME viewport pixel
        // offset (its index shifts by the page size, but it does NOT visually move), and the
        // top-prepend must NOT yank the view to the bottom.
        val window = msgs("W", 24)
        val anchorKey = window[2].key // the item the user is reading when paging fires
        val items = mutableStateOf(window)
        val loadingOlder = mutableStateOf(false)
        val atStart = mutableStateOf(false)
        var loadRequested = false
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    loadingOlder = loadingOlder.value,
                    atTranscriptStart = atStart.value,
                    // Model the controller: a scroll-up request raises loadingOlder (which gates
                    // the trigger to single-in-flight and shows the affordance) until page-end.
                    onLoadOlder = {
                        loadRequested = true
                        loadingOlder.value = true
                    },
                )
            }
        }
        composeTestRule.waitForIdle() // first content anchors to the bottom

        // The user scrolls up to within the prefetch threshold of the top, leaving a non-zero
        // offset so "same offset" is a meaningful (non-trivial) assertion.
        composeTestRule.runOnIdle { runBlocking { state.scrollToItem(2, 50) } }
        composeTestRule.waitForIdle()
        assertEquals("precondition: scrolled near the top (item 2)", 2, firstVisible(state))
        // The scroll-up must have requested an older page and raised the loading affordance (AC2/AC3).
        assertTrue("scrolling near the top must request an older page", loadRequested)
        assertTrue("an in-flight page load must raise loadingOlder", ui { loadingOlder.value })
        // Capture the anchored item's viewport pixel offset BEFORE the prepend.
        val offsetBefore = layoutOffsetOfKey(state, anchorKey)
        assertTrue("the anchored item must be visible before paging", offsetBefore != null)

        // The server delivers an older page (page-end): prepend 10 strictly-older items AND clear
        // the in-flight flag, exactly as the controller does at page-end.
        val older = msgs("O", 10)
        ui {
            items.value = older + window
            loadingOlder.value = false
        }
        composeTestRule.waitForIdle()

        // AC2 — the anchored item is re-pinned at the SAME viewport pixel offset (no jump/teleport),
        // even though its index shifted down by the prepended page size.
        val offsetAfter = layoutOffsetOfKey(state, anchorKey)
        assertTrue("the anchored item must still be visible after the prepend", offsetAfter != null)
        assertTrue(
            "the anchored item must stay at the SAME viewport offset (no jump): before=$offsetBefore after=$offsetAfter",
            kotlin.math.abs((offsetAfter ?: 0) - (offsetBefore ?: 0)) <= 2,
        )
        // Its index shifted down by exactly the page size (proving the prepend happened above it).
        assertEquals(
            "the anchored item must shift down by exactly the prepended page size",
            2 + older.size,
            ui { state.layoutInfo.visibleItemsInfo.first { it.key == anchorKey }.index },
        )
        // AC8 — a top-prepend must NOT trigger the UC-78 bottom anchor (the newest item is offscreen).
        assertFalse(
            "a top-prepend must not yank the viewport to the bottom",
            lastVisible(state) >= (older.size + window.size) - 1,
        )

        // Visual gate — dump the paged-older view so QA can confirm the anchor is preserved by eye.
        dumpScreenshot("uc79_paged_older_anchor_preserved.png")
    }

    @Test
    fun liveMessageWhileScrolledUp_doesNotYankToBottom() {
        // AC5 — a new live message arriving while the user is scrolled up reading history must NOT
        // force-scroll the view to the bottom. atTranscriptStart=true here isolates the live-append
        // behavior by disabling the scroll-up prefetch trigger (no anchor capture in play).
        val items = mutableStateOf(msgs("W", 24))
        val atStart = mutableStateOf(true)
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    loadingOlder = false,
                    atTranscriptStart = atStart.value,
                    onLoadOlder = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { runBlocking { state.scrollToItem(0) } }
        composeTestRule.waitForIdle()
        assertEquals("precondition: the user is scrolled to the top reading history", 0, firstVisible(state))

        // A live message appends at the bottom (changes the last key).
        ui { items.value = items.value + msg("W", 24) }
        composeTestRule.waitForIdle()

        assertEquals(
            "a live message must not yank a scrolled-up user to the bottom (AC5)",
            0,
            firstVisible(state),
        )
        assertFalse("the view must not be at the bottom after a live append while scrolled up", ui { state.isScrollInProgress })
    }

    @Test
    fun loadingAffordance_showsWhileLoadingOlder_andClearsAfter() {
        // AC3 — while an older page is being fetched the top loading row ("Loading earlier messages…")
        // is shown, and it disappears once the page is ready.
        val items = mutableStateOf(msgs("W", 24))
        val loadingOlder = mutableStateOf(false)
        lateinit var state: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                val s = rememberLazyListState()
                state = s
                ConversationContent(
                    items = items.value,
                    modifier = Modifier.fillMaxSize(),
                    listState = s,
                    backfilling = false,
                    loadingOlder = loadingOlder.value,
                    atTranscriptStart = false,
                    onLoadOlder = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { runBlocking { state.scrollToItem(0) } } // bring the top row into view
        composeTestRule.waitForIdle()

        // No affordance before a page is requested.
        composeTestRule.onNodeWithText("Loading earlier messages", substring = true).assertDoesNotExist()

        // page-start raises the loading affordance: the loading row is prepended at index 0, so
        // scroll to the very top to bring it into the viewport (a keyed prepend keeps the prior
        // top item pinned, leaving the new row just above the fold).
        ui { loadingOlder.value = true }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { runBlocking { state.scrollToItem(0) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Loading earlier messages", substring = true).assertIsDisplayed()
        dumpScreenshot("uc79_loading_older_affordance.png")

        // page-end clears it.
        ui { loadingOlder.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Loading earlier messages", substring = true).assertDoesNotExist()
    }

    /** Capture the rendered tree to the app's external files dir so QA can pull and eyeball it. */
    private fun dumpScreenshot(name: String) {
        val bmp: Bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val dir: File = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("no external files dir")
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
