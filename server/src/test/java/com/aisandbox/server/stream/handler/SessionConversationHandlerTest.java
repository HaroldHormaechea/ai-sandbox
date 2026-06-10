package com.aisandbox.server.stream.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handshake.ConversationSubprotocolHandshakeInterceptor;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.InputInjectionService;
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
import org.mockito.ArgumentCaptor;
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

    // ──────────────── UC-50 — pane-signal pending-prompt control dispatch ────────────────
    // The helper emits TWO new control lines, each carrying a THIRD tab-delimited field:
    //   __ctrl__\tpending-question\t<json>   and   __ctrl__\tpending-clear\t<key>
    // The handler splits the control payload on the FIRST tab (so legacy 2-field control
    // lines still dispatch byte-identically), maps the JSON, caches a synthesized Question,
    // and emits PendingPrompt — UNLESS a transcript-derived prompt already fired this turn.

    private static String pendingQuestionCtrl(String json) {
        return TranscriptTailService.CTRL_SOURCE + "\t" + TranscriptTailService.CTRL_PENDING_QUESTION + "\t" + json;
    }

    private static final String SINGLE_PENDING_JSON =
            "{\"kind\":\"questions\",\"key\":\"pane-k1\",\"plan\":\"\",\"questions\":["
                    + "{\"question\":\"Which database?\",\"header\":\"Database\",\"multiSelect\":false,"
                    + "\"options\":[{\"label\":\"PostgreSQL\",\"description\":\"\"},"
                    + "{\"label\":\"MySQL\",\"description\":\"\"}]}]}";

    private static final String MULTI_PENDING_JSON = "{\"kind\":\"questions\",\"key\":\"pane-k2\",\"questions\":["
            + "{\"header\":\"Color\",\"options\":[]},{\"header\":\"Size\",\"options\":[]}]}";

    private static long countPendingPrompt(List<String> sent) {
        return sent.stream()
                .filter(f -> f.contains("\"type\":\"pending-question\""))
                .count();
    }

    @Test
    void pending_question_control_emits_PendingPrompt_and_is_answerable_for_a_single_question() throws Exception {
        // AC1/AC3 — a live, pane-delivered single question is emitted as a PendingPrompt
        // (answerable=true) while the session blocks, with no transcript line at all.
        FakeSession session = driveAllowedTail(pendingQuestionCtrl(SINGLE_PENDING_JSON));

        assertThat(session.sent).anySatisfy(f -> assertThat(f)
                .contains("\"type\":\"pending-question\"")
                .contains("\"promptKey\":\"pane-k1\"")
                .contains("\"answerable\":true"));
        assertThat(countPendingPrompt(session.sent)).isEqualTo(1L);
        assertThat(session.closedWith).isNull();
    }

    @Test
    void pending_question_control_for_a_multi_batch_is_visible_but_answerable_false() throws Exception {
        // AC2 — a multi-question batch is delivered (visible) but answerable=false, since
        // only the focused tab's options are recoverable from one pane capture.
        FakeSession session = driveAllowedTail(pendingQuestionCtrl(MULTI_PENDING_JSON));

        assertThat(session.sent).anySatisfy(f -> assertThat(f)
                .contains("\"type\":\"pending-question\"")
                .contains("\"promptKey\":\"pane-k2\"")
                .contains("\"answerable\":false"));
        assertThat(session.closedWith).isNull();
    }

    @Test
    void pending_clear_control_emits_a_PendingClear_frame_with_the_key() throws Exception {
        // The pane chrome disappeared (answered/dismissed in tmux) with no resolving
        // transcript line → a key-carrying PendingClear so the client clears ONLY its own
        // pane-delivered sheet.
        FakeSession session = driveAllowedTail(
                TranscriptTailService.CTRL_SOURCE + "\t" + TranscriptTailService.CTRL_PENDING_CLEAR + "\tpane-k1");

        assertThat(session.sent)
                .anySatisfy(f ->
                        assertThat(f).contains("\"type\":\"pending-clear\"").contains("\"promptKey\":\"pane-k1\""));
        assertThat(session.closedWith).isNull();
    }

    @Test
    void transcript_prompt_this_turn_suppresses_a_following_pane_pending_prompt() throws Exception {
        // Transcript-vs-pane precedence: on a build that DOES write the blocking turn, the
        // transcript Question fires FIRST (sets transcriptPromptThisTurn), so a later pane
        // pending-question must NOT raise a second sheet — the transcript path owns it.
        FakeSession session = driveAllowedTail(
                askUserQuestionLine("[{\"question\":\"Pick\",\"header\":\"H\","
                        + "\"multiSelect\":false,\"options\":[{\"label\":\"A\",\"description\":\"\"}]}]"),
                pendingQuestionCtrl(SINGLE_PENDING_JSON));

        // The transcript question frame is emitted …
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("\"type\":\"question\""));
        // … but the pane PendingPrompt is suppressed for this turn.
        assertThat(countPendingPrompt(session.sent)).isEqualTo(0L);
        assertThat(session.closedWith).isNull();
    }

    @Test
    void AC9_pane_pending_first_then_transcript_question_emits_pending_once_and_does_not_resuppress() throws Exception {
        // AC9 (critical, current-claude order) — the pane delivers the pending question
        // FIRST (the blocking turn is NOT in the transcript), THEN claude later writes the
        // resolved turn. The PendingPrompt is emitted exactly ONCE (it preceded any
        // transcript prompt, so it is not suppressed), and the later transcript Question
        // is still emitted. The no-double-RENDER guarantee (the pane frame adds no inline
        // bubble) is enforced client-side and covered in ConversationControllerTest.
        FakeSession session = driveAllowedTail(
                pendingQuestionCtrl(SINGLE_PENDING_JSON),
                askUserQuestionLine("[{\"question\":\"Pick\",\"header\":\"H\","
                        + "\"multiSelect\":false,\"options\":[{\"label\":\"A\",\"description\":\"\"}]}]"));

        assertThat(countPendingPrompt(session.sent)).isEqualTo(1L);
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("\"type\":\"question\""));
        assertThat(session.closedWith).isNull();
    }

    @Test
    void malformed_pending_question_payload_is_skipped_without_crashing_the_pump() throws Exception {
        // AC20 parity — a malformed JSON payload yields no PendingPrompt and does NOT close
        // the channel; a subsequent backfill marker still flows on the same open channel.
        FakeSession session =
                driveAllowedTail(pendingQuestionCtrl("not-json"), "__ctrl__\tbackfill-start", "__ctrl__\tbackfill-end");

        assertThat(countPendingPrompt(session.sent)).isEqualTo(0L);
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("backfill-start"));
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("backfill-end"));
        assertThat(session.closedWith).isNull();
    }

    @Test
    void legacy_two_field_control_lines_still_dispatch_byte_identically_after_the_split_on_first_tab()
            throws Exception {
        // Back-compat guard for the split-on-first-tab change: the pre-UC-50 control lines
        // that carry NO third field (rebaseline, backfill-start/end) must still route
        // exactly as before. rebaseline is a server-side no-op (no frame), so we assert the
        // surrounding backfill markers flow and the channel stays open.
        FakeSession session =
                driveAllowedTail("__ctrl__\trebaseline", "__ctrl__\tbackfill-start", "__ctrl__\tbackfill-end");

        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("\"type\":\"backfill-start\""));
        assertThat(session.sent).anySatisfy(f -> assertThat(f).contains("\"type\":\"backfill-end\""));
        assertThat(session.closedWith).isNull();
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

        assertThat(session.sent)
                .anySatisfy(
                        f -> assertThat(f).contains("\"type\":\"tool-detail\"").contains("\"available\":false"));
        assertThat(session.closedWith).isNull();
    }

    // ──────────────── UC-43 — answer-batch routing + per-question derivation ────────────────

    /** A raw transcript line for an {@code AskUserQuestion} tool_use carrying {@code questions} (the mapper maps it to a Question frame, which the handler caches). */
    private static String askUserQuestionLine(String questionsJson) {
        return "{\"type\":\"assistant\",\"uuid\":\"uq\",\"isSidechain\":false,\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuQ\",\"name\":\"AskUserQuestion\",\"input\":{\"questions\":"
                + questionsJson + "}}]}}";
    }

    /**
     * Drive an Allowed-path handler that (1) emits {@code questionLine} on the tail
     * — caching the {@code AskUserQuestion} and emitting its {@code question} frame
     * — then (2) delivers {@code clientFrame} inbound ONLY once that question frame
     * has been emitted (so the cache is populated before the answer is applied),
     * then EOFs once {@code applied} fires (counted down by the facade-call stub).
     * {@code block()} returns deterministically with the facade call already made.
     */
    private static FakeSession driveQuestionThenClientFrame(
            ConversationFacade facade, String questionLine, String clientFrame, CountDownLatch applied)
            throws Exception {
        TranscriptTailService.Tail tail = mock(TranscriptTailService.Tail.class);
        when(tail.readLine()).thenReturn(questionLine).thenAnswer(inv -> {
            applied.await(5, TimeUnit.SECONDS); // hold the channel open until the answer is applied …
            return null; // … then EOF → teardown completes the sink
        });

        when(facade.authorizeOpen(eq(7), any())).thenReturn(new StreamFacade.Allowed());
        when(facade.startTail(eq(7), any())).thenReturn(tail);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(SessionConversationHandler.IDENTITY_ATTR, identity());
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/conversation"), subprotocolHeaders(), attrs);

        CountDownLatch questionEmitted = new CountDownLatch(1);
        session.onSent = payload -> {
            if (payload.contains("\"type\":\"question\"")) {
                questionEmitted.countDown();
            }
        };
        // The cache is populated (cacheQuestion runs before emit), so gating on the emitted
        // question frame guarantees the answer below resolves against a populated cache.
        session.incoming = reactor.core.publisher.Mono.fromCallable(() -> {
                    questionEmitted.await(5, TimeUnit.SECONDS);
                    return session.textMessage(clientFrame);
                })
                .flux()
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());

        newHandler(facade).handle(session).block();
        return session;
    }

    @Test
    @SuppressWarnings("unchecked")
    void answer_batch_routes_to_inject_answer_batch_sorted_and_derived_per_question() throws Exception {
        // AC2/AC3 — a 3-question AskUserQuestion (single, multi, single). The client sends the
        // answers OUT of questionIndex order (2,0,1); the handler must sort to 0,1,2 and derive
        // each spec's optionCount/multiSelect/otherIndex from the cached question[questionIndex].
        ConversationFacade facade = mock(ConversationFacade.class);
        CountDownLatch applied = new CountDownLatch(1);
        doAnswer(inv -> {
                    applied.countDown();
                    return null;
                })
                .when(facade)
                .injectAnswerBatch(eq(7), any(), any(), any());

        String questionLine = askUserQuestionLine("["
                + "{\"question\":\"Q0\",\"header\":\"H0\",\"multiSelect\":false,\"options\":"
                + "[{\"label\":\"A\",\"description\":\"\"},{\"label\":\"B\",\"description\":\"\"},{\"label\":\"C\",\"description\":\"\"}]},"
                + "{\"question\":\"Q1\",\"header\":\"H1\",\"multiSelect\":true,\"options\":"
                + "[{\"label\":\"X\",\"description\":\"\"},{\"label\":\"Y\",\"description\":\"\"}]},"
                + "{\"question\":\"Q2\",\"header\":\"H2\",\"multiSelect\":false,\"options\":"
                + "[{\"label\":\"P\",\"description\":\"\"},{\"label\":\"Q\",\"description\":\"\"}]}]");
        String batchFrame = "{\"type\":\"answer-batch\",\"questionUuid\":\"tuQ\",\"answers\":["
                + "{\"questionIndex\":2,\"selections\":[0],\"freeText\":\"\"},"
                + "{\"questionIndex\":0,\"selections\":[1],\"freeText\":\"\"},"
                + "{\"questionIndex\":1,\"selections\":[0],\"freeText\":\"\"}]}";

        FakeSession session = driveQuestionThenClientFrame(facade, questionLine, batchFrame, applied);

        ArgumentCaptor<List<InputInjectionService.BatchAnswerSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(facade).injectAnswerBatch(eq(7), any(), cap.capture(), any());
        List<InputInjectionService.BatchAnswerSpec> specs = cap.getValue();
        assertThat(specs).hasSize(3);
        // Sorted by questionIndex → 0,1,2; each derived from the cached AskUserQuestion. That the
        // optionCounts are non-zero proves the cached question was STILL present when the specs
        // were derived — i.e. eviction happens AFTER the (single) inject, never before it.
        assertThat(specs.get(0).optionCount()).isEqualTo(3);
        assertThat(specs.get(0).multiSelect()).isFalse();
        assertThat(specs.get(0).selections()).containsExactly(1);
        assertThat(specs.get(0).otherIndex()).isEqualTo(3);
        assertThat(specs.get(1).optionCount()).isEqualTo(2);
        assertThat(specs.get(1).multiSelect()).isTrue();
        assertThat(specs.get(1).selections()).containsExactly(0);
        assertThat(specs.get(2).optionCount()).isEqualTo(2);
        assertThat(specs.get(2).multiSelect()).isFalse();
        assertThat(specs.get(2).selections()).containsExactly(0);
        // A multi-question batch must NOT fall through to the single-answer path.
        verify(facade, never()).injectAnswer(anyInt(), any(), anyInt(), anyBoolean(), any(), anyInt(), any(), any());
        assertThat(session.closedWith).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void answer_batch_derives_free_text_specs_for_non_last_single_select_and_multiSelect_other() throws Exception {
        // UC-44 AC9 (server batch derivation) — the gap that let the bug ship. A 2-question batch
        // where BOTH questions use an "Other" free text:
        //   Q0 single-select [A,B], "Other" "custom0" on a NON-LAST question;
        //   Q1 multiSelect   [X,Y], option 0 + "Other" "custom1".
        // The client appends the Other index (== the question's option count) to `selections` and
        // carries the `freeText`. deriveAnswerSpec must, for each, set otherIndex = listed-option
        // count and BUMP optionCount by 1 (so the keystroke walk includes the "Type something" row),
        // while leaving multiSelect untouched. A blank freeText would NOT bump optionCount — here both
        // are non-blank, so both specs are bumped.
        ConversationFacade facade = mock(ConversationFacade.class);
        CountDownLatch applied = new CountDownLatch(1);
        doAnswer(inv -> {
                    applied.countDown();
                    return null;
                })
                .when(facade)
                .injectAnswerBatch(eq(7), any(), any(), any());

        String questionLine = askUserQuestionLine("["
                + "{\"question\":\"Q0\",\"header\":\"H0\",\"multiSelect\":false,\"options\":"
                + "[{\"label\":\"A\",\"description\":\"\"},{\"label\":\"B\",\"description\":\"\"}]},"
                + "{\"question\":\"Q1\",\"header\":\"H1\",\"multiSelect\":true,\"options\":"
                + "[{\"label\":\"X\",\"description\":\"\"},{\"label\":\"Y\",\"description\":\"\"}]}]");
        // Q0: Other index = 2 (== option count); Q1: option 0 plus Other index 2.
        String batchFrame = "{\"type\":\"answer-batch\",\"questionUuid\":\"tuQ\",\"answers\":["
                + "{\"questionIndex\":0,\"selections\":[2],\"freeText\":\"custom0\"},"
                + "{\"questionIndex\":1,\"selections\":[0,2],\"freeText\":\"custom1\"}]}";

        FakeSession session = driveQuestionThenClientFrame(facade, questionLine, batchFrame, applied);

        ArgumentCaptor<List<InputInjectionService.BatchAnswerSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(facade).injectAnswerBatch(eq(7), any(), cap.capture(), any());
        List<InputInjectionService.BatchAnswerSpec> specs = cap.getValue();
        assertThat(specs).hasSize(2);
        // Q0 — single-select non-last free text: optionCount bumped 2 → 3, otherIndex = 2, text carried.
        assertThat(specs.get(0).multiSelect()).isFalse();
        assertThat(specs.get(0).optionCount()).isEqualTo(3);
        assertThat(specs.get(0).otherIndex()).isEqualTo(2);
        assertThat(specs.get(0).selections()).containsExactly(2);
        assertThat(specs.get(0).freeText()).isEqualTo("custom0");
        // Q1 — multiSelect + free text: optionCount bumped 2 → 3, otherIndex = 2, both selections kept.
        assertThat(specs.get(1).multiSelect()).isTrue();
        assertThat(specs.get(1).optionCount()).isEqualTo(3);
        assertThat(specs.get(1).otherIndex()).isEqualTo(2);
        assertThat(specs.get(1).selections()).containsExactly(0, 2);
        assertThat(specs.get(1).freeText()).isEqualTo("custom1");
        assertThat(session.closedWith).isNull();
    }

    @Test
    void single_answer_path_is_unchanged_and_does_not_use_the_batch_path() throws Exception {
        // AC5 — a single-question AskUserQuestion still resolves via the single `answer` frame →
        // facade.injectAnswer with the option metadata derived from the cached question, and the
        // batch path is never touched.
        ConversationFacade facade = mock(ConversationFacade.class);
        CountDownLatch applied = new CountDownLatch(1);
        doAnswer(inv -> {
                    applied.countDown();
                    return null;
                })
                .when(facade)
                .injectAnswer(eq(7), any(), anyInt(), anyBoolean(), any(), anyInt(), any(), any());

        String questionLine = askUserQuestionLine("["
                + "{\"question\":\"Q0\",\"header\":\"H0\",\"multiSelect\":false,\"options\":"
                + "[{\"label\":\"A\",\"description\":\"\"},{\"label\":\"B\",\"description\":\"\"}]}]");
        String answerFrame =
                "{\"type\":\"answer\",\"questionUuid\":\"tuQ\",\"questionIndex\":0,\"selections\":[1],\"freeText\":\"\"}";

        FakeSession session = driveQuestionThenClientFrame(facade, questionLine, answerFrame, applied);

        verify(facade).injectAnswer(eq(7), any(), eq(2), eq(false), eq(List.of(1)), eq(2), eq(""), any());
        verify(facade, never()).injectAnswerBatch(anyInt(), any(), any(), any());
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
