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

    /**
     * UC-88 — set by [close] BEFORE it cancels the socket, so the [onFailure]
     * okhttp fires for a forced [WebSocket.cancel] is recognised as an expected,
     * intentional teardown and stays quiescent (no state flip → no spurious
     * reconnect / wrong UC-72 dial phase). @Volatile because [close] runs on the
     * caller thread while the [Listener] callbacks run on okhttp's.
     */
    @Volatile
    private var intentionalClose = false

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

    /** UC-41 (AC5) — request the full untruncated input + result for one tool call (server-local read, not injected). */
    fun sendFetchDetail(toolUseId: String, uuid: String): Boolean =
        sendText(
            """{"type":"fetch-detail","toolUseId":"${jsonEscape(toolUseId)}","uuid":"${jsonEscape(uuid)}"}""",
        )

    /** UC-79 (AC2) — request the next OLDER page of transcript (infinite scroll); server-local read, not injected. */
    fun sendLoadOlder(): Boolean = sendText("""{"type":"load-older"}""")

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

    /**
     * UC-43 — send a batched answer for a multi-question (N>1) `AskUserQuestion`
     * as ONE `answer-batch` frame. [items] is one entry per question (the caller
     * supplies them in `questionIndex` order); the server sorts by `questionIndex`
     * defensively and injects the whole sheet as one keystroke sequence.
     */
    fun sendAnswerBatch(questionUuid: String, items: List<com.aisandbox.android.conversation.AnswerItem>): Boolean {
        val answers = items.joinToString(",") { item ->
            val sel = item.selections.joinToString(",")
            """{"questionIndex":${item.questionIndex},"selections":[$sel],""" +
                """"freeText":"${jsonEscape(item.freeText)}"}"""
        }
        return sendText(
            """{"type":"answer-batch","questionUuid":"${jsonEscape(questionUuid)}","answers":[$answers]}""",
        )
    }

    /**
     * Close the conversation channel: enqueue the app-level `{"type":"close"}`
     * frame, then the WS close frame (code 1000), then force-drop the socket.
     *
     * <p>UC-88 — the order is `{"type":"close"}` → [WebSocket.close] → [WebSocket.cancel].
     * The cancel tears a half-open / in-flight socket down in ~0 ms instead of
     * letting it linger 30–60 s (ping/read timeouts + okhttp's
     * `cancelAfterCloseMillis`) and pile up across repeated relaunches.
     * [intentionalClose] is flagged FIRST so the resulting [onFailure] stays
     * quiescent. cancel() is safe before the upgrade completes.
     *
     * <p><b>The `{"type":"close"}` app frame is BEST-EFFORT, not load-bearing.</b>
     * On a half-open socket the immediate cancel() may discard the still-unwritten
     * frame, so the server then learns of the close via TCP RST rather than the
     * app goodbye — and that is FINE: the conversation handler's resource teardown
     * (transcript-tail close, [ActiveStreamRegistry] detach, audit) runs in its
     * `doFinally`, which fires on ANY socket termination (clean close, error, or
     * RST) just as promptly. Nothing on the server depends on receiving this app
     * frame (it merely asks the server to close with a NORMAL status). The
     * cap/wedge channel of UC-88 is the events feed, which sends no app-close frame
     * at all — so keeping this one best-effort is consistent and correct.
     */
    fun close(reason: String = "client-close") {
        intentionalClose = true
        ws?.send("""{"type":"close","reason":"${jsonEscape(reason)}"}""")
        ws?.close(NORMAL_CLOSE_CODE, reason)
        ws?.cancel()
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
            if (intentionalClose) {
                // UC-88 — forced cancel from [close]; okhttp reports it as onFailure.
                // Stay QUIESCENT (see SessionEventsClient.onFailure): [close] already
                // set Disconnected, so don't re-touch _state or the loop may treat
                // this as a spontaneous drop and reconnect / mis-drive the dial.
                if (!openedSignal.isCompleted) openedSignal.complete(Unit)
                return
            }
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
