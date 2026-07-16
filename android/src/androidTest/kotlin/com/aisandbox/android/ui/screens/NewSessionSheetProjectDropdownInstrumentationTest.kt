package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.net.WorkspaceProjectInfo
import com.aisandbox.android.ui.testtags.NewSessionTestTags
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-98 (MANDATORY on-device UI functional test) — instrumented (emulator-tier)
 * coverage for the internal [NewSessionSheet] workspace-project drop-down.
 * Drives the composable directly with a seeded in-memory project list and a
 * captured `onSpawn(label, projectId)` callback — the UC-85 gate style: stable
 * [NewSessionTestTags], no `adb input tap` coordinates, no live server (the
 * sheet is server-free, so the drop-down interaction is fully deterministic).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li><b>None path</b> — the drop-down defaults to "None"; tapping Spawn fires
 *       `onSpawn` with {@code projectId == null} (AC2/AC3).</li>
 *   <li><b>Real-project path</b> — open the drop-down, tap a project option, tap
 *       Spawn → `onSpawn` carries that project's id (AC2/AC4).</li>
 *   <li><b>Catalogue + both modes</b> — the drop-down lists every returned
 *       project plus "None" and renders unconditionally (the composable has no
 *       mode gate, so it is present in BOTH shared and isolated modes — AC7).</li>
 * </ul>
 *
 * <p>The server-side tmux injection choreography (AC4 submit / AC5 folder
 * substitution / AC6 readiness / AC10 stale-id) is covered by the server JVM
 * suite (SessionFacadeWorkspaceInjectTest / ConversationFacadeSpawnInjectTest);
 * that path is not wire-observable from a synthetic on-device session.
 */
@RunWith(AndroidJUnit4::class)
class NewSessionSheetProjectDropdownInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val projects = listOf(
        WorkspaceProjectInfo(id = "alpha", displayName = "alpha"),
        WorkspaceProjectInfo(id = "ai-sandbox", displayName = "ai-sandbox"),
    )

    @Test
    fun none_is_the_default_and_spawning_reports_a_null_project() {
        // AC2/AC3 — "None" is pre-selected; spawning with it fires onSpawn(label, null).
        var spawnedLabel: String? = "unset"
        var spawnedProject: String? = "unset"
        composeTestRule.setContent {
            AiSandboxTheme {
                NewSessionSheet(
                    spawning = false,
                    projects = projects,
                    onCancel = {},
                    onSpawn = { label, projectId ->
                        spawnedLabel = label
                        spawnedProject = projectId
                    },
                )
            }
        }

        // The selector is present (AC7 — unconditional, both modes) and shows "None".
        composeTestRule.onNodeWithTag(NewSessionTestTags.PROJECT_DROPDOWN).assertIsDisplayed()
        composeTestRule.onNodeWithText("None").assertIsDisplayed()

        // Spawn straight away — no project chosen.
        composeTestRule.onNodeWithText("Spawn").performClick()

        assertNull("AC3 — 'None' selection spawns with projectId == null", spawnedProject)
        assertEquals("", spawnedLabel)
    }

    @Test
    fun choosing_a_project_then_spawning_reports_that_project_id() {
        // AC2/AC4 — open the drop-down, pick a real project, spawn → its id is carried.
        var spawnedProject: String? = "unset"
        composeTestRule.setContent {
            AiSandboxTheme {
                NewSessionSheet(
                    spawning = false,
                    projects = projects,
                    onCancel = {},
                    onSpawn = { _, projectId -> spawnedProject = projectId },
                )
            }
        }

        // Open the drop-down by tapping its read-only anchor field.
        composeTestRule.onNodeWithTag(NewSessionTestTags.PROJECT_DROPDOWN_FIELD).performClick()
        // Pick "ai-sandbox".
        composeTestRule
            .onNodeWithTag(NewSessionTestTags.projectOption("ai-sandbox"), useUnmergedTree = true)
            .performClick()
        // Spawn.
        composeTestRule.onNodeWithText("Spawn").performClick()

        assertEquals("AC4 — the chosen project's id is carried to onSpawn", "ai-sandbox", spawnedProject)
    }

    @Test
    fun the_dropdown_lists_none_plus_every_returned_project() {
        // AC2/AC7 — the drop-down offers "None" and one entry per returned project.
        composeTestRule.setContent {
            AiSandboxTheme {
                NewSessionSheet(
                    spawning = false,
                    projects = projects,
                    onCancel = {},
                    onSpawn = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithTag(NewSessionTestTags.PROJECT_DROPDOWN_FIELD).performClick()

        composeTestRule
            .onNodeWithTag(NewSessionTestTags.PROJECT_OPTION_NONE, useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(NewSessionTestTags.projectOption("alpha"), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(NewSessionTestTags.projectOption("ai-sandbox"), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun switching_back_to_none_after_picking_a_project_reports_null() {
        // AC3 edge — a user who opens the dropdown, picks a project, then reselects
        // "None" spawns with no project (byte-identical to today's behaviour).
        var spawnedProject: String? = "unset"
        var spawnCalled = false
        composeTestRule.setContent {
            AiSandboxTheme {
                NewSessionSheet(
                    spawning = false,
                    projects = projects,
                    onCancel = {},
                    onSpawn = { _, projectId ->
                        spawnedProject = projectId
                        spawnCalled = true
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag(NewSessionTestTags.PROJECT_DROPDOWN_FIELD).performClick()
        composeTestRule
            .onNodeWithTag(NewSessionTestTags.projectOption("alpha"), useUnmergedTree = true)
            .performClick()
        // Reopen and choose None.
        composeTestRule.onNodeWithTag(NewSessionTestTags.PROJECT_DROPDOWN_FIELD).performClick()
        composeTestRule
            .onNodeWithTag(NewSessionTestTags.PROJECT_OPTION_NONE, useUnmergedTree = true)
            .performClick()
        composeTestRule.onNodeWithText("Spawn").performClick()

        assertTrue(spawnCalled)
        assertNull("AC3 — reselecting 'None' clears the project back to null", spawnedProject)
    }
}
