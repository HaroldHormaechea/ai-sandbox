package com.aisandbox.android.net

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * UC-100 — client-side codec for the multiplex envelope
 * `{channel, sessionId, type, seq, payload}` carried on the single `/v1/mux`
 * WebSocket. Mirror of the server's `mux/dto/Envelope` + `MuxCodec`.
 *
 * - JSON text envelope for `control` + all text frames; the nested `payload`
 *   is the existing typed model, embedded verbatim (the raw JSON the legacy
 *   clients already produced/consumed).
 * - Compact binary envelope for the `stream` channel's PTY stdout/stdin:
 *   `[channel:1B][sessionId:unsigned-LEB128 varint][seq:8B big-endian] + raw bytes`.
 */
object MuxEnvelope {

    /** JSON envelope channel values. */
    const val CHANNEL_CONTROL = "control"
    const val CHANNEL_STREAM = "stream"
    const val CHANNEL_CONVERSATION = "conversation"
    const val CHANNEL_EVENTS = "events"

    /** Binary header channel bytes (must match the server's `MuxChannel.wireByte()`). */
    const val BYTE_CONTROL: Byte = 0
    const val BYTE_STREAM: Byte = 1
    const val BYTE_CONVERSATION: Byte = 2
    const val BYTE_EVENTS: Byte = 3

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** A decoded inbound text envelope. [payloadJson] is the nested payload re-serialized for the adapter. */
    data class TextFrame(
        val channel: String,
        val sessionId: Int?,
        val type: String?,
        val seq: Long,
        val payloadJson: String,
    )

    /** A decoded inbound binary frame (always the `stream` channel today). */
    data class BinaryFrame(
        val channelByte: Byte,
        val sessionId: Int,
        val seq: Long,
        val data: ByteArray,
    )

    // ──────────────────────── outbound ────────────────────────

    /**
     * Build a text envelope wrapping [payloadJson] (a complete JSON object,
     * e.g. `{"type":"composer-input","text":"hi"}`). The envelope `type` is
     * lifted from the payload's own `type` field so a client never has to
     * duplicate it.
     */
    fun encodeText(channel: String, sessionId: Int?, seq: Long, payloadJson: String): String {
        val type = extractType(payloadJson)
        val sb = StringBuilder(payloadJson.length + 96)
        sb.append("{\"channel\":\"").append(channel).append('"')
        if (sessionId != null) {
            sb.append(",\"sessionId\":").append(sessionId)
        }
        if (type != null) {
            sb.append(",\"type\":\"").append(jsonEscape(type)).append('"')
        }
        sb.append(",\"seq\":").append(seq)
        sb.append(",\"payload\":").append(payloadJson)
        sb.append('}')
        return sb.toString()
    }

    /** Frame a `stream`-channel binary payload with the compact header. */
    fun encodeBinary(sessionId: Int, seq: Long, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(1 + 5 + 8 + data.size)
        out.write(BYTE_STREAM.toInt())
        writeVarint(out, sessionId)
        // 8-byte big-endian seq
        for (shift in 56 downTo 0 step 8) {
            out.write(((seq ushr shift) and 0xFF).toInt())
        }
        out.write(data)
        return out.toByteArray()
    }

    // ──────────────────────── inbound ────────────────────────

    /** Parse an inbound JSON text envelope. Throws [IllegalArgumentException] on malformed JSON. */
    fun decodeText(frame: String): TextFrame {
        val obj: JsonObject = try {
            JSON.parseToJsonElement(frame).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("bad mux envelope: ${e.message}")
        }
        val channel = (obj["channel"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("mux envelope missing channel")
        val sessionId = (obj["sessionId"] as? JsonPrimitive)?.intOrNull
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        val seq = (obj["seq"] as? JsonPrimitive)?.longOrNull ?: 0L
        val payloadJson = obj["payload"]?.toString() ?: "{}"
        return TextFrame(channel, sessionId, type, seq, payloadJson)
    }

    /** Parse a compact binary frame. Throws [IllegalArgumentException] on a malformed header. */
    fun decodeBinary(bytes: ByteArray): BinaryFrame {
        if (bytes.isEmpty()) throw IllegalArgumentException("empty binary mux frame")
        var i = 0
        val channelByte = bytes[i++]
        // unsigned LEB128 varint
        var sessionId = 0
        var shift = 0
        while (true) {
            if (i >= bytes.size) throw IllegalArgumentException("truncated varint in binary mux frame")
            val b = bytes[i++].toInt() and 0xFF
            sessionId = sessionId or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IllegalArgumentException("varint too long in binary mux frame")
        }
        if (i + 8 > bytes.size) throw IllegalArgumentException("truncated seq in binary mux frame")
        var seq = 0L
        for (k in 0 until 8) {
            seq = (seq shl 8) or (bytes[i++].toLong() and 0xFF)
        }
        val data = bytes.copyOfRange(i, bytes.size)
        return BinaryFrame(channelByte, sessionId, seq, data)
    }

    // ──────────────────────── helpers ────────────────────────

    /** Read the `type` discriminator out of a payload JSON object (best-effort). */
    private fun extractType(payloadJson: String): String? =
        try {
            (JSON.parseToJsonElement(payloadJson).jsonObject["type"] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value.toLong() and 0xFFFFFFFFL
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
    }

    fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /** Build a `control` payload JSON for a subscribe/unsubscribe/hello frame. */
    fun controlPayload(type: String, channel: String? = null, sessionId: Int? = null): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"").append(type).append('"')
        if (channel != null) sb.append(",\"channel\":\"").append(channel).append('"')
        if (sessionId != null) sb.append(",\"sessionId\":").append(sessionId)
        if (type == "hello") sb.append(",\"protocol\":\"").append(MuxConnection.PROTOCOL).append('"')
        sb.append('}')
        return sb.toString()
    }
}
