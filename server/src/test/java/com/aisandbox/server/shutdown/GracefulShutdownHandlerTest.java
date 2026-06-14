package com.aisandbox.server.shutdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.StreamBridgeRegistry;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.StreamRegistryService.ActiveStream;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService.Bridge;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * UC-74 — unit coverage for the rewritten {@link GracefulShutdownHandler#stop()}.
 *
 * <p>The bug: {@code systemctl restart ai-sandbox-server} dragged to ~60–70 s
 * whenever a client held a live WebSocket, because the old {@code stop()} issued
 * per-stream closes as fire-and-forget {@code .subscribe()} (never actually
 * draining them) and then spun a dead 100 ms-poll loop until the 60 s
 * {@code total-grace} budget elapsed — pinning the JVM until systemd's
 * {@code TimeoutStopSec=70} backstop. The fix makes {@code stop()} a bounded,
 * deterministic teardown:
 *
 * <ol>
 *   <li>flip {@code facade.setDraining(true)};</li>
 *   <li>drain the {@link StreamRegistryService} terminal streams (UC-44
 *       {@code STREAM_CLOSE} ceremony) and then close every remaining live
 *       socket via {@link ActiveStreamRegistry#gracefulCloseAll}, blocking the
 *       composite on a bounded {@code total-grace} deadline;</li>
 *   <li>destroy any registered PTY bridges (AC4);</li>
 *   <li>{@link ActiveConnectionRegistry#terminateAll()} as the final TCP
 *       backstop.</li>
 * </ol>
 *
 * <p>These tests verify the contract behind AC3 (real drain, not
 * fire-and-forget; bounded by {@code total-grace} even against a client that
 * never acks its close) and AC4 (PTY bridges destroyed) using a deliberately
 * tiny {@code total-grace} so the "stuck client" path resolves in ~1 s rather
 * than the production 10 s. AC1's wall-clock-under-{@code TimeoutStopSec}
 * guarantee follows directly from the bounded block proven here. The real
 * {@link ActiveStreamRegistry} / {@link StreamBridgeRegistry} are used (not
 * mocked) so the production fan-out and snapshot logic is exercised end to end.
 */
class GracefulShutdownHandlerTest {

    private static final int REST_GRACE_S = 1;
    private static final int TOTAL_GRACE_S = 1;

    private GracefulShutdownHandler newHandler(
            StreamFacade facade,
            StreamRegistryService registry,
            ActiveStreamRegistry activeStreams,
            ActiveConnectionRegistry connections,
            StreamBridgeRegistry bridges) {
        ServerProperties props = mock(ServerProperties.class);
        when(props.shutdown()).thenReturn(new ServerProperties.Shutdown(REST_GRACE_S, TOTAL_GRACE_S));
        return new GracefulShutdownHandler(facade, registry, activeStreams, connections, bridges, props);
    }

    /** A WebSocketSession mock whose send/close complete immediately (well-behaved client). */
    private WebSocketSession compliantSession() {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.textMessage(anyString())).thenReturn(mock(WebSocketMessage.class));
        when(s.send(any())).thenReturn(Mono.empty());
        when(s.close(any())).thenReturn(Mono.empty());
        return s;
    }

    @Test
    void stop_flips_draining_drains_both_registries_destroys_bridges_then_terminates_connections() {
        StreamFacade facade = mock(StreamFacade.class);

        // A terminal/binary stream lives in StreamRegistryService (UC-44 ceremony path).
        WebSocketSession terminalSession = compliantSession();
        StreamRegistryService registry = mock(StreamRegistryService.class);
        when(registry.snapshot())
                .thenReturn(List.of(new ActiveStream(new StreamId("term-1"), 1, "fpTerminal", terminalSession)));
        when(registry.globalCount()).thenReturn(1);

        // The structured-conversation channel registers ONLY in ActiveStreamRegistry,
        // so it is the superset the drain safety-net must catch. Use the real registry.
        ActiveStreamRegistry activeStreams = new ActiveStreamRegistry();
        WebSocketSession conversationSession = compliantSession();
        activeStreams.attach("fpConversation", conversationSession);
        // The terminal stream is also indexed here in production; the second close is a no-op.
        activeStreams.attach("fpTerminal", terminalSession);

        // A live PTY bridge that must be destroyed on shutdown (AC4). Real registry.
        StreamBridgeRegistry bridges = new StreamBridgeRegistry();
        Bridge bridge = mock(Bridge.class);
        bridges.register(new StreamId("term-1"), bridge);

        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);

        GracefulShutdownHandler handler = newHandler(facade, registry, activeStreams, connections, bridges);
        handler.start();
        handler.stop();

        // Step 1 — new upgrades rejected.
        verify(facade).setDraining(true);

        // Step 2 — terminal stream got the UC-44 ceremony: STREAM_CLOSE frame then 1001 close.
        // A terminal stream is indexed in BOTH registries in production, so it is closed by the
        // StreamRegistryService drain AND again by the ActiveStreamRegistry superset pass — the
        // second close is the documented idempotent no-op. Hence atLeastOnce(), not times(1).
        verify(terminalSession).send(any());
        verify(terminalSession, atLeastOnce()).close(CloseStatus.GOING_AWAY);

        // Step 3 — the conversation-only socket (ActiveStreamRegistry superset) was closed too.
        verify(conversationSession).close(CloseStatus.GOING_AWAY);

        // Step 4 — the PTY bridge was destroyed.
        verify(bridge).close();

        // Step 5 — the TCP-layer backstop ran.
        verify(connections).terminateAll();

        // gracefulCloseAll drains the index — no live sockets should remain.
        assertThat(activeStreams.totalLiveSessions()).isZero();

        assertThat(handler.isRunning()).isFalse();
    }

    @Test
    void stop_is_bounded_by_total_grace_when_a_client_never_acks_its_close() {
        StreamFacade facade = mock(StreamFacade.class);

        StreamRegistryService registry = mock(StreamRegistryService.class);
        when(registry.snapshot()).thenReturn(List.of()); // no terminal streams; the stuck socket is conversation-only
        when(registry.globalCount()).thenReturn(0);

        // A client that accepts the close frame but NEVER completes the close
        // handshake — close() returns Mono.never(). The old fire-and-forget code
        // would have spun the dead poll loop to the full grace; the new code
        // bounds it with .block(totalGrace).
        ActiveStreamRegistry activeStreams = new ActiveStreamRegistry();
        WebSocketSession stuck = mock(WebSocketSession.class);
        when(stuck.close(any())).thenReturn(Mono.never());
        activeStreams.attach("fpStuck", stuck);

        StreamBridgeRegistry bridges = new StreamBridgeRegistry();
        Bridge bridge = mock(Bridge.class);
        bridges.register(new StreamId("stuck-1"), bridge);

        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);

        GracefulShutdownHandler handler = newHandler(facade, registry, activeStreams, connections, bridges);
        handler.start();

        long startNs = System.nanoTime();
        handler.stop();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        // It actually WAITED for the drain (proves not fire-and-forget) ...
        assertThat(elapsedMs)
                .as("stop() must block on the drain, not return instantly like the old .subscribe()")
                .isGreaterThanOrEqualTo(700L);
        // ... but the wait is BOUNDED by total-grace (1 s here), nowhere near the
        // systemd TimeoutStopSec=70 s. Generous ceiling to stay non-flaky in CI.
        assertThat(elapsedMs)
                .as("stop() must be bounded by total-grace, not drag to the systemd backstop")
                .isLessThan(15_000L);

        // After the bounded block times out, the deterministic teardown still runs:
        // the stuck socket was issued a close, the PTY bridge was destroyed, and the
        // TCP backstop fired — so nothing keeps the JVM alive past the budget.
        verify(stuck).close(CloseStatus.GOING_AWAY);
        verify(bridge).close();
        verify(connections).terminateAll();
    }

    @Test
    void stop_with_no_clients_is_a_fast_clean_no_op_drain() {
        // AC6 — restart with no client connected stays fast and clean.
        StreamFacade facade = mock(StreamFacade.class);

        StreamRegistryService registry = mock(StreamRegistryService.class);
        when(registry.snapshot()).thenReturn(List.of());
        when(registry.globalCount()).thenReturn(0);

        ActiveStreamRegistry activeStreams = new ActiveStreamRegistry();
        StreamBridgeRegistry bridges = new StreamBridgeRegistry();
        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);

        GracefulShutdownHandler handler = newHandler(facade, registry, activeStreams, connections, bridges);
        handler.start();

        long startNs = System.nanoTime();
        handler.stop();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertThat(elapsedMs)
                .as("an empty drain must not wait out any grace budget")
                .isLessThan(2_000L);
        verify(facade).setDraining(true);
        verify(connections).terminateAll();
        // No sockets, no bridges → no close() calls beyond the backstop.
        verify(connections, never()).terminate(any());
    }

    @Test
    void each_terminal_stream_close_is_capped_so_one_stuck_terminal_cannot_stall_the_batch() {
        // AC3 — a single non-responsive terminal stream is capped at rest-grace and
        // must not prevent a sibling well-behaved stream from being drained.
        StreamFacade facade = mock(StreamFacade.class);

        WebSocketSession healthy = compliantSession();
        WebSocketSession stuckTerminal = mock(WebSocketSession.class);
        when(stuckTerminal.textMessage(anyString())).thenReturn(mock(WebSocketMessage.class));
        when(stuckTerminal.send(any())).thenReturn(Mono.never()); // never finishes sending the close frame

        StreamRegistryService registry = mock(StreamRegistryService.class);
        when(registry.snapshot())
                .thenReturn(List.of(
                        new ActiveStream(new StreamId("healthy"), 1, "fpA", healthy),
                        new ActiveStream(new StreamId("stuck"), 2, "fpB", stuckTerminal)));
        when(registry.globalCount()).thenReturn(2);

        ActiveStreamRegistry activeStreams = new ActiveStreamRegistry();
        StreamBridgeRegistry bridges = new StreamBridgeRegistry();
        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);

        GracefulShutdownHandler handler = newHandler(facade, registry, activeStreams, connections, bridges);
        handler.start();

        long startNs = System.nanoTime();
        handler.stop();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        // The healthy stream completed its ceremony; the stuck one was capped.
        verify(healthy).close(CloseStatus.GOING_AWAY);
        verify(stuckTerminal, times(1)).send(any());
        // Bounded by the (rest/total)-grace budgets, not the 70 s backstop.
        assertThat(elapsedMs).isLessThan(15_000L);
        verify(connections).terminateAll();
    }
}
