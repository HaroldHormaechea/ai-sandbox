package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.R
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC20 — instrumented coverage for the sessions screen's swipe-to-delete
 * affordance (replaces the UC18-era long-press → delete path). Drives the
 * now-`internal` [SessionsBody] render seam directly (the screen body minus
 * the [SessionsScreen] Scaffold chrome), seeded with a deterministic
 * [SessionsUiState], and asserts the swipe → confirm-dialog → delete flow
 * runs server-free on the headless emulator.
 *
 * <p>Criterion → test map (use-cases/20-android-swipe-to-delete-session.md):
 *
 * <ul>
 *   <li>AC1 — {@link #swipe_background_exposes_black_outlined_trash_affordance()}:
 *       a destructive trash affordance (contentDescription "Delete session")
 *       is wired behind every row.</li>
 *   <li>AC1 / AC2 — {@link #swipe_left_past_threshold_opens_confirm_dialog_without_removing_row()}:
 *       a threshold swipe-left opens the confirm dialog and the row is NOT
 *       auto-dismissed (confirmValueChange vetoes the settle).</li>
 *   <li>AC3 — {@link #cancelling_the_confirm_dialog_restores_the_row()}:
 *       cancelling closes the dialog, fires no delete, and leaves the row.</li>
 *   <li>AC4 — {@link #confirming_fires_onConfirmDelete_and_the_row_disappears()}:
 *       confirm fires [SessionsBody.onConfirmDelete] with the row's N (force
 *       false for an unattached session) and the row disappears once the state
 *       drops it.</li>
 *   <li>AC6 — {@link #force_toggle_is_shown_for_a_session_with_active_streams()}:
 *       the confirm step still presents the force toggle for an attached
 *       session.</li>
 *   <li>AC7 — {@link #long_pressing_a_session_card_does_not_open_the_delete_dialog()}:
 *       a long-press does NOT open the confirm dialog or fire a delete (the
 *       UC18-era long-press → delete path is removed; swipe is the sole
 *       affordance).</li>
 *   <li>AC8 — {@link #tapping_a_session_card_fires_onOpen_with_that_n()}
 *       and {@link #session_cards_are_displayed_and_have_a_click_action()}:
 *       tap still opens the terminal.</li>
 *   <li>No-regression — {@link #filter_chip_fires_independently_of_cards()}:
 *       a sibling control still fires its own action.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class SessionsScreenInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Two running, unattached sessions → both cards visible under ALL. */
    private val seededState = SessionsUiState(
        sessions = listOf(
            SessionSummary(n = 1, label = "alpha", state = "running"),
            SessionSummary(n = 2, label = "beta", state = "running"),
        ),
        filter = SessionsFilter.ALL,
    )

    /** One running session with two attached streams → force toggle eligible. */
    private val attachedState = SessionsUiState(
        sessions = listOf(
            SessionSummary(n = 3, label = "gamma", state = "running", activeStreams = 2),
        ),
        filter = SessionsFilter.ALL,
    )

    @Test
    fun tapping_a_session_card_fires_onOpen_with_that_n() {
        var openedN: Int? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = { openedN = it },
                    onConfirmDelete = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1").performClick()

        assertEquals("card tap must connect to the tapped session", 1, openedN)
    }

    /**
     * UC-37 AC1 — a long-press on a session row opens the tmux/terminal view
     * (the raw/power fallback) via [SessionsBody.onOpenTerminal], distinct from
     * the single-tap → structured-conversation path ([SessionsBody.onOpen]).
     */
    @Test
    fun long_pressing_a_session_card_fires_onOpenTerminal_with_that_n() {
        var conversationN: Int? = null
        var terminalN: Int? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = { conversationN = it },
                    onOpenTerminal = { terminalN = it },
                    onConfirmDelete = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-2").performTouchInput { longClick() }

        assertEquals("long-press must open the terminal for the pressed session", 2, terminalN)
        assertNull("long-press must NOT trigger the single-tap conversation path", conversationN)
    }

    @Test
    fun session_cards_are_displayed_and_have_a_click_action() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeTestRule.onNodeWithTag("session-card-2")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    /** AC1 — the black-outlined trash affordance is wired behind every row. */
    @Test
    fun swipe_background_exposes_black_outlined_trash_affordance() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }

        // SwipeToDismissBox always composes its backgroundContent, so one trash
        // icon (the destructive affordance) sits behind each visible row.
        composeTestRule.onAllNodesWithContentDescription(
            ctx.getString(R.string.delete_icon_description),
            useUnmergedTree = true,
        ).assertCountEquals(seededState.visible.size)
    }

    /**
     * AC1 / AC2 — a threshold swipe-left opens the confirm dialog WITHOUT
     * auto-dismissing the row (the SwipeToDismissBox vetoes the settle).
     */
    @Test
    fun swipe_left_past_threshold_opens_confirm_dialog_without_removing_row() {
        var deletedN: Int? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { n, _ -> deletedN = n },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1").performTouchInput { swipeLeft() }

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 1)).assertIsDisplayed()
        // Row not auto-removed by the swipe (deletion happens only on confirm).
        composeTestRule.onNodeWithTag("session-card-1").assertIsDisplayed()
        assertNull("swipe alone must NOT delete — only an explicit confirm does", deletedN)
    }

    /** AC3 — cancelling the confirm dialog fires no delete and keeps the row. */
    @Test
    fun cancelling_the_confirm_dialog_restores_the_row() {
        var deletedN: Int? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { n, _ -> deletedN = n },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1").performTouchInput { swipeLeft() }
        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 1)).assertIsDisplayed()

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_cancel)).performClick()

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 1)).assertDoesNotExist()
        composeTestRule.onNodeWithTag("session-card-1").assertIsDisplayed()
        assertNull("cancel must NOT fire a delete", deletedN)
    }

    /**
     * AC4 — confirming fires [SessionsBody.onConfirmDelete] with the row's N
     * (force false for an unattached row), and once the state drops that row
     * it disappears from the list.
     */
    @Test
    fun confirming_fires_onConfirmDelete_and_the_row_disappears() {
        var deletedN: Int? = null
        var deletedForce: Boolean? = null
        composeTestRule.setContent {
            var state by remember { mutableStateOf(seededState) }
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = state,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { n, force ->
                        deletedN = n
                        deletedForce = force
                        // Mirror the production refresh: the deleted row leaves
                        // the list (server no longer enumerates it).
                        state = state.copy(sessions = state.sessions.filterNot { it.n == n })
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1").performTouchInput { swipeLeft() }
        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 1)).assertIsDisplayed()

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_confirm)).performClick()

        assertEquals("confirm must delete the swiped session", 1, deletedN)
        assertEquals("an unattached session confirms with force = false", false, deletedForce)
        composeTestRule.onNodeWithTag("session-card-1").assertDoesNotExist()
        // The sibling row is untouched.
        composeTestRule.onNodeWithTag("session-card-2").assertIsDisplayed()
    }

    /** AC6 — the force toggle is still presented for a session with streams. */
    @Test
    fun force_toggle_is_shown_for_a_session_with_active_streams() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = attachedState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-3").performTouchInput { swipeLeft() }
        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 3)).assertIsDisplayed()

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_force)).assertIsDisplayed()
    }

    /**
     * AC7 — long-press no longer triggers delete. SessionRow uses a plain
     * `clickable` (the UC18-era long-press → delete-dialog path is gone), so a
     * long-press must NOT open the confirm dialog or fire a delete; swipe-left
     * is the sole delete affordance.
     */
    @Test
    fun long_pressing_a_session_card_does_not_open_the_delete_dialog() {
        var deletedN: Int? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { n, _ -> deletedN = n },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1").performTouchInput { longClick() }

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 1)).assertDoesNotExist()
        assertNull("long-press must NOT trigger the delete path (swipe is the sole affordance)", deletedN)
    }

    // ── UC-28 — terminating pill + per-row swipe guard ───────────────────────

    /** One optimistically-terminating row (n=1) + one normal running sibling (n=2). */
    private val optimisticTerminatingState = SessionsUiState(
        sessions = listOf(
            SessionSummary(n = 1, label = "alpha", state = "running"),
            SessionSummary(n = 2, label = "beta", state = "running"),
        ),
        filter = SessionsFilter.ALL,
        terminating = setOf(1), // client-side optimistic flag on n=1
    )

    /** A server-reported terminating row (no optimistic flag). */
    private val serverTerminatingState = SessionsUiState(
        sessions = listOf(
            SessionSummary(n = 1, label = "alpha", state = "terminating"),
        ),
        filter = SessionsFilter.ALL,
    )

    /**
     * UC-28 AC2 — the moment a delete is confirmed (modelled here by the
     * optimistic flag on n=1) the row shows the "terminating" pill in place of
     * its prior running pill, BEFORE any server refresh.
     */
    @Test
    fun optimistic_terminating_row_shows_terminating_pill() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = optimisticTerminatingState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }
        // The StatusPill renders the lowercase "terminating" label for n=1.
        composeTestRule.onNodeWithText("terminating").assertIsDisplayed()
    }

    /**
     * UC-28 AC3 — a server-reported `terminating` row likewise shows the
     * terminating pill (the other half of the union, no optimistic flag).
     */
    @Test
    fun server_reported_terminating_row_shows_terminating_pill() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = serverTerminatingState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithText("terminating").assertIsDisplayed()
    }

    /**
     * UC-28 AC4 — while a row is terminating, the swipe-to-dismiss gesture is
     * disabled for it and cannot raise a second delete confirmation.
     */
    @Test
    fun swipe_on_a_terminating_row_does_not_open_the_confirm_dialog() {
        var deletedN: Int? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = optimisticTerminatingState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { n, _ -> deletedN = n },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1").performTouchInput { swipeLeft() }

        // No confirm dialog for the terminating row, and no delete fired.
        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 1)).assertDoesNotExist()
        assertNull("a terminating row must not raise a second delete (AC4)", deletedN)
    }

    /**
     * UC-28 AC6 — blocking is per-row: a non-terminating SIBLING of a
     * terminating row still swipes to open its own confirm dialog.
     */
    @Test
    fun sibling_of_a_terminating_row_still_swipes_to_delete() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = optimisticTerminatingState,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }

        // n=2 is NOT terminating → swipe still opens its confirm dialog.
        composeTestRule.onNodeWithTag("session-card-2").performTouchInput { swipeLeft() }
        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 2)).assertIsDisplayed()
    }

    @Test
    fun filter_chip_fires_independently_of_cards() {
        var selectedFilter: SessionsFilter? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = seededState,
                    onSelectFilter = { selectedFilter = it },
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }

        // The "Running" filter chip renders "Running · 2"; the per-card
        // StatusPill renders the lowercase server value ("running"), so a
        // case-sensitive substring match uniquely targets the chip.
        composeTestRule.onNodeWithText("Running", substring = true).performClick()

        assertEquals(
            "a non-card control must still fire its own action",
            SessionsFilter.RUNNING,
            selectedFilter,
        )
    }
}
