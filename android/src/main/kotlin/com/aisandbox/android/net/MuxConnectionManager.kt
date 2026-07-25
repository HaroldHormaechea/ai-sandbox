package com.aisandbox.android.net

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * UC-100 — app-scoped owner of the **single** [MuxConnection] (held by
 * `AppContainer`). It maps the process's one server profile to one live socket,
 * owns the **authoritative subscription set** across connection rebuilds, and
 * exposes **stable** relay flows so adapters/controllers keep receiving frames
 * across a profile switch (which rebuilds the underlying [MuxConnection]).
 *
 * <p>Within a single profile the [MuxConnection] persists across socket drops
 * (its own maintain-loop + [ReconnectController]), so a reconnect is invisible
 * here. Only a profile change (re-enrollment / wipe) tears the connection down
 * and rebuilds it, at which point the retained subscription set + relays are
 * re-piped into the fresh connection.
 */
class MuxConnectionManager(
    private val profileStore: ServerProfileStore,
    private val httpClientFactory: (ServerProfile) -> AiSandboxHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Idle)
    val state: StateFlow<MuxConnection.State> = _state.asStateFlow()

    private val _control = MutableSharedFlow<MuxConnection.Control>(replay = 0, extraBufferCapacity = 64)
    val control: SharedFlow<MuxConnection.Control> = _control.asSharedFlow()

    // Stable relays keyed by channel; piped from whichever MuxConnection is current.
    private val textRelays = ConcurrentHashMap<String, MutableSharedFlow<String>>()
    private val binaryRelays = ConcurrentHashMap<Int, MutableSharedFlow<ByteArray>>()

    // Authoritative, REFERENCE-COUNTED subscription set (survives profile rebuilds).
    // Reference counting matters for the `events` channel: both SessionsViewModel
    // and PendingQuestionService subscribe to it over the one socket, so a control
    // `unsubscribe` must be sent only when the LAST consumer of a channel leaves —
    // otherwise one screen's teardown would starve the other's feed.
    private val refcounts: MutableMap<ChannelKey, Int> = HashMap()

    @Volatile private var connection: MuxConnection? = null
    private val pipeJobs = mutableListOf<Job>()
    private val pipedText = ConcurrentHashMap.newKeySet<String>()
    private val pipedBinary = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()

    init {
        scope.launch {
            profileStore.profile.distinctUntilChanged().collect { profile -> rebuild(profile) }
        }
    }

    private fun rebuild(profile: ServerProfile?) {
        synchronized(lock) {
            pipeJobs.forEach { it.cancel() }
            pipeJobs.clear()
            pipedText.clear()
            pipedBinary.clear()
            connection?.close("profile-switch")
            connection = null
            _state.value = MuxConnection.State.Idle
            if (profile == null) return

            val conn = MuxConnection(httpClientFactory(profile), scope)
            connection = conn
            pipeJobs += scope.launch { conn.state.collect { _state.value = it } }
            pipeJobs += scope.launch { conn.control.collect { _control.emit(it) } }
            // Re-pipe every relay a consumer has already asked for.
            for ((key, relay) in textRelays) {
                val (ch, sid) = splitKey(key)
                pipeText(conn, key, ch, sid, relay)
            }
            for ((sid, relay) in binaryRelays) {
                pipeBinary(conn, sid, relay)
            }
            conn.start()
            // Re-assert every live subscription (the connection also re-subscribes on its own reconnects).
            for (k in refcounts.keys) conn.subscribe(k.channel, k.sessionId)
        }
    }

    private fun pipeText(conn: MuxConnection, key: String, ch: String, sid: Int?, relay: MutableSharedFlow<String>) {
        if (pipedText.add(key)) {
            pipeJobs += scope.launch { conn.textFrames(ch, sid).collect { relay.emit(it) } }
        }
    }

    private fun pipeBinary(conn: MuxConnection, sid: Int, relay: MutableSharedFlow<ByteArray>) {
        if (pipedBinary.add(sid)) {
            pipeJobs += scope.launch { conn.binaryFrames(sid).collect { relay.emit(it) } }
        }
    }

    // ──────────────────────── public API for adapters ────────────────────────

    fun subscribe(channel: String, sessionId: Int?) {
        val k = ChannelKey(channel, sessionId)
        val firstConsumer: Boolean
        synchronized(lock) {
            val n = refcounts.getOrDefault(k, 0)
            refcounts[k] = n + 1
            firstConsumer = n == 0
        }
        // Only open the channel on the shared socket for the FIRST consumer; a
        // second consumer just shares the already-live channel + its relay flow.
        if (firstConsumer) connection?.subscribe(channel, sessionId)
    }

    fun unsubscribe(channel: String, sessionId: Int?) {
        val k = ChannelKey(channel, sessionId)
        val lastConsumer: Boolean
        synchronized(lock) {
            val n = refcounts.getOrDefault(k, 0)
            when {
                n <= 1 -> {
                    refcounts.remove(k)
                    lastConsumer = n == 1
                }
                else -> {
                    refcounts[k] = n - 1
                    lastConsumer = false
                }
            }
        }
        // Only close the channel when the LAST consumer leaves (the events feed
        // is shared by two consumers).
        if (lastConsumer) connection?.unsubscribe(channel, sessionId)
    }

    fun sendText(channel: String, sessionId: Int?, payloadJson: String): Boolean =
        connection?.sendText(channel, sessionId, payloadJson) ?: false

    fun sendStreamBinary(sessionId: Int, bytes: ByteArray): Boolean =
        connection?.sendStreamBinary(sessionId, bytes) ?: false

    fun textFrames(channel: String, sessionId: Int?): SharedFlow<String> {
        val key = keyStr(channel, sessionId)
        val relay = textRelays.computeIfAbsent(key) { MutableSharedFlow(replay = 0, extraBufferCapacity = 256) }
        synchronized(lock) { connection?.let { pipeText(it, key, channel, sessionId, relay) } }
        return relay.asSharedFlow()
    }

    fun binaryFrames(sessionId: Int): SharedFlow<ByteArray> {
        val relay = binaryRelays.computeIfAbsent(sessionId) { MutableSharedFlow(replay = 0, extraBufferCapacity = 64) }
        synchronized(lock) { connection?.let { pipeBinary(it, sessionId, relay) } }
        return relay.asSharedFlow()
    }

    private fun keyStr(channel: String, sessionId: Int?): String =
        if (sessionId == null) channel else "$channel:$sessionId"

    private fun splitKey(key: String): Pair<String, Int?> {
        val idx = key.indexOf(':')
        return if (idx < 0) key to null else key.substring(0, idx) to key.substring(idx + 1).toIntOrNull()
    }

    private data class ChannelKey(val channel: String, val sessionId: Int?)
}
