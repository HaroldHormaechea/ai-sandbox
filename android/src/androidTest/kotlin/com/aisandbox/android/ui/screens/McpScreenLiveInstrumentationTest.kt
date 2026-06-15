package com.aisandbox.android.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.EnrollmentClient
import com.aisandbox.android.net.QrPayload
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.ui.theme.AiSandboxTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-67 AC8 — LIVE, from-the-UI verification of the real [McpScreen] rendered on a
 * device against a live management server + a real session container (the
 * authoritative AC8 gate). Unlike the component-tier
 * [ConversationOverflowMenuInstrumentationTest], this launches the PRODUCTION
 * [McpScreen] with its real [McpViewModel] → [com.aisandbox.android.net.McpApi] →
 * the enrolled mTLS HTTP client, so it fetches the session's actual MCP inventory
 * from the server (`GET /v1/sessions/{n}/mcp`) and renders the real states/controls.
 *
 * <p><b>Environment-gated by design.</b> It needs the QA runbook live env up
 * (enrolled emulator + test server + a session container). When that env is absent
 * — e.g. in CI, which has no live server/session — each test SKIPS via
 * {@link org.junit.Assume} rather than failing, so it never reddens an unattended
 * build. It produces value only when QA runs it against the live env; the
 * deterministic, env-free coverage of the screen's logic stays in the JVM
 * [McpUiStateTest] and the [com.aisandbox.android.net.McpApiTest] decode tests.
 *
 * <p>QA live-env setup for this test (see the dev-team QA summary): session 7 holds
 * one user-scoped, deliberately-broken stdio MCP (`broken-tool`) so its
 * `claude mcp list` returns fast and yields a FAILED row; session 99 has no
 * container, exercising the empty state.
 */
