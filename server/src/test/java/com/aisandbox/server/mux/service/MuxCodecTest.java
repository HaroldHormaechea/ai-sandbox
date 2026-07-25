package com.aisandbox.server.mux.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.mux.dto.Envelope;
import com.aisandbox.server.mux.dto.MuxChannel;
import com.aisandbox.server.mux.dto.MuxControlMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.dto.ConversationClientMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-100 (AC2 / AC3) — the {@code /v1/mux} envelope codec. Pins that the fresh
 * framing {@code {channel, sessionId, type, seq, payload}} round-trips for every
 * carried payload hierarchy (the existing typed models nested verbatim), that
 * the {@code type} discriminator is preserved on both the envelope and the
 * nested payload, and that the compact binary {@code stream} framing
 * ({@code [channel:1B][sessionId:varint][seq:8B] + raw bytes}) round-trips with
 * a robust unsigned-LEB128 varint. Malformed binary headers are rejected, not
 * silently mis-decoded.
 */
class MuxCodecTest {

    private final MuxCodec codec = new MuxCodec();

    // ──────────────────────── text envelope ────────────────────────

    @Test
    void control_message_round_trips_through_envelope() {
        MuxControlMessage.Welcome welcome =
                new MuxControlMessage.Welcome("mux.v1", java.util.Map.of("perClientCap", 10));
        JsonNode payload = codec.tree(welcome);

        String wire = codec.encode(MuxChannel.CONTROL, null, 5L, payload);
        Envelope env = codec.decode(wire);

        assertThat(env.channel()).isEqualTo("control");
        assertThat(env.sessionId()).isNull(); // control has no sessionId (NON_NULL omit)
        assertThat(env.type()).isEqualTo("welcome"); // lifted from the payload's own discriminator
        assertThat(env.seq()).isEqualTo(5L);
        assertThat(codec.asControl(env.payload())).isInstanceOf(MuxControlMessage.Welcome.class);
    }

    @Test
    void per_session_channel_carries_session_id_on_the_wire() {
        JsonNode payload = codec.tree(new StreamServerMessage.TargetSelected("main"));
        String wire = codec.encode(MuxChannel.STREAM, 7, 0L, payload);

        assertThat(wire).contains("\"channel\":\"stream\"");
        assertThat(wire).contains("\"sessionId\":7");

        Envelope env = codec.decode(wire);
        assertThat(env.sessionId()).isEqualTo(7);
        assertThat(env.type()).isEqualTo("target-selected");
    }

    @Test
    void stream_server_message_preserves_type_discriminator() {
        JsonNode tree = codec.tree(new StreamServerMessage.ServerError("open_failed", "t", "d"));
        assertThat(tree.get("type").asText()).isEqualTo("error");
        assertThat(tree.get("code").asText()).isEqualTo("open_failed");
    }

    @Test
    void conversation_answer_echo_preserves_type_and_fields() {
        JsonNode tree = codec.tree(new ConversationServerMessage.AnswerEcho("uq", 1, List.of(0, 2), "ft"));
        assertThat(tree.get("type").asText()).isEqualTo("answer-echo");
        assertThat(tree.get("questionIndex").asInt()).isEqualTo(1);
        assertThat(tree.get("selections").toString()).isEqualTo("[0,2]");
    }

    @Test
    void session_event_snapshot_preserves_type_discriminator() {
        JsonNode tree = codec.tree(new SessionEventMessage.Snapshot(List.of()));
        assertThat(tree.get("type").asText()).isEqualTo("snapshot");
    }

    @Test
    void inbound_stream_control_payload_deserialises_to_typed_model() {
        Envelope env = codec.decode("{\"channel\":\"stream\",\"sessionId\":3,\"type\":\"resize\",\"seq\":0,"
                + "\"payload\":{\"type\":\"resize\",\"cols\":120,\"rows\":40}}");
        ControlMessage cm = codec.asStreamControl(env.payload());
        assertThat(cm).isInstanceOf(ControlMessage.Resize.class);
        assertThat(((ControlMessage.Resize) cm).cols()).isEqualTo(120);
        assertThat(((ControlMessage.Resize) cm).rows()).isEqualTo(40);
    }

    @Test
    void inbound_conversation_payload_deserialises_to_typed_model() {
        Envelope env = codec.decode("{\"channel\":\"conversation\",\"sessionId\":7,\"type\":\"composer-input\","
                + "\"seq\":0,\"payload\":{\"type\":\"composer-input\",\"text\":\"hi\"}}");
        ConversationClientMessage cm = codec.asConversation(env.payload());
        assertThat(cm).isInstanceOf(ConversationClientMessage.ComposerInput.class);
        assertThat(((ConversationClientMessage.ComposerInput) cm).text()).isEqualTo("hi");
    }

    @Test
    void decode_rejects_invalid_json() {
        assertThatThrownBy(() -> codec.decode("not json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void as_control_rejects_missing_payload() {
        assertThatThrownBy(() -> codec.asControl(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────── compact binary framing ────────────────────────

    @Test
    void binary_frame_round_trips() {
        byte[] data = "PTY-stdout-bytes".getBytes();
        ByteBuffer framed = codec.encodeBinary(7, 42L, data, 0, data.length);

        MuxCodec.BinaryFrame f = codec.decodeBinary(framed);
        assertThat(f.channel()).isEqualTo(MuxChannel.STREAM);
        assertThat(f.sessionId()).isEqualTo(7);
        assertThat(f.seq()).isEqualTo(42L);
        assertThat(f.data()).isEqualTo(data);
    }

    @Test
    void binary_frame_round_trips_with_partial_offset() {
        byte[] data = "0123456789".getBytes();
        ByteBuffer framed = codec.encodeBinary(1, 0L, data, 3, 4); // "3456"
        MuxCodec.BinaryFrame f = codec.decodeBinary(framed);
        assertThat(new String(f.data())).isEqualTo("3456");
    }

    @Test
    void varint_round_trips_across_boundaries() {
        for (int v : new int[] {0, 1, 127, 128, 255, 16383, 16384, 2_000_000}) {
            byte[] enc = MuxCodec.varint(v);
            ByteBuffer bb = ByteBuffer.wrap(enc);
            assertThat(MuxCodec.readVarint(bb)).as("varint %d", v).isEqualTo(v);
        }
    }

    @Test
    void large_session_id_survives_the_binary_header() {
        ByteBuffer framed = codec.encodeBinary(300, 1L, new byte[] {9}, 0, 1);
        assertThat(codec.decodeBinary(framed).sessionId()).isEqualTo(300);
    }

    @Test
    void decode_binary_rejects_empty_frame() {
        assertThatThrownBy(() -> codec.decodeBinary(ByteBuffer.allocate(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_binary_rejects_unknown_channel_byte() {
        ByteBuffer bb = ByteBuffer.wrap(new byte[] {(byte) 0x7F, 0x00});
        assertThatThrownBy(() -> codec.decodeBinary(bb)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_binary_rejects_truncated_seq() {
        // channel=stream(1), sessionId varint=0, then only 3 seq bytes (need 8).
        ByteBuffer bb = ByteBuffer.wrap(new byte[] {1, 0, 1, 2, 3});
        assertThatThrownBy(() -> codec.decodeBinary(bb)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_binary_rejects_truncated_varint() {
        // channel=stream(1), then a continuation-bit-set byte with nothing after it.
        ByteBuffer bb = ByteBuffer.wrap(new byte[] {1, (byte) 0x80});
        assertThatThrownBy(() -> codec.decodeBinary(bb)).isInstanceOf(IllegalArgumentException.class);
    }
}
