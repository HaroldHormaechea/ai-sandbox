package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * UC-100 (AC2 / AC3) — the Android-side [MuxEnvelope] codec. Mirror of the
 * server's `MuxCodec`: it must produce/parse the fresh framing
 * `{channel, sessionId, type, seq, payload}` for text frames and the compact
 * `[channel:1B][sessionId:varint][seq:8B] + raw bytes` for the `stream`
 * channel, with the envelope `type` lifted from the nested payload. Wire
 * compatibility with the server codec is the whole contract — pinned here on
 * pure functions (no socket).
 */
class MuxEnvelopeTest {

    // ──────────────────────── text envelope ────────────────────────

    @Test
    fun `encodeText wraps the payload and lifts its type onto the envelope`() {
        val wire = MuxEnvelope.encodeText(
            MuxEnvelope.CHANNEL_CONVERSATION, 7, 3L, """{"type":"composer-input","text":"hi"}""",
        )
        assertThat(wire).contains("\"channel\":\"conversation\"")
        assertThat(wire).contains("\"sessionId\":7")
        assertThat(wire).contains("\"type\":\"composer-input\"")
        assertThat(wire).contains("\"seq\":3")
        assertThat(wire).contains("\"payload\":{\"type\":\"composer-input\",\"text\":\"hi\"}")
    }

    @Test
    fun `encodeText omits sessionId for connection-scoped channels`() {
        val wire = MuxEnvelope.encodeText(MuxEnvelope.CHANNEL_CONTROL, null, 0L, """{"type":"hello"}""")
        assertThat(wire).doesNotContain("sessionId")
    }

    @Test
    fun `decodeText round-trips channel sessionId type seq and payload`() {
        val frame = MuxEnvelope.decodeText(
            """{"channel":"stream","sessionId":9,"type":"targets","seq":42,""" +
                """"payload":{"type":"targets","targets":[],"selectedId":"main"}}""",
        )
        assertThat(frame.channel).isEqualTo("stream")
        assertThat(frame.sessionId).isEqualTo(9)
        assertThat(frame.type).isEqualTo("targets")
        assertThat(frame.seq).isEqualTo(42L)
        assertThat(frame.payloadJson).contains("\"selectedId\":\"main\"")
    }

    @Test
    fun `decodeText rejects malformed json`() {
        assertThatThrownBy { MuxEnvelope.decodeText("not json") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decodeText requires a channel`() {
        assertThatThrownBy { MuxEnvelope.decodeText("""{"seq":0}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `controlPayload builds a hello carrying the protocol version`() {
        val hello = MuxEnvelope.controlPayload("hello")
        assertThat(hello).contains("\"type\":\"hello\"")
        assertThat(hello).contains("\"protocol\":\"${MuxConnection.PROTOCOL}\"")
    }

    @Test
    fun `controlPayload builds a subscribe with channel and sessionId`() {
        assertThat(MuxEnvelope.controlPayload("subscribe", "stream", 5))
            .isEqualTo("""{"type":"subscribe","channel":"stream","sessionId":5}""")
        assertThat(MuxEnvelope.controlPayload("subscribe", "events", null))
            .isEqualTo("""{"type":"subscribe","channel":"events"}""")
    }

    // ──────────────────────── binary framing ────────────────────────

    @Test
    fun `binary frame round-trips through encode and decode`() {
        val data = "PTY-stdout".toByteArray()
        val framed = MuxEnvelope.encodeBinary(sessionId = 7, seq = 42L, data = data)

        val decoded = MuxEnvelope.decodeBinary(framed)
        assertThat(decoded.channelByte).isEqualTo(MuxEnvelope.BYTE_STREAM)
        assertThat(decoded.sessionId).isEqualTo(7)
        assertThat(decoded.seq).isEqualTo(42L)
        assertThat(decoded.data).isEqualTo(data)
    }

    @Test
    fun `binary varint survives multi-byte session ids`() {
        for (sid in intArrayOf(0, 1, 127, 128, 255, 16383, 16384, 300)) {
            val framed = MuxEnvelope.encodeBinary(sid, 1L, byteArrayOf(9))
            assertThat(MuxEnvelope.decodeBinary(framed).sessionId).`as`("sid %d", sid).isEqualTo(sid)
        }
    }

    @Test
    fun `binary header byte matches the server stream channel byte`() {
        // The header's first byte MUST equal the server MuxChannel.STREAM.wireByte() (1).
        assertThat(MuxEnvelope.BYTE_STREAM).isEqualTo(1.toByte())
        val framed = MuxEnvelope.encodeBinary(1, 0L, byteArrayOf(1, 2, 3))
        assertThat(framed[0]).isEqualTo(1.toByte())
    }

    @Test
    fun `decodeBinary rejects an empty frame`() {
        assertThatThrownBy { MuxEnvelope.decodeBinary(ByteArray(0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decodeBinary rejects a truncated seq`() {
        // channel=1, sessionId varint=0, then only 3 of the 8 seq bytes.
        assertThatThrownBy { MuxEnvelope.decodeBinary(byteArrayOf(1, 0, 1, 2, 3)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decodeBinary rejects a truncated varint`() {
        assertThatThrownBy { MuxEnvelope.decodeBinary(byteArrayOf(1, 0x80.toByte())) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
