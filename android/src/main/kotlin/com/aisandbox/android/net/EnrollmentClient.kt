package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CertificatePinner
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
 *   <li><b>Pinning ONLY</b> — pin the server cert against
 *       [QrPayload.pinSha256Hex] before sending the request, so a MITM
 *       can't intercept the token-redemption.</li>
 *   <li><b>No client KeyManager</b> — the Android device has no
 *       identity yet; presenting one would trip the server's
 *       MtlsEnforcementFilter bypass logic on `/v1/enrollment` and
 *       wedge the bootstrap.</li>
 * </ul>
 *
 * <p>Success returns the raw PKCS#12 byte-array which the caller hands
 * to [KeyStoreIdentityManager.importPkcs12]. Failures map to a sealed
 * [Outcome] so the onboarding screen can render the AC35 error codes.
 */
class EnrollmentClient(private val payload: QrPayload) {

    private val pin: String =
        "sha256/" + java.util.Base64.getEncoder().encodeToString(hexToBytes(payload.pinSha256Hex))

    suspend fun redeem(): Outcome = withContext(Dispatchers.IO) {
        val client = buildClient()
        val body = buildBody().toRequestBody(JSON_MEDIA_TYPE)
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
        } catch (mis: SSLPeerUnverifiedException) {
            // Pin mismatch on the bootstrap request itself — extremely
            // suspicious. Emit the network event so the UI routes
            // straight to ServerIdentityChangedScreen.
            //
            // UC09 AC6 — the `<bootstrap>` sentinel exists because there is
            // no observed pin to extract on the enrollment POST path: OkHttp
            // aborts the TLS handshake before exposing the peer's SPKI to us,
            // so we cannot fill in the real observed hash here.
            // `NetworkEvent.PinMismatch` still needs a non-null value to
            // render the dialog and route through the existing UI flow, so
            // the sentinel substitutes. Pre-v0.0.10 this string specifically
            // masked the UC09 algorithm bug — every enrollment hit this catch
            // (server emitted full-DER hash, OkHttp verified SPKI; the two
            // are never equal), and the UI said "pin mismatch" rather than
            // "your server is computing the wrong algorithm." UC09's v0.0.10
            // fix (SPKI on both sides) makes this path unreachable in the
            // happy case; it remains the correct response to a genuine MITM.
            NetworkEvents.tryEmit(
                NetworkEvent.PinMismatch(
                    expectedPinHex = payload.pinSha256Hex,
                    observedPinHex = "<bootstrap>"
                )
            )
            Outcome.Failure(code = "pin_mismatch", message = mis.message ?: "Server cert pin mismatch.")
        } catch (t: Throwable) {
            Outcome.Failure(code = "io_error", message = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildClient(): OkHttpClient {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(
            // Bootstrap has no identity — null KeyManager array.
            null,
            arrayOf(acceptAllTrustManager()),
            SecureRandom(),
        )
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, acceptAllTrustManager())
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(hostFromUrl(payload.serverUrl), pin)
                    .build()
            )
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

    private fun acceptAllTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun hostFromUrl(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
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
