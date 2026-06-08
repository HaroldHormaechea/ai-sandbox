package com.aisandbox.server.stream.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handshake.ConversationSubprotocolHandshakeInterceptor;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * UC-37 AC21 — the conversation handler's <b>handshake / authorize gate</b>,
 * exercised through a minimal {@link WebSocketSession} double (mirrors
 * {@code SessionStreamHandlerIdentityTest}). The Allowed path is NOT exercised
 * here because it spawns a real {@code docker compose exec} transcript tail —
 * that belongs to the DinD-gated IT tier. These tests pin the four early exits:
 *
 * <ul>
 *   <li>absent subprotocol → {@code error} frame + close <b>1003</b> (the AC21 gate);</li>
 *   <li>subprotocol present but no identity → close <b>1008</b> (policy violation);</li>
 *   <li>subprotocol + identity but not authorized → {@code error} frame + close 1008;</li>
 *   <li>malformed path (no session number) → close <b>1007</b> (bad data).</li>
 * </ul>
 */
class SessionConversationHandlerTest {

    private static final String TOKEN = ConversationSubprotocolHandshakeInterceptor.SUBPROTOCOL;

    private static SessionConversationHandler newHandler(ConversationFacade facade) {
        return new SessionConversationHandler(
                facade,
                new StreamControlMessageService(),
                new ConversationEventMapper(),
                new ConversationSubprotocolHandshakeInterceptor(),
                262144);
    }

    private static ClientIdentity identity() {
        return new ClientIdentity("alice", "a".repeat(64), BigInteger.ONE);
    }

    @Test
    void absent_subprotocol_emits_error_and_closes_1003() {
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/conversation"), new HttpHeaders(), new HashMap<>());

        newHandler(mock(ConversationFacade.class)).handle(session).block();

        assertThat(session.closedWith).isNotNull();
        assertThat(session.closedWith.getCode()).isEqualTo(1003);
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("unsupported_subprotocol"));
    }

    @Test
    void subprotocol_present_but_no_identity_closes_with_policy_violation() {
        ConversationFacade facade = mock(ConversationFacade.class);
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), new HashMap<>());

        newHandler(facade).handle(session).block();

        assertThat(session.closedWith.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void subprotocol_and_identity_but_not_running_emits_error_and_closes_policy_violation() {
        ConversationFacade facade = mock(ConversationFacade.class);
        when(facade.authorizeOpen(eq(7), any())).thenReturn(new StreamFacade.NotRunning(7, "stopped"));

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionConversationHandler.IDENTITY_ATTR, identity());
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), attrs);

        newHandler(facade).handle(session).block();

        assertThat(session.closedWith.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("not_authorized"));
    }

    @Test
    void malformed_path_closes_with_bad_data() {
        FakeSession session = new FakeSession(URI.create("/no/session/number"), subprotocolHeaders(), new HashMap<>());

        newHandler(mock(ConversationFacade.class)).handle(session).block();

        assertThat(session.closedWith).isEqualTo(CloseStatus.BAD_DATA);
    }

    private static HttpHeaders subprotocolHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.add("Sec-WebSocket-Protocol", TOKEN);
        return h;
    }

    /** Minimal {@link WebSocketSession} double — records the close status and any sent text frames. */
    static final class FakeSession implements WebSocketSession {
        private final URI uri;
        private final HandshakeInfo handshakeInfo;
        private final Map<String, Object> attrs;
        final List<String> sent = new ArrayList<>();
        CloseStatus closedWith;

        FakeSession(URI uri, HttpHeaders headers, Map<String, Object> attrs) {
            this.uri = uri;
            this.attrs = attrs;
            this.handshakeInfo = new HandshakeInfo(uri, headers, Mono.empty(), null);
        }

        @Override
        public String getId() {
            return "fake-conv";
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
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Flux.from(messages).doOnNext(m -> sent.add(m.getPayloadAsText())).then();
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
                java.util.function.Function<
                                org.springframework.core.io.buffer.DataBufferFactory,
                                org.springframework.core.io.buffer.DataBuffer>
                        payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory()));
        }

        @Override
        public WebSocketMessage pingMessage(
                java.util.function.Function<
                                org.springframework.core.io.buffer.DataBufferFactory,
                                org.springframework.core.io.buffer.DataBuffer>
                        payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory()));
        }

        @Override
        public WebSocketMessage pongMessage(
                java.util.function.Function<
                                org.springframework.core.io.buffer.DataBufferFactory,
                                org.springframework.core.io.buffer.DataBuffer>
                        payloadFactory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory()));
        }
    }
}
