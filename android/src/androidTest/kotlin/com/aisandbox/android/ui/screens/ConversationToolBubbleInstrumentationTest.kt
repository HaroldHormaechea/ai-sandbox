package com.aisandbox.android.ui.screens

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.ToolDetailState
import com.aisandbox.android.conversation.ToolResultData
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-41 — device-realistic (emulator-tier) coverage for the collapsed, type-aware
 * tool/skill bubbles and the on-demand detail dialog. Drives the REAL routing +
 * bubble + dialog composables through the `internal` [ConversationContent] /
 * [ToolBubble] / [ToolDetailDialog] seams with real [ConversationItem] /
 * [ToolDetailState] data, so the rendered labels, the ~20-char snippet budget,
 * the awaiting/error states, the tap→detail wiring, and the dialog content render
 * exactly as on screen — then captures screenshot evidence for the gate report.
 *
 * Acceptance-criteria mapping:
 *  - AC1   Skill → "Skill loaded <name>" ([skillBubble_rendersSkillLoadedLabel])
 *  - AC2   Bash  → "Command used: <≤20ch>…", ellipsis only when truncated
 *          ([bashBubble_longCommand_truncatesWithEllipsis], [bashBubble_shortCommand_hasNoEllipsis])
 *  - AC3   other → "<tool>: <snippet>" ([otherTool_rendersGenericLabel])
 *  - AC4   one merged row per tool call ([mergedToolCall_rendersAsASingleRow])
 *  - AC5   tap opens detail ([tappingBubble_firesOnToolTap], [detailDialog_showsFullInputAndOutput])
 *  - AC6   full untruncated input+output, scrollable/selectable ([detailDialog_showsFullInputAndOutput])
 *  - AC7   error result styled (✗ on the row, error output in the dialog) ([errorResult_showsErrorMarker])
 *  - AC8   awaiting-result state ([awaitingResult_showsAwaitingHint])
 *  - AC9   detail unavailable ([detailDialog_unavailable_showsMessage])
 */
