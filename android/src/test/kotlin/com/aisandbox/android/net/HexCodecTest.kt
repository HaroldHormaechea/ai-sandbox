package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * UC10 — [HexCodec] is the shared producer/consumer of hex ↔ bytes
 * conversion used by [SpkiPinningTrustManager] (encoding the observed
 * SPKI back to hex for the structured exception message) and by
 * [EnrollmentClient] / [AiSandboxHttpClient] (decoding the QR's
 * {@code pinSha256Hex} into the 32-byte digest the trust manager
 * expects).
 *
 * <p>This coverage was historically inside [ServerProfileTest] (via
 * the deleted {@code ServerProfile.toOkHttpPin} path's private
 * {@code hexToBytes}); UC10's cleanup commit moved the validation
 * contract to [HexCodec], so the tests now exercise that code path
 * directly.
 */
class HexCodecTest {

    @Test
    fun `lowercase hex round-trips bytes-to-hex back to bytes`() {
        // SHA-256 of an empty input — well-known constant. Pinning a
        // real-world digest keeps the test grounded in the production
        // shape (every SPKI hash is 64 chars of lowercase hex).
        val hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val bytes = HexCodec.hexToBytes(hex)
        assertThat(bytes).hasSize(32)
        assertThat(HexCodec.bytesToHex(bytes)).isEqualTo(hex)
    }

    @Test
    fun `mixed-case hex decodes identically to lowercase`() {
        val lower = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val upper = lower.uppercase()
        val mixed = "E3b0C44298Fc1C149afbf4c8996fb92427ae41e4649B934Ca495991B7852B855"

        assertThat(HexCodec.hexToBytes(lower)).isEqualTo(HexCodec.hexToBytes(upper))
        assertThat(HexCodec.hexToBytes(lower)).isEqualTo(HexCodec.hexToBytes(mixed))
    }

    @Test
    fun `odd-length hex throws IllegalArgumentException with the documented message`() {
        assertThatThrownBy { HexCodec.hexToBytes("abc") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("even length")
    }

    @Test
    fun `non-hex characters throw IllegalArgumentException`() {
        assertThatThrownBy { HexCodec.hexToBytes("z".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("non-hex")
    }

    @Test
    fun `bytesToHex emits lowercase to match the server-side PemUtils sha256Hex format`() {
        // Every byte covered; round-trip via hexToBytes pins both
        // directions on the same alphabet.
        val bytes = ByteArray(32) { (it * 7).toByte() }
        val hex = HexCodec.bytesToHex(bytes)
        assertThat(hex).matches("[0-9a-f]{64}")
        assertThat(HexCodec.hexToBytes(hex)).isEqualTo(bytes)
    }
}
