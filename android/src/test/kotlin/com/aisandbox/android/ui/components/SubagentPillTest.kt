package com.aisandbox.android.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UC-60 — subagents-as-pills on the Android client, as a Robolectric JVM test (no
 * emulator) so it runs in the normal {@code :android:test} pass. The device-realistic
 * mirror lives in the instrumented {@code ConversationScreenInstrumentationTest}.
 *
 * <p>Covers the three client seams the use case touches:
 * <ul>
 *   <li>AC2 colour parity — a subagent pill's switcher dot hashes the FULL
 *       {@code subagent:<id>} target id through the SAME {@link chromaticColorForKey}
 *       the conversation bubble tint uses for that same source, so the dot and the
 *       subagent's message bubbles read as one colour (no divergent palette);</li>
 *   <li>AC1/AC5/AC6 — {@link AgentSwitcherBar} renders a subagent target as a pill
 *       with its label, alongside (not replacing) the existing team/main pills, and a
 *       tap routes its id; AC7 — a single-agent session (main only) shows no pills;</li>
 *   <li>the Major fix's UX echo — {@link Composer} is read-only (disabled + an
 *       explanatory placeholder) when a {@code subagent:} target is selected.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp")
class SubagentPillTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val main = StreamTarget(id = TerminalStreamController.MAIN_TARGET_ID, kind = "main", title = "main")

    private fun subagent(id: String, label: String, working: Boolean = false) = StreamTarget(
        id = TerminalStreamController.SUBAGENT_ID_PREFIX + id,
        kind = "subagent",
        title = label,
        pendingActivity = working,
    )

    // ──────────────────────── AC2 — dot colour == bubble tint ────────────────

    @Test
    fun `subagent dot hash equals the bubble tint for the same subagent source`() {
        // The switcher dot and the conversation bubble both key on the full
        // `subagent:<id>` string and run it through the SAME hash, so they agree.
        val targetId = TerminalStreamController.SUBAGENT_ID_PREFIX + "worker-7"
        assertThat(chromaticColorForKey(targetId)).isEqualTo(bubbleTintForSource(targetId))
        // Deterministic and stable across calls.
        assertThat(chromaticColorForKey(targetId)).isEqualTo(chromaticColorForKey(targetId))
    }

    @Test
    fun `different subagent ids generally take different palette slots`() {
        // Not a hard guarantee for every pair, but the hash must spread ids across the
        // palette rather than collapsing them — sanity that the dot is id-derived.
        val a = chromaticColorForKey("subagent:alpha")
        val b = chromaticColorForKey("subagent:bravo-2")
        val c = chromaticColorForKey("subagent:charlie-33")
        assertThat(setOf(a, b, c).size).isGreaterThan(1)
    }

    // ──────────────────────── AC1/AC5/AC6/AC7 — pill rendering ───────────────

    @Test
    fun `a subagent target renders as a labelled pill alongside the main pill`() {
        composeRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(main, subagent("a1", "code-reviewer", working = true)),
                    selectedTargetId = "main",
                    onSelect = {},
                )
            }
        }
        // The subagent pill shows with its label; main is still present (additive, AC5).
        composeRule.onNodeWithText("main").assertIsDisplayed()
        composeRule.onNodeWithText("code-reviewer").assertIsDisplayed()
    }

    @Test
    fun `subagent and team pills render together without dropping either`() {
        // AC6 — subagents and team agents shown consistently together.
        val team = StreamTarget(
            id = "swarm:claude-swarm-1:0.0",
            kind = "swarm",
            title = "agent ping",
            agentName = "ping",
            agentColor = "blue",
        )
        composeRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(main, team, subagent("a1", "verifier")),
                    selectedTargetId = "main",
                    onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("main").assertIsDisplayed()
        composeRule.onNodeWithText("ping").assertIsDisplayed()
        composeRule.onNodeWithText("verifier").assertIsDisplayed()
    }

    @Test
    fun `tapping a subagent pill routes its full subagent id`() {
        val selections = mutableListOf<String>()
        composeRule.setContent {
            var selected by remember { mutableStateOf("main") }
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(main, subagent("a1", "code-reviewer")),
                    selectedTargetId = selected,
                    onSelect = {
                        selections.add(it)
                        selected = it
                    },
                )
            }
        }
        composeRule.onNodeWithText("code-reviewer").assertHasClickAction().performClick()
        assertThat(selections).containsExactly("subagent:a1")
    }

    @Test
    fun `a single-agent session main only shows no pills`() {
        // AC7 — no extra pills when there is no team and no subagent.
        composeRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(targets = listOf(main), selectedTargetId = "main", onSelect = {})
            }
        }
        composeRule.onNodeWithText("main").assertDoesNotExist()
    }

    // ──────────────────────── Major-fix UX echo — read-only composer ─────────

    @Test
    fun `composer is read-only with an explanatory placeholder when a subagent is selected`() {
        var submitted: String? = null
        composeRule.setContent {
            AiSandboxTheme { Composer(enabled = false, onSubmit = { submitted = it }, readOnly = true) }
        }
        composeRule.onNodeWithText("Viewing a subagent — read-only").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        assertThat(submitted).isNull()
    }

    @Test
    fun `composer read-only placeholder differs from the pending-question lock`() {
        // The subagent read-only state must NOT reuse the "answer the question" copy —
        // a subagent has no question to answer; it is a passive view.
        composeRule.setContent {
            AiSandboxTheme { Composer(enabled = false, onSubmit = {}, readOnly = true) }
        }
        composeRule.onNodeWithText("Viewing a subagent — read-only").assertIsDisplayed()
        composeRule.onNodeWithText("Answer the question above to continue").assertDoesNotExist()
    }

    @Test
    fun `composer is a normal Message box when not read-only and enabled`() {
        composeRule.setContent {
            AiSandboxTheme { Composer(enabled = true, onSubmit = {}, readOnly = false) }
        }
        composeRule.onNodeWithText("Message").assertIsDisplayed()
        composeRule.onNodeWithText("Viewing a subagent — read-only").assertDoesNotExist()
    }
}
