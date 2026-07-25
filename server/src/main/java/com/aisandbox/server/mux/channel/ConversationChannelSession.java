package com.aisandbox.server.mux.channel;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mux.dto.MuxChannel;
import com.aisandbox.server.mux.service.FrameSink;
import com.aisandbox.server.stream.dto.ConversationClientMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.InputInjectionService;
import com.aisandbox.server.stream.service.TranscriptTailService;
import java.io.IOException;
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
import reactor.core.scheduler.Schedulers;

/**
 * UC-100 — the {@code conversation} channel session for one
 * {@code (conversation, n)} subscription. All of the intricate UC-37/UC-50/
 * UC-55/UC-79/UC-85/UC-96/UC-97 logic is lifted verbatim from the legacy
 * {@code com.aisandbox.server.stream.handler.SessionConversationHandler} — the
 * single long-lived tail pump, the generation-token target switch, the pending-
 * question recovery/suppression guards, the answer/answer-batch echo-before-inject
 * ordering, and the atomic load-older page burst. Only the transport is swapped:
 * frames go out through the shared {@link FrameSink} instead of a per-handler
 * {@code Sinks.Many}.
 *
 * <p>The per-subscribe authorization ({@link ConversationFacade#authorizeOpen})
 * runs in the handler at subscribe-time (its four result types map to the
 * {@code sub-error} taxonomy); this session is created only once {@code Allowed}.
 */
public final class ConversationChannelSession implements MuxChannelSession {

    /** Id of the always-present main-session target. */
    public static final String TARGET_MAIN = "main";

    private static final Logger LOG = LoggerFactory.getLogger(ConversationChannelSession.class);

    /** UC-55 — settle window after a multi-question option recovery (see legacy handler). */
    private static final long RECOVERY_SUPPRESS_MS = 1500L;

    private final int n;
    private final ClientIdentity identity;
    private final ConversationFacade facade;
    private final ConversationEventMapper mapper;
    private final FrameSink sink;
    private final ChannelHost host;

    // ── per-connection state (was ConvCtx) ──
    private final Object emitLock = new Object();
    private final AtomicReference<TranscriptTailService.Tail> tailRef = new AtomicReference<>();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicReference<String> selectedTarget = new AtomicReference<>(TARGET_MAIN);
    private final AtomicInteger oldestLineCursor = new AtomicInteger(0);
    private final AtomicBoolean loadingOlder = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, ConversationServerMessage.Question> pendingQuestions =
            new ConcurrentHashMap<>();
    private volatile boolean transcriptPromptThisTurn = false;
    private final Set<String> recoveredKeys = new HashSet<>();
    private long recoverySuppressUntilMs = 0L;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread pumpThread;

    public ConversationChannelSession(
            int n,
            ClientIdentity identity,
            ConversationFacade facade,
            ConversationEventMapper mapper,
            FrameSink sink,
            ChannelHost host) {
        this.n = n;
        this.identity = identity;
        this.facade = facade;
        this.mapper = mapper;
        this.sink = sink;
        this.host = host;
    }

    @Override
    public void start() {
        Schedulers.boundedElastic().schedule(() -> {
            TranscriptTailService.Tail tail;
            try {
                tail = facade.startTail(n, selectedTarget.get());
            } catch (Exception t) {
                LOG.warn("mux conversation tail start failed for n={}: {}", n, t.toString());
                emit(new ConversationServerMessage.ServerError(
                        "tail_failed", "Transcript tail failed", t.getMessage() == null ? "" : t.getMessage()));
                host.requestChannelClose(MuxChannel.CONVERSATION, n, "tail_failed");
                return;
            }
            tailRef.set(tail);
            Thread p = new Thread(this::pumpTail);
            p.setDaemon(true);
            p.setName("ai-sandbox-mux-conv-tail-" + n);
            this.pumpThread = p;
            p.start();
        });
    }

    // ──────────────────────── outbound: tail → frames ────────────────────────

