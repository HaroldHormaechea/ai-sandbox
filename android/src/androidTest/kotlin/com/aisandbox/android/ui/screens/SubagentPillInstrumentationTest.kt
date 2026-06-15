package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.Column
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.components.AgentSwitcherBar
import com.aisandbox.android.ui.components.Composer
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-60 — device-realistic (emulator-tier) coverage that a background subagent
 * surfaces as a pill at the top of the chat screen with the same characteristics
 * as a team-agent pill, taps to a READ-ONLY view, and never perturbs the user's
 * normal pills/composer. Drives the public composables directly (the full
 * {@code ConversationScreen} resolves a live {@code ConversationViewModel} from the
 * {@code AppContainer}, which is not an instrumentation seam) — mirroring
 * {@code TerminalScreenInstrumentationTest} / {@code ConversationScreenInstrumentationTest}.
 * The JVM {@code SubagentPillTest} is the Robolectric mirror that runs without an emulator.
 */
@RunWith(AndroidJUnit4::class)
class SubagentPillInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val main = StreamTarget(id = TerminalStreamController.MAIN_TARGET_ID, kind = "main", title = "main")
    private val team = StreamTarget(
        id = "swarm:claude-swarm-1:0.0",
        kind = "swarm",
        title = "agent ping",
        agentName = "ping",
        agentColor = "blue",
    )

    private fun subagent(id: String, label: String, working: Boolean = false) = StreamTarget(
        id = TerminalStreamController.SUBAGENT_ID_PREFIX + id,
        kind = "subagent",
        title = label,
        pendingActivity = working,
    )

    @Test
    fun subagent_pill_appears_at_top_with_label_alongside_team_and_main() {
        // AC1/AC2/AC5/AC6 — the subagent pill renders at the top with its label, together
        // with the existing team + main pills (additive, not divergent).
        composeTestRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(
                    targets = listOf(main, team, subagent("a1", "code-reviewer", working = true)),
                    selectedTargetId = "main",
                    onSelect = {},
                )
            }
        }
        composeTestRule.onNodeWithText("main").assertIsDisplayed()
        composeTestRule.onNodeWithText("ping").assertIsDisplayed()
        composeTestRule.onNodeWithText("code-reviewer").assertIsDisplayed()
    }

    @Test
    fun tapping_the_subagent_pill_then_showing_its_read_only_composer() {
        // AC3 + the Major fix's UX echo — selecting the subagent pill routes its id, and the
        // composer for that selection is read-only (disabled, explanatory placeholder).
        val selections = mutableListOf<String>()
        composeTestRule.setContent {
            var selected by remember { mutableStateOf("main") }
            AiSandboxTheme {
                Column {
                    AgentSwitcherBar(
                        targets = listOf(main, subagent("a1", "code-reviewer")),
                        selectedTargetId = selected,
                        onSelect = {
                            selections.add(it)
                            selected = it
                        },
                    )
                    val readOnly = selected.startsWith(TerminalStreamController.SUBAGENT_ID_PREFIX)
                    Composer(enabled = !readOnly, onSubmit = {}, readOnly = readOnly)
                }
            }
        }

        // Before tapping: the composer is the normal "Message" box.
        composeTestRule.onNodeWithText("Message").assertIsDisplayed()

        composeTestRule.onNodeWithText("code-reviewer").assertHasClickAction().performClick()
        assertEquals(listOf("subagent:a1"), selections)

        // After tapping the subagent pill: read-only view.
        composeTestRule.onNodeWithText("Viewing a subagent — read-only").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun single_agent_session_shows_no_pills() {
        // AC7 — a session with only the main agent (no team, no subagents) shows no pills.
        composeTestRule.setContent {
            AiSandboxTheme {
                AgentSwitcherBar(targets = listOf(main), selectedTargetId = "main", onSelect = {})
            }
        }
        composeTestRule.onNodeWithText("main").assertDoesNotExist()
    }

    @Test
    fun selecting_a_team_pill_keeps_the_composer_interactive() {
        // AC5/AC7 regression — the read-only gate is subagent-only; selecting a team pill
        // (or main) leaves the composer a normal interactive Message box.
        composeTestRule.setContent {
            var selected by remember { mutableStateOf("main") }
            AiSandboxTheme {
                Column {
                    AgentSwitcherBar(
                        targets = listOf(main, team),
                        selectedTargetId = selected,
                        onSelect = { selected = it },
                    )
                    val readOnly = selected.startsWith(TerminalStreamController.SUBAGENT_ID_PREFIX)
                    Composer(enabled = !readOnly, onSubmit = {}, readOnly = readOnly)
                }
            }
        }
        composeTestRule.onNodeWithText("ping").performClick()
        composeTestRule.onNodeWithText("Message").assertIsDisplayed()
        composeTestRule.onNodeWithText("Viewing a subagent — read-only").assertDoesNotExist()
    }
}
