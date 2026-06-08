package com.aisandbox.server.stream.handshake;

import java.util.List;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * UC-37 (decision D1) — subprotocol gate for the structured-conversation
 * WebSocket ({@code /v1/sessions/{n}/conversation}). The required token is
 * {@link #SUBPROTOCOL}; a client that does not advertise it is cleanly rejected
 * (AC21).
 *
 * <p><b>Why the gate is handler-side, post-upgrade.</b> Reactor-Netty exposes no
 * working pre-upgrade handshake filter — the {@link SubprotocolHandshakeInterceptor}
 * / {@code StreamCapsHandshakeInterceptor} beans on the binary stream are
 * DORMANT no-ops kept only for autowire tracking (proposal RISK 7). So this
 * interceptor is NOT a Spring pre-upgrade hook either; it is a small, pure
 * parsing helper that {@code SessionConversationHandler.handle(...)} ACTUALLY
 * CALLS after the upgrade: the handler advertises {@link #SUBPROTOCOL} via
 * {@code WebSocketHandler.getSubProtocols()} (echoed in the 101), and if the
 * client did not advertise the token it emits a {@code ServerError} and closes
 * with 1003 (per {@code STREAM_PROTOCOL.md}'s close-code matrix). This mirrors
 * D1's instruction to extract {@link SubprotocolHandshakeInterceptor#accepts}'s
 * parsing into a helper and actually invoke it.
 */
@Component
public class ConversationSubprotocolHandshakeInterceptor {

    /** The mandatory conversation subprotocol — distinct from the binary stream's {@code ai-sandbox.v1}. */
    public static final String SUBPROTOCOL = "ai-sandbox.conv.v1";

    /** Whether the upgrade request advertised {@link #SUBPROTOCOL} in {@code Sec-WebSocket-Protocol}. */
    public boolean accepts(ServerHttpRequest req) {
        return advertises(req.getHeaders().get("Sec-WebSocket-Protocol"), SUBPROTOCOL);
    }

    /** Whether an established session's handshake advertised {@link #SUBPROTOCOL}. */
    public boolean accepts(WebSocketSession session) {
        return advertises(session.getHandshakeInfo().getHeaders().get("Sec-WebSocket-Protocol"), SUBPROTOCOL);
    }

    /**
     * Pure parsing helper — does any {@code Sec-WebSocket-Protocol} header value
     * (comma-separated list) advertise {@code subprotocol}? Case-insensitive,
     * whitespace-tolerant. Extracted so both the request and the session
     * overloads (and unit tests) share one implementation.
     */
    public static boolean advertises(List<String> protocolHeaders, String subprotocol) {
        if (protocolHeaders == null) {
            return false;
        }
        for (String h : protocolHeaders) {
            if (h == null) {
                continue;
            }
            for (String part : h.split(",")) {
                if (subprotocol.equalsIgnoreCase(part.trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}
