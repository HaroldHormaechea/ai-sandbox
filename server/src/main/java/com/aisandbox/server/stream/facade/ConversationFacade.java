package com.aisandbox.server.stream.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo;
import com.aisandbox.server.stream.facade.StreamFacade.AuthorizeResult;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.InputInjectionService;
import com.aisandbox.server.stream.service.InputInjectionService.InjectTarget;
import com.aisandbox.server.stream.service.SwarmEnumerationService;
import com.aisandbox.server.stream.service.TmuxBridgeService.BridgeTarget;
import com.aisandbox.server.stream.service.TranscriptTailService;
import com.aisandbox.server.stream.service.TranscriptTailService.TailTarget;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * UC-37 — use-case entry point for the structured-conversation channel
 * ({@code /v1/sessions/{n}/conversation}). Composes the conversation services
 * (transcript tail, input injection, swarm target enumeration) and reuses the
 * binary stream's {@link StreamFacade} for the running-session / cap / draining
 * authorization (decision: one identity + cap model across both channels).
 *
 * <p>Layering: this facade is the conversation domain's boundary; the handler
 * (an entry point) calls only this facade, never the services directly. There is
 * no {@code @Transactional} / repository in this domain (it is process- and
 * filesystem-backed, not DB-backed), so the transaction rule of
 * {@code profile-java-server-architecture} is vacuously satisfied. Cross-channel
 * reuse of {@link StreamFacade} is a facade-to-facade call, per the same profile.
 */
