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
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.Routes
import com.aisandbox.android.ui.components.AgentSwitcherBar
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
     * The exact production notification deep-link navigation (AiSandboxApp.kt:165-167).
     *
     * <p>UC-91 FIX SEAM — the developer's fix adds
     * {@code popUpTo(Routes.ConversationPattern) { inclusive = true }} HERE (mirroring
     * the production deep-link site). With it absent the A→B switch reuses the entry and
     * bleeds; with it present the entry is popped + recreated and the destination re-keys.
     */
    private fun NavHostController.deepLinkToConversation(n: Int, fixed: Boolean = false) =
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

        // Second notification deep-link switches to B via the EXACT pre-fix call (no popUpTo).
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
        // Control — the SAME switch but through the FIXED navigation option
        // (popUpTo inclusive). This is expected to PASS even on the unfixed build,
        // proving the chosen popUpTo fix actually re-keys the destination (transcript
        // + pills both follow), so we are not shipping a third incomplete fix.
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
}
