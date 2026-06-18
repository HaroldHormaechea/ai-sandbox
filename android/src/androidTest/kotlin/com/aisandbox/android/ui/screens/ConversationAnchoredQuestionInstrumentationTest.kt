package com.aisandbox.android.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.AnswerItem
import com.aisandbox.android.conversation.ConvOption
import com.aisandbox.android.conversation.ConvQuestion
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * UC-90 — device-realistic (emulator-tier) coverage of the top-anchored, collapsible
 * question box ([AnchoredQuestionBox]). Drives the REAL `internal` composable directly —
 * the same same-package seam style the UC-37/UC-78/UC-79/UC-89 tests use — composed in the
 * SAME layout shape the screen uses: a sibling ABOVE the [ConversationContent] list Box, so
 * the conversation scrolls beneath it and the UC-89 auto-follow list + jump-to-bottom FAB
 * stay byte-untouched.
 *
 * <p>The question box is located by its testTags — `question_anchor` (container) and
 * `question_collapse_toggle` (toggle) — and by the collapse/expand content descriptions
 * ("Collapse question" / "Expand question"). The UC-89 FAB is located by its content
 * description "Scroll to latest message". State-preservation across a collapse/expand cycle
 * is asserted via the **mounted-zero-height** contract: collapsing wraps the still-composed
 * [com.aisandbox.android.ui.components.QuestionSheet] in `clearAndSetSemantics {}`, so the
 * collapsed inner content is asserted with `assertDoesNotExist()` while the header/toggle
 * stay `assertIsDisplayed()`; selections / typed "Other" text survive because the subtree is
 * never unmounted.
 *
 * <p>Acceptance-criteria mapping (printed as a table in the QA summary):
 *  - AC1  [listScrollsBeneathAnchoredQuestion]
 *  - AC2  [questionBoxStaysPinnedAtTopWhileListScrolls]
 *  - AC3  [questionBoxExpandedByDefault_showsOptionsAndSubmit]
 *  - AC4  [collapse_showsCompactHeaderOnly_innerRemovedFromSemantics]
 *  - AC5  [collapseReadExpand_preservesCheckedOptionAndOtherText]
 *  - AC6  [collapseExpandScroll_neverDismissesOrAutoAnswers]
 *  - AC7  [multiQuestionGroupCollapsesAndExpandsAsOneUnit]
 *  - AC8  [tallExpandedGroup_submitReachableViaScroll_andCollapseReclaimsSpace]
 *  - AC9  [submitAfterCollapseExpandCycle_sendsCorrectAnswer]
 *  - AC10 [jumpToBottomButtonCoexistsWithAnchoredQuestion]
 *  - AC11 structural — the box is a sibling pinned at the TOP slot; the composer lives in the
 *         Scaffold bottom bar OUTSIDE this seam (UNCHANGED by UC-90), so it cannot be obscured
 *         by the top-anchored box. Partly evidenced by [questionBoxStaysPinnedAtTopWhileListScrolls]
 *         (the box occupies only the top and does not move into the list/composer area).
 *  - AC12 satisfied by this instrumented suite existing and passing.
 */
