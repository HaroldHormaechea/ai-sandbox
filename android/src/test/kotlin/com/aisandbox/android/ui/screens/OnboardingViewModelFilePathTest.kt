package com.aisandbox.android.ui.screens

import android.net.Uri
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.ui.components.QrDecodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UC-83 — file-based enrollment routing in [OnboardingViewModel].
 *
 * <p>This is the heart of Criterion 3 + Criterion 5: a picked image is
 * decoded via the injectable `imageDecoder` seam, and the result is
 * funnelled into the SAME parse/validate/enroll path the camera uses
 * (`handleRawQr`). We inject a STUB decoder so the test needs neither a
 * real [android.graphics.Bitmap] nor a device — every decode outcome is
 * driven directly:
 *
 * <ul>
 *   <li>{@link QrDecodeResult.Unreadable} → {@code image_unreadable}</li>
 *   <li>{@link QrDecodeResult.Candidates}(empty) → {@code no_qr_in_image}</li>
 *   <li>decodes but no candidate is a valid invite → {@code bad_qr}</li>
 *   <li>a valid invite → the shared enroll seam (here, the AC6
 *       replace-confirmation, since a profile already exists) — proving the
 *       file path does NOT fork the parse/enroll logic.</li>
 * </ul>
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OnboardingViewModelFilePathTest {

    private val app get() = RuntimeEnvironment.getApplication() as AiSandboxApplication

    private val validInvite =
        """{"u":"https://example.com:12410","t":"$TOKEN_64","exp":"2026-05-17T10:10:00Z","pin":"${"fa".repeat(32)}"}"""

    @Before
    fun setUp() {
        // viewModelScope dispatches on Main; Unconfined keeps launched work
        // running eagerly so we can observe the resulting StateFlow value
        // without pumping a looper. The stub decoder does no real IO, so the
        // withContext(Dispatchers.IO) hop resolves immediately.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Clear any seeded profile so tests don't leak DataStore state.
        runBlocking { app.container.profileStore.clear() }
    }

    private fun viewModelWithDecode(result: QrDecodeResult): OnboardingViewModel =
        OnboardingViewModel(app) { _: Uri -> result }

    private fun awaitFailure(vm: OnboardingViewModel): OnboardingState.Failure = runBlocking {
        vm.onQrImageSelected(Uri.parse("content://test/picked.png"))
        withTimeout(5_000) {
            vm.state.first { it is OnboardingState.Failure } as OnboardingState.Failure
        }
    }

    @Test
    fun `unreadable file lands on image_unreadable failure`() {
        val failure = awaitFailure(viewModelWithDecode(QrDecodeResult.Unreadable))
        assertThat(failure.code).isEqualTo("image_unreadable")
    }

    @Test
    fun `image with no QR lands on no_qr_in_image failure`() {
        val failure = awaitFailure(viewModelWithDecode(QrDecodeResult.Candidates(emptyList())))
        assertThat(failure.code).isEqualTo("no_qr_in_image")
    }

    @Test
    fun `QR that is not a valid invite lands on bad_qr failure`() {
        val failure = awaitFailure(
            viewModelWithDecode(QrDecodeResult.Candidates(listOf("https://evil.example/not-an-invite"))),
        )
        assertThat(failure.code).isEqualTo("bad_qr")
    }

    @Test
    fun `valid invite is funnelled into the shared enroll seam (ConfirmReplace when a profile exists)`() {
        runBlocking {
            // Seed an existing profile so handleRawQr deterministically lands on
            // the AC6 ConfirmReplace branch instead of firing a real network
            // enrollment — this proves the valid payload reached the SHARED seam.
            seedProfile()
            val vm = viewModelWithDecode(QrDecodeResult.Candidates(listOf(validInvite)))

            vm.onQrImageSelected(Uri.parse("content://test/invite.png"))
            val state = withTimeout(5_000) {
                vm.state.first { it is OnboardingState.ConfirmReplace } as OnboardingState.ConfirmReplace
            }

            assertThat(state.payload.serverUrl).isEqualTo("https://example.com:12410")
        }
    }

    @Test
    fun `picks the first parseable invite among multiple candidates`() {
        runBlocking {
            seedProfile()
            // A junk QR first, the real invite second: firstOrNull { parse success }
            // must skip the junk and select the valid invite — never silently commit
            // to the wrong code (multi-QR edge case).
            val vm = viewModelWithDecode(
                QrDecodeResult.Candidates(listOf("garbage-not-json", validInvite)),
            )

            vm.onQrImageSelected(Uri.parse("content://test/multi.png"))
            val state = withTimeout(5_000) {
                vm.state.first { it is OnboardingState.ConfirmReplace } as OnboardingState.ConfirmReplace
            }

            assertThat(state.payload.serverUrl).isEqualTo("https://example.com:12410")
        }
    }

    private suspend fun seedProfile() {
        app.container.profileStore.save(
            ServerProfile(
                serverUrl = "https://prior.example:12410",
                pinSha256Hex = "ab".repeat(32),
                clientCertCn = "CN=prior",
                clientCertExpiresAtMs = 0L,
            ),
        )
    }

    companion object {
        // Obvious placeholder token — mirrors QrPayloadTest.TOKEN_64.
        private const val TOKEN_64 =
            "abcd1234.fake-test-token-not-a-real-key.0123456789ab-cdefABCDEFX"
    }
}
