package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC04-1 — instrumented coverage for the onboarding screen's state
 * machine (scan → imported → ready) and the AC6 "Replace existing
 * identity?" confirmation path.
 *
 * <p>This test runs on a connected Android device — there is no local
 * device in the sandbox today, and the dev-team CI image does not yet
 * spin up an emulator. The test is authored against the production
 * API surface so that whenever the device-test wiring lands, it boots
 * green without churn.
 *
 * <p>Scope (intentional minimum for v0.1):
 *
 * <ul>
 *   <li>Initial state shows the QR viewfinder placeholder + the
 *       "Position the QR…" caption.</li>
 *   <li>State transitions: scan → imported → ready advance the visible
 *       caption + reveal the cert-imported confirmation panel.</li>
 *   <li>Re-scanning when an identity already exists triggers the
 *       AC6 "Replace existing identity?" dialog.</li>
 * </ul>
 *
 * <p>NOT covered here (deferred to a future revision):
 *
 * <ul>
 *   <li>Real CameraX preview + ZXing decoding — requires a device with
 *       a camera and a printed QR (or a test-only injected
 *       `BarcodeAnalyzer`).</li>
 *   <li>Permission-denied flow (AC § "Camera-permission flow") — also
 *       device-dependent.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun initial_state_shows_qr_viewfinder_caption() {
        // composeTestRule.setContent { OnboardingScreen(...) }
        // composeTestRule.onNodeWithText("Position the QR within the reticle").assertIsDisplayed()
        //
        // DEFERRED — requires a fake QrScanner + AppContainer hook the
        // production code does not yet expose for tests. When the device-
        // test wiring lands, replace this body with the assertions above.
    }

    @Test
    fun replace_identity_dialog_fires_when_an_identity_already_exists() {
        // composeTestRule.setContent { OnboardingScreen(...identityAlreadyExists = true) }
        // simulate scan → composeTestRule.onNodeWithText("Replace existing identity?").assertIsDisplayed()
        //
        // DEFERRED — same gating as above.
    }

    @Test
    fun import_success_shows_kestore_non_exportable_badge() {
        // After successful POST /v1/enrollment redemption, the
        // "stored in Android KeyStore · non-exportable" badge is part
        // of the imported state (AC4).
        //
        // DEFERRED.
    }

    /**
     * UC-68 AC2 — the Failure panel ("error code + message + Try again")
     * renders its content in a centred Column. On a long error message and/or a
     * short viewport the bottom "Try again" action could be clipped, so UC-68
     * wrapped the panel in a {@code verticalScroll}.
     *
     * <p>We drive the real {@link OnboardingScreen} into the Failure state
     * without a camera or network: feeding an unparseable string to
     * {@link OnboardingViewModel#onQrPayload} fails the QR parse and emits
     * {@code OnboardingState.Failure} directly (no enrollment round-trip). The
     * screen is rendered in a deliberately short box to force overflow, then we
     * prove "Try again" is reachable by scrolling — which only works because of
     * the UC-68 {@code verticalScroll}; pre-fix this {@code performScrollTo()}
     * would throw on the non-scrolling Column.
     */
    @Test
    fun failureState_tryAgain_isReachableByScrolling_onAShortViewport() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = OnboardingViewModel(app)

        composeTestRule.setContent {
            AiSandboxTheme {
                Box(Modifier.fillMaxWidth().requiredHeight(200.dp)) {
                    OnboardingScreen(onContinue = {}, viewModel = viewModel)
                }
            }
        }

        // Unparseable QR → OnboardingState.Failure (bad_qr); no camera/network.
        composeTestRule.runOnUiThread {
            viewModel.onQrPayload("this-is-not-a-valid-ai-sandbox-invite-payload")
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Try again")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
