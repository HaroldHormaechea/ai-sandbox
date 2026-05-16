package com.aisandbox.server.shutdown;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.StreamRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;

/**
 * AC44 — on SIGTERM Spring stops accepting new HTTP via
 * {@code server.shutdown=graceful}; we add the WebSocket-side ceremony:
 * send a {@code STREAM_CLOSE} text frame to each active session, close
 * each with code 1001 (GOING_AWAY), and force-exit after the configured
 * total grace period.
 */
@Component
@Profile("!docs-only")
public class GracefulShutdownHandler implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final StreamFacade facade;
    private final StreamRegistryService registry;
    private final long restGraceMs;
    private final long totalGraceMs;
    private volatile boolean running = false;

    public GracefulShutdownHandler(StreamFacade facade, StreamRegistryService registry, ServerProperties props) {
        this.facade = facade;
        this.registry = registry;
        this.restGraceMs = props.shutdown().restGraceSeconds() * 1000L;
        this.totalGraceMs = props.shutdown().totalGraceSeconds() * 1000L;
    }

    @Override
    public synchronized void start() {
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        facade.setDraining(true);
        long deadline = System.currentTimeMillis() + totalGraceMs;
        LOG.info("Shutdown started — REST grace {}ms, total grace {}ms", restGraceMs, totalGraceMs);

        for (var s : registry.snapshot()) {
            try {
                String text = "{\"type\":\"close\",\"reason\":\"server_shutting_down\"}";
                s.session
                        .send(reactor.core.publisher.Mono.just(s.session.textMessage(text)))
                        .then(s.session.close(CloseStatus.GOING_AWAY))
                        .subscribe();
                registry.unregister(s.id);
            } catch (RuntimeException re) {
                LOG.warn("Stream close hiccup during shutdown: {}", re.toString());
            }
        }

        while (registry.globalCount() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("Shutdown complete; remaining streams={}", registry.globalCount());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
