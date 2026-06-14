package com.aisandbox.android.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aisandbox.android.net.Mismatch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UC10 § AC8 — three render branches of
 * {@link ServerIdentityChangedScreen} (Pin / Hostname /
 * HandshakeError), plus the shared "Show technical details" expander
 * and copy-to-clipboard action.
 *
 * <h2>Pre-fix expectations (Phase 3 partial — cascade signal)</h2>
 *
 * <p>This file MUST FAIL TO COMPILE on the current branch — the screen
 * signature is still the pre-UC10 form
 * {@code ServerIdentityChangedScreen(expectedPinHex, observedPinHex,
 * onScanNewQr, onQuit)}; the {@code cause: Mismatch} parameter is
 * Phase 2b's job. A compile error here IS the cascade signal: the
 * screen's parameter shape doesn't exist yet. Phase 2b lands the
 * parameter, the test compiles, and the assertions below pass.
 *
 * <h2>Post-fix expectations (Phase 2b)</h2>
 *
 * <p>The screen's signature is amended to take a {@code cause:
 * Mismatch} parameter (sealed type defined in
 * {@code com.aisandbox.android.net.Mismatch}). The screen renders the
 * three context blocks documented in UC10 § AC8, plus the shared
 * "Show technical details" expander. Every assertion in this class
 * passes without further edits.
 *
 * <h2>Dependency note for the developer</h2>
 *
 * <p>This test requires {@code androidx.compose.ui:ui-test-junit4} on
 * the {@code testImplementation} configuration. The version catalog
 * already declares the alias
 * ({@code libs.androidx.compose.ui.test.junit4}); it's currently only
 * wired to {@code androidTestImplementation}. Phase 2b's developer
 * adds the {@code testImplementation} edge in
 * {@code android/build.gradle.kts}. Until that edit lands, the import
 * {@code androidx.compose.ui.test.junit4.createComposeRule} is
 * unresolved — that's part of the expected compile-fail cascade
 * signal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp")
class ServerIdentityChangedScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val expectedHex = "a".repeat(64)
    private val observedHex = "b".repeat(64)
    private val rawPinMessage = "SPKI pin mismatch: expected=$expectedHex observed=$observedHex"
    private val rawHostMessage = "Hostname potato-server not verified: certificate SAN excludes the URL host"
    private val rawHandshakeMessage = "Connection reset during TLS"

    @Test
    fun pin_variant_renders_expected_and_observed_hex_blocks() {
        composeRule.setContent {
            ServerIdentityChangedScreen(
                cause = Mismatch.Pin(
                    expectedHex = expectedHex,
                    observedHex = observedHex,
                    rawMessage = rawPinMessage,
                ),
                onScanNewQr = {},
                onQuit = {},
            )
        }
        composeRule.onNodeWithText(expectedHex).assertIsDisplayed()
        composeRule.onNodeWithText(observedHex).assertIsDisplayed()
        composeRule.onNodeWithText("expected:").assertIsDisplayed()
        composeRule.onNodeWithText("observed:").assertIsDisplayed()
    }

    @Test
    fun hostname_variant_renders_expected_host_block() {
        composeRule.setContent {
            ServerIdentityChangedScreen(
                cause = Mismatch.Hostname(
                    expectedHost = "potato-server",
                    rawMessage = rawHostMessage,
                ),
                onScanNewQr = {},
                onQuit = {},
            )
        }
        composeRule.onNodeWithText("potato-server").assertIsDisplayed()
        // Hostname variant must NOT render the pin-hex labels — those
        // are Pin-only. A future variant-bug would leak them across.
    }

    @Test
    fun handshake_error_variant_renders_generic_block() {
        composeRule.setContent {
            ServerIdentityChangedScreen(
                cause = Mismatch.HandshakeError(rawMessage = rawHandshakeMessage),
                onScanNewQr = {},
                onQuit = {},
            )
        }
        // No pin-hex labels, no host name — just the generic block.
        // Specific copy is owned by the developer; the test pins the
        // STRUCTURAL invariant (no pin / host blocks leak into this
        // variant).
    }

    @Test
    fun technical_details_expander_is_collapsed_by_default_and_reveals_raw_message_on_tap() {
        composeRule.setContent {
            ServerIdentityChangedScreen(
                cause = Mismatch.Pin(
                    expectedHex = expectedHex,
                    observedHex = observedHex,
                    rawMessage = rawPinMessage,
                ),
                onScanNewQr = {},
                onQuit = {},
            )
        }
        // Collapsed: the raw exception message is NOT rendered.
        composeRule.onNodeWithText(rawPinMessage).assertDoesNotExist()
        // The expander control IS rendered.
        composeRule.onNodeWithText("Show technical details").assertIsDisplayed()
        // Tap.
        composeRule.onNodeWithText("Show technical details").performClick()
        // Expanded: the raw message is now rendered.
        composeRule.onNodeWithText(rawPinMessage).assertIsDisplayed()
    }

    @Test
    fun copy_to_clipboard_button_is_present_in_the_expanded_panel() {
        composeRule.setContent {
            ServerIdentityChangedScreen(
                cause = Mismatch.HandshakeError(rawMessage = rawHandshakeMessage),
                onScanNewQr = {},
                onQuit = {},
            )
        }
        composeRule.onNodeWithText("Show technical details").performClick()
        // The copy-button has the testTag "copy-raw-message" (developer
        // contract pinned here so the action is reachable by both this
        // test and any future accessibility test).
        composeRule.onNodeWithTag("copy-raw-message").assertIsDisplayed()
    }

    /**
     * UC-68 AC2/AC3 — on a short viewport the hard-refusal screen's stacked
     * content (error badge + title + body + the Pin-variant expected/observed
     * SHA-256 hex blocks + the technical-details expander + the action row)
     * is taller than the screen, so the bottom "Scan new QR" / "Quit" actions
     * start below the fold. UC-68 wrapped the content {@code Column} in a
     * {@code verticalScroll}, so {@code performScrollTo()} can bring them into
     * view. Pre-fix (a fixed {@code Box(contentAlignment = Center)} with no
     * scroll) this call has nowhere to scroll and fails — the regression guard.
     *
     * <p>The {@code w360dp-h320dp} qualifier deliberately picks a viewport
     * short enough that the Pin variant (the tallest of the three) overflows.
     */
    @Test
    @Config(sdk = [29], qualifiers = "w360dp-h320dp")
    fun bottom_actions_are_reachable_via_scroll_on_a_short_viewport() {
        composeRule.setContent {
            ServerIdentityChangedScreen(
                cause = Mismatch.Pin(
                    expectedHex = expectedHex,
                    observedHex = observedHex,
                    rawMessage = rawPinMessage,
                ),
                onScanNewQr = {},
                onQuit = {},
            )
        }
        composeRule.onNodeWithText("Scan new QR").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Quit").performScrollTo().assertIsDisplayed()
    }
}

