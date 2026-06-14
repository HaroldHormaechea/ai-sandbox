package com.aisandbox.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * UC-66 — REST client for the mTLS-gated model catalogue endpoint
 * (`GET /v1/models`). Mirrors [SessionsApi]: wire DTOs match the server's
 * [com.aisandbox.server.api.dto.ApiDtos.ModelSummary] field-for-field, so the
 * OpenAPI spec is authoritative on both sides.
 *
 * <p>Per the [EnrollmentClient] precedent, the private [mapResponse] /
 * [parseProblem] helpers are duplicated here rather than shared with
 * [SessionsApi] — the two clients stay independently evolvable and
 * [SessionsApi] is left untouched.
 *
 * <p>HTTP-layer errors come back as the shared sealed [ApiResult] so the
 * caller can render an error state without exception-catching; network-layer
 * failures (connection drops, pin mismatch) bubble up as [Throwable], mapped
 * by the calling ViewModel.
 */
class ModelsApi(private val http: AiSandboxHttpClient) {

    private val client get() = http.client
    private val base get() = http.baseUrl

    /** Fetch the server's advertised model catalogue (config-backed, AC3). */
    suspend fun list(): ApiResult<List<ModelInfo>> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/models").get().build()
        client.newCall(req).execute().use { resp ->
            // The controller does `ResponseEntity.ok(List<…>)` → Jackson → a bare
            // JSON array, so decode it directly with the list serializer.
            mapResponse(resp) { body ->
                JSON.decodeFromString(ListSerializer(ModelInfo.serializer()), body)
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

// ── Wire DTO (mirrors server/api/dto/ApiDtos.ModelSummary) ───────────────────

/**
 * UC-66 — mirrors {@code com.aisandbox.server.api.dto.ApiDtos.ModelSummary}.
 * Field set MUST stay in sync — adding a field on the server side requires a
 * corresponding @Serializable property here.
 *
 * @property id    model id/alias sent to Claude Code via `/model <id>`
 * @property label human-readable name shown in the model picker
 */
@Serializable
data class ModelInfo(
    val id: String,
    val label: String = "",
)