    private void pumpTail() {
        int myGen = generation.get();
        TranscriptTailService.Tail tail = tailRef.get();
        try {
            while (!stopped.get()) {
                if (generation.get() != myGen) {
                    myGen = generation.get();
                    tail = tailRef.get();
                }
                if (tail == null) {
                    break;
                }
                String line;
                try {
                    line = tail.readLine();
                } catch (IOException io) {
                    if (generation.get() != myGen) {
                        continue;
                    }
                    LOG.info("mux conversation tail reader done: {}", io.toString());
                    break;
                }
                if (line == null) {
                    if (generation.get() != myGen) {
                        continue;
                    }
                    break; // true EOF → teardown
                }
                dispatchTailLine(line);
            }
        } finally {
            if (!stopped.get()) {
                host.requestChannelClose(MuxChannel.CONVERSATION, n, "eof");
            }
        }
    }

    private void dispatchTailLine(String line) {
        int tab = line.indexOf('\t');
        String source = tab < 0 ? "main" : line.substring(0, tab);
        String payload = tab < 0 ? line : line.substring(tab + 1);

        if (TranscriptTailService.CTRL_SOURCE.equals(source)) {
            int ptab = payload.indexOf('\t');
            String subtype = (ptab < 0 ? payload : payload.substring(0, ptab)).trim();
            String rest = ptab < 0 ? "" : payload.substring(ptab + 1);
            switch (subtype) {
                case TranscriptTailService.CTRL_BACKFILL_START -> {
                    seedOldestCursor(rest);
                    emit(new ConversationServerMessage.BackfillStart(selectedTarget.get()));
                }
                case TranscriptTailService.CTRL_BACKFILL_END -> emit(
                        new ConversationServerMessage.BackfillEnd(selectedTarget.get()));
                case TranscriptTailService.CTRL_REBASELINE -> {
                    loadingOlder.set(false);
                    LOG.debug("mux conversation tail rebaselined for n={}", n);
                }
                case TranscriptTailService.CTRL_NO_TRANSCRIPT -> {
                    LOG.info("mux conversation tail reported no resolvable transcript for n={}", n);
                    emit(new ConversationServerMessage.ServerError(
                            "no_transcript",
                            "No active transcript",
                            "the live session's transcript could not be resolved yet"));
                }
                case TranscriptTailService.CTRL_PENDING_QUESTION -> dispatchPendingQuestion(rest);
                case TranscriptTailService.CTRL_PENDING_CLEAR -> dispatchPendingClear(rest);
                default -> {
                    /* unknown control — ignore */
                }
            }
            return;
        }

        for (ConversationServerMessage frame : mapper.map(source, payload)) {
            if (frame instanceof ConversationServerMessage.Question q) {
                cacheQuestion(q);
                transcriptPromptThisTurn = true;
            } else if (frame instanceof ConversationServerMessage.PlanApproval) {
                transcriptPromptThisTurn = true;
            } else if (frame instanceof ConversationServerMessage.TurnStart
                    || frame instanceof ConversationServerMessage.TurnEnd) {
                transcriptPromptThisTurn = false;
            }
            emit(frame);
        }
    }

    private void dispatchPendingQuestion(String json) {
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        if (pp == null) {
            return;
        }
        boolean multiHeaderOnly = "questions".equals(pp.kind())
                && !pp.answerable()
                && pp.questions() != null
                && pp.questions().size() > 1;
        if (multiHeaderOnly) {
            if (recoveredKeys.contains(pp.promptKey())) {
                return;
            }
            if (System.currentTimeMillis() < recoverySuppressUntilMs) {
                return;
            }
            if (!transcriptPromptThisTurn) {
                pp = recoverMultiQuestion(pp);
                if (pp.answerable()) {
                    recoveredKeys.add(pp.promptKey());
                    recoverySuppressUntilMs = System.currentTimeMillis() + RECOVERY_SUPPRESS_MS;
                }
            }
        }
        cacheQuestion(mapper.pendingPromptToQuestion(pp));
        if (!transcriptPromptThisTurn) {
            emit(pp);
        }
    }

