package com.aisandbox.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * UC-67 — REST client for the per-session MCP management endpoints
 * (`GET /v1/sessions/{n}/mcp`, `POST /v1/sessions/{n}/mcp/{name}/{action}`).
 * Mirrors [ModelsApi]: wire DTOs match the server's
 * [com.aisandbox.server.api.dto.ApiDtos.McpServerSummary] /
 * [com.aisandbox.server.api.dto.ApiDtos.McpActionResult] field-for-field, so the
 * OpenAPI spec is authoritative on both sides.
 *
 * <p>Per the [ModelsApi] / [EnrollmentClient] precedent, the private
 * [mapResponse] / [parseProblem] helpers are duplicated here rather than shared,
 * so the clients stay independently evolvable.
 *
 * <p>HTTP-layer errors come back as the shared sealed [ApiResult] so the caller
 * can render an error state without exception-catching; network-layer failures
 * (connection drops, pin mismatch) bubble up as [Throwable], mapped by the
 * calling ViewModel.
 */
class McpApi(private val http: AiSandboxHttpClient) {

    private val client get() = http.client
    private val base get() = http.baseUrl

    /** List the MCP servers configured for session [n] (AC3/AC4). Empty list = no MCP servers (AC7). */
    suspend fun list(n: Int): ApiResult<List<McpServerInfo>> = withContext(Dispatchers.IO) {
        val url = "$base/v1/sessions/$n/mcp".toHttpUrl()
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            // The controller does `List<…>` → Jackson → a bare JSON array.
            mapResponse(resp) { body ->
                JSON.decodeFromString(ListSerializer(McpServerInfo.serializer()), body)
            }
        }
    }

    /**
     * Drive a control action against one MCP server (AC5/AC6). [action] is one of
     * `login` / `reconnect` / `refresh` (the server pins this set). The server
     * name is carried as an encoded path segment so a name with reserved chars
     * cannot break out of the path.
     */
    suspend fun operate(n: Int, name: String, action: String): ApiResult<McpActionResult> =
        withContext(Dispatchers.IO) {
            val url = "$base/v1/sessions/$n/mcp".toHttpUrl().newBuilder()
                .addPathSegment(name)
                .addPathSegment(action)
                .build()
            val req = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { resp ->
                mapResponse(resp) { body ->
                    JSON.decodeFromString(McpActionResult.serializer(), body)
                }
            }
        }

    /**
     * UC-82 — register a new MCP server for session [n] (AC1). The request body is the
     * transport-tagged [McpAddRequest]; the server validates per-transport and returns
     * an [McpActionResult] carrying the new server's post-add state on 201, or a
     * problem+json (400 malformed / 409 duplicate / 500 add-failed) mapped to
     * [ApiResult.HttpFailure].
     */
    suspend fun add(n: Int, body: McpAddRequest): ApiResult<McpActionResult> = withContext(Dispatchers.IO) {
        val url = "$base/v1/sessions/$n/mcp".toHttpUrl()
        val json = JSON.encodeToString(McpAddRequest.serializer(), body)
        val req = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            mapResponse(resp) { responseBody ->
                JSON.decodeFromString(McpActionResult.serializer(), responseBody)
            }
        }
    }

    /**
     * UC-82 — deregister MCP server [name] from session [n] (AC2). The name is carried
     * as an encoded path segment so a name with reserved chars cannot break out of the
     * path. Returns the server's [McpActionResult] (with the honest deregister +
     * reconcile-on-next-reload message) on 200, or a problem+json (404 / 500) mapped to
     * [ApiResult.HttpFailure].
     */
    suspend fun remove(n: Int, name: String): ApiResult<McpActionResult> = withContext(Dispatchers.IO) {
        val url = "$base/v1/sessions/$n/mcp".toHttpUrl().newBuilder()
            .addPathSegment(name)
            .build()
        val req = Request.Builder().url(url).delete().build()
        client.newCall(req).execute().use { resp ->
            mapResponse(resp) { responseBody ->
                JSON.decodeFromString(McpActionResult.serializer(), responseBody)
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
        // explicitNulls = false: omit null optional fields when encoding McpAddRequest, so a
        // stdio request carries no `url`/`headers` keys and an http/sse one carries no
        // `command`/`args`/`env` keys (decoding is unaffected — it only governs serialization).
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}

// ── Wire DTOs (mirror server/api/dto/ApiDtos) ────────────────────────────────

/**
 * UC-67 — mirrors {@code ApiDtos.McpServerSummary}. Field set MUST stay in sync
 * with the server record (the OpenAPI spec is the contract).
 *
 * @property name      MCP server identifier
 * @property transport stdio / http / sse / unknown (display hint)
 * @property state     connected / needs_auth / failed / pending / unknown
 * @property detail    raw connection detail (command or URL); display-only
 */
@Serializable
data class McpServerInfo(
    val name: String,
    val transport: String = "unknown",
    val state: String = "unknown",
    val detail: String = "",
)

/**
 * UC-67 — mirrors {@code ApiDtos.McpActionResult}: the outcome of a login /
 * reconnect / refresh action, with the server's post-action [state] and an
 * honest [message] (login only INITIATES the flow in the live session).
 */
@Serializable
data class McpActionResult(
    val name: String,
    val state: String = "unknown",
    val message: String = "",
)

/**
 * UC-82 — mirrors {@code ApiDtos.McpAddRequest}: the body of `POST /v1/sessions/{n}/mcp`.
 * Transport-dependent: a `stdio` server carries [command] (+ optional [args] / [env]); an
 * `http` / `sse` server carries [url] (+ optional [headers]). Null/empty optional fields are
 * omitted from the JSON (the encoder is configured with `explicitNulls = false`), so the
 * server sees exactly the transport-relevant fields. Field set MUST stay in sync with the
 * server record (the OpenAPI spec is the contract).
 */
@Serializable
data class McpAddRequest(
    val name: String,
    val transport: String,
    val command: String? = null,
    val args: List<String>? = null,
    val url: String? = null,
    val env: Map<String, String>? = null,
    val headers: List<String>? = null,
)
