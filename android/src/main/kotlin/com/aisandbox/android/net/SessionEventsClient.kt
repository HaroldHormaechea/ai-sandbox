package com.aisandbox.android.net

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * UC-32 — WebSocket client for the live sessions-list status feed
 * ({@code wss://host/v1/sessions/events}, subprotocol {@code ai-sandbox.v1}).
 *
 * <p>Mirrors [StreamClient] (the per-session terminal stream client) but is
 * one-way server→client and far simpler: it sends nothing after the handshake
 * and only decodes inbound TEXT frames into [SessionEventMessage] values.
 *
 * <p>Two output flows:
 * <ul>
 *   <li>[incoming] — every decoded [SessionEventMessage] (Snapshot / Delta).
 *       A hot [SharedFlow] so the controller never misses a frame between
 *       collectors.</li>
 *   <li>[state] — connection state. The [SessionEventsController] reconnect loop
 *       reads this to decide when to back off.</li>
 * </ul>
 *
 * <p>Cert-revocation parity with [StreamClient]: a server close with code 4401
 * ({@link #REVOKED_CLOSE_CODE}) transitions to [State.Revoked] and emits
 * [NetworkEvent.CertRevoked] on the global [NetworkEvents] bus (the root
 * composable routes to the cert-revoked dialog). Any other close / failure →
 * [State.Disconnected], and the controller's [ReconnectController] handles the
 * back-off + the AC5 REST fallback.
 */
class SessionEventsClient(
    private val http: AiSandboxHttpClient,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<SessionEventMessage>(replay = 0, extraBufferCapacity = 32)
    val incoming: SharedFlow<SessionEventMessage> = _incoming.asSharedFlow()

    private var ws: WebSocket? = null
    private val openedSignal = CompletableDeferred<Unit>()

    /** Unique-per-feed id for log correlation. */
    val feedId: String = "sessions-events-${System.currentTimeMillis()}"

    /**
     * Open the WebSocket. Suspends until the upgrade completes or fails. On
     * failure [state] transitions to [State.Disconnected]; the caller owns the
     * reconnect loop via [ReconnectController].
     */
    suspend fun connect() {
        _state.value = State.Connecting
        val request = Request.Builder()
            .url("${wsBase()}/v1/sessions/events")
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()
        ws = http.client.newWebSocket(request, Listener())
        openedSignal.await()
    }

    /**
     * Close the WebSocket cleanly with code 1000. Idempotent — once closed the
     * reference is nulled; reconnects must build a fresh [SessionEventsClient].
     */
    fun close(reason: String = "client-close") {
        ws?.close(NORMAL_CLOSE_CODE, reason)
        ws = null
        _state.value = State.Disconnected(reason = reason)
    }

    // ── Internals ────────────────────────────────────────────────────────────

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
            Log.i(TAG, "events WS open: protocol=${response.header("Sec-WebSocket-Protocol")}")
            _state.value = State.Open
            openedSignal.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSON.decodeFromString(SessionEventMessage.serializer(), text) }
                .getOrElse { t ->
                    Log.w(TAG, "events WS frame decode failed: ${t.message} — ${text.take(160)}")
                    return
                }
            _incoming.tryEmit(msg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "events WS closing: code=$code reason=$reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "events WS closed: code=$code reason=$reason")
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
            Log.w(TAG, "events WS failure: ${t.javaClass.simpleName}: ${t.message}")
            _state.value = State.Disconnected(reason = t.message ?: t.javaClass.simpleName)
            ws = null
            if (!openedSignal.isCompleted) {
                openedSignal.complete(Unit)
            }
        }
    }

    /** Connection state surfaced to the controller. */
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State
        data class Disconnected(val reason: String) : State

        /** Server tore the feed down with code 4401 ("revoked"). */
        data object Revoked : State
    }

    companion object {
        private const val TAG = "SessionEventsClient"

        /** Lenient decoder — tolerate unknown/future server fields (AC parity with SessionsApi). */
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Shared mandatory WebSocket subprotocol (same handshake as the terminal stream). */
        const val SUBPROTOCOL = "ai-sandbox.v1"

        /** Server emits this close code when the client's cert is revoked (UC04 § B2). */
        const val REVOKED_CLOSE_CODE = 4401

        /** RFC 6455 normal closure. */
        const val NORMAL_CLOSE_CODE = 1000
    }
}
