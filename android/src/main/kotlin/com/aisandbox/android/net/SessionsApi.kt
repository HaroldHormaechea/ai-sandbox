package com.aisandbox.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for the mTLS-gated session endpoints.
 *
 * <p>Wire DTOs match the server's [com.aisandbox.server.api.dto.ApiDtos]
 * verbatim — field-for-field — so the OpenAPI spec is authoritative on
 * both sides. Changes to the server DTO MUST be mirrored here.
 *
 * <p>HTTP-layer errors are returned as a sealed [ApiResult] so the
 * caller can react to the AC37 `session_not_running` code without
 * exception-catching. Network-layer failures (connection drops, pin
 * mismatch) bubble up as [Throwable] — the calling ViewModel maps them
 * via the global [NetworkEvents] flow.
 */
class SessionsApi(private val http: AiSandboxHttpClient) {

    private val client get() = http.client
    private val base get() = http.baseUrl

    suspend fun list(): ApiResult<List<SessionSummary>> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/sessions").get().build()
        client.newCall(req).execute().use { resp ->
            mapResponse(resp) {
                JSON.decodeFromString(SessionsListEnvelope.serializer(), it).sessions
                    ?: JSON.decodeFromString<List<SessionSummary>>(
                        kotlinx.serialization.builtins.ListSerializer(SessionSummary.serializer()),
                        it,
                    )
            }
        }
    }

    suspend fun spawn(label: String?): ApiResult<SessionSummary> = withContext(Dispatchers.IO) {
        val body = JSON.encodeToString(
            SpawnRequest.serializer(),
            SpawnRequest(label = label, workspaceMode = null, claudeConfigMode = null),
        ).toRequestBody(JSON_MEDIA_TYPE)
        val req = Request.Builder().url("$base/v1/sessions").post(body).build()
        client.newCall(req).execute().use { resp ->
            mapResponse(resp) { JSON.decodeFromString(SessionSummary.serializer(), it) }
        }
    }

    suspend fun delete(n: Int, force: Boolean): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val url = if (force) "$base/v1/sessions/$n?force=true" else "$base/v1/sessions/$n"
        val req = Request.Builder().url(url).delete().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 204) ApiResult.Success(Unit) else mapResponse(resp) { Unit }
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ── Wire DTOs (mirror server/api/dto/ApiDtos.java) ───────────────────────────

/**
 * Mirrors {@code com.aisandbox.server.api.dto.ApiDtos.SessionSummary}.
 * Field set MUST stay in sync — adding a field on the server side
 * requires a corresponding @Serializable property here.
 */
@Serializable
data class SessionSummary(
    val n: Int,
    val label: String = "",
    val tmuxTitle: String = "",
    /** running | starting | stopped (UC04 AC37 extended set). */
    val state: String = "running",
    val uptimeSec: Long = 0L,
    val activeStreams: Int = 0,
    val startedAt: String? = null,
)

@Serializable
data class SpawnRequest(
    val label: String? = null,
    val workspaceMode: String? = null,
    val claudeConfigMode: String? = null,
)

/**
 * Some legacy server payloads wrapped the list in a `sessions` object;
 * tolerate both shapes during decode.
 */
@Serializable
internal data class SessionsListEnvelope(
    @SerialName("sessions") val sessions: List<SessionSummary>? = null,
)

/** Sealed result for the typed REST calls. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class HttpFailure(val status: Int, val code: String, val detail: String) : ApiResult<Nothing>
}
