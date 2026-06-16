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

    /**
     * UC-88 — set by [close] BEFORE it cancels the socket, so the [onFailure]
     * okhttp fires for a forced [WebSocket.cancel] is recognised as an expected,
     * intentional teardown and stays quiescent (no state flip → no spurious
     * reconnect / wrong UC-72 dial phase). @Volatile because [close] runs on the
     * caller thread while the [Listener] callbacks run on okhttp's.
     */
    @Volatile
    private var intentionalClose = false

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
     * Close the WebSocket cleanly with code 1000, then force-drop it. Idempotent
     * — once closed the reference is nulled; reconnects must build a fresh
     * [SessionEventsClient].
     *
     * <p>UC-88 — a graceful [WebSocket.close] alone lets a half-open / in-flight
     * socket linger 30–60 s (ping-timeout + read-timeout + okhttp's
     * `cancelAfterCloseMillis`). Under sustained half-open network + repeated
     * chat→list, those abandoned sockets pile up faster than they drain and
     * blow past the server's per-fingerprint feed cap — wedging the feed. So
     * after the graceful close we call [WebSocket.cancel] to tear the socket
     * down in ~0 ms. [intentionalClose] is flagged FIRST so the resulting
     * [onFailure] stays quiescent. cancel() is safe before the upgrade completes
     * (cancel-before-open) — okhttp aborts the in-flight connect.
     */
    fun close(reason: String = "client-close") {
        intentionalClose = true
        ws?.close(NORMAL_CLOSE_CODE, reason)
        ws?.cancel()
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
            if (intentionalClose) {
                // UC-88 — we cancelled this socket on purpose ([close] flagged it);
                // okhttp reports a forced cancel as onFailure (not onClosed). Stay
                // QUIESCENT: [close] already set Disconnected and nulled ws, so do
                // NOT touch _state here — otherwise the controller's loop could read
                // this as a spontaneous drop and schedule a reconnect / drive the
                // UC-72 dial yellow→red. Just unblock a connect() still awaiting open.
                if (!openedSignal.isCompleted) openedSignal.complete(Unit)
                return
            }
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

        /**
         * UC-88 — the server closes the feed with Spring's
         * `CloseStatus.SERVICE_OVERLOAD` (RFC 6455 1013) when this client's
         * per-fingerprint subscription cap is exceeded (the wedge symptom of this
         * UC). The controller surfaces this distinctly so we back off audibly
         * rather than silently hammering a server that is refusing us.
         */
        const val SERVICE_OVERLOAD_CLOSE_CODE = 1013

        /** UC-88 — server `CloseStatus.POLICY_VIOLATION` (1008) — auth/identity refusal. */
        const val POLICY_VIOLATION_CLOSE_CODE = 1008
    }
}
