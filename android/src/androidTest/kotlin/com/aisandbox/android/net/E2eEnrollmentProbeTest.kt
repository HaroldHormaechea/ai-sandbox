package com.aisandbox.android.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THROWAWAY end-to-end probe — NOT part of the UC-18 fix.
 *
 * Proves the emulator (VM) can reach a local ai-sandbox management server and
 * complete a real enrollment through the app's own networking stack:
 * OkHttp + the mTLS-exempt `POST /v1/enrollment` + the SPKI-pinning trust
 * manager. The self-signed test cert is accepted because verification is by
 * SPKI pin + SAN (not the system CA store), exactly as in production.
 *
 * Run with a FRESH invite payload (token TTL ~10 min, single-use):
 * ```
 * adb shell am instrument -w \
 *   -e class com.aisandbox.android.net.E2eEnrollmentProbeTest \
 *   -e qrPayload '{"u":"https://10.0.2.2:18443","t":"<token>","exp":"<iso>","pin":"<sha256 spki>"}' \
 *   com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class E2eEnrollmentProbeTest {

    @Test
    fun emulator_enrolls_against_local_server() {
        val raw = InstrumentationRegistry.getArguments().getString("qrPayload")
            ?: error("missing instrumentation arg: -e qrPayload '<invite json>'")
        val payload = QrPayload.parse(raw).getOrThrow()

        val outcome = runBlocking { EnrollmentClient(payload).redeem() }

        assertTrue(
            "expected Outcome.Success from ${payload.serverUrl}, got $outcome",
            outcome is EnrollmentClient.Outcome.Success,
        )
        val p12 = (outcome as EnrollmentClient.Outcome.Success).pkcs12
        assertTrue("PKCS#12 bundle should be non-empty", p12.isNotEmpty())
    }
}
