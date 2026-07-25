package com.aisandbox.server.mux.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.config.ServerProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * UC-100 (AC3 / AC8) — the mux protocol constants + per-channel capability
 * negotiation. The single connection must advertise the same frame-size /
 * backfill bounds the three legacy endpoints enforced (caps sourced from
 * {@link ServerProperties.Streams}), and the version handshake must accept only
 * the matched {@code mux.v1} (hard-cut mismatch → {@code upgrade_required} +
 * close 4426).
 */
class MuxProtocolTest {

    private MuxProtocol protocol() {
        ServerProperties props = mock(ServerProperties.class);
        // idleTimeout, perClientCap, globalCap, maxBinary, maxText, outputRing, keepalivePing, keepalivePong
        when(props.streams()).thenReturn(new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
        return new MuxProtocol(props);
    }

    @Test
    void wire_constants_are_stable() {
        assertThat(MuxProtocol.MUX_PATH).isEqualTo("/v1/mux");
        assertThat(MuxProtocol.SUBPROTOCOL).isEqualTo("ai-sandbox.mux.v1");
        assertThat(MuxProtocol.VERSION).isEqualTo("mux.v1");
        assertThat(MuxProtocol.CLOSE_UPGRADE_REQUIRED).isEqualTo(4426);
        assertThat(MuxProtocol.CLOSE_REVOKED).isEqualTo(4401);
        assertThat(MuxProtocol.STREAM_CHUNK_BYTES).isEqualTo(32 * 1024);
    }

    @Test
    void accepts_only_the_matched_version() {
        MuxProtocol p = protocol();
        assertThat(p.accepts("mux.v1")).isTrue();
        assertThat(p.accepts("mux.v2")).isFalse();
        assertThat(p.accepts("v1")).isFalse();
        assertThat(p.accepts(null)).isFalse();
    }

    @Test
    void capabilities_are_sourced_from_stream_properties() {
        Map<String, Object> caps = protocol().capabilities();
        assertThat(caps).containsEntry("maxBinaryFrameBytes", 262144);
        assertThat(caps).containsEntry("maxTextFrameBytes", 16384);
        assertThat(caps).containsEntry("streamChunkBytes", MuxProtocol.STREAM_CHUNK_BYTES);
        assertThat(caps).containsEntry("perClientCap", 10);
        assertThat(caps).containsEntry("globalCap", 100);
        assertThat(caps).containsKey("conversationBackfillLines");
        assertThat(caps).containsKey("keepalivePingSeconds");
    }
}
