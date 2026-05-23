package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC18 — instrumented coverage for the sessions screen's card-tap
 * handling. Regression guard for the v0.3.3 defect where a
 * `clickable` + `detectTapGestures` race left the session cards
 * unresponsive to taps.
 *
 * <p>Renders the now-`internal` [SessionsBody] render seam directly
 * (the screen body minus the [SessionsScreen] Scaffold chrome), seeded
 * with a deterministic [SessionsUiState], and asserts the wired
 * callbacks fire from real Compose gestures on the headless emulator.
 *
 * <p>Criterion → test map (use-cases/18-android-sessions-cards-untappable.md):
 *
 * <ul>
 *   <li>AC1 / AC5 — {@link #tapping_a_session_card_fires_onOpen_with_that_n()}:
 *       a card tap fires the connect/navigation action ([SessionsBody.onOpen])
 *       with the tapped session's N.</li>
 *   <li>AC2 — {@link #session_cards_are_displayed_and_have_a_click_action()}:
 *       the full card area is a displayed, clickable target (the
 *       `combinedClickable(role = Button)` semantics).</li>
 *   <li>UC04-2b delete guard — {@link #long_pressing_a_session_card_routes_to_onLongPress()}:
 *       a long-press routes to the delete-confirm callback, not the tap.</li>
 *   <li>AC3 — {@link #filter_chip_fires_independently_of_cards()}: a
 *       non-card control (the Running filter chip) still fires its own
 *       action, proving the cards' click wiring did not steal sibling
 *       pointer events.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class SessionsScreenInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Two running sessions → both cards visible under the default ALL filter. */
    private val seededState = SessionsUiState(
        sessions = listOf(
            SessionSummary(n = 1, label = "alpha", state = "running"),
            SessionSummary(n = 2, label = "beta", state = "running"),
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
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = { openedN = it },
                    onLongPress = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-1").performClick()

        assertEquals("card tap must connect to the tapped session", 1, openedN)
    }

    @Test
    fun session_cards_are_displayed_and_have_a_click_action() {
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = {},
                    onLongPress = {},
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

    @Test
    fun long_pressing_a_session_card_routes_to_onLongPress() {
        var longPressedN: Int? = null
        var openedN: Int? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    state = seededState,
                    onSelectFilter = {},
                    onOpen = { openedN = it },
                    onLongPress = { longPressedN = it.n },
                )
            }
        }

        composeTestRule.onNodeWithTag("session-card-2").performTouchInput { longClick() }

        assertEquals("long-press must route to the delete-confirm callback", 2, longPressedN)
        assertEquals("long-press must NOT also fire the tap/connect action", null, openedN)
    }

    @Test
    fun filter_chip_fires_independently_of_cards() {
        var selectedFilter: SessionsFilter? = null
        composeTestRule.setContent {
            AiSandboxTheme {
                SessionsBody(
                    padding = PaddingValues(),
                    state = seededState,
                    onSelectFilter = { selectedFilter = it },
                    onOpen = {},
                    onLongPress = {},
                )
            }
        }

        // The "Running" filter chip renders "Running · 2"; the per-card
        // StatusPill renders the lowercase server value ("running"), so a
        // case-sensitive substring match uniquely targets the chip.
        composeTestRule.onNodeWithText("Running", substring = true).performClick()

        assertEquals(
            "a non-card control must still fire its own action (AC3)",
            SessionsFilter.RUNNING,
            selectedFilter,
        )
    }
}
