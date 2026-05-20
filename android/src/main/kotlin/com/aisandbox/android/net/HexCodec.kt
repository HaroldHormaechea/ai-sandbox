package com.aisandbox.android.net

/**
 * Shared hex ↔ bytes helpers used by [SpkiPinningTrustManager] (encode the
 * observed SPKI back to hex for the structured exception message) and by
 * [EnrollmentClient] / [AiSandboxHttpClient] (decode the QR's
 * {@code pinSha256Hex} into the 32-byte digest the trust manager expects).
 *
 * <p>UC10 § AC1 — extracted into a single utility so the producer side
 * ([SpkiPinningTrustManager]) and the consumer side
 * ([TlsFailureTranslation.extractObservedSpkiHex]) round-trip on the
 * same encoding. The existing inline {@code hexToBytes} in
 * {@code EnrollmentClient} is preserved during Phase 2a to avoid behavioural
 * churn; Phase 2b migrates callers to this helper.
 *
 * <p>Stateless / object-singleton; no allocation overhead beyond the
 * returned arrays.
 */
internal object HexCodec {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /**
     * Decode a lowercase- or uppercase-hex string into its byte form.
     *
     * @throws IllegalArgumentException if [hex] has an odd length or contains
     *   a non-hex character.
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have even length (got ${hex.length})" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) {
                "non-hex character at index ${i * 2} or ${i * 2 + 1}"
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Encode a byte array as lowercase hex. Matches
     * {@code com.aisandbox.server.pki.PemUtils.sha256Hex} so SPKI digests
     * compared between client and server are byte-for-byte identical.
     */
    fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0f]
        }
        return String(out)
    }
}
