package com.aisandbox.server.shutdown;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.StreamBridgeRegistry;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AC44 / UC-74 — on SIGTERM Spring stops accepting new HTTP via
 * {@code server.shutdown=graceful}; this lifecycle bean adds the
 * WebSocket-side ceremony and a bounded, deterministic teardown so the JVM
 * exits promptly instead of being pinned by a lingering client until the
 * systemd {@code TimeoutStopSec} backstop.
 *
 * <p>{@link #stop()} runs, in order:
 *
 * <ol>
 *   <li>Flip the facade {@code draining} flag (reject new stream upgrades).</li>
 *   <li>Drain the {@link StreamRegistryService} binary/terminal streams FIRST —
 *       this pass carries the UC-44 {@code STREAM_CLOSE} text-frame ceremony
 *       (send the close frame, then close with code 1001 GOING_AWAY).</li>
 *   <li>{@link ActiveStreamRegistry#gracefulCloseAll(CloseStatus)} as the
 *       superset safety-net — it is the index of EVERY live socket, including
 *       the structured-conversation channel that never registers in
 *       {@link StreamRegistryService}. Terminal sessions appear in both; the
 *       second close is an idempotent no-op.</li>
 *   <li>Destroy any remaining PTY bridges ({@link StreamBridgeRegistry}) so the
 *       pty4j {@code PtyProcess} children + per-client tmux sessions cannot keep
 *       handles alive (AC4) — done BEFORE the TCP-layer terminate.</li>
 *   <li>{@link ActiveConnectionRegistry#terminateAll()} as the final
 *       TCP-layer backstop.</li>
 * </ol>
 *
 * <p>The reactive WS-drain composite (steps 2–3) is bounded with a blocking
 * {@code .block(totalGrace)} — safe because {@code stop()} runs on a dedicated
 * lifecycle thread (same pattern as {@link ActiveConnectionRegistry#revoke}).
 * Each individual terminal close is additionally capped at {@code restGrace}.
 * With {@code spring.lifecycle.timeout-per-shutdown-phase} bounding the phase
 * and {@code total-grace} ≪ {@code TimeoutStopSec}, a non-responsive client
 * can no longer drag shutdown to the systemd backstop.
 */
@Component
@Profile("!docs-only")
public class GracefulShutdownHandler implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private static final String STREAM_CLOSE_FRAME = "{\"type\":\"close\",\"reason\":\"server_shutting_down\"}";

    private final StreamFacade facade;
    private final StreamRegistryService registry;
    private final ActiveStreamRegistry activeStreams;
    private final ActiveConnectionRegistry connections;
    private final StreamBridgeRegistry bridges;
    private final long restGraceMs;
    private final long totalGraceMs;
    private volatile boolean running = false;

    public GracefulShutdownHandler(
            StreamFacade facade,
            StreamRegistryService registry,
            ActiveStreamRegistry activeStreams,
            ActiveConnectionRegistry connections,
            StreamBridgeRegistry bridges,
            ServerProperties props) {
        this.facade = facade;
        this.registry = registry;
        this.activeStreams = activeStreams;
        this.connections = connections;
        this.bridges = bridges;
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
        LOG.info(
                "Shutdown started — per-close grace {}ms, total drain grace {}ms, live streams={}, live sockets={}",
                restGraceMs,
                totalGraceMs,
                registry.globalCount(),
                activeStreams.totalLiveSessions());

        // Steps 2–3 — drain the terminal/binary streams (UC-44 ceremony) FIRST,
        // then close every remaining live socket (the ActiveStreamRegistry
        // superset, incl. the structured-conversation channel). Block on the
        // composite with the bounded total-grace deadline; a stuck client can
        // no longer pin us beyond this budget.
        Mono<Void> drain = drainTerminalStreams().then(activeStreams.gracefulCloseAll(CloseStatus.GOING_AWAY));
        try {
            drain.block(Duration.ofMillis(totalGraceMs));
        } catch (RuntimeException re) {
            // block() throws on timeout (or an unrecoverable error); either way
            // we fall through to the deterministic PTY + TCP teardown below.
            LOG.warn("Graceful WS drain did not complete within {}ms: {}", totalGraceMs, re.toString());
        }

        // Step 4 — destroy any PTY bridges still registered (AC4), BEFORE the
        // TCP-layer terminate. Bridge.close() is idempotent, so this is a safe
        // backstop even when the handler's doFinally already closed the bridge.
        int destroyed = 0;
        for (TmuxBridgeService.Bridge b : bridges.snapshot()) {
            try {
                b.close();
                destroyed++;
            } catch (RuntimeException re) {
                LOG.warn("PTY bridge close hiccup during shutdown: {}", re.toString());
            }
        }
        if (destroyed > 0) {
            LOG.info("Destroyed {} PTY bridge(s) during shutdown", destroyed);
        }

        // Step 5 — final TCP-layer backstop: close every remaining channel.
        connections.terminateAll();

        LOG.info(
                "Shutdown complete; remaining streams={}, remaining sockets={}",
                registry.globalCount(),
                activeStreams.totalLiveSessions());
    }

    /**
     * Build the composite that closes each {@link StreamRegistryService} terminal
     * stream with the UC-44 ceremony — send the {@code STREAM_CLOSE} text frame,
     * then close with code 1001 (GOING_AWAY). Closes fan out concurrently; each
     * individual close is capped at the per-close {@code restGrace} budget so one
     * unresponsive client cannot stall the batch.
     */
    private Mono<Void> drainTerminalStreams() {
        return Flux.fromIterable(registry.snapshot())
                .flatMap(s -> s.session
                        .send(Mono.just(s.session.textMessage(STREAM_CLOSE_FRAME)))
                        .then(s.session.close(CloseStatus.GOING_AWAY))
                        .timeout(Duration.ofMillis(restGraceMs))
                        .onErrorResume(t -> {
                            LOG.warn("Stream close hiccup during shutdown: {}", t.toString());
                            return Mono.empty();
                        }))
                .then();
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
