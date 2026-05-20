package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for the **single** mTLS-exempt endpoint —
 * {@code POST /v1/enrollment} — used during UC04-1 onboarding.
 *
 * <p>The server-side proposal calls out (§ B2) that this is the only
 * unauthenticated path; the request must NOT carry a client cert. We
 * build a dedicated OkHttp instance with:
 *
 * <ul>
 *   <li><b>SPKI pin in the trust manager</b> — UC10's
 *       [SpkiPinningTrustManager] does the pin check inside
 *       {@code checkServerTrusted}, bypassing OkHttp's
 *       {@code CertificatePinner} + {@code BasicCertificateChainCleaner}
 *       path entirely. Pre-UC10 we wired a lenient trust manager and
 *       relied on {@code CertificatePinner}; that tripped the OkHttp
 *       5.3.2 chain-cleaning trap (empty {@code getAcceptedIssuers()}
 *       → cleaner throws → peerCertificates swallowed → unconditional
 *       "Certificate pinning failure!"). UC10 replaces the pair with
 *       the single TM and removes the {@code <bootstrap>} sentinel
 *       the trap forced us into.</li>
 *   <li><b>No client KeyManager</b> — the Android device has no
 *       identity yet; presenting one would trip the server's
 *       MtlsEnforcementFilter bypass logic on `/v1/enrollment` and
 *       wedge the bootstrap.</li>
 *   <li><b>Default HostnameVerifier</b> — Android's
 *       {@code OkHostnameVerifier} enforces SAN matching, so a QR
 *       pointing at a host the cert doesn't claim still fails the
 *       handshake (now routed to [NetworkEvent.HostnameMismatch], not
 *       [NetworkEvent.PinMismatch]).</li>
 * </ul>
 *
 * <p>Success returns the raw PKCS#12 byte-array which the caller hands
 * to [KeyStoreIdentityManager.importPkcs12]. Failures map to a sealed
 * [Outcome] so the onboarding screen can render the AC35 error codes.
 *
 * <p>UC10 § AC4 — exception dispatch is structural (by class) via
 * [TlsFailureTranslation.translate]: SPKI pin mismatch surfaces as
 * [NetworkEvent.PinMismatch] carrying the REAL observed cert SPKI hex
 * (extracted from the structured [SpkiPinningTrustManager]
 * {@code CertificateException}); hostname / SAN mismatch surfaces as
 * [NetworkEvent.HostnameMismatch]; any other TLS / I/O failure surfaces
 * as [NetworkEvent.HandshakeError].
 */
class EnrollmentClient(private val payload: QrPayload) {

    suspend fun redeem(): Outcome = withContext(Dispatchers.IO) {
        val client = buildClient()
        val body = buildBody().toRequestBody(JSON_MEDIA_TYPE)
        val expectedHost = hostFromUrl(payload.serverUrl)
        val request = Request.Builder()
            .url(payload.serverUrl.trimEnd('/') + "/v1/enrollment")
            .post(body)
            .header("Accept", "application/octet-stream, application/problem+json")
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    201 -> {
                        val bytes = resp.body?.bytes() ?: return@use Outcome.Failure(
                            code = "empty_body",
                            message = "Server returned 201 with empty body."
                        )
                        Outcome.Success(bytes)
                    }
                    401 -> parseProblemJson(resp, default = "enrollment_token_invalid")
                    413 -> parseProblemJson(resp, default = "payload_too_large")
                    429 -> parseProblemJson(resp, default = "enrollment_rate_limited")
                    else -> Outcome.Failure(
                        code = "unexpected_status_${resp.code}",
                        message = resp.message.ifBlank { "HTTP ${resp.code}" }
                    )
                }
            }
        } catch (t: Throwable) {
            // UC10 § AC4 — three-arm dispatch via TlsFailureTranslation.
            // SpkiPinningTrustManager throws a structured
            // CertificateException on pin mismatch; the translator walks
            // the cause chain to lift the real observed SPKI hex.
            // Hostname-only failures (default OkHostnameVerifier fires
            // AFTER our TM accepts) emit HostnameMismatch. Everything
            // else falls to HandshakeError. The legacy `<bootstrap>`
            // sentinel is gone — observed pin is always real now.
            val event = TlsFailureTranslation.translate(
                throwable = t,
                expectedPinHex = payload.pinSha256Hex,
                expectedHost = expectedHost,
            )
            if (event != null) {
                NetworkEvents.tryEmit(event)
            }
            Outcome.Failure(
                code = failureCodeFor(event),
                message = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun buildClient(): OkHttpClient {
        val trustManager = SpkiPinningTrustManager(HexCodec.hexToBytes(payload.pinSha256Hex))
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(
            // Bootstrap has no identity — null KeyManager array.
            null,
            arrayOf(trustManager),
            SecureRandom(),
        )
        return OkHttpClient.Builder()
            // SpkiPinningTrustManager handles the pin check inside
            // checkServerTrusted — no CertificatePinner, no chain cleaner
            // on the verification path. Default HostnameVerifier is left
            // untouched so SAN matching still fires.
            .sslSocketFactory(ctx.socketFactory, trustManager)
            .build()
    }

    private fun buildBody(): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("token", payload.token) })

    private fun parseProblemJson(resp: okhttp3.Response, default: String): Outcome.Failure {
        val text = resp.body?.string().orEmpty()
        return try {
            val obj = Json.parseToJsonElement(text).let {
                if (it is JsonObject) it else JsonObject(emptyMap())
            }
            val code = (obj["code"]?.toString()?.trim('"'))?.takeIf { it.isNotBlank() } ?: default
            val detail = obj["detail"]?.toString()?.trim('"') ?: resp.message
            Outcome.Failure(code = code, message = detail)
        } catch (_: Throwable) {
            Outcome.Failure(code = default, message = resp.message.ifBlank { "HTTP ${resp.code}" })
        }
    }

    private fun hostFromUrl(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')

    /**
     * Map the structured [NetworkEvent] (if any) the translator emitted
     * into the legacy [Outcome.Failure.code] strings the onboarding
     * screen renders. AC35 error-code copy is unchanged for the
     * pre-existing `pin_mismatch` / `io_error` paths; the new
     * `hostname_mismatch` / `handshake_error` codes are surfaced for
     * UC10's expanded failure-mode split (AC4).
     */
    private fun failureCodeFor(event: NetworkEvent?): String = when (event) {
        is NetworkEvent.PinMismatch -> "pin_mismatch"
        is NetworkEvent.HostnameMismatch -> "hostname_mismatch"
        is NetworkEvent.HandshakeError -> "handshake_error"
        else -> "io_error"
    }

    /** Result of [redeem] — Success carries the PKCS#12 blob; Failure carries the code + detail. */
    sealed interface Outcome {
        data class Success(val pkcs12: ByteArray) : Outcome
        data class Failure(val code: String, val message: String) : Outcome
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
