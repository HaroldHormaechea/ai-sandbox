package com.aisandbox.server.stream.service;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.stream.service.StreamRegistryService.ActiveStream;
import java.nio.ByteBuffer;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

/**
 * Sends a WebSocket ping every {@code keepalive-ping-seconds} (default 30s).
 * Sessions that don't return a pong within
 * {@code keepalive-pong-timeout-seconds} (default 15s) are closed with
 * status 1001 (AC33).
 *
 * <p>Implemented as a {@code @Scheduled} sweeper so we don't open one
 * timer per stream.
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
        for (ActiveStream s : registry.snapshot()) {
            long sinceIo = now - s.lastIo.toEpochMilli();
            if (sinceIo > pingMs) {
                try {
                    s.session
                            .send(reactor.core.publisher.Mono.just(
                                    s.session.pingMessage(bf -> bf.wrap(ByteBuffer.allocate(0)))))
                            .subscribe();
                } catch (RuntimeException ignored) {
                    // Best-effort ping; the session may be racing-closed.
                }
            }
            if (sinceIo > pingMs + pongTimeoutMs) {
                s.session.close(CloseStatus.GOING_AWAY).subscribe();
                registry.unregister(s.id);
            }
        }
    }
}
