package com.aisandbox.server.sessionevents.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Delta;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Row;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Snapshot;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade.SubscribeDecision;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.facade.SessionFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * UC-32 — the reactive WebSocket handler for {@code /v1/sessions/events}.
 *
 * <p>The mTLS gate + per-client controls are the testable contract (AC2):
 * <ul>
 *   <li>a null / {@link ClientIdentity#isAnonymous() anonymous} principal is
 *       closed with {@code POLICY_VIOLATION} (1008) — the same allowlist the
 *       REST + terminal-stream endpoints enforce;</li>
 *   <li>a {@code DRAINING} decision closes with {@code GOING_AWAY} (1001) and a
 *       {@code CAP_EXCEEDED} decision with {@code SERVICE_OVERLOAD} (1013);</li>
 *   <li>an {@code ALLOWED} subscriber receives an initial {@link Snapshot} and
 *       then the watcher's {@link Delta} frames in order (AC1 / AC3 — snapshot
 *       first, deltas after, never lost in the subscribe gap);</li>
 *   <li>the feed is indexed on the shared {@link ActiveStreamRegistry} for the
 *       4401 revocation path and detached on close (the mTLS-revocation pitfall).</li>
 * </ul>
 *
 * <p>Identity is supplied through the {@code IDENTITY_ATTR} session-attribute
 * seam, exactly as {@code SessionStreamHandlerIdentityTest} does for the
 * terminal stream — the production Netty channel-id path is exercised by the
 * TLS-gated integration tests.
 */
class SessionEventWebSocketHandlerIdentityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private static ClientIdentity identity() {
        return new ClientIdentity("alice", "f".repeat(64), BigInteger.ONE);
    }

    // ── close-path tests (handle() completes immediately) ──────────────────

    @Test
    void null_identity_closes_with_policy_violation() {
        SessionEventFacade facade = mock(SessionEventFacade.class);
        SessionEventWebSocketHandler handler =
                new SessionEventWebSocketHandler(facade, new SessionEventBroadcaster(), MAPPER);

        CapturingSession session = new CapturingSession(new HashMap<>());
        handler.handle(session).block(TIMEOUT);

        assertThat(session.closedWith).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void anonymous_identity_closes_with_policy_violation() {
        SessionEventFacade facade = mock(SessionEventFacade.class);
        SessionEventWebSocketHandler handler =
                new SessionEventWebSocketHandler(facade, new SessionEventBroadcaster(), MAPPER);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionEventWebSocketHandler.IDENTITY_ATTR, ClientIdentity.ANONYMOUS);
        CapturingSession session = new CapturingSession(attrs);
        handler.handle(session).block(TIMEOUT);

        assertThat(session.closedWith).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void draining_decision_closes_with_going_away() {
        SessionEventFacade facade = mock(SessionEventFacade.class);
        when(facade.authorizeSubscribe(any())).thenReturn(SubscribeDecision.DRAINING);
        SessionEventWebSocketHandler handler =
                new SessionEventWebSocketHandler(facade, new SessionEventBroadcaster(), MAPPER);

        CapturingSession session = stashed();
        handler.handle(session).block(TIMEOUT);

        assertThat(session.closedWith).isEqualTo(CloseStatus.GOING_AWAY);
    }

    @Test
    void cap_exceeded_decision_closes_with_service_overload() {
        SessionEventFacade facade = mock(SessionEventFacade.class);
        when(facade.authorizeSubscribe(any())).thenReturn(SubscribeDecision.CAP_EXCEEDED);
        SessionEventWebSocketHandler handler =
                new SessionEventWebSocketHandler(facade, new SessionEventBroadcaster(), MAPPER);

        CapturingSession session = stashed();
        handler.handle(session).block(TIMEOUT);

        assertThat(session.closedWith).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
    }

    // ── subscription path (snapshot then delta, registry indexing) ─────────

    @Test
    void allowed_subscriber_receives_initial_snapshot_then_a_delta_in_order() throws Exception {
        Instant started = Instant.parse("2026-06-05T10:15:30Z");
        SessionFacade sessionFacade = mock(SessionFacade.class);
        when(sessionFacade.listSessions())
                .thenReturn(List.of(new SessionRecord(1, "build", "vim", "running", 7L, 1, started)));

        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        SessionEventFacade facade = new SessionEventFacade(sessionFacade, broadcaster);
        ActiveStreamRegistry registry = new ActiveStreamRegistry();

        SessionEventWebSocketHandler handler = new SessionEventWebSocketHandler(facade, broadcaster, MAPPER);
        handler.setActiveStreamRegistry(registry);

        CapturingSession session = stashed();
        Disposable run = handler.handle(session).subscribe();

        // The sink is registered before the snapshot is taken, so the feed is
        // live and indexed for 4401 revocation the moment we subscribe.
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        assertThat(registry.sessionCountFor(identity().fingerprintHex())).isEqualTo(1);

        // Frame 0 is the authoritative full snapshot (AC5 resync), carrying the
        // mapped row field-for-field (AC2).
        SessionEventMessage first = session.awaitFrame(0);
        assertThat(first).isInstanceOf(Snapshot.class);
        assertThat(((Snapshot) first).sessions())
                .containsExactly(new Row(1, "build", "vim", "running", 7L, 1, started));

        // A watcher-style broadcast arrives as the next frame, after the snapshot.
        Delta delta = new Delta(List.of(new Row(1, "build", "vim", "stopped", 7L, 0, started)), List.of());
        broadcaster.broadcast(delta);

        SessionEventMessage second = session.awaitFrame(1);
        assertThat(second).isInstanceOf(Delta.class);
        assertThat(((Delta) second).upserts()).containsExactly(new Row(1, "build", "vim", "stopped", 7L, 0, started));

        // Tear down — doFinally must unregister the sink and detach from the
        // revocation registry (so a closed feed leaves no leak).
        run.dispose();
        assertThat(broadcaster.subscriberCount()).isZero();
        assertThat(registry.sessionCountFor(identity().fingerprintHex())).isZero();
    }

    @Test
    void subscribe_before_any_session_yields_an_empty_snapshot() throws Exception {
        SessionFacade sessionFacade = mock(SessionFacade.class);
        when(sessionFacade.listSessions()).thenReturn(List.of());

        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        SessionEventFacade facade = new SessionEventFacade(sessionFacade, broadcaster);
        SessionEventWebSocketHandler handler = new SessionEventWebSocketHandler(facade, broadcaster, MAPPER);

        CapturingSession session = stashed();
        Disposable run = handler.handle(session).subscribe();

        SessionEventMessage first = session.awaitFrame(0);
        assertThat(first).isInstanceOf(Snapshot.class);
        assertThat(((Snapshot) first).sessions()).isEmpty();

        run.dispose();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static CapturingSession stashed() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionEventWebSocketHandler.IDENTITY_ATTR, identity());
        return new CapturingSession(attrs);
    }

    /**
     * Minimal {@link WebSocketSession} double that records every outbound text
     * frame (deserialized back into a {@link SessionEventMessage}). {@code send}
     * eagerly subscribes the outbound publisher so {@code concat(snapshot, sink)}
     * is driven synchronously; it returns a never-completing {@link Mono} to
     * mirror a live socket whose send leg stays open until the sink completes.
     */
    static final class CapturingSession implements WebSocketSession {

        private final URI uri = URI.create("/v1/sessions/events");
        private final Map<String, Object> attrs;
        private final HandshakeInfo handshakeInfo;
        private final CopyOnWriteArrayList<SessionEventMessage> frames = new CopyOnWriteArrayList<>();
        volatile CloseStatus closedWith;

        CapturingSession(Map<String, Object> attrs) {
            this.attrs = attrs;
            this.handshakeInfo = new HandshakeInfo(uri, new HttpHeaders(), Mono.empty(), null);
        }

        SessionEventMessage awaitFrame(int index) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (frames.size() <= index) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Timed out waiting for frame " + index + "; have " + frames.size());
                }
                Thread.sleep(5);
            }
            return frames.get(index);
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Flux.from(messages)
                    .doOnNext(m -> {
                        try {
                            frames.add(MAPPER.readValue(m.getPayloadAsText(), SessionEventMessage.class));
                        } catch (IOException e) {
                            throw new IllegalStateException("undecodable frame: " + m.getPayloadAsText(), e);
                        }
                    })
                    .then(Mono.never());
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return Flux.empty();
        }

        @Override
        public String getId() {
            return "fake-events-session";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return handshakeInfo;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return DefaultDataBufferFactory.sharedInstance;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attrs;
        }

        @Override
        public boolean isOpen() {
            return closedWith == null;
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            this.closedWith = status;
            return Mono.empty();
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return Mono.justOrEmpty(closedWith);
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            return new WebSocketMessage(
                    WebSocketMessage.Type.TEXT, bufferFactory().wrap(payload.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public WebSocketMessage binaryMessage(java.util.function.Function<DataBufferFactory, DataBuffer> factory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, factory.apply(bufferFactory()));
        }

        @Override
        public WebSocketMessage pingMessage(java.util.function.Function<DataBufferFactory, DataBuffer> factory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, factory.apply(bufferFactory()));
        }

        @Override
        public WebSocketMessage pongMessage(java.util.function.Function<DataBufferFactory, DataBuffer> factory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, factory.apply(bufferFactory()));
        }
    }
}
