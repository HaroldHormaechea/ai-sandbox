package com.aisandbox.android.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.R
import com.aisandbox.android.net.DeepLinkEvents
import com.aisandbox.android.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-69 — instrumented coverage that needs a real device/emulator: the two
 * notification channels are actually created by [com.aisandbox.android.AiSandboxApplication],
 * and the deep-link navigation reconstruction (the [DeepLinkEvents] consume-once
 * contract + the [com.aisandbox.android.ui.AiSandboxApp] LaunchedEffect that
 * routes a tapped pending-question notification into the conversation) lands on
 * the right conversation with the sessions list still UNDER it — never an empty
 * backstack, and never a navigation when the start destination is not the
 * sessions list.
 *
 * <p>The nav harness here reuses the PRODUCTION [DeepLinkEvents] and [Routes] and
 * replicates the exact AiSandboxApp deep-link LaunchedEffect against a real
 * [NavHostController], so it exercises the same consume-once + start-destination
 * gating logic the app ships (AC4 + the cold-start-reconstruction pitfall).
 *
 * <h2>Criterion → test map</h2>
 * <ul>
 *   <li>AC8 — {@link #both_notification_channels_are_created()}: the HIGH
 *       pending-questions + LOW question-watcher channels exist so the user can
 *       manage them.</li>
 *   <li>AC4 — {@link #deep_link_from_sessions_start_lands_on_conversation_over_sessions()}:
 *       a deep link reconstructs Sessions → Conversation/{n} (sessions under the
 *       conversation, no empty backstack).</li>
 *   <li>Cold-start pitfall — {@link #deep_link_is_dropped_when_start_is_not_sessions()}:
 *       an un-enrolled start (Onboarding) drops the request without crashing.</li>
 *   <li>AC4 warm path — {@link #a_second_deep_link_after_consume_navigates_again()}:
 *       consume clears the latch so a later tap navigates again.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class PendingQuestionNotificationInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ── AC8 — channels created by the Application ─────────────────────────────

    @Test
    fun both_notification_channels_are_created() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingId = ctx.getString(R.string.pending_question_channel_id)
        val watcherId = ctx.getString(R.string.question_watcher_channel_id)

        val pending = nm.getNotificationChannel(pendingId)
        val watcher = nm.getNotificationChannel(watcherId)

        assertTrue(
            "AC8 — the HIGH pending-questions channel must be created so the user can manage it",
            pending != null,
        )
        assertTrue(
            "the LOW question-watcher channel (ongoing FGS) must be created",
            watcher != null,
        )
        // The alerting channel is HIGH; the watcher channel is LOW (never alerts).
        assertEquals(NotificationManager.IMPORTANCE_HIGH, pending!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, watcher!!.importance)
    }

    // ── AC4 — deep-link nav reconstruction ────────────────────────────────────

    /** Minimal NavHost mirroring the real graph's Onboarding/Sessions/Conversation routes. */
    private fun setContentWithDeepLink(deepLinks: DeepLinkEvents, start: String): () -> NavHostController {
        var captured: NavHostController? = null
        composeTestRule.setContent {
            val navController = rememberNavController()
            captured = navController
            // Verbatim copy of AiSandboxApp's deep-link consume-once LaunchedEffect.
            // UC-93 — mirrors the shipped fixed nav: popUpTo(conversation/{*}) inclusive
            // + launchSingleTop, so a WARM deep-link re-keys the destination instead of
            // reusing the top conversation entry. On the FIRST deep-link there is no
            // conversation entry yet, so popUpTo is a no-op and Sessions stays under the
            // conversation (UC-69 AC4 preserved). On a SECOND deep-link it pops the prior
            // conversation/{*} and pushes a fresh one (re-key), keeping a single entry.
            LaunchedEffect(start) {
                deepLinks.pendingSession.collect { n ->
                    if (n != null) {
                        if (start == Routes.Sessions) {
                            navController.navigate(Routes.conversationFor(n)) {
                                popUpTo(Routes.ConversationPattern) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        deepLinks.consume()
                    }
                }
            }
            NavHost(navController = navController, startDestination = start) {
                composable(Routes.Onboarding) { Text("onboarding") }
                composable(Routes.Sessions) { Text("sessions") }
                composable(
                    route = Routes.ConversationPattern,
                    arguments = listOf(navArgument("n") { type = NavType.IntType }),
                ) { Text("conversation") }
            }
        }
        return { captured!! }
    }

    private fun NavHostController.backStackRoutes(): List<String> =
        currentBackStack.value
            .mapNotNull { it.destination.route }
            // Drop the synthetic graph root entry the NavController keeps at the base.
            .filter { it != this.graph.route }

    @Test
    fun deep_link_from_sessions_start_lands_on_conversation_over_sessions() {
        val deepLinks = DeepLinkEvents()
        val nav = setContentWithDeepLink(deepLinks, start = Routes.Sessions)

        // A tapped pending-question notification requests session 7.
        composeTestRule.runOnUiThread { deepLinks.request(7) }
        composeTestRule.waitForIdle()

        val routes = composeTestRule.runOnIdle { nav().backStackRoutes() }
        // Sessions remains UNDER the conversation — back returns to the list, not
        // an empty backstack (the cold-start reconstruction pitfall).
        assertEquals(listOf(Routes.Sessions, Routes.ConversationPattern), routes)
        // AC4 — the conversation is for the requested session id.
        val n = composeTestRule.runOnIdle {
            nav().currentBackStackEntry?.arguments?.getInt("n")
        }
        assertEquals(7, n)
        // consume() cleared the latch so a config change cannot re-navigate.
        assertNull(deepLinks.pendingSession.value)
    }

    @Test
    fun deep_link_is_dropped_when_start_is_not_sessions() {
        val deepLinks = DeepLinkEvents()
        val nav = setContentWithDeepLink(deepLinks, start = Routes.Onboarding)

        // An un-enrolled cold start has no conversation to open.
        composeTestRule.runOnUiThread { deepLinks.request(7) }
        composeTestRule.waitForIdle()

        val routes = composeTestRule.runOnIdle { nav().backStackRoutes() }
        // No navigation, no crash — stays on the start destination.
        assertEquals(listOf(Routes.Onboarding), routes)
        // The request is still consumed (dropped), so it cannot fire later.
        assertNull(deepLinks.pendingSession.value)
    }

    @Test
    fun a_second_deep_link_after_consume_navigates_again() {
        val deepLinks = DeepLinkEvents()
        val nav = setContentWithDeepLink(deepLinks, start = Routes.Sessions)

        composeTestRule.runOnUiThread { deepLinks.request(3) }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { deepLinks.request(9) }
        composeTestRule.waitForIdle()

        val n = composeTestRule.runOnIdle {
            nav().currentBackStackEntry?.arguments?.getInt("n")
        }
        // UC-93 — popUpTo pops conv/3 and pushes a fresh conv/9, so the top entry is
        // re-keyed (a single conversation entry remains, now for 9).
        assertEquals(9, n)
        assertNull(deepLinks.pendingSession.value)
    }
}
