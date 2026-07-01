package com.aisandbox.server.stream.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * UC-96 — regression guard for the <b>replay-mode {@code AnswerEcho} emit-ordering
 * race</b> that made {@code MultiQuestionGateTest} deterministically time out.
 *
 * <p><b>The bug (server-side, replay-only).</b> Under the deterministic-gate replay
 * profile ({@code answerEchoEnabled()==true}), {@code applyAnswerBatch} used to call
 * {@code facade.injectAnswerBatch(...)} <i>before</i> emitting the per-question
 * {@code AnswerEcho} frames. Under replay, that inject offers the gate token that
 * unparks the fixture tail pump, which races to EOF and calls {@code tryEmitComplete}
 * on the same outbound sink. Because {@link SessionConversationHandler}'s {@code emit}
 * does {@code tryEmitNext} and <b>ignores the {@link Sinks.EmitResult}</b>, any echo
 * enqueued after the sink completes is {@code FAIL_TERMINATED} and silently dropped. A
 * batch of N questions lands N post-unpark emits, so its 2nd (and later) echoes lose
 * the window — the exact single-passes / multi-fails differential.
 *
 * <p><b>The fix (committed, {@code b22171c}).</b> The echo-emit block now runs strictly
 * <i>before</i> the gate-releasing inject in both {@code applyAnswerBatch} and
 * {@code applyAnswer}. With the pump still parked, every echo is enqueued into the live
 * (not-yet-completed) sink first; the pump completes only afterwards.
 *
 * <p>These tests pin the <b>ordering invariant</b>: <i>every</i> {@code AnswerEcho} for
 * a batch reaches the outbound sink <b>strictly before</b> the terminal
 * {@code tryEmitComplete}. The check is deliberately an <i>ordering</i> assertion, not a
 * bare count, so it cannot pass for the wrong reason. The harness models the production
 * race precisely: the mocked {@code injectAnswerBatch}/{@code injectAnswer} <b>opens the
 * tail gate</b> (releasing the fixture pump to EOF → sink completion), exactly as
 * {@code ReplayAnswerSink.recordAnswer(Batch)} does in the real replay facade.
 *
 * <p><b>Repro-first (AC6) at the mechanism level.</b> Because the fix is already
 * committed, the pre-fix ordering is reconstructed against the identical sink machinery
 * (unicast {@code onBackpressureBuffer}, emit-ignores-EmitResult, complete-under-lock) in
 * {@link #reconstructed_pre_fix_ordering_drops_the_second_echo_so_the_guard_goes_red()}:
 * completing the sink first (gate release) then attempting the echo emits drops them, and
 * the very ordering assertion the guard makes goes <b>red</b>. The post-fix order is shown
 * green in {@link #reconstructed_post_fix_ordering_keeps_both_echoes_before_complete()}.
 */
class MultiQuestionEchoOrderingTest {

    private static final String TOKEN = ConversationSubprotocolHandshakeInterceptor.SUBPROTOCOL;

    /** Ordered-events sentinel recorded when the outbound sink completes. */
    private static final String COMPLETE = "__outbound_complete__";

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

    private static HttpHeaders subprotocolHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.add("Sec-WebSocket-Protocol", TOKEN);
        return h;
    }

    /** A raw transcript line for an {@code AskUserQuestion} tool_use carrying {@code questions}. */
    private static String askUserQuestionLine(String questionsJson) {
        return "{\"type\":\"assistant\",\"uuid\":\"uq\",\"isSidechain\":false,\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuQ\",\"name\":\"AskUserQuestion\",\"input\":{\"questions\":"
                + questionsJson + "}}]}}";
    }

    // ──────────────── real-handler ordering guard (green with the committed fix) ────────────────

    /**
     * Drive the REAL Allowed-path handler under replay mode. The tail first emits
     * {@code questionLine} (caching the {@code AskUserQuestion} + emitting its
     * {@code question} frame), then delivers {@code clientFrame} inbound ONLY once that
     * question frame is out (so the answer resolves against a populated cache). The mocked
     * inject (stubbed by the caller) OPENS the tail gate — {@code gateOpened.countDown()} —
     * modelling {@code ReplayAnswerSink}: the inject is what unparks the pump. The pump
     * then reads EOF and completes the sink, counting down {@code sinkCompleted}.
     *
     * <p>The caller's inject stub additionally <b>awaits {@code sinkCompleted}</b> before
     * returning, turning the inject into a hard barrier: after it returns, the sink is
     * guaranteed terminal. This makes the ordering guard <b>deterministic</b> — pre-fix
     * (emit-after-inject) the echoes are always attempted on an already-completed sink and
     * dropped; post-fix (emit-before-inject) they are always enqueued into the live sink
     * first. It models the exact adversarial CI schedule the bug hits, and the fixed code
     * survives it because the echoes precede the inject. No deadlock: {@code sinkCompleted}
     * is counted down on the pump thread, independent of the inject-caller thread.
     */
    private static OrderingSession driveReplayAnswer(
            ConversationFacade facade,
            String questionLine,
            String clientFrame,
            CountDownLatch gateOpened,
            CountDownLatch sinkCompleted)
            throws Exception {
        when(facade.answerEchoEnabled()).thenReturn(true);

        TranscriptTailService.Tail tail = mock(TranscriptTailService.Tail.class);
        when(tail.readLine()).thenReturn(questionLine).thenAnswer(inv -> {
            // The gate is released by the inject call (see the doAnswer stubs below), exactly
            // as recordAnswer(Batch) offers the replay token that unparks the fixture tail.
            gateOpened.await(5, TimeUnit.SECONDS);
            return null; // EOF → pump teardown → tryEmitComplete on the outbound sink
        });

        when(facade.authorizeOpen(eq(7), any())).thenReturn(new StreamFacade.Allowed());
        when(facade.startTail(eq(7), any())).thenReturn(tail);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionConversationHandler.IDENTITY_ATTR, identity());
        OrderingSession session =
                new OrderingSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), attrs);
        session.sinkCompleted = sinkCompleted;

        CountDownLatch questionEmitted = new CountDownLatch(1);
        session.onSent = payload -> {
            if (payload.contains("\"type\":\"question\"")) {
                questionEmitted.countDown();
            }
        };
        session.incoming = Mono.fromCallable(() -> {
                    questionEmitted.await(5, TimeUnit.SECONDS);
                    return session.textMessage(clientFrame);
                })
                .flux()
                .subscribeOn(Schedulers.boundedElastic());

        newHandler(facade).handle(session).block();
        return session;
    }

    @Test
    void batch_answer_emits_both_echoes_before_the_terminal_complete() throws Exception {
        // A 2-question AskUserQuestion (single + multi). Under replay, applyAnswerBatch must
        // emit BOTH AnswerEcho frames into the live sink BEFORE injectAnswerBatch opens the gate
        // that lets the pump complete the sink — otherwise the 2nd echo is FAIL_TERMINATED and
        // MultiQuestionGateTest never sees its 2nd frame (the always-red timeout).
        ConversationFacade facade = mock(ConversationFacade.class);
        CountDownLatch gateOpened = new CountDownLatch(1);
        CountDownLatch sinkCompleted = new CountDownLatch(1);
        doAnswer(inv -> {
                    gateOpened.countDown(); // the inject IS the gate release (unparks the pump)
                    sinkCompleted.await(5, TimeUnit.SECONDS); // barrier: return only once the sink is terminal
                    return null;
                })
                .when(facade)
                .injectAnswerBatch(eq(7), any(), any(), any());

        String questionLine = askUserQuestionLine("["
                + "{\"question\":\"Q0\",\"header\":\"H0\",\"multiSelect\":false,\"options\":"
                + "[{\"label\":\"A\",\"description\":\"\"},{\"label\":\"B\",\"description\":\"\"}]},"
                + "{\"question\":\"Q1\",\"header\":\"H1\",\"multiSelect\":true,\"options\":"
                + "[{\"label\":\"X\",\"description\":\"\"},{\"label\":\"Y\",\"description\":\"\"}]}]");
        String batchFrame = "{\"type\":\"answer-batch\",\"questionUuid\":\"tuQ\",\"answers\":["
                + "{\"questionIndex\":0,\"selections\":[0],\"freeText\":\"\"},"
                + "{\"questionIndex\":1,\"selections\":[1],\"freeText\":\"\"}]}";

        OrderingSession session = driveReplayAnswer(facade, questionLine, batchFrame, gateOpened, sinkCompleted);

        // ORDERING INVARIANT: both echoes present AND both strictly before the terminal complete.
        assertBothBatchEchoesEmittedBeforeComplete(session.events);
        assertThat(session.closedWith).isNull();
    }

    @Test
    void single_answer_emits_its_echo_before_the_terminal_complete() throws Exception {
        // Single-question analogue: applyAnswer must emit its one AnswerEcho before injectAnswer
        // opens the gate. (Single lands ONE post-unpark emit, so it usually "won" the race even
        // pre-fix — the symmetric reorder guarantees the single leg cannot regress.)
        ConversationFacade facade = mock(ConversationFacade.class);
        CountDownLatch gateOpened = new CountDownLatch(1);
        CountDownLatch sinkCompleted = new CountDownLatch(1);
        doAnswer(inv -> {
                    gateOpened.countDown();
                    sinkCompleted.await(5, TimeUnit.SECONDS); // barrier: return only once the sink is terminal
                    return null;
                })
                .when(facade)
                .injectAnswer(eq(7), any(), anyInt(), anyBoolean(), any(), anyInt(), any(), any());

        String questionLine = askUserQuestionLine("["
                + "{\"question\":\"Q0\",\"header\":\"H0\",\"multiSelect\":false,\"options\":"
                + "[{\"label\":\"A\",\"description\":\"\"},{\"label\":\"B\",\"description\":\"\"}]}]");
        String answerFrame =
                "{\"type\":\"answer\",\"questionUuid\":\"tuQ\",\"questionIndex\":0,\"selections\":[1],\"freeText\":\"\"}";

        OrderingSession session = driveReplayAnswer(facade, questionLine, answerFrame, gateOpened, sinkCompleted);

        int idxComplete = session.events.indexOf(COMPLETE);
        int echo = indexOfEcho(session.events, 0);
        assertThat(idxComplete).as("outbound sink completed").isGreaterThanOrEqualTo(0);
        assertThat(echo).as("single AnswerEcho was emitted").isGreaterThanOrEqualTo(0);
        assertThat(echo).as("echo strictly precedes the terminal complete").isLessThan(idxComplete);
        assertThat(session.closedWith).isNull();
    }

    // ──────────────── repro-first (AC6): mechanism-level before/after ────────────────

    @Test
    void reconstructed_pre_fix_ordering_drops_the_second_echo_so_the_guard_goes_red() {
        // REPRO (red). The committed handler cannot be un-fixed here, so the pre-fix ORDERING is
        // reconstructed against the identical sink machinery the handler uses: a unicast
        // onBackpressureBuffer sink, emit = tryEmitNext IGNORING the EmitResult (SessionConversationHandler#emit),
        // complete = tryEmitComplete under the lock (pumpTail's finally). Pre-fix, the gate-releasing
        // inject runs first → the pump completes the sink → THEN the echoes are attempted and are
        // FAIL_TERMINATED (silently dropped). This is the exact <2 the gate observes.
        Sink sink = new Sink();

        // Gate release (inject) completed the sink FIRST …
        sink.complete();
        // … THEN the handler attempts the two batch echoes (post-unpark): both are dropped.
        sink.emit(answerEchoJson(0));
        sink.emit(answerEchoJson(1));

        // Neither echo reached the client (silently dropped after terminal) — repro confirmed.
        assertThat(countBatchEchoes(sink.events)).isZero();
        // And the very ordering-invariant assertion the green guard makes would FAIL on this trace.
        assertThatThrownBy(() -> assertBothBatchEchoesEmittedBeforeComplete(sink.events))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void reconstructed_post_fix_ordering_keeps_both_echoes_before_complete() {
        // AFTER (green) on the identical machinery: echoes emitted into the still-live sink FIRST,
        // gate-release completion LAST → both echoes land, strictly before complete. The same
        // assertion that went red above now passes.
        Sink sink = new Sink();

        sink.emit(answerEchoJson(0));
        sink.emit(answerEchoJson(1));
        sink.complete();

        assertBothBatchEchoesEmittedBeforeComplete(sink.events);
    }

    // ──────────────── shared ordering assertion + helpers ────────────────

    /** The load-bearing invariant: BOTH batch echoes present and strictly before the terminal complete. */
    private static void assertBothBatchEchoesEmittedBeforeComplete(List<String> events) {
        int idxComplete = events.indexOf(COMPLETE);
        assertThat(idxComplete).as("outbound sink must reach terminal complete").isGreaterThanOrEqualTo(0);
        int e0 = indexOfEcho(events, 0);
        int e1 = indexOfEcho(events, 1);
        assertThat(e0).as("AnswerEcho for questionIndex 0 must be emitted").isGreaterThanOrEqualTo(0);
        assertThat(e1).as("AnswerEcho for questionIndex 1 must be emitted").isGreaterThanOrEqualTo(0);
        assertThat(e0)
                .as("echo[0] must be enqueued strictly BEFORE the terminal complete")
                .isLessThan(idxComplete);
        assertThat(e1)
                .as("echo[1] must be enqueued strictly BEFORE the terminal complete")
                .isLessThan(idxComplete);
        // Terminal-after-data: complete is the last recorded event (nothing survives past it).
        assertThat(idxComplete).as("complete is terminal").isEqualTo(events.size() - 1);
    }

    private static int indexOfEcho(List<String> events, int questionIndex) {
        for (int i = 0; i < events.size(); i++) {
            String f = events.get(i);
            if (f.contains("\"type\":\"answer-echo\"") && f.contains("\"questionIndex\":" + questionIndex)) {
                return i;
            }
        }
        return -1;
    }

    private static long countBatchEchoes(List<String> events) {
        return events.stream()
                .filter(f -> f.contains("\"type\":\"answer-echo\""))
                .count();
    }

    private static String answerEchoJson(int questionIndex) {
        return "{\"type\":\"answer-echo\",\"questionUuid\":\"tuQ\",\"questionIndex\":" + questionIndex
                + ",\"selections\":[0],\"freeText\":\"\"}";
    }

    /**
     * Faithful reconstruction of {@link SessionConversationHandler}'s outbound sink discipline:
     * a unicast {@code onBackpressureBuffer} sink; {@code emit} = {@code tryEmitNext} IGNORING the
     * {@link Sinks.EmitResult} (so a post-terminal enqueue is silently dropped — the UC-96 bug
     * surface); {@code complete} = {@code tryEmitComplete} under the same lock. Records every frame
     * and a {@link #COMPLETE} sentinel in emission order.
     */
    private static final class Sink {
        private final Sinks.Many<WebSocketMessage> outbound =
                Sinks.many().unicast().onBackpressureBuffer();
        private final Object lock = new Object();
        final List<String> events = new ArrayList<>();

        Sink() {
            outbound.asFlux()
                    .doOnNext(m -> events.add(m.getPayloadAsText()))
                    .doOnComplete(() -> events.add(COMPLETE))
                    .subscribe();
        }

        void emit(String json) {
            WebSocketMessage frame = new WebSocketMessage(
                    WebSocketMessage.Type.TEXT,
                    DefaultDataBufferFactory.sharedInstance.wrap(json.getBytes(StandardCharsets.UTF_8)));
            synchronized (lock) {
                outbound.tryEmitNext(frame); // EmitResult intentionally ignored (mirrors emit())
            }
        }

        void complete() {
            synchronized (lock) {
                outbound.tryEmitComplete();
            }
        }
    }

    /**
     * Minimal {@link WebSocketSession} double that records outbound frames AND the terminal
     * complete in a single ordered list, so the echo-vs-complete ordering can be asserted.
     */
    static final class OrderingSession implements WebSocketSession {
        private final URI uri;
        private final HandshakeInfo handshakeInfo;
        private final Map<String, Object> attrs;
        final List<String> events = new ArrayList<>();
        CloseStatus closedWith;

        volatile Flux<WebSocketMessage> incoming = Flux.empty();
        volatile Consumer<String> onSent = s -> {};
        /** Counted down when the outbound sink reaches terminal complete (the inject barrier waits on it). */
        volatile CountDownLatch sinkCompleted = new CountDownLatch(1);

        OrderingSession(URI uri, HttpHeaders headers, Map<String, Object> attrs) {
            this.uri = uri;
            this.attrs = attrs;
            this.handshakeInfo = new HandshakeInfo(uri, headers, Mono.empty(), null);
        }

        @Override
        public String getId() {
            return "fake-conv-ordering";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return handshakeInfo;
        }

        @Override
        public org.springframework.core.io.buffer.DataBufferFactory bufferFactory() {
            return DefaultDataBufferFactory.sharedInstance;
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
                        events.add(payload);
                        onSent.accept(payload);
                    })
                    .doOnComplete(() -> {
                        events.add(COMPLETE);
                        sinkCompleted.countDown();
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
                    WebSocketMessage.Type.TEXT, bufferFactory().wrap(payload.getBytes(StandardCharsets.UTF_8)));
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
