package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * UC04 AC7 — hex-pin → OkHttp `sha256/<base64>` pin format conversion.
 *
 * <p>The on-disk format is operator-friendly lowercase hex (matches
 * `openssl x509 ... -fingerprint -sha256` output without the colons);
 * OkHttp's [okhttp3.CertificatePinner] wants `sha256/<base64>`. Converting
 * at the call site keeps the persisted profile readable.
 */
class ServerProfileTest {

    @Test
    fun `lowercase hex pin converts to canonical OkHttp sha256 base64`() {
        // 64 hex chars = 32 bytes. SHA-256 of an empty input is the
        // well-known constant e3b0c4...855 — pin against that so the
        // expected base64 (`47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=`)
        // is reproducible.
        val pin = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val profile = ServerProfile(
            serverUrl = "https://example.com:12410",
            pinSha256Hex = pin,
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )

        val okhttpPin = profile.toOkHttpPin()

        // Format: sha256/<base64 of the raw 32 bytes>.
        assertThat(okhttpPin).isEqualTo("sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
    }

    @Test
    fun `mixed-case hex pin converts identically to lowercase`() {
        val lower = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val upper = lower.uppercase()
        val mixed = "E3b0C44298Fc1C149afbf4c8996fb92427ae41e4649B934Ca495991B7852B855"

        val pLower = ServerProfile("https://e", lower, "x", 0L)
        val pUpper = ServerProfile("https://e", upper, "x", 0L)
        val pMixed = ServerProfile("https://e", mixed, "x", 0L)

        assertThat(pLower.toOkHttpPin()).isEqualTo(pUpper.toOkHttpPin())
        assertThat(pLower.toOkHttpPin()).isEqualTo(pMixed.toOkHttpPin())
    }

    @Test
    fun `odd-length hex pin throws`() {
        val odd = "abc" // 3 chars
        val profile = ServerProfile("https://e", odd, "x", 0L)
        assertThatThrownBy { profile.toOkHttpPin() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("even hex length")
    }

    @Test
    fun `non-hex pin throws`() {
        val bad = "z".repeat(64)
        val profile = ServerProfile("https://e", bad, "x", 0L)
        assertThatThrownBy { profile.toOkHttpPin() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("hex")
    }

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
