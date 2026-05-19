package com.aisandbox.android.net

import kotlinx.serialization.Serializable

/**
 * Persisted server-side identity the client trusts. UC04 § "One server
 * at a time" — exactly zero or one of these is on disk at any moment.
 *
 * @param serverUrl     base URL (must start with `https://`).
 * @param pinSha256Hex  SHA-256 of the server cert's SubjectPublicKeyInfo
 *                      (SPKI), lowercase hex. Format matches
 *                      `PemUtils.spkiFingerprintHex` on the server side
 *                      and the value OkHttp's `CertificatePinner`
 *                      verifies (HPKP / RFC 7469 default) — operators
 *                      can copy-paste between the two without
 *                      conversion. (Pre-v0.0.10 this was the full-DER
 *                      cert hash via `PemUtils.fingerprintHex`, which
 *                      OkHttp never matches against — UC09.)
 * @param clientCertCn  CN of the imported client cert — display only.
 * @param clientCertExpiresAtMs Epoch ms; used by the Settings cert card
 *                      to surface "expires in N days".
 */
@Serializable
data class ServerProfile(
    val serverUrl: String,
    val pinSha256Hex: String,
    val clientCertCn: String,
    val clientCertExpiresAtMs: Long,
) {
    /**
     * OkHttp's `CertificatePinner` accepts `sha256/<base64>` strings;
     * convert our lowercase-hex pin to that form. Done at consumption
     * time rather than at storage so the on-disk format stays
     * operator-readable.
     */
    fun toOkHttpPin(): String =
        "sha256/" + java.util.Base64.getEncoder().encodeToString(hexToBytes(pinSha256Hex))

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "pin must have even hex length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "pin must be hex" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
