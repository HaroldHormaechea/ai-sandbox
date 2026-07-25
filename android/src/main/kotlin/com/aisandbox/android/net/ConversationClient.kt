package com.aisandbox.android.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UC-100 — thin `conversation`-channel adapter over the single [MuxConnection]
 * (via [MuxConnectionManager]). Preserves the previous public surface
 * ([incoming] raw JSON payloads, [state], and every `send*`) so
 * [com.aisandbox.android.conversation.ConversationController] barely changes;
 * it no longer owns a socket or a reconnect loop. `connect()`/`close()` map to
 * `subscribe`/`unsubscribe` over the shared connection.
 *
 * <p>[incoming] carries the nested payload JSON of each `conversation`-channel
 * envelope — byte-identical to what the legacy per-endpoint socket delivered —
 * so the controller's existing parser is unchanged.
 */
class ConversationClient(
    private val manager: MuxConnectionManager,
    private val sessionN: Int,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<State> = manager.state
        .map { mapState(it) }
        .stateIn(scope, SharingStarted.Eagerly, State.Idle)

    /** Server→client conversation payloads (raw JSON text); the controller parses. */
    val incoming: SharedFlow<String> = manager.textFrames(MuxEnvelope.CHANNEL_CONVERSATION, sessionN)

    val streamId: String = "conv-$sessionN-${System.currentTimeMillis()}"

    /** Subscribe the `conversation` channel over the shared connection. */
    suspend fun connect() {
        manager.subscribe(MuxEnvelope.CHANNEL_CONVERSATION, sessionN)
    }

    fun sendText(json: String): Boolean = manager.sendText(MuxEnvelope.CHANNEL_CONVERSATION, sessionN, json)

    fun sendComposer(text: String): Boolean =
        sendText("""{"type":"composer-input","text":"${jsonEscape(text)}"}""")

    fun sendEnumerate(): Boolean = sendText("""{"type":"enumerate-targets"}""")

    fun sendSelectTarget(targetId: String): Boolean =
        sendText("""{"type":"select-target","targetId":"${jsonEscape(targetId)}"}""")

    fun sendInterrupt(): Boolean = sendText("""{"type":"interrupt"}""")

    fun sendFetchDetail(toolUseId: String, uuid: String): Boolean =
        sendText("""{"type":"fetch-detail","toolUseId":"${jsonEscape(toolUseId)}","uuid":"${jsonEscape(uuid)}"}""")

    fun sendLoadOlder(): Boolean = sendText("""{"type":"load-older"}""")

    fun sendResyncPending(): Boolean = sendText("""{"type":"resync-pending"}""")

    fun sendAnswer(questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String): Boolean {
        val sel = selections.joinToString(",")
        return sendText(
            """{"type":"answer","questionUuid":"${jsonEscape(questionUuid)}",""" +
                """"questionIndex":$questionIndex,"selections":[$sel],"freeText":"${jsonEscape(freeText)}"}""",
        )
    }

    fun sendAnswerBatch(questionUuid: String, items: List<com.aisandbox.android.conversation.AnswerItem>): Boolean {
        val answers = items.joinToString(",") { item ->
            val sel = item.selections.joinToString(",")
            """{"questionIndex":${item.questionIndex},"selections":[$sel],""" +
                """"freeText":"${jsonEscape(item.freeText)}"}"""
        }
        return sendText("""{"type":"answer-batch","questionUuid":"${jsonEscape(questionUuid)}","answers":[$answers]}""")
    }

    /**
     * Unsubscribe the `conversation` channel. The server tears down the tail in
     * its channel-session `close()` (the mux analogue of the legacy handler's
     * `doFinally`) — no app-level `{"type":"close"}` frame is needed.
     */
    fun close(reason: String = "client-close") {
        manager.unsubscribe(MuxEnvelope.CHANNEL_CONVERSATION, sessionN)
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

    private fun jsonEscape(s: String): String = MuxEnvelope.jsonEscape(s)

    companion object {
        /** Retained for source-compat; the mux subprotocol lives in [MuxConnection.PROTOCOL_HEADER]. */
        const val SUBPROTOCOL = "ai-sandbox.mux.v1"
        const val NORMAL_CLOSE_CODE = 1000
    }
}