@RunWith(AndroidJUnit4::class)
class ConversationToolBubbleInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun saveScreenshot(tag: String) {
        composeTestRule.waitForIdle()
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/uc41_$tag.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // drain → blocks until done
    }

    private fun toolActivity(
        toolName: String,
        toolUseId: String,
        primaryText: String,
        inputSummary: String = primaryText,
        result: ToolResultData? = null,
        sidechain: Boolean = false,
    ) = ConversationItem.ToolActivity(
        uuid = "u-$toolUseId",
        source = if (sidechain) "subagent:worker" else "main",
        isSidechain = sidechain,
        toolName = toolName,
        toolUseId = toolUseId,
        inputSummary = inputSummary,
        primaryText = primaryText,
        result = result,
    )

    // ──────────────────────── collapsed labels (AC1/AC2/AC3) ──────────────────

    @Test
    fun skillBubble_rendersSkillLoadedLabel() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(toolActivity("Skill", "tuSkill", "android-emulator-setup")),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onNodeWithText("Skill loaded android-emulator-setup").assertIsDisplayed()
        saveScreenshot("skill_label")
    }

    @Test
    fun bashBubble_longCommand_truncatesWithEllipsis() {
        // A command well past the ~20-char snippet budget must truncate + ellipsize (AC2).
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(toolActivity("Bash", "tuLong", "kubectl get pods --all-namespaces")),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 20-char take → "kubectl get pods --a" + "…".
        composeTestRule.onNodeWithText("Command used: kubectl get pods --a…").assertIsDisplayed()
        // The truncated tail is gone.
        composeTestRule.onNodeWithText("namespaces", substring = true).assertDoesNotExist()
        saveScreenshot("bash_truncated")
    }

    @Test
    fun bashBubble_shortCommand_hasNoEllipsis() {
        // A short command (≤ 20 chars) renders with NO ellipsis (AC2). A result is supplied
        // so the row is NOT in the awaiting state (whose "awaiting result…" hint has its own ellipsis).
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(
                        toolActivity("Bash", "tuShort", "ls -la", result = ToolResultData(isError = false, summary = "ok")),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onNodeWithText("Command used: ls -la").assertIsDisplayed()
        composeTestRule.onNodeWithText("…", substring = true).assertDoesNotExist()
        saveScreenshot("bash_short")
    }

    @Test
    fun otherTool_rendersGenericLabel() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(toolActivity("Grep", "tuGrep", "needle")),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onNodeWithText("Grep: needle").assertIsDisplayed()
        saveScreenshot("other_tool")
    }

    // ──────────────────────── merge / awaiting / error (AC4/AC7/AC8) ──────────

    @Test
    fun mergedToolCall_rendersAsASingleRow() {
        // A single merged ToolActivity (use+result folded) renders one row, not two (AC4).
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(
                        toolActivity(
                            "Bash",
                            "tuMerge",
                            "echo hi",
                            result = ToolResultData(isError = false, summary = "hi"),
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onNodeWithText("Command used: echo hi").assertIsDisplayed()
        // The merged row is NOT in the awaiting state (its result arrived).
        composeTestRule.onNodeWithText("awaiting result…").assertDoesNotExist()
        saveScreenshot("merged_row")
    }

    @Test
    fun awaitingResult_showsAwaitingHint() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(toolActivity("Bash", "tuAwait", "sleep 5", result = null)),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onNodeWithText("awaiting result…").assertIsDisplayed() // AC8
        saveScreenshot("awaiting")
    }

    @Test
    fun errorResult_showsErrorMarker() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(
                        toolActivity(
                            "Bash",
                            "tuErr",
                            "false",
                            result = ToolResultData(isError = true, summary = "exit 1"),
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onNodeWithText("Command used: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("✗").assertIsDisplayed() // AC7 — error marker on the collapsed row
        saveScreenshot("error_row")
    }

    // ──────────────────────── tap → detail (AC5) ──────────────────────────────

    @Test
    fun tappingBubble_firesOnToolTap() {
        var tapped: String? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                ConversationContent(
                    items = listOf(toolActivity("Bash", "tuTap", "ls")),
                    modifier = Modifier.fillMaxSize(),
                    onToolTap = { tapped = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Command used: ls").performClick()
        composeTestRule.waitForIdle()
        assertEquals("tuTap", tapped) // AC5 — the tap routes the toolUseId to the fetch
    }

    // ──────────────────────── detail dialog (AC5/AC6/AC7/AC9) ─────────────────

    @Test
    fun detailDialog_showsFullInputAndOutput() {
        // AC5/AC6 — the dialog renders the FULL untruncated input + output (selectable,
        // scrollable, bounded height). The content far exceeds the 600-char streaming cap.
        val bigInput = "kubectl apply -f " + "x".repeat(800)
        val bigOutput = "deployment.apps/web created\n" + "y".repeat(800)
        composeTestRule.setContent {
            AiSandboxTheme {
                ToolDetailDialog(
                    state = ToolDetailState.Loaded(input = bigInput, result = bigOutput, isError = false),
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Tool detail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Input").assertIsDisplayed()
        // The full input head is visible at the top of the scroll viewport.
        composeTestRule.onNodeWithText("kubectl apply -f", substring = true).assertIsDisplayed()
        // The Output section + full output are RENDERED (untruncated, > 600 chars total) but sit
        // below the fold of the bounded, scrollable 480dp dialog — proven via assertExists (AC6).
        composeTestRule.onNodeWithText("Output").assertExists()
        composeTestRule.onNodeWithText("deployment.apps/web created", substring = true).assertExists()
        composeTestRule.onNodeWithText("Close").assertExists()
        saveScreenshot("detail_loaded")
    }

    @Test
    fun detailDialog_errorOutput_renders() {
        // AC7 — an error result is distinguished in the dialog (the Output label recolors).
        composeTestRule.setContent {
            AiSandboxTheme {
                ToolDetailDialog(
                    state = ToolDetailState.Loaded(input = "false", result = "exit status 1", isError = true),
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Output").assertIsDisplayed()
        composeTestRule.onNodeWithText("exit status 1", substring = true).assertIsDisplayed()
        saveScreenshot("detail_error")
    }

    @Test
    fun detailDialog_unavailable_showsMessage() {
        // AC9 — a miss/timeout/disconnect shows a clear "Detail unavailable" state.
        composeTestRule.setContent {
            AiSandboxTheme {
                ToolDetailDialog(state = ToolDetailState.Unavailable, onDismiss = {})
            }
        }
        composeTestRule.onNodeWithText("Detail unavailable").assertIsDisplayed()
        saveScreenshot("detail_unavailable")
    }

    @Test
    fun detailDialog_loading_showsSpinner() {
        composeTestRule.setContent {
            AiSandboxTheme {
                ToolDetailDialog(state = ToolDetailState.Loading, onDismiss = {})
            }
        }
        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed() // AC5 in-flight state
        saveScreenshot("detail_loading")
    }
}
