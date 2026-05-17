package com.aisandbox.android.ui.screens

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 AC14 — terminal BEL (`0x07`) byte in the PTY stream emits a
 * [HapticEvent.Bell]. The detection logic lives inline in
 * [TerminalViewModel.connectLoop] as `bytes.any { it == 0x07.toByte() }`.
 *
 * <p>This test pins:
 *
 * <ul>
 *   <li>The exact byte-value contract (0x07 — ASCII BEL).</li>
 *   <li>That the [HapticEvent] sealed hierarchy currently has exactly
 *       one variant ([HapticEvent.Bell]) — broadening this requires a
 *       conscious test update + a re-read of AC14.</li>
 * </ul>
 *
 * <p>The full ViewModel-orchestration path is deferred to instrumented
 * tests (`androidTest/`) where Application + AppContainer wiring is
 * available; explicit gap noted in the TEST SUMMARY.
 */
class TerminalBellDetectionTest {

    /** Mirror of the inline detector in TerminalViewModel.connectLoop. */
    private fun detectBell(bytes: ByteArray): Boolean = bytes.any { it == 0x07.toByte() }

    @Test
    fun `bel byte 0x07 is detected anywhere in the buffer`() {
        assertThat(detectBell(byteArrayOf(0x07))).isTrue
        assertThat(detectBell(byteArrayOf(0x41, 0x07, 0x42))).isTrue
        assertThat(detectBell(byteArrayOf(0x41, 0x42, 0x07))).isTrue
        // Edge cases.
        assertThat(detectBell(byteArrayOf())).isFalse
        assertThat(detectBell(byteArrayOf(0x06, 0x08))).isFalse
        assertThat(detectBell("hello".toByteArray())).isFalse
    }

    @Test
    fun `0x87 is NOT BEL despite high bit set`() {
        // Bell is exclusively 0x07. 0x87 is a high-bit-set non-control byte
        // (often UTF-8 continuation); must not haptic.
        assertThat(detectBell(byteArrayOf(0x87.toByte()))).isFalse
    }

    @Test
    fun `HapticEvent has exactly one variant Bell today`() {
        // If a future change adds a second haptic variant (e.g.
        // ConnectionRestored = 50 ms pulse), this test forces a
        // conscious update to AC14's wire contract.
        val instance = HapticEvent.Bell
        assertThat(instance).isInstanceOf(HapticEvent::class.java)
        // The sealed interface's permitted subclasses are exposed via
        // reflection; check there is just one (Bell).
        val permitted = HapticEvent::class.sealedSubclasses
        assertThat(permitted).hasSize(1)
        assertThat(permitted.first().simpleName).isEqualTo("Bell")
    }
}
