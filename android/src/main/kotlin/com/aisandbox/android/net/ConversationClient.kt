package com.aisandbox.android.net

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * UC-37 — WebSocket client for one structured-conversation channel
 * (`wss://host/v1/sessions/{n}/conversation`, subprotocol
 * `ai-sandbox.conv.v1`). The JSON-only sibling of [StreamClient]: it carries
 * only **text** frames (server→client conversation events and client→server
 * composer/answer/target/interrupt frames), never binary PTY bytes.
 *
 * <p>Mirrors [StreamClient]'s lifecycle + revoke handling (AC22 reconnect is
 * owned by an external [ReconnectController]; cert-revoke close 4401 emits
 * [NetworkEvent.CertRevoked]). The caller parses the raw JSON on [incoming].
 */
class ConversationClient(
    private val http: AiSandboxHttpClient,
    private val sessionN: Int,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Server→client conversation frames (raw JSON text); the controller parses. */
    private val _incoming = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 256)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var ws: WebSocket? = null
    private val openedSignal = CompletableDeferred<Unit>()

    val streamId: String = "conv-$sessionN-${System.currentTimeMillis()}"

    /** Open the WebSocket; suspends until the upgrade completes or fails. */
    suspend fun connect() {
        _state.value = State.Connecting
        val request = Request.Builder()
            .url("${wsBase()}/v1/sessions/$sessionN/conversation")
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        ws = http.client.newWebSocket(request, Listener())
        openedSignal.await()
    }

    /** Send a JSON text frame (composer-input / answer / select-target / interrupt / enumerate / close). */
    fun sendText(json: String): Boolean = ws?.send(json) ?: false

    fun sendComposer(text: String): Boolean =
        sendText("""{"type":"composer-input","text":"${jsonEscape(text)}"}""")

    fun sendEnumerate(): Boolean = sendText("""{"type":"enumerate-targets"}""")

    fun sendSelectTarget(targetId: String): Boolean =
        sendText("""{"type":"select-target","targetId":"${jsonEscape(targetId)}"}""")

    fun sendInterrupt(): Boolean = sendText("""{"type":"interrupt"}""")

    /**
     * Send a structured answer. [selections] are option indices; [freeText] is
     * the always-present "Other" value (empty when unused).
     */
    fun sendAnswer(questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String): Boolean {
        val sel = selections.joinToString(",")
        return sendText(
            """{"type":"answer","questionUuid":"${jsonEscape(questionUuid)}",""" +
                """"questionIndex":$questionIndex,"selections":[$sel],"freeText":"${jsonEscape(freeText)}"}""",
        )
    }

    fun close(reason: String = "client-close") {
        ws?.send("""{"type":"close","reason":"${jsonEscape(reason)}"}""")
        ws?.close(NORMAL_CLOSE_CODE, reason)
        ws = null
        _state.value = State.Disconnected(reason = reason)
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun wsBase(): String {
        val base = http.baseUrl
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "conv WS open: protocol=${response.header("Sec-WebSocket-Protocol")}")
            _state.value = State.Open
            openedSignal.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _incoming.tryEmit(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The conversation channel is text-only; ignore any stray binary frame.
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "conv WS closed: code=$code reason=$reason")
            if (code == StreamClient.REVOKED_CLOSE_CODE) {
                _state.value = State.Revoked
                NetworkEvents.tryEmit(NetworkEvent.CertRevoked)
            } else {
                _state.value = State.Disconnected(reason = "$code:$reason")
            }
            ws = null
            if (!openedSignal.isCompleted) openedSignal.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "conv WS failure: ${t.javaClass.simpleName}: ${t.message}")
            _state.value = State.Disconnected(reason = t.message ?: t.javaClass.simpleName)
            ws = null
            if (!openedSignal.isCompleted) openedSignal.complete(Unit)
        }
    }

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State
        data class Disconnected(val reason: String) : State
        data object Revoked : State
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    companion object {
        private const val TAG = "ConversationClient"

        /** UC-37 — mandatory conversation subprotocol (distinct from the binary stream's `ai-sandbox.v1`). */
        const val SUBPROTOCOL = "ai-sandbox.conv.v1"

        const val NORMAL_CLOSE_CODE = 1000
    }
}
