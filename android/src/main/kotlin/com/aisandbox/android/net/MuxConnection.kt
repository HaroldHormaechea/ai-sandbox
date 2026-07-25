package com.aisandbox.android.net

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * UC-100 — the **single** multiplexed WebSocket to `/v1/mux` (subprotocol
 * `ai-sandbox.mux.v1`). Replaces the four legacy per-purpose sockets
 * ([StreamClient], [ConversationClient], and the two [SessionEventsClient]
 * consumers) — collapsing them removes the delete→create reconnect storm that
 * tripped the server's per-IP TLS rate limiter (AC1).
 *
 * <p>Owns **exactly one** [ReconnectController] (AC6). A maintain-loop keeps the
 * socket up; on any drop it re-dials with the shared back-off and **re-subscribes
 * to precisely the pre-drop subscription set, deduped**. Channels are opened /
 * closed with `subscribe`/`unsubscribe` control frames — no TCP open/close (AC4).
 *
 * <p>Close-code routing (disjoint):
 * <ul>
 *   <li>**4401** → [NetworkEvent.CertRevoked] (the single mapping site now) →
 *       [State.Revoked], loop stops.</li>
 *   <li>**4426** → [NetworkEvent.ServerUpgradeRequired] → [State.UpgradeRequired],
 *       loop stops (matched-version hard cut).</li>
 * </ul>
 */