@RunWith(AndroidJUnit4::class)
class ConversationAnchoredQuestionInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fabDescription = "Scroll to latest message"
    private val collapseDescription = "Collapse question"
    private val expandDescription = "Expand question"

    // ──────────────────────── fixtures ────────────────────────

    /** Deliberately tall assistant rows so a 24-item list overflows the viewport. */
    private fun msg(tag: String, i: Int) = ConversationItem.AssistantMessage(
        uuid = "$tag-$i",
        source = "main",
        isSidechain = false,
        text = "[$tag] Message #$i — " + "lorem ipsum dolor sit amet consectetur ".repeat(5),
    )

    private fun msgs(tag: String, n: Int): List<ConversationItem> = (0 until n).map { msg(tag, it) }

    /** A single single-select question; short label resolves to its header "Color". */
    private fun singleSelectSheet() = PendingSheet.Questions(
        questionUuid = "uc90-single",
        questions = listOf(
            ConvQuestion(
                question = "Pick a color",
                header = "Color",
                multiSelect = false,
                options = listOf(ConvOption("Red", ""), ConvOption("Blue", "")),
            ),
        ),
    )

    /** A single MULTISELECT question — a checked option and "Other" COMBINE (UC-44), so both
     *  can be asserted to survive a collapse/expand cycle (AC5/AC9). */
    private fun multiSelectSheet() = PendingSheet.Questions(
        questionUuid = "uc90-multi",
        questions = listOf(
            ConvQuestion(
                question = "Pick letters",
                header = "Letters",
                multiSelect = true,
                options = listOf(ConvOption("Xray", ""), ConvOption("Yankee", "")),
            ),
        ),
    )

    /** Three questions (paged) — the whole group is ONE collapse unit (AC7). */
    private fun threeQuestionSheet() = PendingSheet.Questions(
        questionUuid = "uc90-three",
        questions = listOf(
            ConvQuestion("Pick a color", "Color", false, listOf(ConvOption("Red", ""), ConvOption("Blue", ""))),
            ConvQuestion("Pick letters", "Letters", true, listOf(ConvOption("Xray", ""), ConvOption("Yankee", ""))),
            ConvQuestion("Pick a city", "City", false, listOf(ConvOption("Papa", ""), ConvOption("Quebec", ""))),
        ),
    )

    /** A single multiSelect question with MANY options so the expanded box exceeds its 60%
     *  height bound and must scroll internally to reach Send (AC8). */
    private fun tallSheet() = PendingSheet.Questions(
        questionUuid = "uc90-tall",
        questions = listOf(
            ConvQuestion(
                question = "Pick options",
                header = "Tall",
                multiSelect = true,
                options = (0 until 40).map { ConvOption("Opt $it", "") },
            ),
        ),
    )

    // ──────────────────────── AC1 / AC2 — anchored + list scrollable beneath ────────────────────────

    @Test
    fun listScrollsBeneathAnchoredQuestion() {
        // AC1 — while a question is anchored at the top, the conversation list beneath it stays
        // scrollable so the user can read the messages that preceded the question.
        lateinit var listState: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnchoredQuestionBox(
                        sheet = singleSelectSheet(),
                        onSubmit = { _, _, _, _ -> },
                        onSubmitBatch = { _, _ -> },
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val s = rememberLazyListState()
                        listState = s
                        ConversationContent(
                            items = msgs("S", 24),
                            modifier = Modifier.fillMaxSize(),
                            listState = s,
                            atTranscriptStart = true, // isolate from UC-79 paging
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // The question box is anchored and the (overflowing) list is present.
        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()

        // The user scrolls the list to the very top to read the earliest messages …
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(0) } }
        composeTestRule.waitForIdle()
        assertEquals("the list beneath the anchored question must be scrollable to the top", 0, firstVisible(listState))
        composeTestRule.onNodeWithText("[S] Message #0", substring = true).assertIsDisplayed()

        // … and the question box is STILL anchored (not dismissed) after scrolling (AC1/AC6).
        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()
    }

    @Test
    fun questionBoxStaysPinnedAtTopWhileListScrolls() {
        // AC2 — the box is pinned at the TOP: scrolling the conversation underneath must not move
        // the box (its top bound is unchanged), and it stays visible. This also evidences AC11:
        // the box occupies only the top slot and does not drift down into the composer area.
        lateinit var listState: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnchoredQuestionBox(
                        sheet = singleSelectSheet(),
                        onSubmit = { _, _, _, _ -> },
                        onSubmitBatch = { _, _ -> },
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val s = rememberLazyListState()
                        listState = s
                        ConversationContent(
                            items = msgs("P", 24),
                            modifier = Modifier.fillMaxSize(),
                            listState = s,
                            atTranscriptStart = true,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val topBefore = composeTestRule.onNodeWithTag("question_anchor").getUnclippedBoundsInRoot().top.value

        // Scroll the list underneath the anchored box.
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(0) } }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()
        val topAfter = composeTestRule.onNodeWithTag("question_anchor").getUnclippedBoundsInRoot().top.value
        assertEquals("the anchored question box must stay pinned at the top while the list scrolls", topBefore, topAfter, 0.5f)
    }

    // ──────────────────────── AC3 — expanded by default ────────────────────────

    @Test
    fun questionBoxExpandedByDefault_showsOptionsAndSubmit() {
        // AC3 — the box appears EXPANDED by default: the full question, its options and the submit
        // control are visible without any interaction, and the toggle offers "Collapse question".
        composeTestRule.setContent {
            AiSandboxTheme {
                AnchoredQuestionBox(sheet = singleSelectSheet(), onSubmit = { _, _, _, _ -> })
            }
        }
        composeTestRule.onNodeWithText("Pick a color").assertIsDisplayed()
        composeTestRule.onNodeWithText("Blue").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send answer").assertIsDisplayed()
        // Expanded ⇒ the toggle's action is to collapse.
        composeTestRule.onNodeWithContentDescription(collapseDescription).assertIsDisplayed()
    }

    // ──────────────────────── AC4 — collapse → compact header only ────────────────────────

    @Test
    fun collapse_showsCompactHeaderOnly_innerRemovedFromSemantics() {
        // AC4 — collapsing leaves ONLY the compact header bar: the short label (the first
        // question's title, "Color") and the now-"Expand question" toggle remain displayed,
        // while the question body (options, submit) is removed from the semantics tree
        // (mounted-zero-height + clearAndSetSemantics ⇒ assertDoesNotExist).
        composeTestRule.setContent {
            AiSandboxTheme {
                AnchoredQuestionBox(sheet = singleSelectSheet(), onSubmit = { _, _, _, _ -> })
            }
        }
        // Collapse.
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()

        // Compact header survives: short label + the toggle (now "Expand question").
        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Color").assertIsDisplayed() // short label (unique once body is gone)
        composeTestRule.onNodeWithContentDescription(expandDescription).assertIsDisplayed()

        // The body is gone from semantics.
        composeTestRule.onNodeWithText("Pick a color").assertDoesNotExist()
        composeTestRule.onNodeWithText("Blue").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send answer").assertDoesNotExist()

        dumpScreenshot("uc90_collapsed_compact_header.png")
    }

    // ──────────────────────── AC5 — collapse → read → expand preserves input ────────────────────────

    @Test
    fun collapseReadExpand_preservesCheckedOptionAndOtherText() {
        // AC5 — a checked option AND typed "Other" free-text both survive a collapse→expand cycle
        // (the multiSelect path COMBINES them). Using a multiSelect question so BOTH kinds of input
        // are in play simultaneously; the mounted-zero-height collapse never unmounts the sheet, so
        // QuestionSheet's remember(questionUuid) state is retained.
        var selections: List<Int>? = null
        var freeText: String? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                AnchoredQuestionBox(
                    sheet = multiSelectSheet(),
                    onSubmit = { _, _, sel, free ->
                        selections = sel
                        freeText = free
                    },
                )
            }
        }

        // Enter input while expanded: check an option AND type a custom "Other".
        composeTestRule.onNodeWithText("Xray").performClick() // index 0
        composeTestRule.onNode(hasSetTextAction()).performTextInput("lime")

        // Collapse to read context — the body leaves the semantics tree …
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Xray").assertDoesNotExist()

        // … expand again — the body is back, byte-identical, with state intact.
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Xray").assertIsDisplayed()

        // Submit: the checked option (0) AND the "Other" index (== optionCount == 2) plus the typed
        // text survive the cycle, proving nothing was lost (AC5).
        composeTestRule.onNodeWithText("Send answer").performClick()
        assertEquals("checked option + Other index must survive collapse/expand", listOf(0, 2), selections)
        assertEquals("typed Other free-text must survive collapse/expand", "lime", freeText)
    }

    // ──────────────────────── AC6 — collapse/expand/scroll never dismisses or auto-answers ────────────────────────

    @Test
    fun collapseExpandScroll_neverDismissesOrAutoAnswers() {
        // AC6 — collapsing, expanding, and scrolling the list NEVER dismiss the question or submit
        // an answer on the user's behalf; it stays pending until an explicit submit.
        var submitted = false
        lateinit var listState: LazyListState
        composeTestRule.setContent {
            AiSandboxTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnchoredQuestionBox(
                        sheet = singleSelectSheet(),
                        onSubmit = { _, _, _, _ -> submitted = true },
                        onSubmitBatch = { _, _ -> submitted = true },
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val s = rememberLazyListState()
                        listState = s
                        ConversationContent(
                            items = msgs("C", 24),
                            modifier = Modifier.fillMaxSize(),
                            listState = s,
                            atTranscriptStart = true,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // Collapse → expand → collapse → scroll the list → expand. None of this may submit.
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick() // collapse
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick() // expand
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick() // collapse
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(0) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick() // expand
        composeTestRule.waitForIdle()

        // Still pending: no answer was sent, and the box (re-expanded) is intact.
        assertTrue("collapse/expand/scroll must never auto-answer the pending question", !submitted)
        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send answer").assertIsDisplayed()
    }

    // ──────────────────────── AC7 — multi-question group collapses/expands as ONE unit ────────────────────────

    @Test
    fun multiQuestionGroupCollapsesAndExpandsAsOneUnit() {
        // AC7 — a multi-question (paged) group collapses/expands as a SINGLE unit under one header:
        // the whole paged body (the "Question 1 of 3" indicator + Next nav) disappears on collapse
        // and returns on expand, under the one short label ("Color", the first question's title).
        composeTestRule.setContent {
            AiSandboxTheme {
                AnchoredQuestionBox(
                    sheet = threeQuestionSheet(),
                    onSubmit = { _, _, _, _ -> },
                    onSubmitBatch = { _, _ -> },
                )
            }
        }
        // Expanded: the paged body is shown.
        composeTestRule.onNodeWithText("Question 1 of 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").assertIsDisplayed()

        // Collapse the whole group as one unit.
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Question 1 of 3").assertDoesNotExist()
        composeTestRule.onNodeWithText("Next").assertDoesNotExist()
        // The single compact header for the whole group remains.
        composeTestRule.onNodeWithText("Color").assertIsDisplayed()

        // Expand: the whole group returns at page 1.
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Question 1 of 3").assertIsDisplayed()
    }

    // ──────────────────────── AC8 — tall group: not height-capped, Send reachable; collapse reclaims ────────────────────────

    @Test
    fun tallExpandedGroup_submitReachableViaScroll_andCollapseReclaimsSpace() {
        // AC8 — a very tall expanded question is NOT internally height-capped to a tiny window: the
        // box is bounded to 60% of the available height with ONE internal scroll, so Send stays
        // REACHABLE by scrolling within the box; then collapse reclaims the screen space (the body,
        // Send included, leaves the layout/semantics entirely).
        var submitted = false
        composeTestRule.setContent {
            AiSandboxTheme {
                // Give the box real vertical room so BoxWithConstraints.maxHeight is the full screen.
                Column(modifier = Modifier.fillMaxSize()) {
                    AnchoredQuestionBox(
                        sheet = tallSheet(),
                        onSubmit = { _, _, _, _ -> submitted = true },
                        onSubmitBatch = { _, _ -> submitted = true },
                    )
                }
            }
        }

        // Select an option near the top (enables Send), then scroll WITHIN the bounded box to reach
        // Send at the bottom of the long option list and submit — proving it is reachable (AC8).
        composeTestRule.onNodeWithText("Opt 0").performClick()
        composeTestRule.onNodeWithText("Send answer").performScrollTo().performClick()
        assertTrue("Send must be reachable by scrolling within the bounded tall box", submitted)

        // Collapse reclaims the space: the whole body (options + Send) leaves the tree.
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Opt 0").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send answer").assertDoesNotExist()
        composeTestRule.onNodeWithText("Tall").assertIsDisplayed() // compact header remains
    }

    // ──────────────────────── AC9 — submit after a collapse/expand cycle ────────────────────────

    @Test
    fun submitAfterCollapseExpandCycle_sendsCorrectAnswer() {
        // AC9 — submitting works identically whether or not the box was collapsed at some point: a
        // single-select answer chosen, then a collapse→expand cycle, then submit, sends exactly the
        // chosen selection (and the question UI would then be removed by the parent clearing it).
        var selections: List<Int>? = null
        var index: Int? = null
        var batchCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                AnchoredQuestionBox(
                    sheet = singleSelectSheet(),
                    onSubmit = { _, idx, sel, _ ->
                        index = idx
                        selections = sel
                    },
                    onSubmitBatch = { _, _ -> batchCalled = true },
                )
            }
        }
        // Choose "Blue" (index 1) while expanded.
        composeTestRule.onNodeWithText("Blue").performClick()
        // Collapse then expand.
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("question_collapse_toggle").performClick()
        composeTestRule.waitForIdle()
        // Submit after the cycle.
        composeTestRule.onNodeWithText("Send answer").performClick()

        assertEquals("the chosen selection must be sent after a collapse/expand cycle", listOf(1), selections)
        assertEquals("single-question sheet submits via the single answer path (index 0)", 0, index)
        assertTrue("a single-question sheet must not use the batch path", !batchCalled)
    }

    // ──────────────────────── AC10 — UC-89 jump-to-bottom coexists ────────────────────────

    @Test
    fun jumpToBottomButtonCoexistsWithAnchoredQuestion() {
        // AC10 — the UC-89 jump-to-bottom FAB still works while a question is anchored at the top.
        // Render the real layout (anchored box ABOVE the ConversationContent seam that owns the FAB),
        // scroll up (animated, so the seam's settle-reconcile shows the FAB), confirm the FAB AND the
        // anchored box COEXIST, then tap the FAB and confirm it scrolls back to the latest message.
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        composeTestRule.setContent {
            AiSandboxTheme {
                scope = rememberCoroutineScope()
                Column(modifier = Modifier.fillMaxSize()) {
                    AnchoredQuestionBox(
                        sheet = singleSelectSheet(),
                        onSubmit = { _, _, _, _ -> },
                        onSubmitBatch = { _, _ -> },
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val s = rememberLazyListState()
                        listState = s
                        ConversationContent(
                            items = msgs("J", 24),
                            modifier = Modifier.fillMaxSize(),
                            listState = s,
                            atTranscriptStart = true,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        // Opening anchored at the bottom ⇒ FAB hidden, while the question box is anchored.
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()

        // Animated scroll up to the top (the faithful proxy for a fling that comes to rest, so the
        // UC-89 settle effect shows the FAB).
        composeTestRule.runOnIdle { scope.launch { listState.animateScrollToItem(0) } }
        composeTestRule.waitForIdle()

        // Coexistence: BOTH the anchored question box AND the jump-to-bottom FAB are present.
        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(fabDescription).assertIsDisplayed()
        dumpScreenshot("uc90_fab_coexists_with_anchored_question.png")

        // Tapping the FAB still scrolls back to the latest message (it keeps working, AC10) …
        composeTestRule.onNodeWithContentDescription(fabDescription).performClick()
        composeTestRule.waitForIdle()
        assertEquals("the UC-89 jump-to-bottom must still land at the latest message", 23, lastVisible(listState))
        // … and once back at the bottom the FAB hides again, with the box still anchored.
        composeTestRule.onNodeWithContentDescription(fabDescription).assertDoesNotExist()
        composeTestRule.onNodeWithTag("question_anchor").assertIsDisplayed()
    }

    // ──────────────────────── helpers ────────────────────────

    private fun firstVisible(state: LazyListState): Int =
        composeTestRule.runOnUiThread { state.firstVisibleItemIndex }

    private fun lastVisible(state: LazyListState): Int =
        composeTestRule.runOnUiThread { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }

    /** Capture the rendered tree to the app's external files dir so QA can pull and eyeball it. */
    private fun dumpScreenshot(name: String) {
        val bmp: Bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val dir: File = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("no external files dir")
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
