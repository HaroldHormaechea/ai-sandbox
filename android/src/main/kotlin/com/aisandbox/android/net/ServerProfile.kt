package com.aisandbox.android.net

import kotlinx.serialization.Serializable

/**
 * Persisted server-side identity the client trusts. UC04 § "One server
 * at a time" — exactly zero or one of these is on disk at any moment.
 *
 * @param serverUrl     base URL (must start with `https://`).
 * @param pinSha256Hex  SHA-256 of the server cert's SubjectPublicKeyInfo
 *                      (SPKI), lowercase hex. Format matches
 *                      [com.aisandbox.server.pki.PemUtils.spkiFingerprintHex]
 *                      on the server side and the digest
 *                      [SpkiPinningTrustManager] computes against the
 *                      presented cert at TLS-handshake time — operators
 *                      can copy-paste between the two without
 *                      conversion. Pre-v0.0.10 this was the full-DER
 *                      cert hash via `PemUtils.fingerprintHex` (UC09).
 *                      UC10 then removed the OkHttp `CertificatePinner`
 *                      path entirely; the hex is now consumed by
 *                      [SpkiPinningTrustManager] via
 *                      [HexCodec.hexToBytes].
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
)
