package com.aisandbox.android.net

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
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
import okio.ByteString.Companion.encodeUtf8

/**
 * WebSocket client for one terminal stream
 * ({@code wss://host/v1/sessions/{n}/stream}, subprotocol
 * {@code ai-sandbox.v1}). UC04 § A3 / AC12 / AC18 / AC25 / AC26.
 *
 * <p>Two output flows the UI / foreground service consume:
 *
 * <ul>
 *   <li>{@link #incoming} — every binary frame received from the server
 *       (PTY stdout). Hot {@link SharedFlow} so a backgrounded UI doesn't
 *       miss anything between detach and re-attach.</li>
 *   <li>{@link #state} — connection state ({@link State}). The
 *       foreground-notification body and the terminal toolbar status dot
 *       read from this.</li>
 * </ul>
 *
 * <p>Inputs the UI provides via [sendStdin] (binary PTY input) and
 * [sendControl] (JSON text frame — resize, mouse, close).
 *
 * <p>Cert-revocation handling (AC25/AC26): when the server tears the WS
 * down with close code {@link #REVOKED_CLOSE_CODE} (4401), this client
 * emits {@link NetworkEvent#CertRevoked} on the global
 * [NetworkEvents] bus and transitions to {@link State.Revoked}. The
 * root composable subscribes to the bus and routes to the cert-revoked
 * dialog (UC04-7).
 *
 * <p>Reconnect / give-up logic is owned by an external
 * [ReconnectController] so it can be unit-tested in isolation; the
 * client itself just reports the state transitions.
 */
class StreamClient(
    private val http: AiSandboxHttpClient,
    private val sessionN: Int,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    /**
     * UC-21 — server→client JSON text frames (the agent-switcher protocol:
     * {@code targets} / {@code target-selected} / {@code error}). Emitted raw;
     * the caller parses. Hot {@link SharedFlow} so the controller doesn't miss
     * frames between collectors.
     */
    private val _controlIncoming = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 16)
    val controlIncoming: SharedFlow<String> = _controlIncoming.asSharedFlow()

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

    /** Unique-per-stream id used in NetworkEvent.* surfacing. */
    val streamId: String = "stream-$sessionN-${System.currentTimeMillis()}"

    /**
     * Open the WebSocket. Suspends until the upgrade either completes or
     * fails. On failure the [state] transitions to [State.Disconnected];
     * the caller is responsible for the reconnect loop via
     * [ReconnectController].
     */
    suspend fun connect() {
        _state.value = State.Connecting
        val request = Request.Builder()
            .url("${wsBase()}/v1/sessions/$sessionN/stream")
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        ws = http.client.newWebSocket(request, Listener())
        // Wait for the onOpen callback or the listener to put us in
        // Disconnected via onFailure. CompletableDeferred is single-shot.
        openedSignal.await()
    }

    /** Send a binary frame (PTY stdin) — bytes pass through verbatim. */
    fun sendStdin(bytes: ByteArray): Boolean =
        ws?.send(ByteString.of(*bytes)) ?: false

    /**
     * Send a control text frame (JSON). Caller is responsible for the
     * JSON shape per `STREAM_PROTOCOL.md`.
     */
    fun sendControl(textJson: String): Boolean = ws?.send(textJson) ?: false

    /** Convenience for AC18 resize frame. */
    fun sendResize(cols: Int, rows: Int): Boolean =
        sendControl("""{"type":"resize","cols":$cols,"rows":$rows}""")

    /** UC-21 — ask the server to enumerate stream targets (main + agent panes). */
    fun sendEnumerate(): Boolean = sendControl("""{"type":"enumerate-targets"}""")

    /** UC-21 — ask the server to switch this stream to {@code targetId} mid-stream. */
    fun sendSelectTarget(targetId: String): Boolean =
        sendControl("""{"type":"select-target","targetId":"${jsonEscape(targetId)}"}""")

    /**
     * Close the WebSocket cleanly with code 1000, then force-drop it.
     * Idempotent — once closed the WS reference is nulled and re-connects
     * must build a fresh [StreamClient].
     *
     * <p>UC-88 — a graceful [WebSocket.close] alone lets a half-open /
     * in-flight socket linger 30–60 s (ping-timeout + read-timeout +
     * okhttp's `cancelAfterCloseMillis`); under repeated reconnect/relaunch
     * those orphaned sockets pile up. So after the graceful close we call
     * [WebSocket.cancel] to tear the socket down in ~0 ms. [intentionalClose]
     * is flagged FIRST so the resulting [onFailure] stays quiescent.
     * cancel() is safe before the upgrade completes (cancel-before-open).
     */
    fun close(reason: String = "client-close") {
        intentionalClose = true
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
            Log.i(TAG, "WS open: protocol=${response.header("Sec-WebSocket-Protocol")}")
            _state.value = State.Open
            openedSignal.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            _incoming.tryEmit(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // UC-21 — server JSON control frames (targets / target-selected /
            // error). Surface raw on controlIncoming; the controller parses.
            Log.i(TAG, "WS text frame: ${text.take(200)}")
            _controlIncoming.tryEmit(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closing: code=$code reason=$reason")
            // Acknowledge the server's close frame so the TCP teardown
            // is graceful on both sides.
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: code=$code reason=$reason")
            if (code == REVOKED_CLOSE_CODE) {
                _state.value = State.Revoked
                NetworkEvents.tryEmit(NetworkEvent.CertRevoked)
            } else {
                _state.value = State.Disconnected(reason = "$code:$reason")
            }
            ws = null
            if (!openedSignal.isCompleted) {
                openedSignal.complete(Unit)
            }
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
            Log.w(TAG, "WS failure: ${t.javaClass.simpleName}: ${t.message}", t)
            _state.value = State.Disconnected(reason = t.message ?: t.javaClass.simpleName)
            ws = null
            if (!openedSignal.isCompleted) {
                openedSignal.complete(Unit)
            }
        }
    }

    /** Connection state surfaced to the UI / foreground notification. */
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State
        data class Disconnected(val reason: String) : State
        /** AC25/AC26 — server tore the WS down with code 4401 ("revoked"). */
        data object Revoked : State
    }

    @Suppress("unused")
    private fun bytesOf(text: String): ByteString = text.encodeUtf8()

    /** Minimal JSON string escaping for a target id embedded in a control frame. */
    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "StreamClient"

        /** UC04 — mandatory WebSocket subprotocol. */
        const val SUBPROTOCOL = "ai-sandbox.v1"

        /**
         * UC04 § B2 — server side emits this close code when the
         * allowlist watcher revokes the client's cert. The Android
         * client surfaces the cert-revoked dialog (UC04-7) on this
         * close code.
         */
        const val REVOKED_CLOSE_CODE = 4401

        /** RFC 6455 normal closure. */
        const val NORMAL_CLOSE_CODE = 1000

        /** UC-88 — server `CloseStatus.SERVICE_OVERLOAD` (1013): per-client cap refusal. */
        const val SERVICE_OVERLOAD_CLOSE_CODE = 1013

        /** UC-88 — server `CloseStatus.POLICY_VIOLATION` (1008): auth/identity refusal. */
        const val POLICY_VIOLATION_CLOSE_CODE = 1008
    }
}
