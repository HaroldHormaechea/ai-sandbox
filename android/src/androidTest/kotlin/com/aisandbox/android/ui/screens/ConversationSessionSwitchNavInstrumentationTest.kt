package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.conversation.ConvOption
import com.aisandbox.android.conversation.ConvQuestion
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.Routes
import com.aisandbox.android.ui.components.AgentSwitcherBar
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * UC-91 — REPRO-FIRST regression for the session→session conversation bleed +
 * missing agent pills that UC-86 (android-v0.4.17) shipped GREEN yet did not
 * fix.
 *
 * <p><b>⚠ UC-93 CORRECTION (supersedes the "reproduced faithfully here" claims
 * below).</b> The UC-93 re-analysis (analyst + challenger, endorsed; confirmed on a
 * live aisandbox AVD / API 36) DISPROVED the nav-entry-reuse mechanism this class
 * documents: on Navigation-Compose <b>2.9.8</b>, a warm {@code launchSingleTop}
 * navigate ALREADY re-keys the destination and re-fires {@code LaunchedEffect}, so
 * {@code attach(target)} fires <i>with or without</i> {@code popUpTo} (measured
 * {@code attachOrder}: A→B = [1, 2], A→A = [1, 1]). Consequently these tests assert
 * <b>correct navigation / positive behavior</b>, NOT a reproduction of the wedge, and
 * they are <b>false-green</b> as a fail-before guard for the {@code popUpTo} fix —
 * removing {@code popUpTo} would NOT turn them red. The REAL wedge mechanism is at the
 * controller layer ({@code ConversationController.attach} no-ops on a live connection /
 * {@code connectJob.isActive} and never re-emits the held pending question on warm
 * re-entry); its fail-before/pass-after guard lives in the UC-93 controller-layer test.
 * Assertions below are intentionally left unchanged (they remain valid positive
 * coverage). See the UC-93 section lower in this file.
 *
 * <p><b>Why a nav-layer test (and why UC-86 missed it).</b> UC-86's coverage
 * ({@link ConversationAnchorInstrumentationTest#targetSwitch_reAnchorsToNewStreamBottom})
 * drives the {@link ConversationContent} seam directly — it mutates the
 * transcript {@code MutableState} in place and asserts SCROLL ANCHORING. That
 * bypasses the real defect, which lives one layer up: the {@code conversation/{n}}
 * navigation destination. The production notification deep-link
 * ({@code AiSandboxApp.kt:165-167}) does
 * {@code navigate(conversationFor(n)) { launchSingleTop = true }} with <b>no
 * {@code popUpTo}</b>. Because {@code conversation/{n}} is a single destination
 * ({@code Routes.kt}), switching A→B reuses the SAME {@code NavBackStackEntry}
 * and mutates its args in place. The destination's content lambda reads the
 * session id via a plain, non-observed {@code backStackEntry.arguments?.getInt("n")}
 * ({@code AiSandboxApp.kt:210}), so it never recomposes with the new {@code n};
 * the screen's {@code LaunchedEffect(sessionN)} ({@code ConversationScreen.kt:112})
 * never re-fires, {@code attach(B)} is never called, and the screen keeps
 * rendering session A's transcript AND session A's pill set (sticky).
 *
 * <p>This test reproduces THAT path: a real {@link NavHost} with the
 * {@code conversation/{n}} route wired exactly as production wires it, navigated
 * via the exact pre-fix deep-link call. It asserts the <b>rendered transcript
 * identity</b> and the <b>rendered pill set</b> (via the REAL production
 * composables {@link ConversationContent} + {@link AgentSwitcherBar}) — not scroll
 * position — for both switch orders (A→B and B→A) and rapid back-and-forth.
 *
 * <p>The transcript / pill data is fed through a per-session {@link FakeSession}
 * standing in for the {@code ConversationController} the screen would resolve from
 * the {@code AppContainer} (which is not an instrumentation seam — see
 * {@link SubagentPillInstrumentationTest}). The data source is irrelevant to the
 * defect: the bug is entirely in nav-entry reuse + the non-reactive {@code n} read
 * + the size-keyed {@code LaunchedEffect}, all of which are reproduced faithfully
 * here. {@link #attachOrder} records every {@code attach(n)} so the test also proves
 * the causal chain (whether {@code attach(B)} is ever reached).
 *
 * <p>Acceptance-criteria mapping:
 *  - AC2 (no transcript carryover on switch)  → [switch_AtoB_rendersBsTranscriptOnly_andBsPills]
 *  - AC3 (agent pills appear on switch-to)     → [switch_AtoB_rendersBsTranscriptOnly_andBsPills]
 *  - AC4 (independent of entry order)          → [switch_BtoA_rendersAsTranscriptOnly_andDropsBsPills]
 *  - AC5 (not sticky under rapid switching)    → [rapidSwitch_ABAB_alwaysReflectsCurrentSession]
 *  - AC7 (regression test asserts identity+pills, would have failed before fix)
 *
 * <p>The fix (developer, GREEN phase) adds
 * {@code popUpTo(Routes.ConversationPattern) { inclusive = true }} to the deep-link
 * navigation. [switch_AtoB_withPopUpTo_rendersB_provesFixMechanism] exercises that
 * navigation option and is expected to PASS even pre-fix, demonstrating the chosen
 * fix actually re-keys the destination (so we don't ship a third incomplete fix).
 */
@RunWith(AndroidJUnit4::class)
class ConversationSessionSwitchNavInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mainTarget = StreamTarget(
        id = TerminalStreamController.MAIN_TARGET_ID,
        kind = "main",
        title = "main",
    )

    /** A background subagent pill, mirroring [SubagentPillInstrumentationTest]'s `subagent(...)`. */
    private fun subagent(id: String, label: String) = StreamTarget(
        id = TerminalStreamController.SUBAGENT_ID_PREFIX + id,
        kind = "subagent",
        title = label,
    )

    private fun msg(tag: String, i: Int) = ConversationItem.AssistantMessage(
        uuid = "$tag-$i",
        source = "main",
        isSidechain = false,
        text = "[$tag] Message #$i",
    )

    private fun msgs(tag: String, n: Int): List<ConversationItem> = (0 until n).map { msg(tag, it) }

    /** Per-session view state — the controller stand-in the destination collects from. */
    private class FakeSession(
        items: List<ConversationItem>,
        targets: List<StreamTarget>,
    ) {
        val items = MutableStateFlow(items)
        val targets = MutableStateFlow(targets)
    }

    /** Records the order of attach(n) calls so the causal chain is observable from the test. */
    private val attachOrder: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    private val sessions = HashMap<Int, FakeSession>()

    private fun attach(n: Int): FakeSession {
        attachOrder.add(n)
        return sessions.getValue(n)
    }

    /**
     * Faithful stand-in for [ConversationScreen]'s session-keying seam: the
     * {@code LaunchedEffect(sessionN) { attach(sessionN) }} from
     * {@code ConversationScreen.kt:112}, then the transcript + pill set rendered by the
     * REAL production composables. If the destination is not re-keyed on a session
     * switch, [LaunchedEffect] never re-fires, so the prior session's view persists —
     * which is exactly the bug.
     */
    @Composable
    private fun ReproConversationScreen(sessionN: Int) {
        var session by remember { mutableStateOf<FakeSession?>(null) }
        LaunchedEffect(sessionN) { session = attach(sessionN) }
        val s = session ?: return
        val items by s.items.collectAsState()
        val targets by s.targets.collectAsState()
        Column(modifier = Modifier.fillMaxSize()) {
            // REAL pill bar — renders nothing when the only target is `main` (AC#12).
            AgentSwitcherBar(
                targets = targets,
                selectedTargetId = TerminalStreamController.MAIN_TARGET_ID,
                onSelect = {},
            )
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                // REAL transcript renderer — same seam UC-86's test drove.
                ConversationContent(items = items, modifier = Modifier.fillMaxSize())
            }
        }
    }

    /**
     * The production notification deep-link navigation (AiSandboxApp.kt:165-167).
     *
     * <p>The SHIPPED production call (commit 88788a5) adds
     * {@code popUpTo(Routes.ConversationPattern) { inclusive = true }} alongside
     * {@code launchSingleTop = true} at the deep-link site, so {@code fixed} DEFAULTS to
     * {@code true} and the tests exercise the shipped navigation. The {@code fixed = false}
     * variant (launchSingleTop only) is retained only to document the prior call shape.
     *
     * <p>NOTE (analyst + challenger, endorsed; confirmed on the aisandbox AVD / API 36):
     * on Navigation-Compose 2.9.8 BOTH variants re-key the destination and re-fire
     * {@code LaunchedEffect(sessionN)} on a warm deep-link (measured attachOrder
     * A→B = [1, 2], A→A = [1, 1]). popUpTo is therefore indistinguishable from plain
     * launchSingleTop at this layer — the nav-entry-reuse wedge does NOT reproduce here.
     * The real UC-93 wedge is at the controller layer (warm re-attach no-op), guarded by
     * a separate controller-level test.
     */
    private fun NavHostController.deepLinkToConversation(n: Int, fixed: Boolean = true) =
        navigate(Routes.conversationFor(n)) {
            launchSingleTop = true
            if (fixed) popUpTo(Routes.ConversationPattern) { inclusive = true }
        }

    /** Build the real NavHost with the conversation route wired exactly as AiSandboxApp wires it. */
    private fun setNavContent(onReady: (NavHostController) -> Unit) {
        composeTestRule.setContent {
            val navController = rememberNavController()
            LaunchedEffect(navController) { onReady(navController) }
            AiSandboxTheme {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") { /* placeholder start destination */ }
                    // Verbatim from AiSandboxApp.kt:206-217 — the non-reactive `n` read is the defect.
                    composable(
                        route = Routes.ConversationPattern,
                        arguments = listOf(navArgument("n") { type = NavType.IntType }),
                    ) { backStackEntry ->
                        val n = backStackEntry.arguments?.getInt("n") ?: 0
                        ReproConversationScreen(sessionN = n)
                    }
                }
            }
        }
    }

    @Test
    fun switch_AtoB_rendersBsTranscriptOnly_andBsPills() {
        // Session A: a no-agent session (main only → no pills). Session B: agent-bearing.
        sessions[1] = FakeSession(items = msgs("A", 6), targets = listOf(mainTarget))
        sessions[2] = FakeSession(
            items = msgs("B", 6),
            targets = listOf(mainTarget, subagent("rev", "reviewer-B")),
        )

        lateinit var nav: NavHostController
        setNavContent { nav = it }
        composeTestRule.waitForIdle()

        // First notification deep-link lands on A.
        composeTestRule.runOnUiThread { nav.deepLinkToConversation(1) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("[A] Message #5", substring = true).assertIsDisplayed()

        // Second notification deep-link switches to B via the SHIPPED fixed nav (popUpTo).
        composeTestRule.runOnUiThread { nav.deepLinkToConversation(2) }
        composeTestRule.waitForIdle()

        // AC2 — B's transcript only; no carryover from A.
        composeTestRule.onNodeWithText("[B] Message #5", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("[A] Message #5", substring = true).assertDoesNotExist()
        // AC3 — B's agent pill appears on switch-to.
        composeTestRule.onNodeWithText("reviewer-B").assertIsDisplayed()
        // Causal chain — attach(B) must have been reached (proves the destination re-keyed).
        assertTrue(
            "attach(B=2) must be reached on a session switch, but attachOrder=$attachOrder",
            attachOrder.contains(2),
        )
        assertEquals("the last attach must be the switched-to session B", 2, attachOrder.last())
    }

    @Test
    fun switch_BtoA_rendersAsTranscriptOnly_andDropsBsPills() {
        // AC4 — independent of entry order: land first on agent-bearing B, switch to no-agent A.
        sessions[1] = FakeSession(items = msgs("A", 6), targets = listOf(mainTarget))
        sessions[2] = FakeSession(
            items = msgs("B", 6),
            targets = listOf(mainTarget, subagent("rev", "reviewer-B")),
        )

        lateinit var nav: NavHostController
        setNavContent { nav = it }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { nav.deepLinkToConversation(2) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("[B] Message #5", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("reviewer-B").assertIsDisplayed()

        composeTestRule.runOnUiThread { nav.deepLinkToConversation(1) }
        composeTestRule.waitForIdle()

        // A's transcript, no B carryover, and B's pill is gone (A is a no-agent session).
        composeTestRule.onNodeWithText("[A] Message #5", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("[B] Message #5", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("reviewer-B").assertDoesNotExist()
        assertEquals("the last attach must be the switched-to session A", 1, attachOrder.last())
    }

    @Test
    fun rapidSwitch_ABAB_alwaysReflectsCurrentSession() {
        // AC5 — rapid back-and-forth never leaves a stale transcript or pill set.
        sessions[1] = FakeSession(items = msgs("A", 6), targets = listOf(mainTarget))
        sessions[2] = FakeSession(
            items = msgs("B", 6),
            targets = listOf(mainTarget, subagent("rev", "reviewer-B")),
        )

        lateinit var nav: NavHostController
        setNavContent { nav = it }
        composeTestRule.waitForIdle()

        val order = listOf(1, 2, 1, 2)
        for (n in order) {
            composeTestRule.runOnUiThread { nav.deepLinkToConversation(n) }
            composeTestRule.waitForIdle()
        }

        // Final session is B — its transcript + pills must be what's on screen.
        composeTestRule.onNodeWithText("[B] Message #5", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("[A] Message #5", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("reviewer-B").assertIsDisplayed()
        assertEquals("the last attach must be the final session B", 2, attachOrder.last())
    }

    @Test
    fun switch_AtoB_withPopUpTo_rendersB_provesFixMechanism() {
        // ⚠ UC-93 CORRECTION: the "proves the fix re-keys" claim is superseded. On
        // Navigation-Compose 2.9.8 plain launchSingleTop ALSO re-keys (see the class
        // KDoc's UC-93 correction), so popUpTo is indistinguishable from the pre-fix
        // call at this layer — this asserts correct positive navigation behavior, NOT a
        // reproduction of (or a guard against) the wedge. The real fail-before/pass-after
        // guard lives in the UC-93 controller-layer test. Assertions left unchanged.
        // Control — the SAME switch through the popUpTo navigation option (popUpTo
        // inclusive); passes pre- and post-fix.
        sessions[1] = FakeSession(items = msgs("A", 6), targets = listOf(mainTarget))
        sessions[2] = FakeSession(
            items = msgs("B", 6),
            targets = listOf(mainTarget, subagent("rev", "reviewer-B")),
        )

        lateinit var nav: NavHostController
        setNavContent { nav = it }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { nav.deepLinkToConversation(1) }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { nav.deepLinkToConversation(2, fixed = true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("[B] Message #5", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("[A] Message #5", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("reviewer-B").assertIsDisplayed()
        assertTrue("attach(B) must be reached with the popUpTo fix", attachOrder.contains(2))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UC-93 — POSITIVE behavioral coverage: the REAL QuestionSheet rendered over the
    // notification warm deep-link.
    //
    // INTENT: assert the desired end-state of the warm pending-question deep-link — a
    // warm deep-link to session N re-attaches (attach(N) fires → fresh socket → server
    // re-emits N's pending question) and the REAL [QuestionSheet] renders, for both
    // single- and multi-question groups, and for the same-session (A→A) and
    // cross-session (A→B) cases. The pending question is ARMED on the FakeSheetSession
    // and delivered to the UI ([pendingSheet]) ONLY from INSIDE the recorded attach(n)
    // (modeling the server's ~600ms per-connection re-emit), so the sheet can appear
    // ONLY if attach(n) fired; the [attachOrder] seam proves the causal chain.
    //
    // SCOPE (analyst + challenger, endorsed): these are POSITIVE coverage, NOT a
    // fail-before/pass-after regression guard. The originally-triaged nav mechanism is
    // DISPROVEN — on Navigation-Compose 2.9.8 plain `launchSingleTop` (no popUpTo)
    // already re-keys the destination and re-fires LaunchedEffect, so attach fires on
    // every warm deep-link (live-emulator measurement on aisandbox AVD / API 36:
    // A→B = [1, 2], A→A = [1, 1]). popUpTo vs. not is therefore indistinguishable at
    // the nav layer, so a nav-layer fail-before guard is genuinely unsatisfiable. The
    // REAL UC-93 regression guard lives at the CONTROLLER layer (ConversationController
    // warm re-attach no-op when connectJob is active) and is covered by a separate
    // controller-level test — not here.
    //
    // Acceptance-criteria mapping (UC-93):
    //  - AC2 (lands on target, re-attaches, renders single AND multi sheet) →
    //        [warmDeepLink_sameSession_reAttaches_rendersNewSingleQuestion],
    //        [warmDeepLink_AtoB_reAttachesB_andRendersBsMultiQuestion]
    //  - AC3 (same-session warm case surfaces the newly-arrived question) →
    //        [warmDeepLink_sameSession_reAttaches_rendersNewSingleQuestion]
    //  - AC7 (fail-before/pass-after regression guard) → NOT here; covered at the
    //        controller layer (see the ConversationController warm-re-attach test).
    // ──────────────────────────────────────────────────────────────────────────

    /** A single-question AskUserQuestion (AC2 single case). */
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

    /** A multi-question (N>1) AskUserQuestion group (AC2 multi case → the paged sheet). */
    private fun multiQuestion(uuid: String) = PendingSheet.Questions(
        questionUuid = uuid,
        questions = listOf(
            ConvQuestion("Q1-$uuid?", "hdr1-$uuid", false, listOf(ConvOption("o1a", "d1"))),
            ConvQuestion("Q2-$uuid?", "hdr2-$uuid", false, listOf(ConvOption("o2a", "d2"))),
        ),
        answerable = true,
    )

    /**
     * Per-session state for the UC-93 sheet tests. [armedQuestion] models a server-side
     * pending question waiting to be re-emitted: it is delivered to the UI ([pendingSheet])
     * ONLY when a fresh connection is opened — i.e. from inside [attachWithReEmit].
     */
    private class FakeSheetSession {
        val pendingSheet = MutableStateFlow<PendingSheet?>(null)

        @Volatile
        var armedQuestion: PendingSheet? = null
    }

    private val sheetSessions = HashMap<Int, FakeSheetSession>()

    /**
     * Models the production attach seam + the server's per-connection pending-question
     * re-emit: every attach(n) "reconnects" and the server re-delivers session n's
     * currently-armed pending question. We record the attach in [attachOrder] and set
     * pendingSheet INSIDE attach, so the sheet can appear ONLY if attach(n) actually
     * fired (the [attachOrder] seam proves the causal chain). NOTE: on this
     * Navigation-Compose version a warm deep-link re-fires attach even without popUpTo
     * (see the section header's live-emulator finding), so these tests assert the
     * positive render behavior rather than a pre-fix wedge.
     */
    private fun attachWithReEmit(n: Int): FakeSheetSession {
        attachOrder.add(n)
        val s = sheetSessions.getValue(n)
        s.pendingSheet.value = s.armedQuestion
        return s
    }

    /**
     * Stand-in for [ConversationScreen] wired to render the REAL [QuestionSheet] gated
     * on the per-session pendingSheet flow (ConversationScreen.kt:210-217), behind the
     * same {@code LaunchedEffect(sessionN) { attach(sessionN) }} keying seam
     * (ConversationScreen.kt:112). When the destination re-keys on a warm deep-link,
     * [LaunchedEffect] re-fires, [attachWithReEmit] runs, and the newly-armed question
     * reaches [QuestionSheet].
     */
    @Composable
    private fun ReproConversationScreenWithSheet(sessionN: Int) {
        var session by remember { mutableStateOf<FakeSheetSession?>(null) }
        LaunchedEffect(sessionN) { session = attachWithReEmit(sessionN) }
        val s = session ?: return
        val sheet by s.pendingSheet.collectAsState()
        Column(modifier = Modifier.fillMaxSize()) {
            sheet?.let {
                // REAL production sheet — same composable ConversationScreen renders.
                QuestionSheet(
                    sheet = it,
                    onSubmit = { _, _, _, _ -> },
                    onSubmitBatch = { _, _ -> },
                )
            }
        }
    }

    /** Build the real NavHost with the conversation route wired exactly as AiSandboxApp wires it. */
    private fun setSheetNavContent(onReady: (NavHostController) -> Unit) {
        composeTestRule.setContent {
            val navController = rememberNavController()
            LaunchedEffect(navController) { onReady(navController) }
            AiSandboxTheme {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") { /* placeholder start destination */ }
                    composable(
                        route = Routes.ConversationPattern,
                        arguments = listOf(navArgument("n") { type = NavType.IntType }),
                    ) { backStackEntry ->
                        val n = backStackEntry.arguments?.getInt("n") ?: 0
                        ReproConversationScreenWithSheet(sessionN = n)
                    }
                }
            }
        }
    }

    @Test
    fun warmDeepLink_sameSession_reAttaches_rendersNewSingleQuestion() {
        // AC2 / AC3 (single-question, same-session A→A) — session A is opened with NO
        // pending question; a question then ARRIVES for A; the user taps its notification
        // while A is already on top. The warm deep-link re-keys the destination,
        // attach(A) re-fires, the server re-emits A's now-armed question, and the REAL
        // single-question QuestionSheet renders (no silent launchSingleTop no-op that
        // drops the re-attach). The default deep-link mirrors the SHIPPED production nav
        // (popUpTo + launchSingleTop).
        sheetSessions[1] = FakeSheetSession()

        lateinit var nav: NavHostController
        setSheetNavContent { nav = it }
        composeTestRule.waitForIdle()

        // Open A — no question armed yet, so nothing renders.
        composeTestRule.runOnUiThread { nav.deepLinkToConversation(1) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Q-q1 single?", substring = true).assertDoesNotExist()

        // A new pending question arrives server-side for A; the warm deep-link re-attaches.
        sheetSessions.getValue(1).armedQuestion = singleQuestion("q1")
        composeTestRule.runOnUiThread { nav.deepLinkToConversation(1) }
        composeTestRule.waitForIdle()

        // Re-attached → the newly-arrived single question rendered (SingleQuestionBody).
        composeTestRule.onNodeWithText("Q-q1 single?", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Send answer").assertIsDisplayed()
        // Causal chain — attach(A) must have re-fired (proves the warm deep-link re-keys).
        assertTrue(
            "attach(A=1) must re-fire on the warm same-session deep-link, attachOrder=$attachOrder",
            attachOrder.count { it == 1 } >= 2,
        )
        assertEquals("the last attach must be the target session A", 1, attachOrder.last())
    }

    @Test
    fun warmDeepLink_AtoB_reAttachesB_andRendersBsMultiQuestion() {
        // AC2 (multi-question, cross-session A→B) — a DIFFERENT conversation (A) is on
        // top; the deep-link to B carries a multi-question group. The warm deep-link
        // re-keys to B, attach(B) opens the connection, the server re-emits B's
        // multi-question batch, and the paged QuestionSheet renders ("Question 1 of 2").
        sheetSessions[1] = FakeSheetSession()
        sheetSessions[2] = FakeSheetSession().also { it.armedQuestion = multiQuestion("q2") }

        lateinit var nav: NavHostController
        setSheetNavContent { nav = it }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { nav.deepLinkToConversation(1) }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { nav.deepLinkToConversation(2) }
        composeTestRule.waitForIdle()

        // B's paged multi-question sheet — the "X of N" indicator + the first question.
        composeTestRule.onNodeWithText("Question 1 of 2", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Q1-q2?", substring = true).assertIsDisplayed()
        assertTrue("attach(B=2) must be reached on the warm A→B deep-link", attachOrder.contains(2))
        assertEquals("the last attach must be the switched-to session B", 2, attachOrder.last())
    }
}
