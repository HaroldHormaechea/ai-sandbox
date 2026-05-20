package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 — persisted [ServerProfile] shape pinning.
 *
 * <p>UC10 deleted [ServerProfile.toOkHttpPin] (alongside the OkHttp
 * {@code CertificatePinner} path); the pin hex is now consumed by
 * [SpkiPinningTrustManager] via [HexCodec.hexToBytes]. The on-disk
 * format remains operator-friendly lowercase hex, matching
 * {@code com.aisandbox.server.pki.PemUtils.spkiFingerprintHex} on
 * the server side.
 *
 * <p>Hex-validation coverage moved to [HexCodecTest], which exercises
 * the production code path the production now actually uses. The
 * tests that survive here pin invariants intrinsic to the
 * [ServerProfile] data class itself — primarily serialization shape.
 */
class ServerProfileTest {

    @Test
    fun `serialization round trip preserves all four fields`() {
        // JSON shape pinning — DataStore-backed profile store serializes
        // ServerProfile to JSON; the field order must stay stable so
        // ProfileStore reads can ignore unrelated extra keys later.
        val original = ServerProfile(
            serverUrl = "https://example.com:12410",
            pinSha256Hex = "a".repeat(64),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 1_700_000_000_000L,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(ServerProfile.serializer(), original)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(ServerProfile.serializer(), json)

        assertThat(decoded).isEqualTo(original)
        assertThat(json).contains("\"serverUrl\":")
        assertThat(json).contains("\"pinSha256Hex\":")
        assertThat(json).contains("\"clientCertCn\":")
        assertThat(json).contains("\"clientCertExpiresAtMs\":")
    }
}
