package com.aisandbox.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
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

    /**
     * UC-62 — create (or focus) the singleton server host-shell session via
     * `POST /v1/sessions/server-ssh` (no body). The server enforces the
     * singleton, so a second call returns the SAME row (200) rather than a
     * second session. Returns the row as a [SessionSummary] (it carries
     * `type == "server-ssh"` and the reserved id [SERVER_SSH_N]). Transport
     * failures bubble as a [Throwable] exactly like [spawn].
     */
    suspend fun createServerSsh(): ApiResult<SessionSummary> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base/v1/sessions/server-ssh")
            .post(ByteArray(0).toRequestBody(null))
            .build()
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

        /**
         * UC-62 — reserved id for the singleton server host-shell session.
         * MUST equal the server's `SpecialSessions.SERVER_SSH_N` (0): it is a
         * sentinel outside the real `ai-sandbox-<N≥1>` Docker numbering, so it
         * reuses the existing `GET`/`DELETE /v1/sessions/{n}` + `{n}/stream`
         * plumbing without colliding with a real session.
         */
        const val SERVER_SSH_N: Int = 0

        /** UC-62 — the `type` value the server tags the host-shell row with. */
        const val TYPE_SERVER_SSH: String = "server-ssh"

        /** UC-62 — the `type` value for an ordinary Claude/Docker session row. */
        const val TYPE_CLAUDE: String = "claude"
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
    /**
     * UC-48 — true when Claude is actively working in the session's main pane (a
     * turn is mid-flight); false when idle / awaiting an answer / non-running.
     * The server defaults it false and hysteresis-debounces it; the default here
     * keeps decode lenient against older server payloads. The row animates a
     * working spinner while true AND state=="running" (double-gated). Covers REST
     * and the UC-32 push (which reuses this DTO).
     */
    val working: Boolean = false,
    /**
     * UC-49 — true when the session's main pane is showing an AskUserQuestion
     * awaiting an answer (single or multi-question); false when idle / working /
     * non-running. Mutually exclusive with [working] server-side. The server
     * defaults it false; the default here keeps decode lenient against older
     * payloads. The row shows a "?" badge while true AND state=="running"
     * (double-gated) and suppresses the working spinner (pending takes precedence).
     * Covers REST and the UC-32 push (which reuses this DTO).
     */
    val pendingQuestion: Boolean = false,
    /**
     * UC-69 — the first pending question's text (the body of the local push
     * notification posted when this session enters the pending-question state;
     * the first question when several are raised together). Null when no question
     * is up, the session is non-running, or the body was not parseable. The server
     * omits the field when null (@JsonInclude(NON_NULL)); the default keeps decode
     * lenient against older payloads. Covers REST and the UC-32 push (which reuses
     * this DTO). The app-wide pending-question watcher
     * ([com.aisandbox.android.notifications.PendingQuestionService]) builds the
     * notification body from it; the in-app row does not render it.
     */
    val pendingQuestionText: String? = null,
    /**
     * UC-61 — client-only marker for an optimistic placeholder row inserted by
     * [com.aisandbox.android.ui.screens.SessionsCoordinator.spawn] before the
     * server has assigned the real session number `n`. `@Transient` (the
     * kotlinx.serialization one, NOT `kotlin.jvm.Transient`) keeps it OFF THE
     * WIRE — it is never encoded into `SpawnRequest`/list payloads — and the
     * `= false` default makes decode of server payloads unaffected
     * (`ignoreUnknownKeys` + this default). Every row sourced from the server
     * (REST or the UC-32 push feed) therefore decodes as `optimistic = false`;
     * only the locally-built placeholder sets it true, so the merge/rollback
     * logic can distinguish the guess from authoritative rows.
     */
    @Transient
    val optimistic: Boolean = false,
    /**
     * UC-62 — session kind: `claude` (an ordinary sandbox/Docker session) or
     * `server-ssh` (the single always-on server host-shell row, reserved id
     * [SessionsApi.SERVER_SSH_N]). Defaults to `claude` so the field decodes
     * leniently against older server payloads and so every existing row is an
     * ordinary session. The list view pins a `server-ssh` row to the top,
     * badges it "SERVER SSH SESSION", offers only Remove, and routes its taps
     * to the terminal (never the Claude conversation view). Covers REST and the
     * UC-32 push (which reuses this DTO).
     */
    val type: String = "claude",
) {
    /** UC-62 — true when this row is the server host-shell session. */
    val isServerSsh: Boolean get() = type == SessionsApi.TYPE_SERVER_SSH
}

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
