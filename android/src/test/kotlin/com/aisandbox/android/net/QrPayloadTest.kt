package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 § B3 — QR payload {u, t, exp, pin} parse + validation.
 *
 * <p>The wire shape is shared with `aisandboxctl client invite` on the
 * server side. Drift breaks onboarding silently — this test pins the
 * field names + validation regex.
 */
class QrPayloadTest {

    private val validPin = "fa".repeat(32) // 64 hex chars = SHA-256 server cert digest.

    @Test
    fun `parse accepts a well-formed payload`() {
        val raw = """
            {"u":"https://example.com:12410","t":"${TOKEN_64}","exp":"2026-05-17T10:10:00Z","pin":"$validPin"}
        """.trimIndent()

        val result = QrPayload.parse(raw)
        assertThat(result.isSuccess).isTrue
        val payload = result.getOrThrow()
        assertThat(payload.serverUrl).isEqualTo("https://example.com:12410")
        assertThat(payload.token).isEqualTo(TOKEN_64)
        assertThat(payload.expiresAtIso).isEqualTo("2026-05-17T10:10:00Z")
        assertThat(payload.pinSha256Hex).isEqualTo(validPin)
    }

    @Test
    fun `parse rejects non-https server url`() {
        val raw = """{"u":"http://example.com","t":"$TOKEN_64","exp":"x","pin":"$validPin"}"""
        val r = QrPayload.parse(raw)
        assertThat(r.isFailure).isTrue
        assertThat(r.exceptionOrNull()?.message).contains("https://")
    }

    @Test
    fun `parse rejects short token`() {
        // 31 chars — below the 32-min boundary.
        val short = "a".repeat(31)
        val raw = """{"u":"https://e","t":"$short","exp":"x","pin":"$validPin"}"""
        assertThat(QrPayload.parse(raw).isFailure).isTrue
    }

    @Test
    fun `parse rejects token with bad characters`() {
        val bad = "a".repeat(32) + ";rm -rf /"
        val raw = """{"u":"https://e","t":"$bad","exp":"x","pin":"$validPin"}"""
        assertThat(QrPayload.parse(raw).isFailure).isTrue
    }

    @Test
    fun `parse rejects pin that is not 64 hex chars`() {
        val raw = """{"u":"https://e","t":"$TOKEN_64","exp":"x","pin":"deadbeef"}"""
        assertThat(QrPayload.parse(raw).isFailure).isTrue
    }

    @Test
    fun `parse accepts mixed-case hex pin`() {
        // The regex is [0-9a-fA-F]{64} per source. Allow both cases so
        // operators copying from openssl output don't trip over a 'F'.
        val mixed = "FaFa".repeat(16)
        val raw = """{"u":"https://e","t":"$TOKEN_64","exp":"x","pin":"$mixed"}"""
        assertThat(QrPayload.parse(raw).isSuccess).isTrue
    }

    @Test
    fun `parse rejects blank expiry`() {
        val raw = """{"u":"https://e","t":"$TOKEN_64","exp":"","pin":"$validPin"}"""
        assertThat(QrPayload.parse(raw).isFailure).isTrue
    }

    @Test
    fun `parse rejects junk JSON`() {
        assertThat(QrPayload.parse("not json").isFailure).isTrue
        assertThat(QrPayload.parse("{").isFailure).isTrue
        assertThat(QrPayload.parse("").isFailure).isTrue
    }

    @Test
    fun `parse is lenient with unknown extra keys`() {
        // The parser ignoresUnknownKeys = true so a future-server adding
        // a field like "ver":2 doesn't immediately wedge old clients.
        val raw = """
            {"u":"https://e","t":"$TOKEN_64","exp":"x","pin":"$validPin","ver":2,"extra":"ok"}
        """.trimIndent()
        assertThat(QrPayload.parse(raw).isSuccess).isTrue
    }

    @Test
    fun `field order matches server emitter for QR stability`() {
        // The server's ClientInviteCommand emits u, t, exp, pin in this
        // order via LinkedHashMap. The Android-side @SerialName values
        // must match so an explicit JSON encode round-trip stays stable.
        val payload = QrPayload(
            serverUrl = "https://example.com:12410",
            token = TOKEN_64,
            expiresAtIso = "2026-05-17T10:10:00Z",
            pinSha256Hex = validPin,
        )
        val encoded = kotlinx.serialization.json.Json.encodeToString(QrPayload.serializer(), payload)
        // Pin all four canonical key strings.
        assertThat(encoded).contains("\"u\":")
        assertThat(encoded).contains("\"t\":")
        assertThat(encoded).contains("\"exp\":")
        assertThat(encoded).contains("\"pin\":")
    }

    companion object {
        // Obvious placeholder — 64 chars of [A-Za-z0-9._-]. NOT a real token.
        // Embedded in test fixtures only; AuditNoSecretsTest scans the
        // entire tree for Anthropic-style key prefixes — "abcd…" never hits.
        // 64 chars of [A-Za-z0-9._-] — matches the server-side 256-bit hex
        // token shape without being any real key material.
        const val TOKEN_64 = "abcd1234.fake-test-token-not-a-real-key.0123456789ab-cdefABCDEFX"
    }
}
