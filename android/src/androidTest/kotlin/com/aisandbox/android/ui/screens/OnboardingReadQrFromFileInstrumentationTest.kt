package com.aisandbox.android.ui.screens

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.ui.components.QrDecodeResult
import com.aisandbox.android.ui.theme.AiSandboxTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-83 § Criterion 1 + Criterion 3 — instrumented coverage that the
 * "Read QR from file" affordance is present in BOTH onboarding entry
 * panels (camera-permission + scanning) and that picking an image routes
 * through [OnboardingViewModel.onQrImageSelected] into the SAME state
 * machine the camera scan uses.
 *
 * <p>Runs on the connected KVM emulator. The test app does NOT hold the
 * CAMERA runtime permission, so [OnboardingScreen] stays in
 * `NeedsCameraPermission` on first compose (no camera surface is ever
 * mounted) — exactly the state Criterion 6 protects. We never automate
 * the system document picker (that is OS UI, not ours); instead we drive
 * the decode result directly through an injected stub `imageDecoder`,
 * which is the same seam the production `OpenDocument` launcher feeds.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingReadQrFromFileInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private val readQrLabel = "Read QR from file"

    @Test
    fun readFromFileButton_isShown_inTheCameraPermissionPanel() {
        // Decoder is irrelevant here — we only assert the affordance renders.
        val vm = OnboardingViewModel(app) { QrDecodeResult.Candidates(emptyList()) }
        composeTestRule.setContent {
            AiSandboxTheme { OnboardingScreen(onContinue = {}, viewModel = vm) }
        }

        // Default state with no camera permission is the permission panel.
        composeTestRule.onNodeWithText("Grant camera access").assertIsDisplayed()
        composeTestRule.onNodeWithText(readQrLabel).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun readFromFileButton_isShown_inTheScanningPanel() {
        val vm = OnboardingViewModel(app) { QrDecodeResult.Candidates(emptyList()) }
        composeTestRule.setContent {
            AiSandboxTheme { OnboardingScreen(onContinue = {}, viewModel = vm) }
        }

        // Advance into the scanning panel (no camera permission ⇒ the camera
        // surface stays unmounted, but the panel + affordance still render).
        composeTestRule.runOnUiThread { vm.onCameraPermissionGranted() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(readQrLabel).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun selectingAnImage_routesThroughOnQrImageSelected_intoTheSharedStateMachine() {
        // Stub the decode to "readable, but no QR" so the file path lands on
        // the no_qr_in_image failure — proving the picked-image result is fed
        // into the SAME OnboardingState machine the camera scan drives, and
        // rendered by the shared FailurePanel.
        val vm = OnboardingViewModel(app) { QrDecodeResult.Candidates(emptyList()) }
        composeTestRule.setContent {
            AiSandboxTheme { OnboardingScreen(onContinue = {}, viewModel = vm) }
        }

        composeTestRule.runOnUiThread {
            vm.onQrImageSelected(Uri.parse("content://test/picked.png"))
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("no_qr_in_image").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("no_qr_in_image").assertIsDisplayed()
        // The shared failure UI offers the retry-to-scan affordance.
        composeTestRule.onNodeWithText("Try again").assertIsDisplayed()
    }
}
