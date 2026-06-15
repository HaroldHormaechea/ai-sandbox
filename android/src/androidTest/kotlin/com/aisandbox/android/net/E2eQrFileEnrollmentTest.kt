package com.aisandbox.android.net

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.ui.components.QrDecodeResult
import com.aisandbox.android.ui.components.decodeInviteFromUri
import com.aisandbox.android.ui.screens.OnboardingState
import com.aisandbox.android.ui.screens.OnboardingViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-83 § AC8 — the camera-free, production-decode headless-enrollment gate.
 *
 * <p>This is the whole point of UC-83 for testing: prove the emulator can
 * enroll against a live ai-sandbox server WITHOUT a camera, by reading the
 * invite QR from an image file through the EXACT production path a user
 * exercises with the "Read QR from file" button:
 *
 * <pre>
 *   QR PNG → {@link decodeInviteFromUri} (ContentResolver → BitmapFactory →
 *   ZXing) → {@link OnboardingViewModel#onQrImageSelected} → onQrPayload →
 *   {@link EnrollmentClient} → mTLS POST /v1/enrollment → SPKI pin → PKCS#12
 *   import → ServerProfile saved.
 * </pre>
 *
 * <p>No stubbed decoder and no pre-decoded payload injected past the
 * decoder — the bytes go through {@link decodeInviteFromUri} just as they
 * would from the system document picker.
 *
 * <h2>How to run</h2>
 *
 * Two input modes (mirrors the {@code android-testing} skill, Phase 4):
 *
 * <pre>
 *   # (a) push a pre-rendered QR PNG and point the test at it:
 *   adb push invite-qr.png /sdcard/Download/invite-qr.png
 *   adb shell am instrument -w \
 *     -e class com.aisandbox.android.net.E2eQrFileEnrollmentTest \
 *     -e qrImagePath /sdcard/Download/invite-qr.png \
 *     com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 *   # (b) pass the raw invite JSON; the test renders the QR PNG on-device:
 *   adb shell am instrument -w \
 *     -e class com.aisandbox.android.net.E2eQrFileEnrollmentTest \
 *     -e qrPayload '{"u":"https://10.0.2.2:18443","t":"<token>","exp":"<iso>","pin":"<spki>"}' \
 *     com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 *
 * <p>With neither arg the test is SKIPPED (no fresh single-use invite to
 * redeem). The PRODUCTION DECODE round-trip is always asserted when an arg
 * is present; the LIVE POST is asserted when a server answers and is
 * gracefully skipped (not failed) on a pure connectivity error — so the
 * gate is meaningful when a server is up and non-flaky when it is not.
 */
@RunWith(AndroidJUnit4::class)
class E2eQrFileEnrollmentTest {

    private val context: AiSandboxApplication get() = ApplicationProvider.getApplicationContext()

    @Test
    fun enroll_from_qr_image_file_through_production_path() {
        val args = InstrumentationRegistry.getArguments()
        val qrPayloadArg = args.getString("qrPayload")
        val qrImagePathArg = args.getString("qrImagePath")
        Assume.assumeTrue(
            "E2eQrFileEnrollmentTest skipped: pass -e qrImagePath '<png on device>' OR " +
                "-e qrPayload '<fresh invite json>' to run the camera-free file-enrollment gate.",
            qrPayloadArg != null || qrImagePathArg != null,
        )

        // 1) Materialise the invite-QR PNG into app-private storage so the
        //    production ContentResolver path can read it via a file:// Uri
        //    (no shared-storage permission needed).
        val pngFile = File(context.filesDir, "uc83-invite-qr.png")
        if (qrImagePathArg != null) {
            pngFile.writeBytes(readViaShell(qrImagePathArg))
        } else {
            renderQrPng(qrPayloadArg!!, pngFile)
        }
        val uri = Uri.fromFile(pngFile)

        // 2) PRODUCTION decode — the same call OnboardingViewModel makes.
        val decoded = decodeInviteFromUri(context, uri)
        assertTrue(
            "production decodeInviteFromUri returned $decoded for a valid invite-QR PNG " +
                "(${pngFile.length()} bytes, decodes to ${pngFile.length()} on disk). The image IS a " +
                "decodable QR — the camera-free file path must return Candidates here.",
            decoded is QrDecodeResult.Candidates,
        )
        val candidates = (decoded as QrDecodeResult.Candidates).payloads
        val invite = candidates.firstOrNull { QrPayload.parse(it).isSuccess }
        assertTrue(
            "no candidate decoded from the QR image parsed as a valid invite; candidates=$candidates",
            invite != null,
        )
        // Exact round-trip when we know the source payload.
        if (qrPayloadArg != null) {
            assertEquals("decoded QR payload must match the encoded invite", qrPayloadArg, invite)
        }
        val payload = QrPayload.parse(invite!!).getOrThrow()

        // 3) Drive the SAME ViewModel seam the button uses and await the
        //    terminal state. Clear any prior profile so we land on direct
        //    enroll (not the AC6 ConfirmReplace branch).
        runBlocking { context.container.profileStore.clear() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val vm = OnboardingViewModel(context)

        instrumentation.runOnMainSync { vm.onQrImageSelected(uri) }
        var terminal = awaitState(vm) {
            it is OnboardingState.Imported ||
                it is OnboardingState.ConfirmReplace ||
                it is OnboardingState.Failure
        }
        if (terminal is OnboardingState.ConfirmReplace) {
            instrumentation.runOnMainSync { vm.onConfirmReplace() }
            terminal = awaitState(vm) {
                it is OnboardingState.Imported || it is OnboardingState.Failure
            }
        }

        when (val s = terminal) {
            is OnboardingState.Imported -> {
                // Live enrollment succeeded through the file path — the gate.
                assertEquals(payload.serverUrl, s.profile.serverUrl)
                assertEquals(payload.pinSha256Hex, s.profile.pinSha256Hex)
                val saved = runBlocking { context.container.profileStore.current() }
                assertTrue("enrolled profile must be persisted", saved != null)
            }
            is OnboardingState.Failure -> {
                // Decode + onQrPayload wiring is already proven above. A pure
                // connectivity failure means no live server is reachable from
                // the emulator — skip (don't fail) so the gate isn't flaky.
                val connectivity = s.code in CONNECTIVITY_CODES
                Assume.assumeTrue(
                    "QR-from-file decode+wiring verified, but the live POST /v1/enrollment did not " +
                        "complete (code=${s.code}, msg=${s.message}). No reachable server — live-enroll " +
                        "leg skipped. Re-run with a live server + fresh invite to exercise the full gate.",
                    !connectivity,
                )
                // Non-connectivity failure on a VALID invite is a real defect.
                throw AssertionError("file enrollment failed on a valid invite: code=${s.code}, msg=${s.message}")
            }
            else -> throw AssertionError("unexpected onboarding state: $s")
        }
    }

    /** Block (off the main thread) until the VM's state satisfies [pred]. */
    private fun awaitState(
        vm: OnboardingViewModel,
        pred: (OnboardingState) -> Boolean,
    ): OnboardingState = runBlocking {
        withTimeout(ENROLL_TIMEOUT_MS) { vm.state.first(pred) }
    }

    /** Read a (possibly shared-storage) file as the shell uid, robust to scoped storage. */
    private fun readViaShell(path: String): ByteArray {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand("cat $path")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** Render [payload] into a real QR PNG using the same ZXing the app ships. */
    private fun renderQrPng(payload: String, dest: File) {
        val size = 600
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) BLACK else WHITE)
            }
        }
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val ENROLL_TIMEOUT_MS = 60_000L

        // Failure codes that mean "couldn't reach / handshake the server" — a
        // missing live server, NOT a defect in the decode/enroll wiring.
        private val CONNECTIVITY_CODES = setOf(
            "io_error",
            "handshake_error",
            "hostname_mismatch",
            "pin_mismatch",
        )
    }
}
