package com.aisandbox.server.stream.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handshake.ConversationSubprotocolHandshakeInterceptor;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.TranscriptTailService;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * {@code SessionStreamHandlerIdentityTest}). These tests pin the four early exits:
 *
 * <ul>
 *   <li>absent subprotocol → {@code error} frame + close <b>1003</b> (the AC21 gate);</li>
 *   <li>subprotocol present but no identity → close <b>1008</b> (policy violation);</li>
 *   <li>subprotocol + identity but not authorized → {@code error} frame + close 1008;</li>
 *   <li>malformed path (no session number) → close <b>1007</b> (bad data).</li>
 * </ul>
 *
 * <p><b>Allowed-path control-line dispatch (the UC-37 bug-fix gate).</b> The
 * Allowed path normally spawns a real {@code docker compose exec} transcript
 * tail (DinD-gated IT tier), but by mocking {@link ConversationFacade#startTail}
 * to return a fake {@link TranscriptTailService.Tail} we drive the long-lived
 * tail pump in-process and pin {@code dispatchTailLine}'s control-frame routing
 * without Docker. This covers the fix for the three conversation-mode bugs whose
 * shared root cause was an unresolvable transcript: the helper now fails LOUD
 * with a {@code __ctrl__\tno-transcript} line, and the handler must surface it as
 * a <b>non-fatal</b> {@code error} frame that does NOT close the channel
 * (regression guard for the original silent-hang). The backfill markers
 * ({@code backfill-start}/{@code backfill-end}) that AC6 leans on are pinned the
 * same way, and the tail is asserted to be anchored to the URI's session number
 * (a server-tier slice of AC23 — a session's tail is opened for that session
 * only; the cross-session-leakage prevention proper lives in the Node helper's
 * identity anchoring, off-limits to {@code paths.test}).
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
        FakeSession session =
                new FakeSession(URI.create("/v1/sessions/7/conversation"), new HttpHeaders(), new HashMap<>());

        newHandler(mock(ConversationFacade.class)).handle(session).block();

        assertThat(session.closedWith).isNotNull();
        assertThat(session.closedWith.getCode()).isEqualTo(1003);
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("unsupported_subprotocol"));
    }

    @Test
    void subprotocol_present_but_no_identity_closes_with_policy_violation() {
        ConversationFacade facade = mock(ConversationFacade.class);
        FakeSession session =
                new FakeSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), new HashMap<>());

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

    // ──────────────── Allowed path — control-line dispatch (UC-37 fix) ────────────────

    /**
     * Build an Allowed-path handler whose tail emits {@code tailLines} (in order)
     * then EOF, and drive it to completion against a fresh {@link FakeSession}.
     * Mocking {@link TranscriptTailService.Tail#readLine()} lets the real
     * long-lived pump + {@code dispatchTailLine} run with no Docker. {@code block()}
     * returns only once the pump hits EOF and completes the outbound sink, so every
     * emitted frame is already recorded in {@link FakeSession#sent}.
     */
    private static FakeSession driveAllowedTail(String... tailLines) throws Exception {
        TranscriptTailService.Tail tail = mock(TranscriptTailService.Tail.class);
        var stub = when(tail.readLine());
        for (String l : tailLines) {
            stub = stub.thenReturn(l);
        }
        stub.thenReturn(null); // EOF → pump tears down, completes the sink

        ConversationFacade facade = mock(ConversationFacade.class);
        when(facade.authorizeOpen(eq(7), any())).thenReturn(new StreamFacade.Allowed());
        when(facade.startTail(eq(7), any())).thenReturn(tail);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionConversationHandler.IDENTITY_ATTR, identity());
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), attrs);

        newHandler(facade).handle(session).block();
        return session;
    }

    @Test
    void no_transcript_control_emits_nonfatal_error_and_keeps_channel_open() throws Exception {
        // The helper failed to resolve an active transcript and signalled it LOUD.
        FakeSession session = driveAllowedTail("__ctrl__\tno-transcript");

        // Surfaced to the client as the no_transcript error frame …
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("no_transcript"));
        // … but NON-fatal: the handler must NOT close the channel (the original bug
        // was a silent hang; the new behaviour is observable yet survivable).
        assertThat(session.closedWith).isNull();
    }

    @Test
    void no_transcript_then_backfill_recovers_on_the_same_open_channel() throws Exception {
        // After failing loud, a later claude (re)start makes the transcript appear:
        // backfill markers flow through on the SAME still-open channel.
        FakeSession session =
                driveAllowedTail("__ctrl__\tno-transcript", "__ctrl__\tbackfill-start", "__ctrl__\tbackfill-end");

        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("no_transcript"));
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("backfill-start"));
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("backfill-end"));
        assertThat(session.closedWith).isNull();
    }

    @Test
    void tail_is_anchored_to_the_path_session_number() throws Exception {
        // AC23 (server-tier slice): the tail opened for this WebSocket is started
        // for the session number in the URI (7), never a foreign session.
        TranscriptTailService.Tail tail = mock(TranscriptTailService.Tail.class);
        when(tail.readLine()).thenReturn(null);

        ConversationFacade facade = mock(ConversationFacade.class);
        when(facade.authorizeOpen(eq(7), any())).thenReturn(new StreamFacade.Allowed());
        when(facade.startTail(eq(7), any())).thenReturn(tail);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionConversationHandler.IDENTITY_ATTR, identity());
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), attrs);

        newHandler(facade).handle(session).block();

        verify(facade).startTail(eq(7), eq(SessionConversationHandler.TARGET_MAIN));
    }

    // ──────────────── UC-41 AC5/AC9 — fetch-detail → tool-detail ────────────────

    /**
     * Drive an Allowed-path handler with a single inbound {@code fetch-detail} frame. The
     * tail blocks on a latch and EOFs only once the {@code tool-detail} response frame has
     * been emitted, so {@code block()} returns deterministically with the response already
     * recorded in {@link FakeSession#sent} — no Docker, no sleeps.
     */
    private static FakeSession driveFetchDetail(ConversationFacade facade, String fetchDetailJson) throws Exception {
        CountDownLatch detailEmitted = new CountDownLatch(1);
        TranscriptTailService.Tail tail = mock(TranscriptTailService.Tail.class);
        when(tail.readLine()).thenAnswer(inv -> {
            detailEmitted.await(5, TimeUnit.SECONDS); // hold the channel open until the reply lands …
            return null; // … then EOF → teardown completes the sink
        });

        when(facade.authorizeOpen(eq(7), any())).thenReturn(new StreamFacade.Allowed());
        when(facade.startTail(eq(7), any())).thenReturn(tail);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionConversationHandler.IDENTITY_ATTR, identity());
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), attrs);
        session.onSent = payload -> {
            if (payload.contains("tool-detail")) {
                detailEmitted.countDown();
            }
        };
        session.incoming = Flux.just(session.textMessage(fetchDetailJson));

        newHandler(facade).handle(session).block();
        return session;
    }

    @Test
    void fetch_detail_emits_a_tool_detail_frame_with_the_full_input_and_result() throws Exception {
        ConversationFacade facade = mock(ConversationFacade.class);
        when(facade.fetchToolDetail(eq(7), any(), eq("tu1"), any()))
                .thenReturn(new ConversationFacade.ToolDetailView(
                        "tu1", "Bash", "ls -la /workspace", "drwxr-xr-x output", false, true));

        FakeSession session =
                driveFetchDetail(facade, "{\"type\":\"fetch-detail\",\"toolUseId\":\"tu1\",\"uuid\":\"u-line\"}");

        assertThat(session.sent).anySatisfy(f -> assertThat(f)
                .contains("\"type\":\"tool-detail\"")
                .contains("\"toolUseId\":\"tu1\"")
                .contains("\"input\":\"ls -la /workspace\"")
                .contains("\"available\":true"));
        assertThat(session.closedWith).isNull();
    }

    @Test
    void fetch_detail_miss_emits_an_unavailable_tool_detail_frame() throws Exception {
        // AC9 — an unresolvable id yields available=false rather than hanging.
        ConversationFacade facade = mock(ConversationFacade.class);
        when(facade.fetchToolDetail(eq(7), any(), eq("gone"), any()))
                .thenReturn(new ConversationFacade.ToolDetailView("gone", null, "", "", false, false));

        FakeSession session =
                driveFetchDetail(facade, "{\"type\":\"fetch-detail\",\"toolUseId\":\"gone\",\"uuid\":\"u-line\"}");

        assertThat(session.sent).anySatisfy(f -> assertThat(f)
                .contains("\"type\":\"tool-detail\"")
                .contains("\"toolUseId\":\"gone\"")
                .contains("\"available\":false"));
        assertThat(session.closedWith).isNull();
    }

    @Test
    void fetch_detail_degrades_to_unavailable_when_the_facade_throws() throws Exception {
        // AC9 — a facade exception must NOT crash the pump; the client sees available=false.
        ConversationFacade facade = mock(ConversationFacade.class);
        when(facade.fetchToolDetail(eq(7), any(), eq("boom"), any()))
                .thenThrow(new RuntimeException("helper exploded"));

        FakeSession session =
                driveFetchDetail(facade, "{\"type\":\"fetch-detail\",\"toolUseId\":\"boom\",\"uuid\":\"u-line\"}");

        assertThat(session.sent).anySatisfy(f -> assertThat(f)
                .contains("\"type\":\"tool-detail\"")
                .contains("\"available\":false"));
        assertThat(session.closedWith).isNull();
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

        /** Inbound client frames delivered on {@link #receive()} (empty by default). */
        volatile Flux<WebSocketMessage> incoming = Flux.empty();

        /** Invoked for every outbound frame as it is sent (used to synchronize tail teardown in tests). */
        volatile Consumer<String> onSent = s -> {};

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
            return incoming;
        }

        @Override
        public Mono<Void> send(Publisher<WebSocketMessage> messages) {
            return Flux.from(messages)
                    .doOnNext(m -> {
                        String payload = m.getPayloadAsText();
                        sent.add(payload);
                        onSent.accept(payload);
                    })
                    .then();
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