class MuxConnection(
    private val http: AiSandboxHttpClient,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Parsed control-channel frames (subscribed / unsubscribed / sub-error / welcome / error). */
    private val _control = MutableSharedFlow<Control>(replay = 0, extraBufferCapacity = 64)
    val control: SharedFlow<Control> = _control.asSharedFlow()

    // Per-(channel,sessionId) inbound payload flows, created lazily.
    private val textFlows = ConcurrentHashMap<String, MutableSharedFlow<String>>()
    private val binaryFlows = ConcurrentHashMap<Int, MutableSharedFlow<ByteArray>>()

    // Authoritative subscription set — re-asserted (deduped) on every reconnect.
    private val subscriptions: MutableSet<ChannelKey> = ConcurrentHashMap.newKeySet()

    // Per-subscription outbound seq counters.
    private val seqByChannel = ConcurrentHashMap<String, AtomicLong>()

    private val reconnect = ReconnectController()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var intentionalClose = false
    @Volatile private var maintainJob: Job? = null

    // Per-attempt signals.
    @Volatile private var openSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var closeSignal: CompletableDeferred<StopReason>? = null

    /** Start the maintain-loop (idempotent). */
    fun start() {
        if (maintainJob?.isActive == true) return
        maintainJob = scope.launch { maintain() }
    }

    private suspend fun maintain() {
        while (scope.isActive && !intentionalClose) {
            _state.value = State.Connecting
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<StopReason>()
            openSignal = opened
            closeSignal = closed

            val request = Request.Builder()
                .url("${wsBase()}/v1/mux")
                .header("Sec-WebSocket-Protocol", PROTOCOL_HEADER)
                .build()
            ws = http.client.newWebSocket(request, Listener())

            val ok = try {
                opened.await()
            } catch (_: Exception) {
                false
            }
            if (ok) {
                _state.value = State.Open
                reconnect.reset()
                sendControlRaw(MuxEnvelope.controlPayload("hello"))
                // Re-assert the whole subscription set (deduped by the server) — AC6.
                for (key in subscriptions.toList()) {
                    sendControlRaw(MuxEnvelope.controlPayload("subscribe", key.channel, key.sessionId))
                }
            }

            val reason = try {
                closed.await()
            } catch (_: Exception) {
                StopReason.DROPPED
            }
            ws = null
            when (reason) {
                StopReason.INTENTIONAL -> return
                StopReason.REVOKED -> {
                    _state.value = State.Revoked
                    return
                }
                StopReason.UPGRADE_REQUIRED -> {
                    _state.value = State.UpgradeRequired
                    return
                }
                StopReason.DROPPED, StopReason.OPEN_FAILED -> {
                    if (intentionalClose) return
                    _state.value = State.Disconnected
                    delay(reconnect.nextDelayMs())
                }
            }
        }
    }

    // ──────────────────────── subscription lifecycle ────────────────────────

    /** Open a logical channel (idempotent — the set dedupes; the server re-acks a live channel). */
    fun subscribe(channel: String, sessionId: Int?) {
        val key = ChannelKey(channel, sessionId)
        subscriptions.add(key)
        if (_state.value is State.Open) {
            sendControlRaw(MuxEnvelope.controlPayload("subscribe", channel, sessionId))
        }
    }

    /** Close a logical channel (idempotent — unsubscribing an absent channel is a no-op). */
    fun unsubscribe(channel: String, sessionId: Int?) {
        val key = ChannelKey(channel, sessionId)
        subscriptions.remove(key)
        if (_state.value is State.Open) {
            sendControlRaw(MuxEnvelope.controlPayload("unsubscribe", channel, sessionId))
        }
    }

    // ──────────────────────── outbound data ────────────────────────

    fun sendText(channel: String, sessionId: Int?, payloadJson: String): Boolean {
        val seq = seqFor(channel, sessionId).getAndIncrement()
        return ws?.send(MuxEnvelope.encodeText(channel, sessionId, seq, payloadJson)) ?: false
    }

    fun sendStreamBinary(sessionId: Int, bytes: ByteArray): Boolean {
        val seq = seqFor(MuxEnvelope.CHANNEL_STREAM, sessionId).getAndIncrement()
        return ws?.send(MuxEnvelope.encodeBinary(sessionId, seq, bytes).toByteString()) ?: false
    }

    private fun sendControlRaw(payloadJson: String): Boolean {
        val seq = seqFor(MuxEnvelope.CHANNEL_CONTROL, null).getAndIncrement()
        return ws?.send(MuxEnvelope.encodeText(MuxEnvelope.CHANNEL_CONTROL, null, seq, payloadJson)) ?: false
    }

    private fun seqFor(channel: String, sessionId: Int?): AtomicLong =
        seqByChannel.computeIfAbsent(keyStr(channel, sessionId)) { AtomicLong(0) }

    // ──────────────────────── inbound flows for adapters ────────────────────────

    fun textFrames(channel: String, sessionId: Int?): SharedFlow<String> =
        textFlows.computeIfAbsent(keyStr(channel, sessionId)) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 256)
        }.asSharedFlow()

    fun binaryFrames(sessionId: Int): SharedFlow<ByteArray> =
        binaryFlows.computeIfAbsent(sessionId) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }.asSharedFlow()

    // ──────────────────────── teardown ────────────────────────

    fun close(reason: String = "client-close") {
        intentionalClose = true
        subscriptions.clear()
        ws?.close(NORMAL_CLOSE_CODE, reason)
        ws?.cancel()
        ws = null
        closeSignal?.complete(StopReason.INTENTIONAL)
        _state.value = State.Disconnected
    }

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
            Log.i(TAG, "mux WS open: protocol=${response.header("Sec-WebSocket-Protocol")}")
            openSignal?.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                MuxEnvelope.decodeText(text)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "mux WS text decode failed: ${e.message}")
                return
            }
            when (frame.channel) {
                MuxEnvelope.CHANNEL_CONTROL -> _control.tryEmit(Control.parse(frame))
                else -> {
                    textFlows[keyStr(frame.channel, frame.sessionId)]?.tryEmit(frame.payloadJson)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = try {
                MuxEnvelope.decodeBinary(bytes.toByteArray())
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "mux WS binary decode failed: ${e.message}")
                return
            }
            if (frame.channelByte == MuxEnvelope.BYTE_STREAM) {
                binaryFlows[frame.sessionId]?.tryEmit(frame.data)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "mux WS closed: code=$code reason=$reason")
            openSignal?.complete(false)
            closeSignal?.complete(stopFor(code))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (intentionalClose) {
                openSignal?.complete(false)
                closeSignal?.complete(StopReason.INTENTIONAL)
                return
            }
            Log.w(TAG, "mux WS failure: ${t.javaClass.simpleName}: ${t.message}")
            openSignal?.complete(false)
            closeSignal?.complete(StopReason.DROPPED)
        }
    }

    private fun stopFor(code: Int): StopReason =
        when (code) {
            REVOKED_CLOSE_CODE -> {
                NetworkEvents.tryEmit(NetworkEvent.CertRevoked)
                StopReason.REVOKED
            }
            UPGRADE_REQUIRED_CLOSE_CODE -> {
                NetworkEvents.tryEmit(NetworkEvent.ServerUpgradeRequired)
                StopReason.UPGRADE_REQUIRED
            }
            else -> StopReason.DROPPED
        }

    private fun keyStr(channel: String, sessionId: Int?): String =
        if (sessionId == null) channel else "$channel:$sessionId"

    /** A tracked subscription. */
    private data class ChannelKey(val channel: String, val sessionId: Int?)

    private enum class StopReason { INTENTIONAL, DROPPED, OPEN_FAILED, REVOKED, UPGRADE_REQUIRED }

    /** Connection state surfaced to the manager + adapters. */
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State
        data object Disconnected : State
        /** 4401 — server revoked our cert. */
        data object Revoked : State
        /** 4426 — protocol version mismatch (hard cut); route to the update-required screen. */
        data object UpgradeRequired : State
    }

    /** A parsed control-channel frame. [code] is populated for `sub-error`. */
    data class Control(
        val type: String,
        val channel: String?,
        val sessionId: Int?,
        val code: String?,
        val detail: String?,
    ) {
        companion object {
            fun parse(frame: MuxEnvelope.TextFrame): Control {
                // Re-decode the payload for the control-specific fields.
                val p = frame.payloadJson
                fun field(name: String): String? {
                    val m = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(p)
                    return m?.groupValues?.get(1)
                }
                fun intField(name: String): Int? {
                    val m = Regex("\"$name\"\\s*:\\s*(\\d+)").find(p)
                    return m?.groupValues?.get(1)?.toIntOrNull()
                }
                return Control(
                    type = frame.type ?: field("type") ?: "",
                    channel = field("channel"),
                    sessionId = frame.sessionId ?: intField("sessionId"),
                    code = field("code"),
                    detail = field("detail"),
                )
            }
        }
    }

    companion object {
        private const val TAG = "MuxConnection"

        /** UC-100 protocol version exchanged in hello/welcome + GET /v1/capabilities. */
        const val PROTOCOL = "mux.v1"

        /** Mandatory subprotocol header for the /v1/mux upgrade. */
        const val PROTOCOL_HEADER = "ai-sandbox.mux.v1"

        const val NORMAL_CLOSE_CODE = 1000
        const val REVOKED_CLOSE_CODE = 4401
        const val UPGRADE_REQUIRED_CLOSE_CODE = 4426
    }
}
