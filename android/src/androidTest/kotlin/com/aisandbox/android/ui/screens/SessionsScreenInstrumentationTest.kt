package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.R
import com.aisandbox.android.net.LifecycleAction
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.ui.components.PENDING_QUESTION_BADGE_DESCRIPTION
import com.aisandbox.android.ui.theme.AiSandboxTheme
import com.aisandbox.android.ui.theme.ErrorTone
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.Warning
import kotlin.math.abs
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

    // ── UC-46 — per-row overflow lifecycle menu (AC1 / AC2 / AC3 / AC6) ──────

    /** One running row (n=1) and one paused row (n=7) for menu-state coverage. */
    private val lifecycleState = SessionsUiState(
        sessions = listOf(
            SessionSummary(n = 1, label = "alpha", state = "running"),
            SessionSummary(n = 7, label = "frozen", state = "paused"),
        ),
        filter = SessionsFilter.ALL,
    )

    private fun setLifecycleBody(
        state: SessionsUiState = lifecycleState,
        onOpen: (Int) -> Unit = {},
        onConfirmDelete: (Int, Boolean) -> Unit = { _, _ -> },
        onLifecycle: (Int, LifecycleAction) -> Unit = { _, _ -> },
        onBlockedOpen: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    state = state,
                    onSelectFilter = {},
                    onOpen = onOpen,
                    onOpenTerminal = {},
                    onConfirmDelete = onConfirmDelete,
                    onLifecycle = onLifecycle,
                    onBlockedOpen = onBlockedOpen,
                )
            }
        }
    }

    /** AC1 — the overflow menu lists Remove + the four lifecycle actions. */
    @Test
    fun menu_lists_remove_and_all_lifecycle_actions() {
        setLifecycleBody()
        composeTestRule.onNodeWithTag("session-menu-1").performClick()

        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_remove)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_stop)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_start)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_pause)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_unpause)).assertIsDisplayed()
    }

    /**
     * AC3 — for a RUNNING row, only Pause + Stop are valid; Start + Unpause are
     * disabled (greyed, not hidden). Remove is always enabled.
     */
    @Test
    fun menu_enables_only_valid_actions_for_running_row() {
        setLifecycleBody()
        composeTestRule.onNodeWithTag("session-menu-1").performClick()

        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_pause)).assertIsEnabled()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_stop)).assertIsEnabled()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_remove)).assertIsEnabled()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_start)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_unpause)).assertIsNotEnabled()
    }

    /**
     * AC3 — for a PAUSED row, only Unpause + Stop are valid; Pause + Start are
     * disabled.
     */
    @Test
    fun menu_enables_only_valid_actions_for_paused_row() {
        setLifecycleBody()
        composeTestRule.onNodeWithTag("session-menu-7").performClick()

        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_unpause)).assertIsEnabled()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_stop)).assertIsEnabled()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_pause)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_start)).assertIsNotEnabled()
    }

    /**
     * AC2 — "Remove" in the menu routes to the EXISTING confirmed delete dialog
     * (the same DeleteSessionDialog the swipe opens), not a second unconfirmed
     * delete and not a lifecycle action.
     */
    @Test
    fun menu_remove_opens_the_delete_confirm_dialog_and_fires_no_lifecycle() {
        var lifecycleFired: Pair<Int, LifecycleAction>? = null
        setLifecycleBody(onLifecycle = { n, a -> lifecycleFired = n to a })

        composeTestRule.onNodeWithTag("session-menu-1").performClick()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_remove)).performClick()

        composeTestRule.onNodeWithText(ctx.getString(R.string.delete_title, 1)).assertIsDisplayed()
        assertNull("Remove must not fire a lifecycle action — it opens the confirmed delete path", lifecycleFired)
    }

    /** AC6 — clicking a valid lifecycle item fires onLifecycle(n, action). */
    @Test
    fun menu_valid_action_fires_onLifecycle_with_n_and_action() {
        var fired: Pair<Int, LifecycleAction>? = null
        setLifecycleBody(onLifecycle = { n, a -> fired = n to a })

        composeTestRule.onNodeWithTag("session-menu-1").performClick()
        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_pause)).performClick()

        assertEquals("a valid menu action must fire onLifecycle with the row's N", 1, fired?.first)
        assertEquals("a valid menu action must fire its LifecycleAction", LifecycleAction.PAUSE, fired?.second)
    }

    /**
     * AC6 — while a lifecycle action is in flight (pendingActions) the row's
     * overflow control is disabled so the action can't be double-fired.
     */
    @Test
    fun menu_button_is_disabled_while_a_lifecycle_action_is_pending() {
        val pending = lifecycleState.copy(pendingActions = setOf(1))
        setLifecycleBody(state = pending)
        composeTestRule.onNodeWithTag("session-menu-1").assertIsNotEnabled()
        // A sibling without a pending action keeps its menu enabled.
        composeTestRule.onNodeWithTag("session-menu-7").assertIsEnabled()
    }

    /** The overflow control is also disabled for a terminating row. */
    @Test
    fun menu_button_is_disabled_for_a_terminating_row() {
        val terminating = SessionsUiState(
            sessions = listOf(SessionSummary(n = 1, label = "alpha", state = "running")),
            terminating = setOf(1),
        )
        setLifecycleBody(state = terminating)
        composeTestRule.onNodeWithTag("session-menu-1").assertIsNotEnabled()
    }

    /**
     * UC-46 row-open gate — tapping the overflow icon opens the MENU and does
     * NOT navigate into the session (the IconButton consumes the tap separately
     * from the row's tap → open). Preserves the tap/long-press affordances.
     */
    @Test
    fun tapping_overflow_button_opens_menu_and_does_not_open_the_session() {
        var openedN: Int? = null
        setLifecycleBody(onOpen = { openedN = it })

        composeTestRule.onNodeWithTag("session-menu-1").performClick()

        composeTestRule.onNodeWithText(ctx.getString(R.string.session_action_remove)).assertIsDisplayed()
        assertNull("tapping the overflow button must NOT open the session", openedN)
    }

    /**
     * UC-46 row-open gate — tapping a NON-attachable row (paused) surfaces the
     * hint instead of navigating into a guaranteed-failing connection.
     */
    @Test
    fun tapping_a_non_attachable_paused_row_surfaces_hint_not_open() {
        var openedN: Int? = null
        var hintShown = false
        setLifecycleBody(onOpen = { openedN = it }, onBlockedOpen = { hintShown = true })

        composeTestRule.onNodeWithTag("session-card-7").performClick()

        assertEquals("a paused (non-attachable) row tap must surface the open-gate hint", true, hintShown)
        assertNull("a paused row tap must NOT navigate into the session", openedN)
    }

    // ── UC-47 — conversation name as the row's primary status line ───────────

    private fun setBody(state: SessionsUiState) {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    onOpenTerminal = {},
                    state = state,
                    onSelectFilter = {},
                    onOpen = {},
                    onConfirmDelete = { _, _ -> },
                )
            }
        }
    }

    /**
     * UC-47 AC1 — when a running row has a known conversation name, the status
     * line shows that NAME (not the raw tmux title). The tmux title is the
     * fallback only, so with a name present it must not appear.
     */
    @Test
    fun row_shows_the_conversation_name_as_the_primary_status_line() {
        setBody(
            SessionsUiState(
                sessions = listOf(
                    SessionSummary(
                        n = 1,
                        label = "alpha",
                        state = "running",
                        tmuxTitle = "(idle)",
                        conversationName = "Refactor the SessionRow",
                    ),
                ),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onNodeWithText("Refactor the SessionRow").assertIsDisplayed()
        // The tmux-title fallback must NOT be rendered when a name is present.
        composeTestRule.onNodeWithText("(idle)").assertDoesNotExist()
    }

    /**
     * UC-47 AC3 — when no conversation name is known (null), the row falls back
     * to the existing tmux-title behavior without an empty/broken label.
     */
    @Test
    fun row_falls_back_to_tmux_title_when_no_conversation_name() {
        setBody(
            SessionsUiState(
                sessions = listOf(
                    SessionSummary(
                        n = 1,
                        label = "alpha",
                        state = "running",
                        tmuxTitle = "vim src/main.rs",
                        conversationName = null,
                    ),
                ),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onNodeWithText("vim src/main.rs").assertIsDisplayed()
    }

    /**
     * UC-47 AC3 — a BLANK conversation name is treated as "no name" and also
     * falls back to the tmux title (the `takeIf { isNotBlank() }` guard).
     */
    @Test
    fun row_falls_back_to_tmux_title_when_conversation_name_is_blank() {
        setBody(
            SessionsUiState(
                sessions = listOf(
                    SessionSummary(
                        n = 1,
                        label = "alpha",
                        state = "running",
                        tmuxTitle = "building…",
                        conversationName = "   ",
                    ),
                ),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onNodeWithText("building…").assertIsDisplayed()
    }

    /**
     * UC-47 AC5 — a long conversation name is rendered single-line + ellipsized
     * (maxLines = 1, TextOverflow.Ellipsis) so it never breaks the row layout or
     * pushes out the StatusPill. The Text node carries the full string (ellipsis
     * is a render-only treatment); this test pins that the long name is present
     * and the row + its pill still render. Visual truncation is verified on the
     * emulator (operator-run).
     */
    @Test
    fun long_conversation_name_renders_without_breaking_the_row() {
        val longName = "Investigate why the enumeration latency regressed " +
            "after we added the conversation-name derivation to the hot path and fix it"
        setBody(
            SessionsUiState(
                sessions = listOf(
                    SessionSummary(
                        n = 1,
                        label = "alpha",
                        state = "running",
                        tmuxTitle = "(idle)",
                        conversationName = longName,
                    ),
                ),
                filter = SessionsFilter.ALL,
            ),
        )

        // The name is shown and the card + its status pill still render.
        composeTestRule.onNodeWithText(longName, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-card-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("running").assertIsDisplayed()
    }

    // ── UC-48 — per-row working spinner (AC1 / AC2 / AC5 / AC6 / AC7) ─────────

    /**
     * Matches the indeterminate [CircularProgressIndicator] the working spinner
     * is (Material3 stamps `ProgressBarRangeInfo.Indeterminate` onto its
     * semantics). The conversation view's `SpinnerRow` uses the SAME indeterminate
     * primitive (16.dp / 2.dp), so a count of these nodes is the spinner count and
     * the affordance is consistent across the app (AC5).
     */
    private val workingSpinner =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

    private fun runningRow(n: Int, working: Boolean, name: String? = null, state: String = "running") =
        SessionSummary(n = n, label = "s$n", state = state, tmuxTitle = "(idle)", conversationName = name, working = working)

    /**
     * AC1 — a running, WORKING row animates exactly one working spinner in its
     * status area; an idle running row in the same list shows none. AC2 is the
     * complement: working=false ⇒ no spinner.
     */
    @Test
    fun working_running_row_shows_one_spinner_and_idle_row_shows_none() {
        setBody(
            SessionsUiState(
                sessions = listOf(
                    runningRow(1, working = true),
                    runningRow(2, working = false),
                ),
                filter = SessionsFilter.ALL,
            ),
        )

        // Exactly one spinner across the two rows — only the working one (AC1/AC2).
        composeTestRule.onAllNodes(workingSpinner).assertCountEquals(1)
    }

    /**
     * AC6 — the working spinner coexists cleanly with the lifecycle StatusPill and
     * the (UC-46/47) conversation name in the row's status section: all three are
     * present, the spinner is shown, and the row/card stays intact.
     */
    @Test
    fun working_spinner_coexists_with_pill_and_conversation_name() {
        setBody(
            SessionsUiState(
                sessions = listOf(runningRow(1, working = true, name = "Refactor the SessionRow")),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodes(workingSpinner).assertCountEquals(1)
        // The lifecycle pill still renders its running label.
        composeTestRule.onNodeWithText("running").assertIsDisplayed()
        // The conversation name still renders as the status line.
        composeTestRule.onNodeWithText("Refactor the SessionRow", substring = true).assertIsDisplayed()
        // The card is intact (no layout break swallowing the row).
        composeTestRule.onNodeWithTag("session-card-1").assertIsDisplayed()
    }

    /**
     * AC7 — a non-running session never shows the spinner even if its last-known
     * working flag is true: a paused row with working=true shows NO spinner (the
     * render is double-gated on state=="running").
     */
    @Test
    fun paused_row_with_stale_working_true_shows_no_spinner() {
        setBody(
            SessionsUiState(
                sessions = listOf(runningRow(7, working = true, state = "paused")),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodes(workingSpinner).assertCountEquals(0)
        composeTestRule.onNodeWithText("paused").assertIsDisplayed()
    }

    /**
     * AC7 — a TERMINATING override (a stale working=true racing a teardown) must
     * not animate: the row's effective state is `terminating`, so the spinner is
     * gated off even though the row carries working=true.
     */
    @Test
    fun terminating_row_with_stale_working_true_shows_no_spinner() {
        setBody(
            SessionsUiState(
                sessions = listOf(runningRow(1, working = true)),
                terminating = setOf(1),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodes(workingSpinner).assertCountEquals(0)
    }

    // ── UC-49 — per-row pending-question "?" badge (AC1 / AC5 / AC7 / AC8) ────

    /** A running row carrying both the working and pending flags + an optional name/state. */
    private fun pendingRow(
        n: Int,
        pending: Boolean,
        working: Boolean = false,
        name: String? = null,
        state: String = "running",
    ) = SessionSummary(
        n = n,
        label = "s$n",
        state = state,
        tmuxTitle = "(idle)",
        conversationName = name,
        working = working,
        pendingQuestion = pending,
    )

    /**
     * AC1 / AC5 — a running row with a pending question shows exactly one "?" badge
     * and NO working spinner, EVEN when the row also carries working=true: pending
     * takes precedence client-side so the row never shows both.
     */
    @Test
    fun pending_running_row_shows_badge_and_never_the_spinner() {
        setBody(
            SessionsUiState(
                sessions = listOf(pendingRow(1, pending = true, working = true)),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodesWithContentDescription(PENDING_QUESTION_BADGE_DESCRIPTION).assertCountEquals(1)
        // AC5 — the working spinner is suppressed while pending (never both).
        composeTestRule.onAllNodes(workingSpinner).assertCountEquals(0)
    }

    /**
     * AC5 complement — a running row that is working but NOT pending shows the
     * spinner and NO "?" badge: the two indicators are mutually exclusive and the
     * badge only appears for a genuine pending question.
     */
    @Test
    fun working_but_not_pending_row_shows_spinner_not_badge() {
        setBody(
            SessionsUiState(
                sessions = listOf(pendingRow(1, pending = false, working = true)),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodes(workingSpinner).assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription(PENDING_QUESTION_BADGE_DESCRIPTION).assertCountEquals(0)
    }

    /**
     * AC5 — across a list, a pending row shows the badge and a working row shows
     * the spinner: exactly one badge and exactly one spinner, never both on one row.
     */
    @Test
    fun pending_and_working_rows_show_one_badge_and_one_spinner_respectively() {
        setBody(
            SessionsUiState(
                sessions = listOf(
                    pendingRow(1, pending = true, working = true),
                    pendingRow(2, pending = false, working = true),
                ),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodesWithContentDescription(PENDING_QUESTION_BADGE_DESCRIPTION).assertCountEquals(1)
        composeTestRule.onAllNodes(workingSpinner).assertCountEquals(1)
    }

    /**
     * AC7 — the "?" badge coexists with the lifecycle StatusPill, the (UC-47)
     * conversation name, and the (UC-46) overflow menu without breaking the row:
     * all are present and the card stays intact.
     */
    @Test
    fun pending_badge_coexists_with_pill_name_and_menu() {
        setBody(
            SessionsUiState(
                sessions = listOf(pendingRow(1, pending = true, name = "Pick a database")),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodesWithContentDescription(PENDING_QUESTION_BADGE_DESCRIPTION).assertCountEquals(1)
        composeTestRule.onNodeWithText("running").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pick a database", substring = true).assertIsDisplayed()
        // The UC-46 overflow menu trigger still renders for the row.
        composeTestRule.onNodeWithTag("session-menu-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-card-1").assertIsDisplayed()
    }

    /**
     * AC8 — a non-running (paused) row never shows the badge even with a stale
     * pendingQuestion=true: the render is double-gated on state=="running".
     */
    @Test
    fun paused_row_with_stale_pending_true_shows_no_badge() {
        setBody(
            SessionsUiState(
                sessions = listOf(pendingRow(7, pending = true, state = "paused")),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodesWithContentDescription(PENDING_QUESTION_BADGE_DESCRIPTION).assertCountEquals(0)
        composeTestRule.onNodeWithText("paused").assertIsDisplayed()
    }

    /**
     * AC8 — a stopped row never shows the badge.
     */
    @Test
    fun stopped_row_with_stale_pending_true_shows_no_badge() {
        setBody(
            SessionsUiState(
                sessions = listOf(pendingRow(3, pending = true, state = "stopped")),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodesWithContentDescription(PENDING_QUESTION_BADGE_DESCRIPTION).assertCountEquals(0)
    }

    /**
     * AC8 — a TERMINATING override (a stale pending=true racing a teardown) must
     * not show the badge: the row's effective state is `terminating`, so the badge
     * is gated off even though the row carries pendingQuestion=true.
     */
    @Test
    fun terminating_row_with_stale_pending_true_shows_no_badge() {
        setBody(
            SessionsUiState(
                sessions = listOf(pendingRow(1, pending = true)),
                terminating = setOf(1),
                filter = SessionsFilter.ALL,
            ),
        )

        composeTestRule.onAllNodesWithContentDescription(PENDING_QUESTION_BADGE_DESCRIPTION).assertCountEquals(0)
    }

    // ── UC-70 — "Not connected, retrying…" list background (AC1/AC2/AC4/AC5/AC7) ──
    //
    // Drives the same internal SessionsBody render seam seeded with a
    // SessionsFeedStatus. The RetryingBackground's 1-Hz countdown is an INFINITE
    // LaunchedEffect, which would keep `waitForIdle` busy forever under the
    // default auto-advancing clock — so the reconnecting tests freeze the clock
    // (`mainClock.autoAdvance = false`) to read the initial frame deterministically.
    // The countdown/give-up instants are pinned relative to a captured `base` so
    // the ceil-to-seconds + mm:ss formatting render to exact, stable strings
    // (the composable's `now` is captured at composition, well within 1 s of base).

    @Test
    fun reconnecting_feed_shows_light_grey_message_attempt_and_countdown_with_rows_hidden() {
        // Freeze the clock so the countdown's infinite ticker doesn't stall idle.
        composeTestRule.mainClock.autoAdvance = false
        val base = System.currentTimeMillis()
        setBody(
            SessionsUiState(
                // Stale rows still in state — must be hidden while reconnecting (AC2).
                sessions = listOf(
                    SessionSummary(n = 1, label = "alpha", state = "running"),
                    SessionSummary(n = 2, label = "beta", state = "running"),
                ),
                filter = SessionsFilter.ALL,
                feedStatus = SessionsFeedStatus(
                    phase = SessionsFeedStatus.Phase.RECONNECTING,
                    attempt = 3,
                    nextRetryAtMs = base + 8_000L, // ceil → "8s"
                    giveUpAtMs = base + 125_000L, // ceil → 125 s → "2:05"
                ),
            ),
        )

        // AC1 — the centered light-grey background message is shown.
        composeTestRule.onNodeWithTag("sessions_retrying_background").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.sessions_feed_retrying)).assertIsDisplayed()
        // AC3 — the current attempt count.
        composeTestRule.onNodeWithText(ctx.getString(R.string.sessions_feed_attempt, 3)).assertIsDisplayed()
        // AC4 — the next-retry countdown (exact, ceil-to-seconds).
        composeTestRule.onNodeWithTag("sessions_retrying_countdown").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.sessions_feed_next_retry, 8)).assertIsDisplayed()
        // AC5 — a finite budget surfaces the give-up line, mm:ss formatted.
        composeTestRule.onNodeWithTag("sessions_retrying_give_up").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.sessions_feed_give_up, "2:05")).assertIsDisplayed()
        // AC2 — no stale rows linger behind the background.
        composeTestRule.onNodeWithTag("session-card-1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("session-card-2").assertDoesNotExist()
    }

    /**
     * AC5 unlimited branch (the UC-71 forward case) — a RECONNECTING feed whose
     * controller reports no finite budget (giveUpAtMs == null) shows the attempt
     * + countdown but OMITS the give-up/limit line entirely.
     */
    @Test
    fun reconnecting_with_unlimited_budget_omits_the_give_up_line() {
        composeTestRule.mainClock.autoAdvance = false
        val base = System.currentTimeMillis()
        setBody(
            SessionsUiState(
                filter = SessionsFilter.ALL,
                feedStatus = SessionsFeedStatus(
                    phase = SessionsFeedStatus.Phase.RECONNECTING,
                    attempt = 1,
                    nextRetryAtMs = base + 2_000L,
                    giveUpAtMs = null, // unlimited → no limit line
                ),
            ),
        )

        composeTestRule.onNodeWithTag("sessions_retrying_background").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sessions_retrying_countdown").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sessions_retrying_give_up")
            .assertDoesNotExist()
    }

    /**
     * AC6 + the headline pitfall — a CONNECTED feed with genuinely zero sessions
     * shows the normal empty state, NOT the retrying message.
     */
    @Test
    fun connected_with_zero_sessions_shows_empty_state_not_the_retrying_message() {
        setBody(
            SessionsUiState(
                sessions = emptyList(),
                filter = SessionsFilter.ALL,
                feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.CONNECTED),
            ),
        )

        composeTestRule.onNodeWithText(ctx.getString(R.string.sessions_empty_all)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sessions_retrying_background").assertDoesNotExist()
        composeTestRule.onNodeWithText(ctx.getString(R.string.sessions_feed_retrying)).assertDoesNotExist()
    }

    /** AC6 — a CONNECTED feed with rows shows the rows and no retrying background. */
    @Test
    fun connected_with_rows_shows_rows_and_no_retrying_message() {
        setBody(
            SessionsUiState(
                sessions = listOf(SessionSummary(n = 1, label = "alpha", state = "running")),
                filter = SessionsFilter.ALL,
                feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.CONNECTED),
            ),
        )

        composeTestRule.onNodeWithTag("session-card-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sessions_retrying_background").assertDoesNotExist()
    }

    /**
     * Hard-req #4 — a terminally STOPPED feed shows a single static "Not
     * connected" with NO countdown (and no infinite ticker, so the default clock
     * is fine here).
     */
    @Test
    fun stopped_feed_shows_static_not_connected_without_a_countdown() {
        setBody(
            SessionsUiState(
                sessions = listOf(SessionSummary(n = 1, label = "alpha", state = "running")),
                filter = SessionsFilter.ALL,
                feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.STOPPED),
            ),
        )

        composeTestRule.onNodeWithTag("sessions_retrying_background").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sessions_retrying_disconnected").assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.sessions_feed_disconnected)).assertIsDisplayed()
        // STOPPED is static — no countdown / give-up timer.
        composeTestRule.onNodeWithTag("sessions_retrying_countdown").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sessions_retrying_give_up").assertDoesNotExist()
        // Stale rows hidden behind the static background too.
        composeTestRule.onNodeWithTag("session-card-1").assertDoesNotExist()
    }

    // ── UC-72 — connectivity status dot: yellow (RETRYING) / red (BACKOFF) ────
    //
    // The dot itself lives in the SessionsScreen Scaffold top bar — outside the
    // SessionsBody render seam and bound to a live SessionsViewModel — so it
    // can't be seeded deterministically through the screen. This [ConnectivityDot]
    // render seam is a FAITHFUL copy of the production dot in
    // `SessionsScreen` (the `dotColor` + `dotDescription` when-blocks, the 8.dp
    // CircleShape Box, the testTag + contentDescription), driven by the REAL
    // production `SessionsUiState.connectivity` derivation and the REAL theme
    // color tokens (Warning / ErrorTone / Success) + string resources. So the
    // colour mapping, the connectivity derivation, and the TalkBack strings are
    // all exercised on device; only the trivial enum→colour `when` arm is
    // mirrored (kept byte-identical to production). Colour is sampled at the dot's
    // centre pixel; the description via the semantics tree.

    @Composable
    private fun ConnectivityDot(state: SessionsUiState) {
        val dotColor = when (state.connectivity) {
            Connectivity.REACHABLE -> Success
            Connectivity.UNREACHABLE -> ErrorTone
            Connectivity.RETRYING -> Warning
            Connectivity.BACKOFF -> ErrorTone
            Connectivity.CHECKING, Connectivity.UNKNOWN -> Warning
        }
        val dotDescription = when (state.connectivity) {
            Connectivity.REACHABLE -> stringResource(R.string.sessions_connectivity_reachable)
            Connectivity.UNREACHABLE -> stringResource(R.string.sessions_connectivity_unreachable)
            Connectivity.RETRYING -> stringResource(R.string.sessions_connectivity_retrying)
            Connectivity.BACKOFF -> stringResource(R.string.sessions_connectivity_backoff)
            Connectivity.CHECKING, Connectivity.UNKNOWN ->
                stringResource(R.string.sessions_connectivity_checking)
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
                .testTag("sessions_connectivity_dot")
                .semantics { contentDescription = dotDescription },
        )
    }

    private fun setDot(state: SessionsUiState) {
        composeTestRule.setContent {
            AiSandboxTheme { ConnectivityDot(state) }
        }
    }

    /** Sample the dot's centre pixel as a packed ARGB int. */
    private fun dotArgb(): Int {
        composeTestRule.waitForIdle()
        val bmp = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val b = composeTestRule.onNodeWithTag("sessions_connectivity_dot").getUnclippedBoundsInRoot()
        val density = composeTestRule.density.density
        val x = (((b.left.value + b.right.value) / 2f) * density).toInt().coerceIn(0, bmp.width - 1)
        val y = (((b.top.value + b.bottom.value) / 2f) * density).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(x, y)
    }

    private fun assertChannelsClose(actual: Int, expected: Int, tol: Int = 10) {
        val ok = abs((actual shr 16 and 0xFF) - (expected shr 16 and 0xFF)) <= tol &&
            abs((actual shr 8 and 0xFF) - (expected shr 8 and 0xFF)) <= tol &&
            abs((actual and 0xFF) - (expected and 0xFF)) <= tol
        org.junit.Assert.assertTrue(
            "dot colour #%08X should match #%08X within ±%d/channel".format(actual, expected, tol),
            ok,
        )
    }

    @Test
    fun connectivity_dot_is_yellow_while_retrying_with_reconnecting_description() {
        // AC2 — a reconnect attempt in flight (feedStatus.activity == ATTEMPTING)
        // → connectivity RETRYING → the dot is the yellow Warning tone and reads
        // "Reconnecting to server…" for TalkBack.
        val state = SessionsUiState(
            feedStatus = SessionsFeedStatus(
                phase = SessionsFeedStatus.Phase.RECONNECTING,
                activity = SessionsFeedStatus.ReconnectActivity.ATTEMPTING,
                attempt = 2,
            ),
        )
        // Sanity: the production derivation really lands on RETRYING here.
        assertEquals(Connectivity.RETRYING, state.connectivity)
        setDot(state)

        composeTestRule.onNodeWithContentDescription(
            ctx.getString(R.string.sessions_connectivity_retrying),
        ).assertIsDisplayed()
        assertChannelsClose(dotArgb(), Warning.toArgb())
    }

    @Test
    fun connectivity_dot_is_red_while_in_backoff_with_waiting_description() {
        // AC3 — waiting out the back-off delay (feedStatus.activity == WAITING)
        // → connectivity BACKOFF → the dot is the red ErrorTone and reads
        // "Waiting to retry…".
        val state = SessionsUiState(
            feedStatus = SessionsFeedStatus(
                phase = SessionsFeedStatus.Phase.RECONNECTING,
                activity = SessionsFeedStatus.ReconnectActivity.WAITING,
                attempt = 2,
                nextRetryAtMs = 5_000L,
            ),
        )
        assertEquals(Connectivity.BACKOFF, state.connectivity)
        setDot(state)

        composeTestRule.onNodeWithContentDescription(
            ctx.getString(R.string.sessions_connectivity_backoff),
        ).assertIsDisplayed()
        assertChannelsClose(dotArgb(), ErrorTone.toArgb())
    }

    @Test
    fun connectivity_dot_is_red_when_the_feed_has_terminally_stopped() {
        // A gave-up feed (phase STOPPED) → BACKOFF → red, same "Waiting to retry…"
        // description.
        val state = SessionsUiState(
            feedStatus = SessionsFeedStatus(phase = SessionsFeedStatus.Phase.STOPPED),
        )
        assertEquals(Connectivity.BACKOFF, state.connectivity)
        setDot(state)

        composeTestRule.onNodeWithContentDescription(
            ctx.getString(R.string.sessions_connectivity_backoff),
        ).assertIsDisplayed()
        assertChannelsClose(dotArgb(), ErrorTone.toArgb())
    }

    @Test
    fun connectivity_dot_is_green_when_connected_and_reachable_unchanged() {
        // No-regression — with an IDLE feed and a server that has answered, the
        // dot stays the REST-derived green (REACHABLE): UC-72's arms do not
        // disturb the connected/healthy case.
        val state = SessionsUiState(
            serverResponded = true,
            feedStatus = SessionsFeedStatus(), // CONNECTED + IDLE
        )
        assertEquals(Connectivity.REACHABLE, state.connectivity)
        setDot(state)

        composeTestRule.onNodeWithContentDescription(
            ctx.getString(R.string.sessions_connectivity_reachable),
        ).assertIsDisplayed()
        assertChannelsClose(dotArgb(), Success.toArgb())
    }
}
