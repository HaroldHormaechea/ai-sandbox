package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handler.SessionStreamHandler;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AC9 / AC27 / AC28 — the {@code SessionStreamHandler} must NOT close
 * every upgrade with {@code POLICY_VIOLATION}. The developer's fix gives
 * the handler two ways to resolve a {@link ClientIdentity}:
 *
 * <ol>
 *   <li>Pre-stashed session attribute ({@code IDENTITY_ATTR}) — used by
 *       unit tests that inject identity without TLS plumbing.</li>
 *   <li>Channel-id lookup against {@link com.aisandbox.server.identity.ActiveConnectionRegistry}
 *       — the production TLS path. Lives behind a post-construct setter
 *       so the constructor signature stays stable.</li>
 * </ol>
 *
 * <p>This test exercises the session-attribute fallback: with identity
 * stashed up front, the handler must NOT short-circuit to POLICY_VIOLATION.
 * The production TLS-side path is exercised end-to-end by the DinD-gated
 * {@code StreamHandshakeIT}.
 */
class SessionStreamHandlerIdentityTest {

    private static ServerProperties props() {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
    }

    @Test
    void no_identity_anywhere_closes_with_policy_violation() {
        SessionStreamHandler handler = newHandler(new StreamFacade(
                mock(SessionRegistryService.class),
                new StreamRegistryService(props()),
                mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                props()));

        FakeWebSocketSession session = new FakeWebSocketSession(URI.create("/v1/sessions/1/stream"), new HashMap<>());

        handler.handle(session).block();

        assertThat(session.closedWith).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void identity_stashed_in_session_attributes_is_picked_up_before_policy_violation() throws Exception {
        // Wire the facade to claim "session does not exist", which is the
        // earliest non-identity-related exit. That proves identity
        // resolution succeeded (otherwise the handler would have closed
        // with POLICY_VIOLATION and never asked the facade).
        SessionRegistryService sessions = mock(SessionRegistryService.class);
        when(sessions.exists(1)).thenReturn(false);

        StreamFacade facade = new StreamFacade(
                sessions,
                new StreamRegistryService(props()),
                mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                props());
        SessionStreamHandler handler = newHandler(facade);

        ClientIdentity stashed = new ClientIdentity("alice", "f".repeat(64), BigInteger.ONE);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionStreamHandler.IDENTITY_ATTR, stashed);
        FakeWebSocketSession session = new FakeWebSocketSession(URI.create("/v1/sessions/1/stream"), attrs);

        handler.handle(session).block();

        // We never see POLICY_VIOLATION — identity resolved successfully.
        assertThat(session.closedWith).isNotEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void bad_path_closes_with_BAD_DATA() {
        SessionStreamHandler handler = newHandler(new StreamFacade(
                mock(SessionRegistryService.class),
                new StreamRegistryService(props()),
                mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                props()));

        FakeWebSocketSession session = new FakeWebSocketSession(URI.create("/no/session/number/here"), new HashMap<>());

        handler.handle(session).block();

        assertThat(session.closedWith).isEqualTo(CloseStatus.BAD_DATA);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static SessionStreamHandler newHandler(StreamFacade facade) {
        return new SessionStreamHandler(facade, new StreamControlMessageService(), 262144, 262144, 16384);
    }

    /**
     * Minimal {@link WebSocketSession} double — enough surface for {@link
     * SessionStreamHandler#handle(WebSocketSession)} to thread through.
     * We don't subclass {@code ReactorNettyWebSocketSession} because the
     * production identity-resolution path branches on that class only when
     * no attribute is stashed (the channel-id fallback). For the session-
     * attribute path, any {@code WebSocketSession} works.
     */
    static final class FakeWebSocketSession implements WebSocketSession {

        private final URI uri;
        private final Map<String, Object> attrs;
        private final HandshakeInfo handshakeInfo;
        CloseStatus closedWith;

        FakeWebSocketSession(URI uri, Map<String, Object> attrs) {
            this.uri = uri;
            this.attrs = attrs;
            this.handshakeInfo = new HandshakeInfo(uri, new HttpHeaders(), Mono.empty(), null);
        }

        @Override
        public String getId() {
            return "fake-session";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return handshakeInfo;
        }

        @Override
        public org.springframework.core.io.buffer.DataBufferFactory bufferFactory() {
            return org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attrs;
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return Flux.empty();
        }

        @Override
        public Mono<Void> send(org.reactivestreams.Publisher<WebSocketMessage> messages) {
            return Mono.empty();
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
                    WebSocketMessage.Type.TEXT,
                    bufferFactory().wrap(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public WebSocketMessage binaryMessage(
                java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory,
                                org.springframework.core.io.buffer.DataBuffer>
                        payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory()));
        }

        @Override
        public WebSocketMessage pingMessage(
                java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory,
                                org.springframework.core.io.buffer.DataBuffer>
                        payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory()));
        }

        @Override
        public WebSocketMessage pongMessage(
                java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory,
                                org.springframework.core.io.buffer.DataBuffer>
                        payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory()));
        }
    }
}
