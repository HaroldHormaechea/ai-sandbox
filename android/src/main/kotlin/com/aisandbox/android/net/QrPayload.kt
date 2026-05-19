package com.aisandbox.android.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parsed payload of an enrollment QR (UC04 § B3 / aisandboxctl client invite).
 *
 * Wire shape:
 * ```json
 * { "u": "https://host:12410", "t": "<hex token>", "exp": "2026-05-17T12:34:56Z", "pin": "<sha256 hex of SPKI>" }
 * ```
 *
 * Field names match the CLI emitter ([com.aisandbox.server.cli.ClientInviteCommand]).
 * Drift either side breaks onboarding silently — the order of declaration
 * here is preserved by `kotlinx.serialization` so the camera-side decode
 * is forgiving but the encode round-trip is bit-stable.
 *
 * @property pinSha256Hex SHA-256 of the server cert's SubjectPublicKeyInfo
 *   (SPKI), lowercase hex. Matches `PemUtils.spkiFingerprintHex` on the
 *   server side and the value OkHttp's `CertificatePinner` verifies
 *   (HPKP / RFC 7469 default). Pre-v0.0.10 was the full-DER cert hash;
 *   UC09 reconciled the algorithm.
 */
@Serializable
data class QrPayload(
    @SerialName("u") val serverUrl: String,
    @SerialName("t") val token: String,
    @SerialName("exp") val expiresAtIso: String,
    @SerialName("pin") val pinSha256Hex: String,
) {

    /**
     * Lightweight sanity check — the camera scanner can throw arbitrary
     * garbage at us so we validate before persisting.
     */
    fun validate(): Result<Unit> = runCatching {
        require(serverUrl.startsWith("https://")) { "u must be an https:// URL" }
        require(token.matches(TOKEN_RE)) {
            "t must be 32–256 chars of [A-Za-z0-9._-]"
        }
        require(pinSha256Hex.matches(PIN_RE)) {
            "pin must be 64 hex chars (SHA-256 of server cert SPKI)"
        }
        require(expiresAtIso.isNotBlank()) { "exp must be a non-empty ISO-8601 instant" }
    }

    companion object {
        private val TOKEN_RE = Regex("[A-Za-z0-9._-]{32,256}")
        private val PIN_RE = Regex("[0-9a-fA-F]{64}")

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Decode the QR's raw payload string into a [QrPayload]. */
        fun parse(raw: String): Result<QrPayload> = runCatching {
            JSON.decodeFromString(serializer(), raw).also { it.validate().getOrThrow() }
        }
    }
}