    private ConversationServerMessage.PendingPrompt recoverMultiQuestion(ConversationServerMessage.PendingPrompt pp) {
        try {
            List<ConversationServerMessage.QuestionItem> full =
                    facade.recoverWizardOptions(n, selectedTarget.get(), pp.questions());
            boolean answerable = mapper.answerable(pp.kind(), full);
            return new ConversationServerMessage.PendingPrompt(pp.promptKey(), pp.kind(), full, pp.plan(), answerable);
        } catch (RuntimeException e) {
            LOG.warn("UC-55 wizard option recovery failed for n={} key={}: {}", n, pp.promptKey(), e.toString());
            return pp;
        }
    }

    private void dispatchPendingClear(String promptKey) {
        if (System.currentTimeMillis() < recoverySuppressUntilMs) {
            return;
        }
        if (promptKey != null && !promptKey.isBlank()) {
            pendingQuestions.remove(promptKey);
            recoveredKeys.remove(promptKey);
        }
        emit(new ConversationServerMessage.PendingClear(promptKey == null ? "" : promptKey));
    }

    private void resyncPending() {
        if (transcriptPromptThisTurn) {
            return;
        }
        String payload;
        try {
            payload = facade.resyncPending(n, selectedTarget.get());
        } catch (RuntimeException e) {
            LOG.warn(
                    "UC-97 resync-pending re-derive failed for n={} target={}: {}",
                    n,
                    selectedTarget.get(),
                    e.toString());
            return;
        }
        if (payload == null || payload.isBlank()) {
            return;
        }
        int tab = payload.indexOf('\t');
        String kind = tab < 0 ? payload : payload.substring(0, tab);
        String rest = tab < 0 ? "" : payload.substring(tab + 1);
        if (TranscriptTailService.CTRL_PENDING_CLEAR.equals(kind)) {
            for (String k : new ArrayList<>(pendingQuestions.keySet())) {
                pendingQuestions.remove(k);
                emit(new ConversationServerMessage.PendingClear(k));
            }
            return;
        }
        if (!TranscriptTailService.CTRL_PENDING_QUESTION.equals(kind)) {
            return;
        }
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(rest);
        if (pp == null) {
            return;
        }
        boolean multiHeaderOnly = "questions".equals(pp.kind())
                && !pp.answerable()
                && pp.questions() != null
                && pp.questions().size() > 1;
        if (multiHeaderOnly) {
            pp = recoverMultiQuestion(pp);
        }
        cacheQuestion(mapper.pendingPromptToQuestion(pp));
        emit(pp);
    }

    private void cacheQuestion(ConversationServerMessage.Question q) {
        cachePut(q.toolUseId(), q);
        cachePut(q.uuid(), q);
    }

    private void cachePut(String key, ConversationServerMessage.Question q) {
        if (key == null) {
            return;
        }
        ConversationServerMessage.Question existing = pendingQuestions.get(key);
        if (existing != null && hasAnyOptions(existing) && !hasAnyOptions(q)) {
            return;
        }
        pendingQuestions.put(key, q);
    }

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

    /** Inbound conversation client message (already parsed + length-checked by the handler). */
    public void onConversation(ConversationClientMessage cm) {
        switch (cm) {
            case ConversationClientMessage.ComposerInput in -> Schedulers.boundedElastic()
                    .schedule(() -> safe(() -> facade.injectComposer(n, selectedTarget.get(), in.text(), identity)));
            case ConversationClientMessage.Answer a -> Schedulers.boundedElastic()
                    .schedule(() -> applyAnswer(a));
            case ConversationClientMessage.AnswerBatch ab -> Schedulers.boundedElastic()
                    .schedule(() -> applyAnswerBatch(ab));
            case ConversationClientMessage.Interrupt it -> Schedulers.boundedElastic()
                    .schedule(() -> safe(() -> facade.interrupt(n, selectedTarget.get(), identity)));
            case ConversationClientMessage.SelectTarget st -> Schedulers.boundedElastic()
                    .schedule(() -> switchTarget(st.targetId()));
            case ConversationClientMessage.EnumerateTargets et -> Schedulers.boundedElastic()
                    .schedule(() -> {
                        List<com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo> targets =
                                facade.enumerateConversationTargets(n, selectedTarget.get());
                        emit(new ConversationServerMessage.Targets(targets, selectedTarget.get()));
                    });
            case ConversationClientMessage.FetchDetail fd -> Schedulers.boundedElastic()
                    .schedule(() -> fetchDetail(fd));
            case ConversationClientMessage.LoadOlder lo -> Schedulers.boundedElastic()
                    .schedule(this::loadOlder);
            case ConversationClientMessage.ResyncPending rp -> Schedulers.boundedElastic()
                    .schedule(this::resyncPending);
            case ConversationClientMessage.Close c -> host.requestChannelClose(
                    MuxChannel.CONVERSATION, n, c.reason() == null ? "client-close" : c.reason());
        }
    }