@RunWith(AndroidJUnit4::class)
class McpScreenLiveInstrumentationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Enroll the app against the live test server, exactly as
     * [com.aisandbox.android.ui.screens.OnboardingViewModel] does: redeem a fresh
     * single-use invite (`-e qrPayload '<invite json>'`), import the returned PKCS#12
     * into the KeyStore, and persist the [ServerProfile]. Without this the screen
     * shows "Not enrolled" — the on-device enrollment probe is networking-only and
     * does NOT persist a profile, so the McpScreen has no server to talk to. When no
     * `qrPayload` arg is supplied (e.g. CI), the tests skip via Assume on the
     * not-enrolled state, so this never reddens an unattended build.
     */
    @Before
    fun enrollIfInviteProvided() {
        if (enrolledThisProcess) return
        val raw = InstrumentationRegistry.getArguments().getString("qrPayload") ?: return
        val payload = QrPayload.parse(raw).getOrThrow()
        val app = ApplicationProvider.getApplicationContext<AiSandboxApplication>()
        runBlocking {
            val outcome = EnrollmentClient(payload).redeem()
            if (outcome is EnrollmentClient.Outcome.Success) {
                val cert = app.container.identity.importPkcs12(outcome.pkcs12).leaf
                app.container.profileStore.save(
                    ServerProfile(
                        serverUrl = payload.serverUrl,
                        pinSha256Hex = payload.pinSha256Hex,
                        clientCertCn = cert.subjectX500Principal.name,
                        clientCertExpiresAtMs = cert.notAfter.time,
                    ),
                )
                enrolledThisProcess = true
            }
        }
    }

    private fun anyText(text: String): Boolean =
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * Settle the screen into a terminal (non-Loading) state, retrying through the
     * cold-start "Not enrolled" transient: on a freshly-started instrumentation
     * process the profile DataStore may not have hydrated by the first fetch, so the
     * first open errors; the screen's Retry button re-fetches against the now-warm
     * store. (Unreachable in the real app — McpScreen is only opened after enrolled
     * screens have already read the profile.)
     */
    private fun settle(vararg terminalTexts: String) {
        repeat(4) {
            composeTestRule.waitUntil(timeoutMillis = 30_000) {
                terminalTexts.any { anyText(it) } || anyText("Couldn't load MCP servers")
            }
            if (terminalTexts.any { anyText(it) }) return
            // Error state — tap Retry and wait again (handles the cold-start race).
            if (anyText("Retry")) composeTestRule.onNodeWithText("Retry").performClick()
            composeTestRule.waitForIdle()
        }
    }

    /**
     * AC3/AC4/AC5 — the populated session lists its REAL MCP servers with correct
     * state chips and state-driven controls. Live env (QA runbook): session 7 holds
     * a real Atlassian SSE MCP (→ NEEDS_AUTH, the use-case's named example) and a
     * deliberately-broken stdio MCP (→ FAILED). This asserts, from the device UI:
     *   - both real servers are listed (sourced from the server, not a placeholder);
     *   - the Atlassian row shows the "Needs auth" chip and the broken row "Failed";
     *   - the Login control is ENABLED for exactly the needs_auth server and DISABLED
     *     for the other; Reconnect is the inverse (AC5 — controls enabled/disabled
     *     appropriately for state; the authenticate/login control is offered exactly
     *     where it is required).
     */
    @Test
    fun populatedSession_listsRealServers_withStateChips_andLoginEnabledOnlyForNeedsAuth() {
        composeTestRule.setContent { AiSandboxTheme { McpScreen(sessionN = 7, onBack = {}) } }

        // Real Atlassian SSE health-check is ~16s; allow the settle's 30s window.
        settle("atlassian", "broken-tool", "No MCP servers for this session")

        // Live env present only when the real needs_auth row rendered; otherwise skip (CI).
        assumeTrue(
            "AC8 live env absent (session 7 atlassian/server unreachable) — skipping live UI test",
            anyText("atlassian") && anyText("broken-tool"),
        )

        // AC3/AC4 — both REAL MCP servers are listed, sourced from the server.
        composeTestRule.onNodeWithText("atlassian").assertIsDisplayed()
        composeTestRule.onNodeWithText("broken-tool").assertIsDisplayed()
        // AC3 — states rendered as chips: real Atlassian → "Needs auth", broken → "Failed".
        composeTestRule.onNodeWithText("Needs auth").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed").assertIsDisplayed()

        // AC5 — across the two rows, Login is enabled on exactly the needs_auth server
        // (Atlassian) and disabled on the other; Reconnect is the inverse.
        composeTestRule.onAllNodesWithText("Login").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Login").filter(isEnabled()).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Login").filter(isNotEnabled()).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Reconnect").filter(isEnabled()).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Reconnect").filter(isNotEnabled()).assertCountEquals(1)
    }

    /** AC7 — a session with no MCP servers (no container) renders the empty state, not an error. */
    @Test
    fun emptySession_rendersTheEmptyState() {
        composeTestRule.setContent { AiSandboxTheme { McpScreen(sessionN = 99, onBack = {}) } }

        settle("No MCP servers for this session")

        assumeTrue(
            "AC8 live env absent (server unreachable) — skipping live UI test",
            anyText("No MCP servers for this session"),
        )

        // AC7 — the no-MCP-servers session shows the clear empty message, never the error state.
        composeTestRule.onNodeWithText("No MCP servers for this session").assertIsDisplayed()
    }

    /**
     * UC-82 AC1/AC6 — the Add dialog renders transport-aware fields and gates the Add
     * button on the minimal required fields. DETERMINISTIC: the Add action lives in the
     * top bar (rendered regardless of inventory state), and the dialog's field-swap +
     * client-side enablement need no live server — so this runs on any emulator, even
     * when the live env is absent.
     */
    @Test
    fun addDialog_swapsTransportAwareFields_andGatesTheAddButton() {
        composeTestRule.setContent { AiSandboxTheme { McpScreen(sessionN = 7, onBack = {}) } }
        composeTestRule.waitForIdle()

        // Open the Add dialog from the top-bar action (present in every screen state).
        composeTestRule.onNodeWithContentDescription("Add MCP server").performClick()
        composeTestRule.waitForIdle()

        // Default transport is stdio → the Command field is shown, URL is not.
        composeTestRule.onNodeWithText("Command").assertIsDisplayed()
        assertFalse(anyText("URL (http:// or https://)"))
        // Required fields blank → Add is disabled (AC6 client-side gate).
        composeTestRule.onNodeWithText("Add").assertIsNotEnabled()

        // Fill the stdio minimum (name + command) → Add becomes enabled.
        composeTestRule.onNodeWithText("Name").performTextInput("call-graph")
        composeTestRule.onNodeWithText("Command").performTextInput("npx")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Add").assertIsEnabled()

        // Switch transport to HTTP → the stdio Command field disappears, URL appears.
        composeTestRule.onNodeWithText("HTTP").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("URL (http:// or https://)").assertIsDisplayed()
        assertFalse(anyText("Command"))
    }

    /**
     * UC-82 AC1/AC2/AC3 — LIVE round-trip from the device UI: add a stdio MCP server
     * through the Add dialog, see it appear in the list (live refresh, AC3), then remove
     * it via the per-row Delete → confirm dialog and see it disappear (AC2/AC3). Mutates
     * the live session, so it is env-gated: when the live server/session is absent (CI),
     * it skips via Assume rather than failing.
     */
    @Test
    fun liveRoundTrip_addThenRemove_throughTheUi_reflectsLive() {
        val probe = "uc82-probe"
        composeTestRule.setContent { AiSandboxTheme { McpScreen(sessionN = 7, onBack = {}) } }
        settle("atlassian", "broken-tool", "No MCP servers for this session", probe)

        assumeTrue(
            "AC8 live env absent (session 7 / server unreachable) — skipping live add/remove round-trip",
            anyText("atlassian") || anyText("broken-tool") || anyText("No MCP servers for this session"),
        )

        // ── Add the probe server through the dialog (AC1).
        composeTestRule.onNodeWithContentDescription("Add MCP server").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Name").performTextInput(probe)
        composeTestRule.onNodeWithText("Command").performTextInput("/bin/true")
        composeTestRule.onNodeWithText("Add").performClick()

        // AC3 — it appears in the list without reopening the screen (live refresh).
        composeTestRule.waitUntil(timeoutMillis = 60_000) { anyText(probe) }
        composeTestRule.onNodeWithText(probe).assertIsDisplayed()

        // ── Remove it via the per-row Delete → confirm dialog (AC2).
        composeTestRule.onNodeWithContentDescription("Remove $probe").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Remove \"$probe\"?").assertIsDisplayed()
        // The confirm body is honest about NOT force-killing a running child (AC2).
        assertTrue(anyText("isn't force-killed"))
        composeTestRule.onNodeWithText("Remove").performClick()

        // AC3 — the row disappears live after deregistration.
        composeTestRule.waitUntil(timeoutMillis = 60_000) { !anyText(probe) }
        assertFalse(anyText(probe))
    }

    private companion object {
        /** Enroll once per instrumentation process (the single-use invite can be redeemed only once). */
        @JvmStatic
        private var enrolledThisProcess = false
    }
}
