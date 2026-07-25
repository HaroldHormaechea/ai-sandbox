package com.aisandbox.android.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC-100 — the thin adapters reflect the single connection's centrally-mapped
 * state. Close-code routing (4401→Revoked, 4426→UpgradeRequired) now happens
 * once inside [MuxConnection]; the adapters just project it into their own
 * State (this replaces the socket-owning close-code test the old StreamClientTest
 * carried). Driven by a mocked manager whose [MuxConnectionManager.state] the
 * test flips — fully deterministic, no socket.
 */
class MuxAdapterStateMappingTest {

    private fun managerWith(stateFlow: MutableStateFlow<MuxConnection.State>): MuxConnectionManager {
        val m = mock(MuxConnectionManager::class.java)
        `when`(m.state).thenReturn(stateFlow)
        `when`(m.textFrames(anyString(), any())).thenReturn(MutableSharedFlow(replay = 0, extraBufferCapacity = 8))
        `when`(m.binaryFrames(anyInt())).thenReturn(MutableSharedFlow(replay = 0, extraBufferCapacity = 8))
        return m
    }

    @Test
    fun `stream adapter maps Revoked (4401) through to its Revoked state`() = runBlocking {
        val flow = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Open)
        val client = StreamClient(managerWith(flow), sessionN = 7)
        flow.value = MuxConnection.State.Revoked
        val s = withTimeout(2_000) { client.state.first { it is StreamClient.State.Revoked } }
        assertThat(s).isInstanceOf(StreamClient.State.Revoked::class.java)
    }

    @Test
    fun `stream adapter maps UpgradeRequired (4426) to a Disconnected upgrade_required`() = runBlocking {
        val flow = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Open)
        val client = StreamClient(managerWith(flow), sessionN = 7)
        flow.value = MuxConnection.State.UpgradeRequired
        val s = withTimeout(2_000) { client.state.first { it is StreamClient.State.Disconnected } }
        assertThat((s as StreamClient.State.Disconnected).reason).isEqualTo("upgrade_required")
    }

    @Test
    fun `session-events adapter maps Open through to Open`() = runBlocking {
        val flow = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Connecting)
        val client = SessionEventsClient(managerWith(flow))
        flow.value = MuxConnection.State.Open
        val s = withTimeout(2_000) { client.state.first { it is SessionEventsClient.State.Open } }
        assertThat(s).isInstanceOf(SessionEventsClient.State.Open::class.java)
    }

    @Test
    fun `conversation adapter maps Revoked through to Revoked`() = runBlocking {
        val flow = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Open)
        val client = ConversationClient(managerWith(flow), sessionN = 3)
        flow.value = MuxConnection.State.Revoked
        val s = withTimeout(2_000) { client.state.first { it is ConversationClient.State.Revoked } }
        assertThat(s).isInstanceOf(ConversationClient.State.Revoked::class.java)
    }

    @Test
    fun `mux close-code constants are disjoint and stable`() {
        assertThat(MuxConnection.REVOKED_CLOSE_CODE).isEqualTo(4401)
        assertThat(MuxConnection.UPGRADE_REQUIRED_CLOSE_CODE).isEqualTo(4426)
        assertThat(MuxConnection.REVOKED_CLOSE_CODE).isNotEqualTo(MuxConnection.UPGRADE_REQUIRED_CLOSE_CODE)
    }
}
