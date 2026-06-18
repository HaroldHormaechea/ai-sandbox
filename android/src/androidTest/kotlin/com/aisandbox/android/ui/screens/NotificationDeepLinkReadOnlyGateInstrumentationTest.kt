package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.ConvOption
import com.aisandbox.android.conversation.ConvQuestion
import com.aisandbox.android.conversation.ConversationController
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-93 (Case R) — device-realistic coverage of the SHIPPED fix's crux: the
 * read-only render gate. A warm push-notification deep-link can re-enter a
 * process-cached {@link ConversationController} that was left selecting a read-only
 * {@code subagent:} pill. {@code ConversationScreen} computes
 * {@code readOnly = selectedTargetId.startsWith(SUBAGENT_ID_PREFIX)} and gates the
 * whole answer slot out with {@code if (!readOnly) { pendingSheet?.let { … } }}
 * (ConversationScreen.kt:159 + 255-256) — so the {@link QuestionSheet} never renders
 * while the controller is pinned to a subagent, EVEN IF a pending question is present.
 * That is the wedge.
 *
 * <p>The fix — {@link ConversationController#focusAnswerableTargetForDeepLink()},
 * invoked from the deep-link consume block in {@code AiSandboxApp.kt} — re-focuses the
 * answerable {@code main} pane on the same cached controller, flipping
 * {@code selectedTargetId} back to {@code main} so {@code readOnly} becomes false and
 * the server re-tails main + re-emits its pending question. These tests drive the REAL
 * controller method through the REAL gate expression and the REAL {@link QuestionSheet}
 * for BOTH single- and multi-question groups: a populated sheet is hidden while the
 * selection is a subagent (fail-before) and renders once the hook re-focuses main
 * (pass-after). The server's per-connection re-emit is modeled by a separate
 * {@code pendingSheet} flow (the server is not under test here); the selection
 * transition is the REAL production code.
 *
 * <p>The subagent-selected precondition mirrors {@link SubagentPillInstrumentationTest};
 * the {@link QuestionSheet} wiring mirrors the positive UC-93 coverage in
 * {@link ConversationSessionSwitchNavInstrumentationTest}.
 *
 * <p>Acceptance-criteria mapping (UC-93):
 *  - AC2 / AC7 (single-question render after re-focus) →
 *        [subagentSelection_gatesOutQuestion_thenDeepLinkRefocusRendersSingle]
 *  - AC2 (multi-question paged render after re-focus) →
 *        [subagentSelection_gatesOutQuestion_thenDeepLinkRefocusRendersMulti]
 *  - AC3 / idempotency (no-op on a healthy main selection) →
 *        [mainSelection_deepLinkRefocusIsNoOp_questionStaysRendered]
 */
@RunWith(AndroidJUnit4::class)
class NotificationDeepLinkReadOnlyGateInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** A single-question AskUserQuestion. */
    private fun singleQuestion(uuid: String) = PendingSheet.Questions(
        questionUuid = uuid,
        questions = listOf(
            ConvQuestion(
                question = "Q-$uuid single?",
                header = "hdr-$uuid",
                multiSelect = false,
                options = listOf(ConvOption("opt-A", "first"), ConvOption("opt-B", "second")),
            ),
        ),
        answerable = true,
    )

    /** A multi-question (N>1) group → the paged sheet ("Question 1 of N"). */
    private fun multiQuestion(uuid: String) = PendingSheet.Questions(
        questionUuid = uuid,
        questions = listOf(
            ConvQuestion("Q1-$uuid?", "hdr1-$uuid", false, listOf(ConvOption("o1a", "d1"))),
            ConvQuestion("Q2-$uuid?", "hdr2-$uuid", false, listOf(ConvOption("o2a", "d2"))),
        ),
        answerable = true,
    )

    /**
     * A REAL [ConversationController] used only for its selection state. It never
     * connects: the http/client factories throw (a fail-fast guard proving these tests
     * touch no network), and [ConversationController.attach] is never called. The
     * [ServerProfileStore] is the real one from the instrumentation context — never read
     * because we never attach.
     */
    private fun selectionController(): ConversationController {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ConversationController(
            sessionN = 7,
            profileStore = ServerProfileStore(ctx),
            httpClientFactory = { error("no network in the read-only-gate test") },
            clientFactory = { _, _ -> error("no network in the read-only-gate test") },
            onClosed = {},
        )
    }

    /**
     * Faithful stand-in for [ConversationScreen]'s answer slot gate: the REAL
     * {@code readOnly = selectedTargetId.startsWith(SUBAGENT_ID_PREFIX)} derivation
     * (ConversationScreen.kt:159) wrapping the REAL [QuestionSheet] behind
     * {@code if (!readOnly) { pendingSheet?.let { … } }} (ConversationScreen.kt:255-256).
     * {@code selectedTargetId} is the REAL controller flow; {@code pendingSheet} is a
     * separate flow standing in for the server's per-connection re-emit.
     */
    @Composable
    private fun GatedAnswerSlot(
        controller: ConversationController,
        pendingSheetFlow: MutableStateFlow<PendingSheet?>,
    ) {
        val selectedTargetId by controller.selectedTargetId.collectAsState()
        val pendingSheet by pendingSheetFlow.collectAsState()
        val readOnly = selectedTargetId.startsWith(TerminalStreamController.SUBAGENT_ID_PREFIX)
        Column(modifier = Modifier.fillMaxSize()) {
            if (!readOnly) {
                pendingSheet?.let {
                    QuestionSheet(
                        sheet = it,
                        onSubmit = { _, _, _, _ -> },
                        onSubmitBatch = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test
    fun subagentSelection_gatesOutQuestion_thenDeepLinkRefocusRendersSingle() {
        // AC2 / AC7 — the wedge then the fix. A single-question ask is PRESENT throughout
        // (populated pendingSheet), but while the controller is pinned to a read-only
        // `subagent:` pane the REAL gate hides it. The deep-link hook re-focuses main →
        // readOnly clears → the REAL single-question QuestionSheet renders.
        val controller = selectionController()
        val pending = MutableStateFlow<PendingSheet?>(singleQuestion("q1"))
        try {
            // Precondition: left selecting a read-only subagent pill (the real pill path).
            composeTestRule.runOnUiThread {
                controller.selectTarget(TerminalStreamController.SUBAGENT_ID_PREFIX + "rev")
            }
            composeTestRule.setContent { AiSandboxTheme { GatedAnswerSlot(controller, pending) } }
            composeTestRule.waitForIdle()

            // Wedge — the question is populated yet gated out by readOnly (NOT rendered).
            composeTestRule.onNodeWithText("Q-q1 single?", substring = true).assertDoesNotExist()

            // Deep-link consume hook fires → re-focus the answerable main pane (the SHIPPED fix).
            composeTestRule.runOnUiThread { controller.focusAnswerableTargetForDeepLink() }
            composeTestRule.waitForIdle()

            // Un-wedged — readOnly cleared, the REAL single-question QuestionSheet renders.
            composeTestRule.onNodeWithText("Q-q1 single?", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("Send answer").assertIsDisplayed()
            assertEquals(
                "the hook must re-focus the answerable main pane",
                TerminalStreamController.MAIN_TARGET_ID,
                controller.selectedTargetId.value,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun subagentSelection_gatesOutQuestion_thenDeepLinkRefocusRendersMulti() {
        // AC2 (multi-question) — same wedge→fix, but the ask is an N>1 group, so the paged
        // sheet ("Question 1 of 2") must render once the hook re-focuses main.
        val controller = selectionController()
        val pending = MutableStateFlow<PendingSheet?>(multiQuestion("q2"))
        try {
            composeTestRule.runOnUiThread {
                controller.selectTarget(TerminalStreamController.SUBAGENT_ID_PREFIX + "rev")
            }
            composeTestRule.setContent { AiSandboxTheme { GatedAnswerSlot(controller, pending) } }
            composeTestRule.waitForIdle()

            // Wedge — the multi-question group is gated out while subagent-selected.
            composeTestRule.onNodeWithText("Question 1 of 2", substring = true).assertDoesNotExist()

            composeTestRule.runOnUiThread { controller.focusAnswerableTargetForDeepLink() }
            composeTestRule.waitForIdle()

            // Un-wedged — the REAL paged multi-question QuestionSheet renders.
            composeTestRule.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("Q1-q2?", substring = true).assertIsDisplayed()
            assertEquals(
                TerminalStreamController.MAIN_TARGET_ID,
                controller.selectedTargetId.value,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun mainSelection_deepLinkRefocusIsNoOp_questionStaysRendered() {
        // AC3 / idempotency — on a healthy main selection a question is already rendered; the
        // deep-link hook is a strict no-op (selection stays main, the sheet keeps rendering),
        // so the healthy path is never perturbed.
        val controller = selectionController()
        val pending = MutableStateFlow<PendingSheet?>(singleQuestion("q3"))
        try {
            // No subagent selection — the controller starts on main.
            composeTestRule.setContent { AiSandboxTheme { GatedAnswerSlot(controller, pending) } }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Q-q3 single?", substring = true).assertIsDisplayed()

            composeTestRule.runOnUiThread { controller.focusAnswerableTargetForDeepLink() }
            composeTestRule.waitForIdle()

            // Still main, still rendering — the hook neither switched panes nor hid the sheet.
            assertEquals(
                TerminalStreamController.MAIN_TARGET_ID,
                controller.selectedTargetId.value,
            )
            composeTestRule.onNodeWithText("Q-q3 single?", substring = true).assertIsDisplayed()
        } finally {
            controller.close()
        }
    }
}
