package com.aisandbox.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
            mapResponse(resp) { body ->
                // The server returns a BARE JSON array (the controller does
                // `ResponseEntity.ok(List<…>)` → Jackson → `[…]`). Some
                // legacy payloads wrapped it in a `{ "sessions": [...] }`
                // envelope, so we tolerate both. Parse to a JsonElement
                // first and branch on the ACTUAL shape.
                //
                // The previous envelope-FIRST ordering
                // (`decodeFromString(Envelope.serializer(), it).sessions ?: <bare list>`)
                // was dead: decoding a bare array with the envelope (object)
                // serializer throws a SerializationException BEFORE the `?:`
                // fallback can run — kotlinx's structure decoder requires '{'
                // and never silently returns null for an array. So `list()`
                // always failed with decode_error against the real (bare-array)
                // server body — the BUG 1 root, upstream of the ViewModel fix.
                // Empirically verified against kotlinx-serialization with the
                // same isLenient/ignoreUnknownKeys config used here.
                when (val element = JSON.parseToJsonElement(body)) {
                    is JsonArray ->
                        JSON.decodeFromJsonElement(ListSerializer(SessionSummary.serializer()), element)
                    is JsonObject ->
                        JSON.decodeFromJsonElement(SessionsListEnvelope.serializer(), element).sessions.orEmpty()
                    else ->
                        throw SerializationException(
                            "GET /v1/sessions body is neither a JSON array nor an object: $element",
                        )
                }
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

    /**
     * UC-46 — drive a Docker-lifecycle action on a session via
     * {@code POST /v1/sessions/{n}/{action}} (no body). 204 → [ApiResult.Success];
     * a 404/409/500 problem+json comes back as [ApiResult.HttpFailure] (the
     * caller surfaces `session_not_found` / `session_state_conflict` /
     * `internal_error` codes). Transport failures bubble as a [Throwable]
     * exactly like [delete].
     */
    suspend fun lifecycle(n: Int, action: LifecycleAction): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/v1/sessions/$n/${action.token}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
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
    /**
     * running | starting | provisioning | terminating | stopped
     * (UC04 AC37 + UC-27 + UC-28).
     * provisioning = container up but still installing spawn-time toolchains
     * (shown as "installing…"); terminating (UC-28) = teardown in flight
     * (destructive-red "terminating" pill, blocks re-delete). Decoded
     * leniently — `ignoreUnknownKeys` and the StatusPill fallback tolerate any
     * future token.
     */
    val state: String = "running",
    val uptimeSec: Long = 0L,
    val activeStreams: Int = 0,
    val startedAt: String? = null,
    /**
     * UC-47 — the Claude conversation name for the session's main pane, or null
     * when none is known (idle / no active conversation / non-running). The server
     * omits the field when null (@JsonInclude(NON_NULL)); the default keeps decode
     * lenient. The row shows it as the primary status line, falling back to
     * [tmuxTitle] when null/blank. Covers REST and the UC-32 push (which reuses
     * this DTO).
     */
    val conversationName: String? = null,
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

/**
 * UC-46 — client mirror of the server's
 * {@code com.aisandbox.server.sessions.dto.LifecycleAction}. The
 * [token] is the path segment for {@code POST /v1/sessions/{n}/{action}};
 * [isValidFrom] MUST stay byte-identical to the server's transition matrix
 * so the row context menu greys out exactly the actions the server would
 * reject with 409 `session_state_conflict`. The server remains the final
 * arbiter — this mirror only avoids guaranteed-409 round-trips.
 *
 * <table>
 *   <tr><th>Action</th><th>Valid from</th></tr>
 *   <tr><td>START</td><td>stopped</td></tr>
 *   <tr><td>STOP</td><td>running, provisioning, paused</td></tr>
 *   <tr><td>PAUSE</td><td>running</td></tr>
 *   <tr><td>UNPAUSE</td><td>paused</td></tr>
 * </table>
 */
enum class LifecycleAction(val token: String) {
    STOP("stop"),
    START("start"),
    PAUSE("pause"),
    UNPAUSE("unpause");

    /** Whether this action is a legal transition from [state]. */
    fun isValidFrom(state: String): Boolean = when (this) {
        START -> state == "stopped"
        STOP -> state == "running" || state == "provisioning" || state == "paused"
        PAUSE -> state == "running"
        UNPAUSE -> state == "paused"
    }
}
