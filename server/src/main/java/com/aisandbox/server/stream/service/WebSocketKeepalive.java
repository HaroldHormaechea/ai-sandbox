package com.aisandbox.server.stream.service;

import com.aisandbox.server.config.ServerProperties;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Sends a WebSocket ping every {@code keepalive-ping-seconds} (default 30s).
 * Sessions that don't return a pong within
 * {@code keepalive-pong-timeout-seconds} (default 15s) are closed with
 * status 1001 (AC33).
 *
 * <p>Implemented as a {@code @Scheduled} sweeper so we don't open one
 * timer per stream.
 *
 * <p>UC-100 — now keyed on the connection-level entries the single
 * {@code /v1/mux} handler registers (not per-stream {@code ActiveStream}s), so a
 * conversation- or events-only client — which holds no {@code ActiveStream} —
 * still gets pinged.
 */
@Component
@Profile("!docs-only")
public class WebSocketKeepalive {

    private final StreamRegistryService registry;
    private final long pingMs;
    private final long pongTimeoutMs;

    public WebSocketKeepalive(StreamRegistryService registry, ServerProperties props) {
        this.registry = registry;
        this.pingMs = props.streams().keepalivePingSeconds() * 1000L;
        this.pongTimeoutMs = props.streams().keepalivePongTimeoutSeconds() * 1000L;
    }

    @Scheduled(fixedDelay = 5_000L)
    public void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<WebSocketSession, Instant> entry :
                registry.connectionSnapshot().entrySet()) {
            WebSocketSession session = entry.getKey();
            long sinceIo = now - entry.getValue().toEpochMilli();
            if (sinceIo > pingMs) {
                try {
                    session.send(reactor.core.publisher.Mono.just(
                                    session.pingMessage(bf -> bf.wrap(ByteBuffer.allocate(0)))))
                            .subscribe();
                } catch (RuntimeException ignored) {
                    // Best-effort ping; the session may be racing-closed.
                }
            }
            if (sinceIo > pingMs + pongTimeoutMs) {
                session.close(CloseStatus.GOING_AWAY).subscribe();
                registry.unregisterConnection(session);
            }
        }
    }
}
