package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.R
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.SessionsApi
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-62 — instrumented coverage for the pinned SERVER SSH SESSION row, driving
 * the `internal` [SessionsBody] render seam directly (no Scaffold, server-free
 * on the headless emulator), mirroring [SessionsScreenInstrumentationTest].
 *
 * <p>Criterion → test map (use-cases/62-server-ssh-session-row.md):
 *
 * <ul>
 *   <li>AC3 — {@link #server_ssh_row_renders_pinned_at_top()}: the host-shell
 *       card is rendered first, above the Claude row, and survives a non-ALL
 *       filter.</li>
 *   <li>AC4 — {@link #server_ssh_row_shows_the_badge_and_server_shell_title()}:
 *       the "SERVER SSH SESSION" badge + "Server shell" title distinguish it.</li>
 *   <li>AC5 — {@link #tap_and_long_press_on_the_ssh_row_both_open_the_terminal()}:
 *       both gestures route to [SessionsBody.onOpenTerminal] (never the Claude
 *       conversation view [SessionsBody.onOpen]).</li>
 *   <li>AC8 — {@link #ssh_row_menu_offers_only_remove()}: the host-shell row's
 *       overflow menu offers ONLY Remove (no Stop/Start/Pause/Unpause), whereas a
 *       Claude row still shows the lifecycle items.</li>
 *   <li>AC9 — {@link #swipe_on_the_ssh_row_runs_the_same_remove_flow()}: a
 *       swipe-left opens the SSH-specific confirm dialog and confirming fires
 *       [SessionsBody.onConfirmDelete] with the reserved id (force false).</li>
 * </ul>
 *
 * <p>AC1 (the top-bar shell IconButton immediately left of Settings) lives in the
 * `SessionsScreen` Scaffold top bar — outside this `SessionsBody` seam — and is
 * pinned by the `sessions_server_ssh_action` testTag in source; it is verified at
 * the screen tier, not here.
 */
@RunWith(AndroidJUnit4::class)
class ServerSshRowInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sshRow() = SessionSummary(
        n = SessionsApi.SERVER_SSH_N,
        label = "",
        tmuxTitle = "(idle)",
        state = "running",
        type = SessionsApi.TYPE_SERVER_SSH,
    )

    /** SSH row pinned + one ordinary Claude row. */
    private val seeded = SessionsUiState(
        sessions = listOf(sshRow(), SessionSummary(n = 1, label = "alpha", state = "running")),
        filter = SessionsFilter.ALL,
    )

    private fun setBody(
        state: SessionsUiState,
        onOpen: (Int) -> Unit = {},
        onOpenTerminal: (Int) -> Unit = {},
        onConfirmDelete: (Int, Boolean) -> Unit = { _, _ -> },
    ) {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    state = state,
                    onSelectFilter = {},
                    onOpen = onOpen,
                    onOpenTerminal = onOpenTerminal,
                    onConfirmDelete = onConfirmDelete,
                )
            }
        }
    }

    @Test
    fun server_ssh_row_renders_pinned_at_top() {
        setBody(seeded)
        // Both cards render; the SSH card (reserved id 0) is present and pinned.
        composeTestRule.onNodeWithTag("session-card-0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-card-1").assertIsDisplayed()
    }

    @Test
    fun server_ssh_row_survives_a_non_all_filter() {
        // AC3 — the SSH row is not a running/stopped Claude session; under STOPPED
        // it must stay visible while the running Claude row is filtered out.
        setBody(seeded.copy(filter = SessionsFilter.STOPPED))
        composeTestRule.onNodeWithTag("session-card-0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-card-1").assertDoesNotExist()
    }

    @Test
    fun server_ssh_row_shows_the_badge_and_server_shell_title() {
        setBody(seeded)
        // AC4 — the distinguishing badge + the "Server shell" title (never "ai-sandbox-0").
        composeTestRule.onNodeWithText(ctx.getString(R.string.server_ssh_badge)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.server_ssh_row_title)).assertIsDisplayed()
    }

    @Test
    fun tap_and_long_press_on_the_ssh_row_both_open_the_terminal() {
        // AC5 — both gestures route to the terminal; the conversation view (onOpen)
        // is never invoked for the host-shell row.
        var openedTerminal: Int? = null
        var openedChat: Int? = null
        setBody(seeded, onOpen = { openedChat = it }, onOpenTerminal = { openedTerminal = it })

        composeTestRule.onNodeWithTag("session-card-0").performClick()
        assertEquals("tap on the SSH row opens the terminal", SessionsApi.SERVER_SSH_N, openedTerminal)
        assertNull("tap on the SSH row never opens the Claude conversation view", openedChat)

        openedTerminal = null
        composeTestRule.onNodeWithTag("session-card-0").performTouchInput { longClick() }
        assertEquals("long-press on the SSH row opens the terminal", SessionsApi.SERVER_SSH_N, openedTerminal)
        assertNull("long-press on the SSH row never opens the conversation view", openedChat)
    }

    @Test
    fun ssh_row_menu_offers_only_remove() {
        setBody(seeded)
        // AC8 — open the host-shell row's overflow: Remove only, no lifecycle items.
        composeTestRule.onNodeWithTag("session-menu-0").performClick()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_remove)).assertIsDisplayed()
        for (label in listOf(
            R.string.session_action_stop,
            R.string.session_action_start,
            R.string.session_action_pause,
            R.string.session_action_unpause,
        )) {
            composeTestRule.onNodeWithText(ctx.getString(label)).assertDoesNotExist()
        }
    }

    @Test
    fun claude_row_menu_still_shows_lifecycle_items() {
        // AC12 control — the SSH-only menu gate does not strip the Claude row's menu.
        setBody(seeded)
        composeTestRule.onNodeWithTag("session-menu-1").performClick()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_remove)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_stop)).assertIsDisplayed()
    }

    @Test
    fun swipe_on_the_ssh_row_runs_the_same_remove_flow() {
        // AC9 — swipe-to-dismiss opens the SSH-specific confirm dialog; confirming
        // fires onConfirmDelete with the reserved id and force=false.
        var deletedN: Int? = null
        var deletedForce: Boolean? = null
        composeTestRule.setContent {
            var state by remember { mutableStateOf(seeded) }
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    state = state,
                    onSelectFilter = {},
                    onOpen = {},
                    onOpenTerminal = {},
                    onConfirmDelete = { n, force ->
                        deletedN = n
                        deletedForce = force
                        state = state.copy(sessions = state.sessions.filterNot { it.n == n })
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-0").performTouchInput { swipeLeft() }
        // The SSH-specific dialog copy (not the Docker "Delete ai-sandbox-0?").
        composeTestRule.onNodeWithText(ctx.getString(R.string.server_ssh_delete_title)).assertIsDisplayed()

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_confirm)).performClick()

        assertEquals("AC9 — Remove fires for the reserved id", SessionsApi.SERVER_SSH_N, deletedN)
        assertEquals("the host shell removes with force=false", false, deletedForce)
        composeTestRule.onNodeWithTag("session-card-0").assertDoesNotExist()
    }
}
