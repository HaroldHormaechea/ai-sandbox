package com.aisandbox.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * UC-84 — REST client for the mTLS-gated server self-update endpoints
 * ({@code GET /v1/server/update/check}, {@code POST /v1/server/update/apply})
 * plus a {@code GET /v1/healthz} probe used to detect the server coming back
 * after the update restart.
 *
 * <p>Mirrors [SessionsApi]'s shape exactly: a sealed [ApiResult] for HTTP-layer
 * outcomes (so the caller can branch on the {@code update_*} problem codes) and
 * a [Throwable] bubble for transport-layer failures (which the calling
 * coordinator treats as "server still restarting" while polling). Wire DTOs
 * match the server's {@code ApiDtos.UpdateCheckResponse / UpdateApplyResponse}
 * field-for-field — the OpenAPI spec is authoritative on both sides.
 */
class ServerUpdateApi(private val http: AiSandboxHttpClient) {

    private val client get() = http.client
    private val base get() = http.baseUrl

    /** GET /v1/server/update/check — current vs latest server-v* release. */
    suspend fun check(): ApiResult<UpdateCheckResponse> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/server/update/check").get().build()
        client.newCall(req).execute().use { resp ->
            mapResponse(resp) { JSON.decodeFromString(UpdateCheckResponse.serializer(), it) }
        }
    }

    /** POST /v1/server/update/apply (no body) — emits the parameter-free trigger. */
    suspend fun apply(): ApiResult<UpdateApplyResponse> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/v1/server/update/apply")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(req).execute().use { resp ->
            mapResponse(resp) { JSON.decodeFromString(UpdateApplyResponse.serializer(), it) }
        }
    }

    /**
     * GET /v1/healthz — liveness probe used while polling for the server to come
     * back after the update restart. [ApiResult.Success] iff the server answered
     * 2xx (it is back AND healthy); a 503 comes back as [ApiResult.HttpFailure]
     * (back but not yet healthy — keep polling); a transport throw bubbles (still
     * down — keep polling). The body is ignored; only the status matters here.
     */
    suspend fun healthz(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/healthz").get().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code in 200..299) {
                ApiResult.Success(Unit)
            } else {
                parseProblem(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    private inline fun <T> mapResponse(
        resp: okhttp3.Response,
        deserialize: (String) -> T,
    ): ApiResult<T> {
        val body = resp.body?.string().orEmpty()
        return when (resp.code) {
            in 200..299 -> try {
                ApiResult.Success(deserialize(body))
            } catch (t: Throwable) {
                ApiResult.HttpFailure(
                    status = resp.code,
                    code = "decode_error",
                    detail = t.message ?: "Cannot decode response body",
                )
            }
            else -> parseProblem(resp.code, body)
        }
    }

    private fun parseProblem(status: Int, body: String): ApiResult.HttpFailure {
        return try {
            val obj = JSON.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            val code = obj?.get("code")?.toString()?.trim('"') ?: "http_$status"
            val detail = obj?.get("detail")?.toString()?.trim('"') ?: ""
            ApiResult.HttpFailure(status = status, code = code, detail = detail)
        } catch (_: Throwable) {
            ApiResult.HttpFailure(status = status, code = "http_$status", detail = body)
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

// ── Wire DTOs (mirror server/api/dto/ApiDtos.java) ───────────────────────────

/**
 * Mirrors {@code com.aisandbox.server.api.dto.ApiDtos.UpdateCheckResponse}.
 * Nullable fields are omitted by the server when null (@JsonInclude(NON_NULL));
 * the defaults keep decode lenient.
 */
@Serializable
data class UpdateCheckResponse(
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val releaseHtmlUrl: String? = null,
    val debAssetUrl: String? = null,
)

/** Mirrors {@code com.aisandbox.server.api.dto.ApiDtos.UpdateApplyResponse}. */
@Serializable
data class UpdateApplyResponse(
    val accepted: Boolean = false,
    val targetVersion: String? = null,
)
