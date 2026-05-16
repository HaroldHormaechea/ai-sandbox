package com.aisandbox.server.stream.handshake;

import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;

/**
 * Rejects WebSocket upgrades that don't advertise the
 * {@code Sec-WebSocket-Protocol: ai-sandbox.v1} subprotocol (AC27).
 *
 * <p>Implemented as a "handshake interceptor" function called by
 * {@code SimpleUrlHandlerMapping}'s pre-upgrade hook.
 */
@Component
public class SubprotocolHandshakeInterceptor {

    public static final String SUBPROTOCOL = "ai-sandbox.v1";

    public boolean accepts(ServerHttpRequest req) {
        var protos = req.getHeaders().get("Sec-WebSocket-Protocol");
        if (protos == null) {
            return false;
        }
        for (String h : protos) {
            for (String part : h.split(",")) {
                if (SUBPROTOCOL.equalsIgnoreCase(part.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void rejectInto(ServerHttpResponse resp) {
        resp.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
        resp.getHeaders().add("Content-Type", "application/problem+json");
    }

    /** Reserved for future {@link WebSocketHandler} attribute population. */
    public Map<String, Object> attributes() {
        return Map.of("subprotocol", SUBPROTOCOL);
    }
}
