package com.aisandbox.server.mux.service;

import com.aisandbox.server.config.ServerProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * UC-100 — the multiplex protocol version constant and the per-channel
 * capability defaults advertised in the {@code welcome} handshake. Caps are
 * derived from {@link ServerProperties.Streams} so the single connection keeps
 * honouring the same frame-size / backfill bounds the three legacy endpoints
 * enforced.
 */
@Component
public class MuxProtocol {

    /** Wire path of the single multiplexed WebSocket. */
    public static final String MUX_PATH = "/v1/mux";

    /** Advertised subprotocol token for the {@code /v1/mux} upgrade. */
    public static final String SUBPROTOCOL = "ai-sandbox.mux.v1";

    /**
     * Protocol version string exchanged in {@code hello}/{@code welcome} and
     * echoed by {@code GET /v1/capabilities} as {@code ws_protocol}. A hard-cut
     * mismatch (old client ↔ new server, or vice-versa) is refused with
     * {@code upgrade_required} + close {@code 4426}.
     */
    public static final String VERSION = "mux.v1";

    /** Close code for a version mismatch on the mux handshake (defense-in-depth alongside the 426 HTTP route). */
    public static final int CLOSE_UPGRADE_REQUIRED = 4426;

    /** Close code inherited from UC04 for a cert revocation (unchanged). */
    public static final int CLOSE_REVOKED = 4401;

    /** Max bytes carried in a single {@code stream}-channel binary envelope (challenger-endorsed chunk size). */
    public static final int STREAM_CHUNK_BYTES = 32 * 1024;

    private final ServerProperties props;

    public MuxProtocol(ServerProperties props) {
        this.props = props;
    }

    /** {@code true} when the client-advertised protocol string is compatible with this server. */
    public boolean accepts(String clientProtocol) {
        return VERSION.equals(clientProtocol);
    }

    /**
     * The per-channel capability map echoed in the {@code welcome} frame. Values
     * come from {@link ServerProperties.Streams} so the mux connection inherits
     * the legacy per-endpoint bounds (frame sizes, backfill window, caps).
     */
    public Map<String, Object> capabilities() {
        ServerProperties.Streams s = props.streams();
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("maxBinaryFrameBytes", s.maxBinaryFrameBytes());
        caps.put("maxTextFrameBytes", s.maxTextFrameBytes());
        caps.put("streamChunkBytes", STREAM_CHUNK_BYTES);
        caps.put("perClientCap", s.perClientCap());
        caps.put("globalCap", s.globalCap());
        caps.put("conversationBackfillLines", s.conversationBackfillLines());
        caps.put("keepalivePingSeconds", s.keepalivePingSeconds());
        return caps;
    }
}