@Component
public class ConversationFacade {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationFacade.class);

    /**
     * AC18 — cap on how many non-selected targets we pending-scan per enumerate,
     * so a large team never fans out into an unbounded burst of docker-exec
     * scans. Excess targets simply show no badge (degrade, not fail).
     */
    private static final int MAX_PENDING_SCANS = 6;

    private final StreamFacade streamFacade;
    private final SwarmEnumerationService swarm;
    private final TranscriptTailService tail;
    private final InputInjectionService injection;
    private final ConversationEventMapper mapper;
    private final AuditLogger audit;
    private final ServerProperties props;

    public ConversationFacade(
            StreamFacade streamFacade,
            SwarmEnumerationService swarm,
            TranscriptTailService tail,
            InputInjectionService injection,
            ConversationEventMapper mapper,
            AuditLogger audit,
            ServerProperties props) {
        this.streamFacade = streamFacade;
        this.swarm = swarm;
        this.tail = tail;
        this.injection = injection;
        this.mapper = mapper;
        this.audit = audit;
        this.props = props;
    }

    /** AC21/cap — reuse the binary stream's running-session + cap + draining gate. */
    public AuthorizeResult authorizeOpen(int n, ClientIdentity identity) {
        return streamFacade.authorizeOpen(n, identity);
    }

    /** The bounded backfill window (transcript lines) replayed on open / reconnect (AC6/AC22). */
    public int backfillLines() {
        return props.streams().conversationBackfillLines();
    }

    /**
     * AC16/AC18 — enumerate conversation targets (main + agent-team panes),
     * augmenting each NON-selected target with its {@code pendingActivity} /
     * {@code pendingQuestion} flags via a bounded transcript scan. The selected
     * target is never badged (the client is already viewing it). Best-effort:
     * a scan that fails leaves a target with both flags {@code false}.
     */
    public List<TargetInfo> enumerateConversationTargets(int n, String selectedTargetId) {
        List<TargetInfo> base = swarm.enumerate(n);
        List<TargetInfo> out = new ArrayList<>(base.size());
        int scans = 0;
        for (TargetInfo t : base) {
            boolean selected = t.id().equals(selectedTargetId);
            if (selected || scans >= MAX_PENDING_SCANS) {
                out.add(t);
                continue;
            }
            scans++;
            TranscriptTailService.PendingState state = tail.scanPending(n, toTailTarget(t));
            out.add(
                    switch (state) {
                        case PENDING_QUESTION -> t.withPending(true, true);
                        case PENDING_ACTIVITY -> t.withPending(true, false);
                        case IDLE -> t;
                    });
        }
        return out;
    }

    /**
     * AC6/AC20 — start the long-lived transcript tail for {@code targetId} (or the
     * main session). The caller (the handler) owns the returned handle's pump and
     * lifecycle.
     */
    public TranscriptTailService.Tail startTail(int n, String targetId) throws IOException {
        TailTarget target = toTailTarget(resolveBridgeTarget(n, targetId));
        audit.logEvent(AuditAction.CONVERSATION_OPEN, "ok", "n", n, "targetId", targetId == null ? "main" : targetId);
        return tail.start(n, target, backfillLines());
    }

    /**
     * UC-41 (AC5/AC6/AC9) — on-demand fetch of the FULL, untruncated input + result for
     * one tool call, in response to a client {@code fetch-detail}. Resolves {@code targetId}
     * to its tail coordinates, re-reads the transcript via the helper, renders the
     * untruncated detail (bounded to {@code conversationDetailMaxBytes}), audits, and
     * returns an internal {@link ToolDetailView}. A miss (id scrolled out / unresolvable /
     * helper timeout) yields {@link ToolDetailView#unavailable(String)} (AC9) — never throws.
     */
    public ToolDetailView fetchToolDetail(int n, String targetId, String toolUseId, ClientIdentity identity) {
        TailTarget target = toTailTarget(resolveBridgeTarget(n, targetId));
        List<String> lines = tail.fetchDetailLines(n, target, toolUseId);
        ConversationEventMapper.DetailRender render = mapper.renderDetail(toolUseId, lines);
        audit.logEvent(
                AuditAction.CONVERSATION_FETCH_DETAIL,
                render.available() ? "ok" : "miss",
                "n",
                n,
                "targetId",
                targetId == null ? "main" : targetId,
                "toolUseId",
                toolUseId == null ? "" : toolUseId,
                "fingerprint",
                identity == null ? "" : identity.fingerprintHex());
        if (!render.available()) {
            return ToolDetailView.unavailable(toolUseId);
        }
        return new ToolDetailView(
                toolUseId, render.toolName(), render.input(), render.result(), render.isError(), true);
    }

    /** AC8/AC9 — inject a composer submission into {@code targetId}'s session. */
    public void injectComposer(int n, String targetId, String text, ClientIdentity identity) throws IOException {
        injection.injectComposer(n, toInjectTarget(resolveBridgeTarget(n, targetId)), text);
        audit.logEvent(
                AuditAction.CONVERSATION_INPUT,
                "ok",
                "n",
                n,
                "targetId",
                targetId == null ? "main" : targetId,
                "fingerprint",
                identity == null ? "" : identity.fingerprintHex());
    }

    /** AC11 — translate a structured answer into the session's selection keystrokes. */
    public void injectAnswer(
            int n,
            String targetId,
            int optionCount,
            boolean multiSelect,
            List<Integer> selections,
            int otherIndex,
            String freeText,
            ClientIdentity identity)
            throws IOException {
        injection.injectAnswer(
                n,
                toInjectTarget(resolveBridgeTarget(n, targetId)),
                optionCount,
                multiSelect,
                selections,
                otherIndex,
                freeText);
        audit.logEvent(
                AuditAction.CONVERSATION_ANSWER,
                "ok",
                "n",
                n,
                "targetId",
                targetId == null ? "main" : targetId,
                "multiSelect",
                multiSelect);
    }

    /**
     * UC-43/AC11 — translate a multi-question batch answer into the wizard's
     * selection keystrokes (one scheduled sequence resolving the whole sheet).
     * Same audit event shape as the single {@link #injectAnswer} path, tagged with
     * the question count.
     */
    public void injectAnswerBatch(
            int n, String targetId, List<InputInjectionService.BatchAnswerSpec> answers, ClientIdentity identity)
            throws IOException {
        injection.injectAnswerBatch(n, toInjectTarget(resolveBridgeTarget(n, targetId)), answers);
        audit.logEvent(
                AuditAction.CONVERSATION_ANSWER,
                "ok",
                "n",
                n,
                "targetId",
                targetId == null ? "main" : targetId,
                "batch",
                answers == null ? 0 : answers.size());
    }

    /** Interrupt (ESC) the active turn on {@code targetId}'s session. */
    public void interrupt(int n, String targetId, ClientIdentity identity) throws IOException {
        injection.interrupt(n, toInjectTarget(resolveBridgeTarget(n, targetId)));
        audit.logEvent(
                AuditAction.CONVERSATION_INTERRUPT, "ok", "n", n, "targetId", targetId == null ? "main" : targetId);
    }

    /** Audit a conversation channel close. */
    public void auditClose(int n, ClientIdentity identity, int closeCode, String reason) {
        audit.logEvent(
                AuditAction.CONVERSATION_CLOSE,
                "ok",
                "n",
                n,
                "fingerprint",
                identity == null ? "" : identity.fingerprintHex(),
                "closeCode",
                closeCode,
                "reason",
                reason == null ? "" : reason);
    }

    // ──────────────────────── target resolution ────────────────────────

    /** Resolve a target id to its tmux coordinates; falls back to main on a vanished id. */
    private BridgeTarget resolveBridgeTarget(int n, String targetId) {
        if (targetId == null || targetId.isBlank() || SwarmEnumerationService.MAIN_ID.equals(targetId)) {
            // For main, still let the enumerator recover the orchestrator pane.
            try {
                return swarm.resolveTarget(n, SwarmEnumerationService.MAIN_ID);
            } catch (RuntimeException e) {
                return BridgeTarget.main();
            }
        }
        try {
            return swarm.resolveTarget(n, targetId);
        } catch (RuntimeException e) {
            LOG.info("conversation target {} no longer resolvable; falling back to main: {}", targetId, e.toString());
            return BridgeTarget.main();
        }
    }

    private static TailTarget toTailTarget(BridgeTarget b) {
        if (b == null) {
            return TailTarget.main();
        }
        return new TailTarget(b.socketPath(), b.baseSession(), b.window(), b.pane());
    }

    private TailTarget toTailTarget(TargetInfo t) {
        if (t == null) {
            return TailTarget.main();
        }
        return new TailTarget(t.socket(), t.session(), t.window(), t.pane());
    }

    private static InjectTarget toInjectTarget(BridgeTarget b) {
        if (b == null) {
            return InjectTarget.main();
        }
        return new InjectTarget(b.socketPath(), b.baseSession(), b.window(), b.pane());
    }

    /**
     * UC-41 — internal (domain-layer) view of a fetched tool detail, returned by
     * {@link #fetchToolDetail}. The handler maps it to the wire
     * {@code ConversationServerMessage.ToolDetail} frame. {@code available=false} carries
     * empty {@code input}/{@code result} and a {@code false} {@code isError} (AC9).
     */
    public record ToolDetailView(
            String toolUseId, String toolName, String input, String result, boolean isError, boolean available) {
        static ToolDetailView unavailable(String toolUseId) {
            return new ToolDetailView(toolUseId, null, "", "", false, false);
        }
    }
}
