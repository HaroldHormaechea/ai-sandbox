package com.aisandbox.server.mux.service;

import com.aisandbox.server.mux.dto.Envelope;
import com.aisandbox.server.mux.dto.MuxChannel;
import com.aisandbox.server.mux.dto.MuxControlMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.dto.ConversationClientMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.ByteBuffer;
import org.springframework.stereotype.Component;

/**
 * UC-100 — (de)serialization for the {@code /v1/mux} envelope. Handles both the
 * JSON text envelope (control + all data channels) and the compact binary
 * envelope used by the {@code stream} channel's PTY stdout/stdin
 * ({@code [channel:1B][sessionId:varint][seq:8B] + raw bytes}) — no base64 on
 * the hot path.
 *
 * <p>The nested {@code payload} is kept as a {@link JsonNode} so the transport
 * layer is agnostic to the concrete typed model. Outbound, each typed model is
 * rendered through its <b>sealed-interface</b> static type so Jackson writes the
 * {@code @JsonTypeInfo} {@code type} discriminator; inbound, the same
 * discriminator drives {@code treeToValue} back to the concrete record.
 */
@Component
public class MuxCodec {

    /**
     * A single mapper configured exactly like the legacy sessionevents config:
     * {@link JavaTimeModule} + timestamps-as-ISO-8601 so {@code SessionEventMessage.Row.startedAt}
     * serializes as an ISO string (matching the REST list), and every other
     * payload round-trips byte-identically to its legacy endpoint.
     */
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    // ──────────────────────── outbound: model → envelope text ────────────────────────

    /** Render an envelope text frame wrapping an already-computed payload tree. */
    public String encode(MuxChannel channel, Integer sessionId, long seq, JsonNode payload) {
        String type = payload != null && payload.hasNonNull("type")
                ? payload.get("type").asText()
                : null;
        Envelope env = new Envelope(channel.wire(), sessionId, type, seq, payload);
        try {
            return mapper.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            // Should never happen for a well-formed envelope; fall back to a control error frame.
            return "{\"channel\":\"control\",\"type\":\"error\",\"seq\":" + seq
                    + ",\"payload\":{\"type\":\"error\",\"code\":\"serialize_failed\",\"title\":\"serialize failed\","
                    + "\"detail\":\"\"}}";
        }
    }

    public JsonNode tree(MuxControlMessage m) {
        return treeOf(m, MuxControlMessage.class);
    }

    public JsonNode tree(StreamServerMessage m) {
        return treeOf(m, StreamServerMessage.class);
    }

    public JsonNode tree(ConversationServerMessage m) {
        return treeOf(m, ConversationServerMessage.class);
    }

    public JsonNode tree(SessionEventMessage m) {
        return treeOf(m, SessionEventMessage.class);
    }

    private <T> JsonNode treeOf(T model, Class<T> staticType) {
        try {
            // writerFor(staticType) forces the @JsonTypeInfo "type" discriminator to be written.
            return mapper.readTree(mapper.writerFor(staticType).writeValueAsString(model));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mux payload serialization failed: " + e.getOriginalMessage(), e);
        }
    }

    // ──────────────────────── outbound: binary stream framing ────────────────────────

    /**
     * Frame a {@code stream}-channel binary payload:
     * {@code [channel:1B][sessionId:varint][seq:8B] + raw bytes}.
     */
    public ByteBuffer encodeBinary(int sessionId, long seq, byte[] data, int off, int len) {
        byte[] sid = varint(sessionId);
        ByteBuffer bb = ByteBuffer.allocate(1 + sid.length + 8 + len);
        bb.put(MuxChannel.STREAM.wireByte());
        bb.put(sid);
        bb.putLong(seq);
        bb.put(data, off, len);
        bb.flip();
        return bb;
    }

    // ──────────────────────── inbound: text envelope → model ────────────────────────

    /** Parse an inbound JSON text frame into an {@link Envelope} (payload kept as a tree). */
    public Envelope decode(String textFrame) {
        try {
            return mapper.readValue(textFrame, Envelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid mux envelope JSON: " + e.getOriginalMessage());
        }
    }

    public MuxControlMessage asControl(JsonNode payload) {
        return convert(payload, MuxControlMessage.class);
    }

    public ControlMessage asStreamControl(JsonNode payload) {
        return convert(payload, ControlMessage.class);
    }

    public ConversationClientMessage asConversation(JsonNode payload) {
        return convert(payload, ConversationClientMessage.class);
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("missing envelope payload for " + type.getSimpleName());
        }
        try {
            return mapper.treeToValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Invalid " + type.getSimpleName() + " payload: " + e.getOriginalMessage());
        }
    }

    // ──────────────────────── inbound: binary stream framing ────────────────────────

    /** Decoded compact binary frame: the resolved channel, sessionId, seq and the raw payload bytes. */
    public record BinaryFrame(MuxChannel channel, int sessionId, long seq, byte[] data) {}

    /** Parse a compact binary frame. Throws {@link IllegalArgumentException} on a malformed header. */
    public BinaryFrame decodeBinary(ByteBuffer bb) {
        if (bb.remaining() < 1) {
            throw new IllegalArgumentException("empty binary mux frame");
        }
        MuxChannel channel = MuxChannel.fromWireByte(bb.get());
        if (channel == null) {
            throw new IllegalArgumentException("unknown binary mux channel byte");
        }
        int sessionId = readVarint(bb);
        if (bb.remaining() < 8) {
            throw new IllegalArgumentException("truncated binary mux seq");
        }
        long seq = bb.getLong();
        byte[] data = new byte[bb.remaining()];
        bb.get(data);
        return new BinaryFrame(channel, sessionId, seq, data);
    }

    // ──────────────────────── varint (unsigned LEB128) ────────────────────────

    static byte[] varint(int value) {
        // Session numbers are small non-negative ints; treat as unsigned LEB128.
        long v = value & 0xFFFFFFFFL;
        byte[] tmp = new byte[5];
        int i = 0;
        do {
            byte b = (byte) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                b |= (byte) 0x80;
            }
            tmp[i++] = b;
        } while (v != 0);
        byte[] out = new byte[i];
        System.arraycopy(tmp, 0, out, 0, i);
        return out;
    }

    static int readVarint(ByteBuffer bb) {
        int shift = 0;
        int result = 0;
        while (true) {
            if (!bb.hasRemaining()) {
                throw new IllegalArgumentException("truncated varint in binary mux frame");
            }
            byte b = bb.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                throw new IllegalArgumentException("varint too long in binary mux frame");
            }
        }
        return result;
    }
}
