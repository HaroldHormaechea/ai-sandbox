package com.aisandbox.android.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * UC-100 — the `stream`-channel adapter's wire contract, re-pinned over the
 * single multiplexed connection. [StreamClient] no longer owns a socket; it
 * delegates every `send*` to [MuxConnectionManager] on the `stream` channel.
 * These tests assert the exact **payload JSON** the adapter hands the manager
 * (AC2 — payloads carried unchanged) and that inbound `stream`-channel text
 * frames surface on [StreamClient.controlIncoming] — deterministically, against
 * a mocked manager (no MockWebServer, so none of the socket-collector hang that
 * disabled the original StreamClientTest).
 */
class StreamClientControlFrameTest {

    private val n = 7

    /** A mocked manager pre-stubbed for the adapter's field initializers. */
    private class Harness(val n: Int) {
        val manager: MuxConnectionManager = mock(MuxConnectionManager::class.java)
        val stateFlow = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Open)
        val binaryFlow = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 8)
        val textFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)

        init {
            `when`(manager.state).thenReturn(stateFlow)
            `when`(manager.binaryFrames(n)).thenReturn(binaryFlow)
            `when`(manager.textFrames(MuxEnvelope.CHANNEL_STREAM, n)).thenReturn(textFlow)
            `when`(manager.sendText(anyString(), any(), anyString())).thenReturn(true)
            `when`(manager.sendStreamBinary(anyInt(), anyNn())).thenReturn(true)
        }

        fun client() = StreamClient(manager, sessionN = n)

        companion object {
            // Kotlin-safe any() for a NON-null reference param: ArgumentMatchers.any()
            // returns null, which trips Kotlin's null-check on a non-null parameter
            // (`any(...) must not be null`). The unchecked cast registers the matcher
            // and hands back a value Kotlin treats as non-null.
            @Suppress("UNCHECKED_CAST")
            fun <T> anyNn(): T = any<T>() as T
        }
    }

    @Test
    fun `sendEnumerate emits the enumerate-targets payload on the stream channel`() {
        val h = Harness(n)
        val stream = h.client()

        assertThat(stream.sendEnumerate()).isTrue
        verify(h.manager).sendText(MuxEnvelope.CHANNEL_STREAM, n, """{"type":"enumerate-targets"}""")
    }

    @Test
    fun `sendSelectTarget emits a select-target payload with the target id`() {
        val h = Harness(n)
        val stream = h.client()

        assertThat(stream.sendSelectTarget("swarm:claude-swarm-1:0.1")).isTrue
        verify(h.manager).sendText(
            MuxEnvelope.CHANNEL_STREAM,
            n,
            """{"type":"select-target","targetId":"swarm:claude-swarm-1:0.1"}""",
        )
    }

    @Test
    fun `sendSelectTarget json-escapes quotes and backslashes in the target id`() {
        val h = Harness(n)
        val stream = h.client()

        // raw id: x"y\z → wire must escape to x\"y\\z (still valid JSON).
        stream.sendSelectTarget("x\"y\\z")
        verify(h.manager).sendText(
            MuxEnvelope.CHANNEL_STREAM,
            n,
            """{"type":"select-target","targetId":"x\"y\\z"}""",
        )
    }

    @Test
    fun `sendResize emits a resize payload`() {
        val h = Harness(n)
        val stream = h.client()

        assertThat(stream.sendResize(120, 40)).isTrue
        verify(h.manager).sendText(MuxEnvelope.CHANNEL_STREAM, n, """{"type":"resize","cols":120,"rows":40}""")
    }

    @Test
    fun `sendStdin goes out as a binary stream frame`() {
        val h = Harness(n)
        val stream = h.client()
        val bytes = "ls -la\n".toByteArray()

        assertThat(stream.sendStdin(bytes)).isTrue
        verify(h.manager).sendStreamBinary(n, bytes)
    }

    @Test
    fun `sends reflect the manager result when the connection is down`() {
        val h = Harness(n)
        `when`(h.manager.sendText(anyString(), any(), anyString())).thenReturn(false)
        val stream = h.client()

        // No live connection → the manager returns false and the adapter surfaces it.
        assertThat(stream.sendEnumerate()).isFalse
        assertThat(stream.sendSelectTarget("main")).isFalse
    }

    @Test
    fun `inbound stream-channel text frame is surfaced on controlIncoming`() = runTest {
        val h = Harness(n)
        val stream = h.client()

        val targetsFrame = """{"type":"targets","targets":[],"selectedId":"main"}"""
        h.textFlow.emit(targetsFrame) // replay=1 so a late collector still sees it

        val received = withTimeout(2_000) { stream.controlIncoming.first() }
        assertThat(received).isEqualTo(targetsFrame)
    }

    @Test
    fun `mux subprotocol constant is stable`() {
        // The hard cut replaced the three legacy subprotocols with the one mux token.
        assertThat(StreamClient.SUBPROTOCOL).isEqualTo("ai-sandbox.mux.v1")
    }
}