    private void fetchDetail(ConversationClientMessage.FetchDetail fd) {
        ConversationServerMessage.ToolDetail frame;
        try {
            ConversationFacade.ToolDetailView view =
                    facade.fetchToolDetail(n, selectedTarget.get(), fd.toolUseId(), identity);
            frame = new ConversationServerMessage.ToolDetail(
                    view.toolUseId(), view.toolName(), view.input(), view.result(), view.isError(), view.available());
        } catch (RuntimeException e) {
            LOG.warn("mux conversation fetch-detail for {} failed: {}", fd.toolUseId(), e.toString());
            frame = new ConversationServerMessage.ToolDetail(fd.toolUseId(), null, "", "", false, false);
        }
        emit(frame);
    }

    private void seedOldestCursor(String rest) {
        if (rest == null || rest.isBlank()) {
            return;
        }
        try {
            oldestLineCursor.set(Math.max(0, Integer.parseInt(rest.trim())));
            loadingOlder.set(false);
        } catch (NumberFormatException nfe) {
            LOG.debug("mux conversation backfill-start carried no parseable cursor for n={}: '{}'", n, rest);
        }
    }

    private void loadOlder() {
        if (!loadingOlder.compareAndSet(false, true)) {
            return;
        }
        try {
            int before = oldestLineCursor.get();
            if (before <= 0) {
                emit(new ConversationServerMessage.PageEnd(true));
                return;
            }
            ConversationFacade.OlderPage page =
                    facade.fetchOlderPage(n, selectedTarget.get(), before, facade.conversationPageLines());
            synchronized (emitLock) {
                emit(new ConversationServerMessage.PageStart());
                for (ConversationServerMessage frame : page.frames()) {
                    emit(frame);
                }
                emit(new ConversationServerMessage.PageEnd(page.atStart()));
            }
            oldestLineCursor.set(page.newOldestLine());
        } catch (RuntimeException e) {
            LOG.warn("mux conversation load-older for n={} failed: {}", n, e.toString());
            emit(new ConversationServerMessage.PageEnd(false));
        } finally {
            loadingOlder.set(false);
        }
    }

    private void applyAnswer(ConversationClientMessage.Answer a) {
        ConversationServerMessage.Question q = a.questionUuid() == null ? null : pendingQuestions.get(a.questionUuid());
        InputInjectionService.BatchAnswerSpec spec =
                deriveAnswerSpec(q, a.questionIndex(), a.selections(), a.freeText());
        // UC-85/UC-96 — echo BEFORE the gate-releasing inject (kept symmetric with the batch path).
        if (facade.answerEchoEnabled()) {
            emit(new ConversationServerMessage.AnswerEcho(
                    a.questionUuid(), a.questionIndex(), a.selections(), a.freeText()));
        }
        safe(() -> facade.injectAnswer(
                n,
                selectedTarget.get(),
                spec.optionCount(),
                spec.multiSelect(),
                spec.selections(),
                spec.otherIndex(),
                spec.freeText(),
                identity));
        if (q != null) {
            evictCachedQuestion(q);
        }
    }

