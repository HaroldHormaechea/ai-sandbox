package com.aisandbox.server.stream.handler;

import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.dto.ConversationClientMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handshake.ConversationSubprotocolHandshakeInterceptor;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.InputInjectionService;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.TranscriptTailService;
import io.netty.channel.ChannelId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * UC-37 — reactive WebSocket handler for the structured-conversation channel
 * ({@code /v1/sessions/{n}/conversation}), the JSON-only sibling of the binary
 * {@link SessionStreamHandler}. It tails the live session's transcript (output)
 * and injects the local composer / structured answers as {@code send-keys} into
 * the SAME tmux session (input) — never {@code claude -p}, never
 * {@code --remote-control} (AC23/AC24).
 *
 * <p><b>Subprotocol gate (D1, AC21).</b> {@link #getSubProtocols()} advertises
 * {@link ConversationSubprotocolHandshakeInterceptor#SUBPROTOCOL} (echoed in the
 * 101). Reactor-Netty has no working pre-upgrade filter, so the version gate is
 * applied HERE, post-upgrade: if the client did not advertise the token,
 * {@link #handle} emits a {@code ServerError} and closes 1003.
 *
 * <p><b>Identity</b> resolution reuses the {@link SessionStreamHandler} pattern:
 * a pre-stashed session attribute (tests) or the TLS-side
 * {@link ActiveConnectionRegistry} keyed by Netty {@link ChannelId}.
 *
 * <p><b>Target switching</b> mirrors the binary stream's generation-token
 * re-bridge: on a {@code select-target} the handler starts a fresh tail, swaps
 * the live handle, bumps a generation token (so the single long-lived tail pump
 * picks up the new handle), then closes the old one — never completing the
 * shared outbound sink on a swap.
 */
public class SessionConversationHandler implements WebSocketHandler {

    /** Key under which the resolved identity is stored on the WebSocket session. */
    public static final String IDENTITY_ATTR = "ai-sandbox.client-identity";

    /** Id of the always-present main-session target (AC16). */
    public static final String TARGET_MAIN = "main";

    private static final Logger LOG = LoggerFactory.getLogger(SessionConversationHandler.class);

    /** Close code for an unsupported / absent subprotocol (STREAM_PROTOCOL.md matrix). */
    private static final CloseStatus UNSUPPORTED = new CloseStatus(1003, "unsupported_subprotocol");

    /**
     * UC-55 — settle window after a multi-question option recovery during which transient
     * pane-signal control lines (a header-only pending-question for a NEW key, or a
     * pending-clear) are dropped. The recovery steps the live pane and restores it; the
     * helper's independent 300ms poll can briefly observe an intermediate tab and emit a
     * transient. This window absorbs those (the buffered transients are processed within
     * ms of recovery completing); a value comfortably above the helper poll interval.
     */
    private static final long RECOVERY_SUPPRESS_MS = 1500L;

    private final ConversationFacade facade;
    private final StreamControlMessageService controlSvc;
    private final ConversationEventMapper mapper;
    private final ConversationSubprotocolHandshakeInterceptor subprotocol;
    private final int maxTextBytes;

    private volatile ActiveConnectionRegistry connections;
    private volatile ActiveStreamRegistry streamRegistry;

    public SessionConversationHandler(
            ConversationFacade facade,
            StreamControlMessageService controlSvc,
            ConversationEventMapper mapper,
            ConversationSubprotocolHandshakeInterceptor subprotocol,
            int maxTextBytes) {
        this.facade = facade;
        this.controlSvc = controlSvc;
        this.mapper = mapper;
        this.subprotocol = subprotocol;
        this.maxTextBytes = maxTextBytes;
    }

    public void setActiveConnectionRegistry(ActiveConnectionRegistry connections) {
        this.connections = connections;
    }

    public void setActiveStreamRegistry(ActiveStreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    /** D1/AC21 — advertise the conversation subprotocol so it is echoed in the 101 handshake. */
    @Override
    public List<String> getSubProtocols() {
        return List.of(ConversationSubprotocolHandshakeInterceptor.SUBPROTOCOL);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        int n;
        try {
            n = extractN(session);
        } catch (IllegalArgumentException iae) {
            return session.close(CloseStatus.BAD_DATA);
        }

        // D1/AC21 — post-upgrade subprotocol gate.
        if (!subprotocol.accepts(session)) {
            return session.send(Mono.just(session.textMessage(serverErrorFrame(
                            "unsupported_subprotocol",
                            "Unsupported subprotocol",
                            "advertise " + ConversationSubprotocolHandshakeInterceptor.SUBPROTOCOL))))
                    .then(session.close(UNSUPPORTED));
        }

        ClientIdentity identity = resolveIdentity(session);
        if (identity == null) {
            LOG.warn("Closing conversation: no client identity for channel {}", channelIdOf(session));
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        session.getAttributes().put(IDENTITY_ATTR, identity);

        StreamFacade.AuthorizeResult auth = facade.authorizeOpen(n, identity);
        if (!(auth instanceof StreamFacade.Allowed)) {
            String detail = describe(auth);
            return session.send(Mono.just(session.textMessage(
                            serverErrorFrame("not_authorized", "Cannot open conversation", detail))))
                    .then(session.close(CloseStatus.POLICY_VIOLATION.withReason(detail)));
        }

        Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
        ConvCtx ctx = new ConvCtx(n, identity, outbound);

        ActiveStreamRegistry streams = this.streamRegistry;
        final String fingerprint = identity.fingerprintHex();
        if (streams != null) {
            streams.attach(fingerprint, session);
        }

        return Mono.fromCallable(() -> facade.startTail(n, ctx.selectedTarget.get()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(tail -> {
                    ctx.tailRef.set(tail);

                    Thread pump = new Thread(() -> pumpTail(session, ctx));
                    pump.setDaemon(true);
                    pump.setName("ai-sandbox-conv-tail-" + n);
                    pump.start();

                    Flux<Void> incoming = session.receive()
                            .flatMap(msg -> handleIncoming(msg, session, ctx))
                            .then()
                            .flux();
                    Mono<Void> outboundCompletion = session.send(outbound.asFlux());
                    return Mono.when(incoming, outboundCompletion);
                })
                .onErrorResume(t -> {
                    LOG.warn("conversation tail start failed for n={}: {}", n, t.toString());
                    return session.send(Mono.just(session.textMessage(
                                    serverErrorFrame("tail_failed", "Transcript tail failed", t.getMessage()))))
                            .then(session.close(CloseStatus.SERVER_ERROR));
                })
                .doFinally(sig -> {
                    TranscriptTailService.Tail t = ctx.tailRef.get();
                    if (t != null) {
                        t.close();
                    }
                    if (streams != null) {
                        streams.detach(fingerprint, session);
                    }
                    facade.auditClose(n, identity, 1000, sig.name());
                });
    }

    // ──────────────────────── outbound: tail → frames ────────────────────────

    /**
     * Single long-lived tail reader. Reads the CURRENT tail handle
     * ({@code ctx.tailRef}); on a target switch the generation token advances and
     * this loop picks up the swapped-in tail instead of treating the old tail's
     * EOF as teardown. The outbound sink is completed ONLY on true teardown.
     */
    private void pumpTail(WebSocketSession session, ConvCtx ctx) {
        int myGen = ctx.generation.get();
        TranscriptTailService.Tail tail = ctx.tailRef.get();
        try {
            while (true) {
                if (ctx.generation.get() != myGen) {
                    myGen = ctx.generation.get();
                    tail = ctx.tailRef.get();
                }
                if (tail == null) {
                    break;
                }
                String line;
                try {
                    line = tail.readLine();
                } catch (IOException io) {
                    if (ctx.generation.get() != myGen) {
                        continue; // old tail closed by a swap — pick up the new one
                    }
                    LOG.info("conversation tail reader done: {}", io.toString());
                    break;
                }
                if (line == null) {
                    if (ctx.generation.get() != myGen) {
                        continue; // old tail EOF due to a swap — continue on the new one
                    }
                    break; // true EOF → teardown
                }
                dispatchTailLine(session, ctx, line);
            }
        } finally {
            // Serialize the terminal complete against any in-flight emit from an
            // inbound-handler thread — see ConvCtx#outboundLock.
            synchronized (ctx.outboundLock) {
                ctx.outbound.tryEmitComplete();
            }
        }
    }

    /** Parse a helper envelope line ({@code source\traw}) and emit the mapped frame(s). */
    private void dispatchTailLine(WebSocketSession session, ConvCtx ctx, String line) {
        int tab = line.indexOf('\t');
        String source = tab < 0 ? "main" : line.substring(0, tab);
        String payload = tab < 0 ? line : line.substring(tab + 1);

        if (TranscriptTailService.CTRL_SOURCE.equals(source)) {
            // UC-50 — a control payload may carry a THIRD tab-delimited field (the
            // pending-prompt JSON / promptKey). Split on the FIRST tab so a payload
            // with no second tab dispatches byte-identically to the pre-UC-50 shape.
            int ptab = payload.indexOf('\t');
            String subtype = (ptab < 0 ? payload : payload.substring(0, ptab)).trim();
            String rest = ptab < 0 ? "" : payload.substring(ptab + 1);
            switch (subtype) {
                case TranscriptTailService.CTRL_BACKFILL_START -> {
                    // UC-79 — the primary backfill-start carries the window's start line
                    // index; seed/re-seed the per-connection oldest-line cursor from it so a
                    // later load-older knows where to page from (AC2). A re-backfill (target
                    // switch, rebaseline) re-seeds here. A subagent backfill inside the same
                    // window carries no index → keep the existing cursor.
                    seedOldestCursor(ctx, rest);
                    emit(ctx, session, new ConversationServerMessage.BackfillStart(ctx.selectedTarget.get()));
                }
                case TranscriptTailService.CTRL_BACKFILL_END -> emit(
                        ctx, session, new ConversationServerMessage.BackfillEnd(ctx.selectedTarget.get()));
                case TranscriptTailService.CTRL_REBASELINE -> {
                    // entrypoint restart loop spawned a fresh claude → new transcript. The
                    // helper re-baselines + re-backfills; the following backfill-start re-seeds
                    // the cursor. Clear any stale in-flight guard so paging works post-rotation.
                    ctx.loadingOlder.set(false);
                    LOG.debug("conversation tail rebaselined for n={}", ctx.n);
                }
                case TranscriptTailService.CTRL_NO_TRANSCRIPT -> {
                    // The helper could not resolve an active transcript within its
                    // grace window. Surface it as a NON-FATAL error frame (no close):
                    // the helper keeps polling and will emit backfill markers if the
                    // transcript later appears (e.g. mid claude restart). This makes
                    // the original silent-hang bug class observable client-side.
                    LOG.info("conversation tail reported no resolvable transcript for n={}", ctx.n);
                    emit(
                            ctx,
                            session,
                            new ConversationServerMessage.ServerError(
                                    "no_transcript",
                                    "No active transcript",
                                    "the live session's transcript could not be resolved yet"));
                }
                case TranscriptTailService.CTRL_PENDING_QUESTION -> dispatchPendingQuestion(session, ctx, rest);
                case TranscriptTailService.CTRL_PENDING_CLEAR -> dispatchPendingClear(session, ctx, rest);
                default -> {
                    /* unknown control — ignore */
                }
            }
            return;
        }

        for (ConversationServerMessage frame : mapper.map(source, payload)) {
            // UC-50 — transcript-vs-pane precedence: a transcript-derived Question /
            // PlanApproval (delivered when the build DOES write the blocking turn, via
            // the UC-40 residual idle-flush) wins over the pane PendingPrompt for this
            // turn, so the two delivery paths never both raise a sheet. The flag is
            // cleared on TurnStart / TurnEnd (the turn boundary).
            if (frame instanceof ConversationServerMessage.Question q) {
                cacheQuestion(ctx, q);
                ctx.transcriptPromptThisTurn = true;
            } else if (frame instanceof ConversationServerMessage.PlanApproval) {
                ctx.transcriptPromptThisTurn = true;
            } else if (frame instanceof ConversationServerMessage.TurnStart
                    || frame instanceof ConversationServerMessage.TurnEnd) {
                ctx.transcriptPromptThisTurn = false;
            }
            emit(ctx, session, frame);
        }
    }

    /**
     * UC-50/UC-55 — handle a pane-signal {@code pending-question} control line: map the
     * JSON payload to a {@link ConversationServerMessage.PendingPrompt}, cache a
     * synthesized {@link ConversationServerMessage.Question} keyed by promptKey (so an
     * in-app answer can derive its option spec), and emit the PendingPrompt — UNLESS a
     * transcript-derived prompt already fired this turn ({@link
     * ConvCtx#transcriptPromptThisTurn}), in which case the transcript path owns the
     * sheet and the pane signal is suppressed (no double sheet). On claude 2.1.169 the
     * transcript path never fires, so the pane signal is what delivers the question.
     *
     * <p><b>UC-55 — multi-question recovery (eager).</b> A multi-question batch arrives
     * header-only ({@code answerable=false}: one pane capture only shows the focused tab).
     * Here the handler eagerly recovers EVERY tab's options by stepping the live pane
     * (under the facade's per-session pane lock), re-maps the prompt through the same
     * {@link ConversationEventMapper#answerable} gate, and emits it {@code answerable=true}
     * with the full per-tab options — so the Android sheet renders the existing paged
     * controls and the cached {@code Question} carries the per-tab option metadata
     * {@code deriveAnswerSpec} needs for injection. No tmux fallback for the standard
     * multi-question wizard (AC2/AC5/AC10). The stepping races with the helper's own 300ms
     * pane poll, so two guards keep that race invisible to the client: (1) a header-only
     * re-emit for an already-recovered key (the helper re-settling the restored tab) is
     * dropped, and (2) for a short settle window after a recovery, a header-only
     * pending-question for a DIFFERENT key — a transient from the recovery's own
     * tab-stepping — is dropped.
     */
    private void dispatchPendingQuestion(WebSocketSession session, ConvCtx ctx, String json) {
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        if (pp == null) {
            return; // malformed payload — skip without crashing the pump (AC20 parity)
        }
        boolean multiHeaderOnly = "questions".equals(pp.kind())
                && !pp.answerable()
                && pp.questions() != null
                && pp.questions().size() > 1;
        if (multiHeaderOnly) {
            // Guard 1 — a header-only re-emit for an already-recovered key must not
            // downgrade the open full-options sheet (a racing poll re-settling the
            // restored tab). Drop it.
            if (ctx.recoveredKeys.contains(pp.promptKey())) {
                return;
            }
            // Guard 2 — a header-only multi-question prompt for a NEW key during the
            // post-recovery settle window is a transient produced by the recovery's own
            // tab-stepping (the pane is already restored to tab 0); drop it.
            if (System.currentTimeMillis() < ctx.recoverySuppressUntilMs) {
                return;
            }
            // Recover all tabs' options only when this pane signal actually owns the sheet
            // this turn (never perturb the pane when the transcript path owns it).
            if (!ctx.transcriptPromptThisTurn) {
                pp = recoverMultiQuestion(ctx, pp);
                if (pp.answerable()) {
                    ctx.recoveredKeys.add(pp.promptKey());
                    ctx.recoverySuppressUntilMs = System.currentTimeMillis() + RECOVERY_SUPPRESS_MS;
                }
            }
        }
        cacheQuestion(ctx, mapper.pendingPromptToQuestion(pp));
        if (!ctx.transcriptPromptThisTurn) {
            emit(ctx, session, pp);
        }
    }

    /**
     * UC-55 — step the live pane through every tab to recover the full per-question option
     * set, then re-map the prompt through the same {@code answerable} gate. On any failure
     * the original header-only prompt is returned unchanged (so the batch stays
     * {@code answerable=false} — the narrow genuinely-unrecoverable exception — rather than
     * crashing the pump or rendering a tab with no options).
     */
    private ConversationServerMessage.PendingPrompt recoverMultiQuestion(
            ConvCtx ctx, ConversationServerMessage.PendingPrompt pp) {
        try {
            List<ConversationServerMessage.QuestionItem> full =
                    facade.recoverWizardOptions(ctx.n, ctx.selectedTarget.get(), pp.questions());
            boolean answerable = mapper.answerable(pp.kind(), full);
            return new ConversationServerMessage.PendingPrompt(pp.promptKey(), pp.kind(), full, pp.plan(), answerable);
        } catch (RuntimeException e) {
            LOG.warn("UC-55 wizard option recovery failed for n={} key={}: {}", ctx.n, pp.promptKey(), e.toString());
            return pp;
        }
    }

    /**
     * UC-50/UC-55 — handle a pane-signal {@code pending-clear} control line: evict the
     * synthesized question cached under the promptKey and tell the client to clear its
     * pending sheet if that key matches the open sheet (it never clobbers a
     * transcript-delivered sheet, which carries a different key). UC-55: a clear arriving
     * within the post-recovery settle window is a transient from the recovery's own
     * tab-stepping (the pane briefly left/returned to the wizard chrome), not a real
     * resolution — drop it so the just-delivered answerable sheet is not torn down.
     */
    private void dispatchPendingClear(WebSocketSession session, ConvCtx ctx, String promptKey) {
        if (System.currentTimeMillis() < ctx.recoverySuppressUntilMs) {
            return;
        }
        if (promptKey != null && !promptKey.isBlank()) {
            ctx.pendingQuestions.remove(promptKey);
            ctx.recoveredKeys.remove(promptKey);
        }
        emit(ctx, session, new ConversationServerMessage.PendingClear(promptKey == null ? "" : promptKey));
    }

    private void cacheQuestion(ConvCtx ctx, ConversationServerMessage.Question q) {
        cachePut(ctx, q.toolUseId(), q);
        cachePut(ctx, q.uuid(), q);
    }

    /**
     * UC-55 (LOCKED CONDITION 2) — cache a synthesized question under {@code key}, but
     * NEVER downgrade a full-options cached {@code Question} to a header-only one for the
     * same key. A racing helper {@code pending-question} poll can re-deliver a multi-question
     * prompt header-only (empty options) while a recovered, full-options sheet is already
     * cached + open; overwriting it would strip the per-tab option metadata {@link
     * #deriveAnswerSpec} reads, desyncing the answer-injection walk. The
     * {@code dispatchPendingQuestion} guards drop most such re-emits before they reach here;
     * this is the defensive backstop that makes the invariant hold unconditionally.
     */
    private void cachePut(ConvCtx ctx, String key, ConversationServerMessage.Question q) {
        if (key == null) {
            return;
        }
        ConversationServerMessage.Question existing = ctx.pendingQuestions.get(key);
        if (existing != null && hasAnyOptions(existing) && !hasAnyOptions(q)) {
            return; // don't downgrade full-options → header-only
        }
        ctx.pendingQuestions.put(key, q);
    }

    /** True iff at least one of the question's items carries a non-empty option list. */
    private static boolean hasAnyOptions(ConversationServerMessage.Question q) {
        if (q.questions() == null) {
            return false;
        }
        for (ConversationServerMessage.QuestionItem item : q.questions()) {
            if (item.options() != null && !item.options().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ──────────────────────── inbound: frames → actions ────────────────────────

    private Mono<Void> handleIncoming(WebSocketMessage msg, WebSocketSession session, ConvCtx ctx) {
        if (msg.getType() != WebSocketMessage.Type.TEXT) {
            return Mono.empty(); // conversation channel is JSON-text only
        }
        String text = msg.getPayloadAsText();
        if (text.length() > maxTextBytes) {
            return session.close(CloseStatus.TOO_BIG_TO_PROCESS);
        }
        ConversationClientMessage cm;
        try {
            cm = controlSvc.parseConversation(text);
        } catch (IllegalArgumentException iae) {
            emit(
                    ctx,
                    session,
                    new ConversationServerMessage.ServerError(
                            "bad_request", "Bad conversation message", iae.getMessage()));
            return Mono.empty();
        }
        return applyClientMessage(cm, session, ctx);
    }

    private Mono<Void> applyClientMessage(ConversationClientMessage cm, WebSocketSession session, ConvCtx ctx) {
        switch (cm) {
            case ConversationClientMessage.ComposerInput in -> Schedulers.boundedElastic()
                    .schedule(() -> safe(
                            ctx,
                            session,
                            () -> facade.injectComposer(ctx.n, ctx.selectedTarget.get(), in.text(), ctx.identity)));
            case ConversationClientMessage.Answer a -> Schedulers.boundedElastic()
                    .schedule(() -> applyAnswer(session, ctx, a));
            case ConversationClientMessage.AnswerBatch ab -> Schedulers.boundedElastic()
                    .schedule(() -> applyAnswerBatch(session, ctx, ab));
            case ConversationClientMessage.Interrupt it -> Schedulers.boundedElastic()
                    .schedule(() ->
                            safe(ctx, session, () -> facade.interrupt(ctx.n, ctx.selectedTarget.get(), ctx.identity)));
            case ConversationClientMessage.SelectTarget st -> Schedulers.boundedElastic()
                    .schedule(() -> switchTarget(session, ctx, st.targetId()));
            case ConversationClientMessage.EnumerateTargets et -> Schedulers.boundedElastic()
                    .schedule(() -> {
                        List<com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo> targets =
                                facade.enumerateConversationTargets(ctx.n, ctx.selectedTarget.get());
                        emit(ctx, session, new ConversationServerMessage.Targets(targets, ctx.selectedTarget.get()));
                    });
            case ConversationClientMessage.FetchDetail fd -> Schedulers.boundedElastic()
                    .schedule(() -> fetchDetail(session, ctx, fd));
            case ConversationClientMessage.LoadOlder lo -> Schedulers.boundedElastic()
                    .schedule(() -> loadOlder(session, ctx));
            case ConversationClientMessage.Close c -> session.close(
                            CloseStatus.NORMAL.withReason(c.reason() == null ? "client-close" : c.reason()))
                    .subscribe();
        }
        return Mono.empty();
    }

    /**
     * UC-41 (AC5/AC9) — handle a {@code fetch-detail}: re-read the tool call's full input +
     * result via the facade and emit a {@code tool-detail} frame. Any failure (helper miss,
     * timeout, exception) degrades to an {@code available=false} frame so the client shows a
     * clean "detail unavailable" state rather than hanging (AC9) — it never crashes the pump.
     */
    private void fetchDetail(WebSocketSession session, ConvCtx ctx, ConversationClientMessage.FetchDetail fd) {
        ConversationServerMessage.ToolDetail frame;
        try {
            ConversationFacade.ToolDetailView view =
                    facade.fetchToolDetail(ctx.n, ctx.selectedTarget.get(), fd.toolUseId(), ctx.identity);
            frame = new ConversationServerMessage.ToolDetail(
                    view.toolUseId(), view.toolName(), view.input(), view.result(), view.isError(), view.available());
        } catch (RuntimeException e) {
            LOG.warn("conversation fetch-detail for {} failed: {}", fd.toolUseId(), e.toString());
            frame = new ConversationServerMessage.ToolDetail(fd.toolUseId(), null, "", "", false, false);
        }
        emit(ctx, session, frame);
    }

    /**
     * UC-79 (AC2/AC4) — seed/re-seed the per-connection oldest-line cursor from the start
     * index carried on a primary {@code backfill-start} control ({@code rest}). A subagent
     * backfill (no index field) leaves the cursor untouched, and a non-numeric/blank index
     * is ignored — the cursor only ever moves to a parsed primary window start.
     */
    private void seedOldestCursor(ConvCtx ctx, String rest) {
        if (rest == null || rest.isBlank()) {
            return; // subagent backfill (no index) — keep the existing primary cursor
        }
        try {
            ctx.oldestLineCursor.set(Math.max(0, Integer.parseInt(rest.trim())));
            ctx.loadingOlder.set(false); // a fresh window invalidates any in-flight page
        } catch (NumberFormatException nfe) {
            LOG.debug("conversation backfill-start carried no parseable cursor for n={}: '{}'", ctx.n, rest);
        }
    }

    /**
     * UC-79 (AC2/AC3/AC4/AC6) — handle a {@code load-older}: fetch the next OLDER page of
     * the selected target's transcript (ending just below the connection's oldest-line
     * cursor) and emit a {@code page-start} → older frames → {@code page-end(atStart)}
     * sequence; advance the cursor to the page's new oldest line. A single-in-flight guard
     * ({@link ConvCtx#loadingOlder}) drops overlapping requests from a fast scroll-up fling.
     * When the cursor is already at the transcript start (0) it emits {@code page-end(true)}
     * with no page so the client stops paging. Any failure degrades to {@code page-end(false)}
     * so the client clears its loading affordance rather than hanging — it never crashes the pump.
     */
    private void loadOlder(WebSocketSession session, ConvCtx ctx) {
        if (!ctx.loadingOlder.compareAndSet(false, true)) {
            return; // a page load is already in flight (rapid scroll-up) — drop this request
        }
        try {
            int before = ctx.oldestLineCursor.get();
            if (before <= 0) {
                emit(ctx, session, new ConversationServerMessage.PageEnd(true));
                return;
            }
            ConversationFacade.OlderPage page =
                    facade.fetchOlderPage(ctx.n, ctx.selectedTarget.get(), before, facade.conversationPageLines());
            // Emit the whole page burst ATOMICALLY w.r.t. the live tail pump: the fetch above
            // ran lock-free, but the page-start → frames → page-end sequence must not be
            // interleaved by a concurrently-pumped live frame, or that live frame would be
            // mis-prepended into the older page on the client (AC6). emit()'s own lock is the
            // same monitor and is reentrant, so holding it here makes the burst contiguous.
            synchronized (ctx.outboundLock) {
                emit(ctx, session, new ConversationServerMessage.PageStart());
                for (ConversationServerMessage frame : page.frames()) {
                    emit(ctx, session, frame);
                }
                emit(ctx, session, new ConversationServerMessage.PageEnd(page.atStart()));
            }
            ctx.oldestLineCursor.set(page.newOldestLine());
        } catch (RuntimeException e) {
            LOG.warn("conversation load-older for n={} failed: {}", ctx.n, e.toString());
            emit(ctx, session, new ConversationServerMessage.PageEnd(false));
        } finally {
            ctx.loadingOlder.set(false);
        }
    }

    private void applyAnswer(WebSocketSession session, ConvCtx ctx, ConversationClientMessage.Answer a) {
        ConversationServerMessage.Question q =
                a.questionUuid() == null ? null : ctx.pendingQuestions.get(a.questionUuid());
        InputInjectionService.BatchAnswerSpec spec =
                deriveAnswerSpec(q, a.questionIndex(), a.selections(), a.freeText());
        safe(
                ctx,
                session,
                () -> facade.injectAnswer(
                        ctx.n,
                        ctx.selectedTarget.get(),
                        spec.optionCount(),
                        spec.multiSelect(),
                        spec.selections(),
                        spec.otherIndex(),
                        spec.freeText(),
                        ctx.identity));
        if (q != null) {
            evictCachedQuestion(ctx, q);
        }
    }

    /**
     * UC-43 — handle a multi-question {@code answer-batch}: resolve the single
     * cached {@code AskUserQuestion}, derive each question's selection metadata
     * (sorted by {@code questionIndex} so the keystroke walk matches the wizard's
     * tab order), inject the whole batch as ONE scheduled keystroke sequence, and
     * evict the cached question only AFTER the batch is injected (the cached
     * question covers all N questions, so it must survive until the form submits).
     */
    private void applyAnswerBatch(WebSocketSession session, ConvCtx ctx, ConversationClientMessage.AnswerBatch ab) {
        ConversationServerMessage.Question q =
                ab.questionUuid() == null ? null : ctx.pendingQuestions.get(ab.questionUuid());
        List<ConversationClientMessage.AnswerItem> items =
                ab.answers() == null ? List.of() : new ArrayList<>(ab.answers());
        items.sort(Comparator.comparingInt(ConversationClientMessage.AnswerItem::questionIndex));
        List<InputInjectionService.BatchAnswerSpec> specs = new ArrayList<>(items.size());
        for (ConversationClientMessage.AnswerItem item : items) {
            specs.add(deriveAnswerSpec(q, item.questionIndex(), item.selections(), item.freeText()));
        }
        safe(ctx, session, () -> facade.injectAnswerBatch(ctx.n, ctx.selectedTarget.get(), specs, ctx.identity));
        if (q != null) {
            evictCachedQuestion(ctx, q);
        }
    }

    /**
     * Derive one question's {@link InputInjectionService.BatchAnswerSpec} from the
     * cached question + the client-supplied selections/free-text. Shared by the
     * single ({@link #applyAnswer}) and batch ({@link #applyAnswerBatch}) paths so
     * the option-count / otherIndex derivation cannot drift between them.
     */
    private InputInjectionService.BatchAnswerSpec deriveAnswerSpec(
            ConversationServerMessage.Question q, int questionIndex, List<Integer> selections, String freeText) {
        int optionCount = 0;
        boolean multiSelect = false;
        int otherIndex = -1;
        if (q != null && q.questions() != null && questionIndex < q.questions().size()) {
            ConversationServerMessage.QuestionItem item = q.questions().get(Math.max(0, questionIndex));
            optionCount = item.options() == null ? 0 : item.options().size();
            multiSelect = item.multiSelect();
            // The "Other" free-text slot is presented by the client after the
            // listed options, so its option index is the option count.
            otherIndex = optionCount;
            if (freeText != null && !freeText.isBlank()) {
                optionCount = optionCount + 1; // include the Other row in the cursor walk
            }
        }
        return new InputInjectionService.BatchAnswerSpec(optionCount, multiSelect, selections, otherIndex, freeText);
    }

    /** Evict a resolved question from the per-connection cache under both of its keys. */
    private void evictCachedQuestion(ConvCtx ctx, ConversationServerMessage.Question q) {
        if (q.toolUseId() != null) {
            ctx.pendingQuestions.remove(q.toolUseId());
        }
        if (q.uuid() != null) {
            ctx.pendingQuestions.remove(q.uuid());
        }
    }

    /**
     * Switch the conversation's tailed/inject target on the same WebSocket.
     * Swap-ordering invariant (mirrors {@link SessionStreamHandler#rebridge}):
     * start new tail → swap tailRef + bump generation → close old tail. Closing
     * the old tail first would unblock the pump's {@code readLine} while it still
     * holds the OLD generation, so it would treat the EOF as teardown and complete
     * the shared sink — killing the WebSocket instead of continuing on the new tail.
     */
    private void switchTarget(WebSocketSession session, ConvCtx ctx, String targetId) {
        String resolved = (targetId == null || targetId.isBlank()) ? TARGET_MAIN : targetId;
        try {
            TranscriptTailService.Tail fresh = facade.startTail(ctx.n, resolved);
            TranscriptTailService.Tail old = ctx.tailRef.getAndSet(fresh);
            ctx.generation.incrementAndGet();
            if (old != null) {
                old.close();
            }
            ctx.selectedTarget.set(resolved);
            ctx.pendingQuestions.clear(); // questions are per-target; the new tail re-backfills its own
            // UC-79 — the new target's tail re-backfills and re-seeds the oldest-line cursor
            // via its own backfill-start; clear the in-flight guard so paging works after the switch.
            ctx.loadingOlder.set(false);
            emit(ctx, session, new ConversationServerMessage.TargetSelected(resolved));
        } catch (Exception e) {
            LOG.warn("conversation target switch to {} failed: {}", resolved, e.toString());
            emit(
                    ctx,
                    session,
                    new ConversationServerMessage.ServerError(
                            "select_failed", "Target switch failed", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    // ──────────────────────── helpers ────────────────────────

    private interface IoAction {
        void run() throws IOException;
    }

    /** Run an injection action, surfacing an {@code error} frame on failure rather than crashing the pump. */
    private void safe(ConvCtx ctx, WebSocketSession session, IoAction action) {
        try {
            action.run();
        } catch (IOException io) {
            emit(
                    ctx,
                    session,
                    new ConversationServerMessage.ServerError(
                            "inject_failed", "Input injection failed", io.getMessage() == null ? "" : io.getMessage()));
        }
    }

    private void emit(ConvCtx ctx, WebSocketSession session, ConversationServerMessage msg) {
        WebSocketMessage frame = session.textMessage(new String(controlSvc.serialize(msg), StandardCharsets.UTF_8));
        // Serialize with the pump's terminal complete and any other emitting thread —
        // see ConvCtx#outboundLock for why a concurrent tryEmit would silently hang.
        synchronized (ctx.outboundLock) {
            ctx.outbound.tryEmitNext(frame);
        }
    }

    private String serverErrorFrame(String code, String title, String detail) {
        return new String(
                controlSvc.serialize(
                        new ConversationServerMessage.ServerError(code, title, detail == null ? "" : detail)),
                StandardCharsets.UTF_8);
    }

    private static String describe(StreamFacade.AuthorizeResult r) {
        return switch (r) {
            case StreamFacade.SessionNotFound nf -> "session " + nf.n() + " not found";
            case StreamFacade.NotRunning nr -> "session " + nr.n() + " is " + nr.state();
            case StreamFacade.CapExceeded ce -> "stream cap exceeded (" + ce.scope() + ")";
            case StreamFacade.Draining d -> "server is shutting down";
            case StreamFacade.Allowed a -> "ok";
        };
    }

    private ClientIdentity resolveIdentity(WebSocketSession session) {
        Object stashed = session.getAttributes().get(IDENTITY_ATTR);
        if (stashed instanceof ClientIdentity ci) {
            return ci;
        }
        ActiveConnectionRegistry reg = connections;
        if (reg == null) {
            return null;
        }
        ChannelId cid = channelIdOf(session);
        return cid == null ? null : reg.identityFor(cid);
    }

    private static ChannelId channelIdOf(WebSocketSession session) {
        if (session instanceof ReactorNettyWebSocketSession rnws) {
            return rnws.getChannelId();
        }
        return null;
    }

    private static int extractN(WebSocketSession session) {
        // URI shape: wss://host/v1/sessions/{n}/conversation
        String path = session.getHandshakeInfo().getUri().getPath();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("sessions".equals(parts[i]) && i + 1 < parts.length) {
                try {
                    return Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("bad session number in path: " + parts[i + 1]);
                }
            }
        }
        throw new IllegalArgumentException("session number missing in path: " + path);
    }

    /** Per-connection context shared between the inbound pipeline and the tail pump. */
    private static final class ConvCtx {
        final int n;
        final ClientIdentity identity;
        final Sinks.Many<WebSocketMessage> outbound;
        /**
         * Serializes ALL access to {@link #outbound}. The sink is written from several
         * threads — the single tail-pump thread (control/transcript frames + the terminal
         * {@code complete} on EOF) and the {@code boundedElastic}-scheduled inbound handlers
         * (composer / answer / answer-batch / interrupt / select-target / enumerate /
         * fetch-detail frames). {@link Sinks.Many#tryEmitNext}/{@code tryEmitComplete} are
         * explicitly NOT safe for concurrent invocation: a racing emit returns
         * {@code FAIL_NON_SERIALIZED} and is dropped. If the dropped emit is the terminal
         * {@code complete}, the downstream {@code session.send(outbound.asFlux())} never sees
         * {@code onComplete} and the connection's {@code Mono.when(...)} never completes —
         * a silent hang. Holding this monitor around every emit guarantees serialization.
         */
        final Object outboundLock = new Object();

        final AtomicReference<TranscriptTailService.Tail> tailRef = new AtomicReference<>();
        final AtomicInteger generation = new AtomicInteger(0);
        final AtomicReference<String> selectedTarget = new AtomicReference<>(TARGET_MAIN);

        /**
         * UC-79 — the oldest transcript line index currently loaded for the selected
         * target's PRIMARY file. Seeded/re-seeded from the helper's {@code backfill-start
         * <idx>} control (parsed on the pump thread) and advanced as older pages load
         * (on a {@code boundedElastic} {@code load-older} thread) — hence an
         * {@link AtomicInteger}. A {@code load-older} fetches {@code [cursor-pageSize,
         * cursor)} and resets the cursor to the page's new oldest line; {@code 0} means
         * the transcript start is loaded (no further paging).
         */
        final AtomicInteger oldestLineCursor = new AtomicInteger(0);

        /**
         * UC-79 — single-in-flight guard for {@code load-older}: a fast scroll-up fling can
         * fire several {@code load-older} frames before the first page returns. The handler
         * {@code compareAndSet(false,true)} before fetching and resets it when done, so only
         * one page is ever in flight and the cursor advances exactly once per page (AC2 — no
         * skipped/overlapping pages). Cleared on target switch / rebaseline / re-seed.
         */
        final AtomicBoolean loadingOlder = new AtomicBoolean(false);

        final ConcurrentHashMap<String, ConversationServerMessage.Question> pendingQuestions =
                new ConcurrentHashMap<>();

        /**
         * UC-50 — set when a transcript-derived {@code Question}/{@code PlanApproval}
         * frame is emitted this turn, cleared on {@code TurnStart}/{@code TurnEnd}.
         * While set, the pane-signal {@link ConversationServerMessage.PendingPrompt} is
         * suppressed so the two delivery paths never both raise a sheet (transcript
         * wins). Mutated and read only on the single tail-pump thread, so a plain field
         * is sufficient — no cross-thread visibility concern.
         */
        boolean transcriptPromptThisTurn = false;

        /**
         * UC-55 — promptKeys whose full per-tab options have been recovered and emitted as
         * an answerable sheet. A header-only re-emit for one of these (a racing helper poll
         * re-settling the restored tab) is dropped so it cannot downgrade the open sheet.
         * Mutated/read only on the single tail-pump thread, so a plain {@link HashSet} is
         * sufficient — no cross-thread visibility concern (mirrors {@link #transcriptPromptThisTurn}).
         */
        final Set<String> recoveredKeys = new HashSet<>();

        /**
         * UC-55 — wall-clock deadline ({@code System.currentTimeMillis()}) until which
         * transient pane-signal control lines produced by a recovery's own tab-stepping are
         * dropped. {@code 0} when no recovery has run. Pump-thread-only, like the fields above.
         */
        long recoverySuppressUntilMs = 0L;

        ConvCtx(int n, ClientIdentity identity, Sinks.Many<WebSocketMessage> outbound) {
            this.n = n;
            this.identity = identity;
            this.outbound = outbound;
        }
    }
}
