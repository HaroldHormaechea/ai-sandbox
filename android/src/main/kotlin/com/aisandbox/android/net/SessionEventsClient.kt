package com.aisandbox.android.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/**
 * UC-100 — thin `events`-channel adapter over the single [MuxConnection]
 * (via [MuxConnectionManager]). The global sessions-list feed is now a cheap
 * subscription on the one shared socket — both consumers ([SessionsViewModel]
 * and [com.aisandbox.android.notifications.PendingQuestionService]) subscribe
 * over it (two feeds, one socket). Preserves the previous public surface
 * ([incoming] decoded [SessionEventMessage], [state]) so the controller barely
 * changes; it no longer owns a socket or reconnect loop.
 */
class SessionEventsClient(
    private val manager: MuxConnectionManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<State> = manager.state
        .map { mapState(it) }
        .stateIn(scope, SharingStarted.Eagerly, State.Idle)

    val incoming: SharedFlow<SessionEventMessage> = manager.textFrames(MuxEnvelope.CHANNEL_EVENTS, null)
        .mapNotNull { payload ->
            runCatching { JSON.decodeFromString(SessionEventMessage.serializer(), payload) }
                .getOrElse { t ->
                    Log.w(TAG, "events payload decode failed: ${t.message} — ${payload.take(160)}")
                    null
                }
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    val feedId: String = "sessions-events-${System.currentTimeMillis()}"

    /** Subscribe the global `events` channel over the shared connection. */
    suspend fun connect() {
        manager.subscribe(MuxEnvelope.CHANNEL_EVENTS, null)
    }

    /** Unsubscribe the `events` channel (the socket stays up for other channels / the other events consumer). */
    fun close(reason: String = "client-close") {
        manager.unsubscribe(MuxEnvelope.CHANNEL_EVENTS, null)
        scope.cancel()
    }

    private fun mapState(s: MuxConnection.State): State = when (s) {
        MuxConnection.State.Idle -> State.Idle
        MuxConnection.State.Connecting -> State.Connecting
        MuxConnection.State.Open -> State.Open
        MuxConnection.State.Disconnected -> State.Disconnected("disconnected")
        MuxConnection.State.Revoked -> State.Revoked
        MuxConnection.State.UpgradeRequired -> State.Disconnected("upgrade_required")
    }

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State
        data class Disconnected(val reason: String) : State
        data object Revoked : State
    }

    companion object {
        private const val TAG = "SessionEventsClient"

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Retained for source-compat; the mux subprotocol lives in [MuxConnection.PROTOCOL_HEADER]. */
        const val SUBPROTOCOL = "ai-sandbox.mux.v1"
        const val REVOKED_CLOSE_CODE = 4401
        const val NORMAL_CLOSE_CODE = 1000
        const val SERVICE_OVERLOAD_CLOSE_CODE = 1013
        const val POLICY_VIOLATION_CLOSE_CODE = 1008
    }
}
