package com.aisandbox.android.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * UC-100 — close() contract for the three thin channel adapters over the single
 * multiplexed connection. Post-refactor, an adapter no longer owns a socket:
 * [close] is a channel `unsubscribe` on the shared [MuxConnectionManager] plus a
 * local scope cancel — the per-client-socket teardown the old accumulation test
 * used to guard is now structurally impossible (there is one socket, owned
 * centrally by the manager/connection).
 *
 * <p>Pinned here (deterministic, no live socket):
 * <ul>
 *   <li><b>close is a safe, idempotent unsubscribe</b> — calling it twice (incl.
 *       before any connect) must not throw and must issue the channel
 *       unsubscribe on the shared manager.</li>
 *   <li><b>server-refusal close-code constants</b> — 1013 / 1008 wire constants
 *       the controllers branch on are pinned so a drift surfaces here.</li>
 * </ul>
 */
class WsClientCloseCancelTest {

    private fun manager(): MuxConnectionManager {
        val m = mock(MuxConnectionManager::class.java)
        `when`(m.state).thenReturn(MutableStateFlow<MuxConnection.State>(MuxConnection.State.Idle))
        `when`(m.textFrames(anyString(), any())).thenReturn(MutableSharedFlow(replay = 0, extraBufferCapacity = 8))
        `when`(m.binaryFrames(anyInt())).thenReturn(MutableSharedFlow(replay = 0, extraBufferCapacity = 8))
        return m
    }

    @Test
    fun `SessionEventsClient close before connect is a safe idempotent unsubscribe`() {
        val m = manager()
        val client = SessionEventsClient(m)
        assertThatCode {
            client.close("reconnect")
            client.close("reconnect") // idempotent — second close must not throw
        }.doesNotThrowAnyException()
        verify(m, atLeastOnce()).unsubscribe(MuxEnvelope.CHANNEL_EVENTS, null)
    }

    @Test
    fun `StreamClient close before connect is a safe idempotent unsubscribe`() {
        val m = manager()
        val client = StreamClient(m, sessionN = 7)
        assertThatCode {
            client.close("reconnect")
            client.close("reconnect")
        }.doesNotThrowAnyException()
        verify(m, atLeastOnce()).unsubscribe(MuxEnvelope.CHANNEL_STREAM, 7)
    }

    @Test
    fun `ConversationClient close before connect is a safe idempotent unsubscribe`() {
        val m = manager()
        val client = ConversationClient(m, sessionN = 7)
        assertThatCode {
            client.close("reconnect")
            client.close("reconnect")
        }.doesNotThrowAnyException()
        verify(m, atLeastOnce()).unsubscribe(MuxEnvelope.CHANNEL_CONVERSATION, 7)
    }

    // ── server-refusal close-code constants pinned ───────────────────────────

    @Test
    fun `SessionEventsClient pins the server-refusal close codes`() {
        assertThat(SessionEventsClient.SERVICE_OVERLOAD_CLOSE_CODE).isEqualTo(1013)
        assertThat(SessionEventsClient.POLICY_VIOLATION_CLOSE_CODE).isEqualTo(1008)
    }

    @Test
    fun `StreamClient pins the server-refusal close codes`() {
        assertThat(StreamClient.SERVICE_OVERLOAD_CLOSE_CODE).isEqualTo(1013)
        assertThat(StreamClient.POLICY_VIOLATION_CLOSE_CODE).isEqualTo(1008)
    }
}