    private void applyAnswerBatch(ConversationClientMessage.AnswerBatch ab) {
        ConversationServerMessage.Question q =
                ab.questionUuid() == null ? null : pendingQuestions.get(ab.questionUuid());
        List<ConversationClientMessage.AnswerItem> items =
                ab.answers() == null ? List.of() : new ArrayList<>(ab.answers());
        items.sort(Comparator.comparingInt(ConversationClientMessage.AnswerItem::questionIndex));
        List<InputInjectionService.BatchAnswerSpec> specs = new ArrayList<>(items.size());
        for (ConversationClientMessage.AnswerItem item : items) {
            specs.add(deriveAnswerSpec(q, item.questionIndex(), item.selections(), item.freeText()));
        }
        // UC-85/UC-96 — emit ALL echoes BEFORE the gate-releasing inject (the batch's second echo is the
        // one that used to lose the terminal-complete race on the legacy per-handler sink).
        if (facade.answerEchoEnabled()) {
            for (ConversationClientMessage.AnswerItem item : items) {
                emit(new ConversationServerMessage.AnswerEcho(
                        ab.questionUuid(), item.questionIndex(), item.selections(), item.freeText()));
            }
        }
        safe(() -> facade.injectAnswerBatch(n, selectedTarget.get(), specs, identity));
        if (q != null) {
            evictCachedQuestion(q);
        }
    }

    private InputInjectionService.BatchAnswerSpec deriveAnswerSpec(
            ConversationServerMessage.Question q, int questionIndex, List<Integer> selections, String freeText) {
        int optionCount = 0;
        boolean multiSelect = false;
        int otherIndex = -1;
        if (q != null && q.questions() != null && questionIndex < q.questions().size()) {
            ConversationServerMessage.QuestionItem item = q.questions().get(Math.max(0, questionIndex));
            optionCount = item.options() == null ? 0 : item.options().size();
            multiSelect = item.multiSelect();
            otherIndex = optionCount;
            if (freeText != null && !freeText.isBlank()) {
                optionCount = optionCount + 1;
            }
        }
        return new InputInjectionService.BatchAnswerSpec(optionCount, multiSelect, selections, otherIndex, freeText);
    }

    private void evictCachedQuestion(ConversationServerMessage.Question q) {
        if (q.toolUseId() != null) {
            pendingQuestions.remove(q.toolUseId());
        }
        if (q.uuid() != null) {
            pendingQuestions.remove(q.uuid());
        }
    }

    private void switchTarget(String targetId) {
        String resolved = (targetId == null || targetId.isBlank()) ? TARGET_MAIN : targetId;
        try {
            TranscriptTailService.Tail fresh = facade.startTail(n, resolved);
            TranscriptTailService.Tail old = tailRef.getAndSet(fresh);
            generation.incrementAndGet();
            if (old != null) {
                old.close();
            }
            selectedTarget.set(resolved);
            pendingQuestions.clear();
            loadingOlder.set(false);
            emit(new ConversationServerMessage.TargetSelected(resolved));
        } catch (Exception e) {
            LOG.warn("mux conversation target switch to {} failed: {}", resolved, e.toString());
            emit(new ConversationServerMessage.ServerError(
                    "select_failed", "Target switch failed", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    // ──────────────────────── helpers ────────────────────────

    private interface IoAction {
        void run() throws IOException;
    }

    private void safe(IoAction action) {
        try {
            action.run();
        } catch (IOException io) {
            emit(new ConversationServerMessage.ServerError(
                    "inject_failed", "Input injection failed", io.getMessage() == null ? "" : io.getMessage()));
        }
    }

    private void emit(ConversationServerMessage msg) {
        synchronized (emitLock) {
            sink.send(msg);
        }
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        TranscriptTailService.Tail t = tailRef.get();
        if (t != null) {
            t.close();
        }
        Thread p = this.pumpThread;
        if (p != null) {
            p.interrupt();
        }
        facade.auditClose(n, identity, 1000, "unsubscribe");
    }
}
