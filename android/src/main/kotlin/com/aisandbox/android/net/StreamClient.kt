package com.aisandbox.android.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * UC-100 — thin `stream`-channel adapter over the single [MuxConnection]
 * (via [MuxConnectionManager]). It no longer owns a socket or a reconnect loop;
 * `connect()`/`close()` are `subscribe`/`unsubscribe` over the shared
 * connection (no TCP open/close — AC1/AC4). The public surface
 * ([incoming]/[controlIncoming]/[state]/`send*`) is preserved so
 * [com.aisandbox.android.terminal.TerminalStreamController] barely changes.
 *
 * <p>`incoming` = the channel's binary PTY stdout; `controlIncoming` = the
 * channel's JSON text frames (targets / target-selected / error). Cert-revoke
 * (4401) and version mismatch (4426) are now handled once, centrally, by
 * [MuxConnection]; this adapter just reflects the mapped [State].
 */
class StreamClient(
    private val manager: MuxConnectionManager,
    private val sessionN: Int,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<State> = manager.state
        .map { mapState(it) }
        .stateIn(scope, SharingStarted.Eagerly, State.Idle)

    val incoming: SharedFlow<ByteArray> = manager.binaryFrames(sessionN)

    val controlIncoming: SharedFlow<String> = manager.textFrames(MuxEnvelope.CHANNEL_STREAM, sessionN)

    val streamId: String = "stream-$sessionN-${System.currentTimeMillis()}"

    /** Subscribe the `stream` channel and suspend until the shared connection is Open. */
    suspend fun connect() {
        manager.subscribe(MuxEnvelope.CHANNEL_STREAM, sessionN)
        // Await the shared connection reaching Open (it self-reconnects; no per-client loop).
        state.first { it is State.Open || it is State.Revoked }
    }

    fun sendStdin(bytes: ByteArray): Boolean = manager.sendStreamBinary(sessionN, bytes)

    fun sendControl(textJson: String): Boolean = manager.sendText(MuxEnvelope.CHANNEL_STREAM, sessionN, textJson)

    fun sendResize(cols: Int, rows: Int): Boolean =
        sendControl("""{"type":"resize","cols":$cols,"rows":$rows}""")

    fun sendEnumerate(): Boolean = sendControl("""{"type":"enumerate-targets"}""")

    fun sendSelectTarget(targetId: String): Boolean =
        sendControl("""{"type":"select-target","targetId":"${MuxEnvelope.jsonEscape(targetId)}"}""")

    /** Unsubscribe the `stream` channel (no socket teardown — the connection stays up for other channels). */
    fun close(reason: String = "client-close") {
        manager.unsubscribe(MuxEnvelope.CHANNEL_STREAM, sessionN)
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

    /** Connection state surfaced to the UI / foreground notification (unchanged shape). */
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State
        data class Disconnected(val reason: String) : State
        data object Revoked : State
    }

    companion object {
        /** Retained for source-compat; the mux subprotocol lives in [MuxConnection.PROTOCOL_HEADER]. */
        const val SUBPROTOCOL = "ai-sandbox.mux.v1"
        const val REVOKED_CLOSE_CODE = 4401
        const val NORMAL_CLOSE_CODE = 1000
        const val SERVICE_OVERLOAD_CLOSE_CODE = 1013
        const val POLICY_VIOLATION_CLOSE_CODE = 1008
    }
}
