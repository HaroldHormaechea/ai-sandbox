package com.aisandbox.server.stream.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mcp.McpLoginInitiator;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo;
import com.aisandbox.server.stream.facade.StreamFacade.AuthorizeResult;
import com.aisandbox.server.stream.replay.ReplayAnswerSink;
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
import java.util.concurrent.ConcurrentHashMap;
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
public class ConversationFacade implements McpLoginInitiator {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationFacade.class);

    /**
     * AC18 — cap on how many non-selected targets we pending-scan per enumerate,
     * so a large team never fans out into an unbounded burst of docker-exec
     * scans. Excess targets simply show no badge (degrade, not fail).
     */
    private static final int MAX_PENDING_SCANS = 6;

    /**
     * UC-60 — id prefix marking a target as a background SUBAGENT pill (rather than a
     * tmux-pane team agent). The full id is {@code subagent:<bareAgentId>}; the id space
     * is disjoint from {@code main} and the {@code swarm:…} team-pane ids (AC6 — no
     * duplication/mislabeling). The server is the AUTHORITATIVE guard: a subagent runs
     * in-process under the lead with NO pane of its own, so every INPUT path
     * (composer/answer/interrupt/model) must short-circuit for a {@code subagent:} id —
     * otherwise {@link #resolveBridgeTarget} would fall back to {@link BridgeTarget#main()}
     * and silently inject into the LEAD's pane (the Major misroute UC-60 fixes). The
     * Android read-only composer is a UX echo of this contract, not the enforcement.
     */
    public static final String SUBAGENT_ID_PREFIX = "subagent:";

    /** Whether {@code targetId} addresses a background subagent pill (UC-60). */
    private static boolean isSubagentTarget(String targetId) {
        return targetId != null && targetId.startsWith(SUBAGENT_ID_PREFIX);
    }

    /** The bare agent id of a {@code subagent:<id>} target (UC-60). */
    private static String subagentIdOf(String targetId) {
        return targetId.substring(SUBAGENT_ID_PREFIX.length());
    }

    private final StreamFacade streamFacade;
    private final SwarmEnumerationService swarm;
    private final TranscriptTailService tail;
    private final InputInjectionService injection;
    private final ConversationEventMapper mapper;
    private final AuditLogger audit;
    private final ServerProperties props;

    /**
     * UC-55 — per-session ({@code n}) pane lock. The wizard-option recovery walk
     * ({@link #recoverWizardOptions}) and answer injection ({@link #injectAnswer} /
     * {@link #injectAnswerBatch}) both drive {@code tmux send-keys} into the SAME live
     * pane; serializing them per session guarantees a recovery's transient tab-stepping
     * can never interleave with an answer's keystroke walk (the single-writer rule from
     * the proposal). Keyed by {@code n} so it also serializes across two clients viewing
     * the same session. A plain {@code Object} monitor is sufficient — these are short,
     * bounded {@code docker exec} sequences, never held across a network wait.
     */
    private final ConcurrentHashMap<Integer, Object> paneLocks = new ConcurrentHashMap<>();

    /**
     * UC-85 — the deterministic-gate answer sink. Late-bound via a setter (the same pattern
     * {@link StreamFacade} uses for the swarm enumerator) so existing unit constructions of this
     * facade compile unchanged. Defaults to {@link ReplayAnswerSink#DISABLED} (production
     * behaviour: real tmux injection, no echo). Under the {@code replay} profile the recording
     * sink is injected: {@link #injectAnswer}/{@link #injectAnswerBatch} then record the answer
     * (which releases the fixture tail's await-answer gate) and SKIP tmux — mirroring the UC-60
     * read-only no-op-inject branch — and {@link #answerEchoEnabled()} returns {@code true} so the
     * handler echoes the answer back over the WebSocket.
     */
    private volatile ReplayAnswerSink replayAnswerSink = ReplayAnswerSink.DISABLED;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReplayAnswerSink(ReplayAnswerSink replayAnswerSink) {
        if (replayAnswerSink != null) {
            this.replayAnswerSink = replayAnswerSink;
        }
    }

    /**
     * UC-85 — whether the handler should echo an {@code AnswerEcho} back over the WebSocket after
     * an answer (true only under the {@code replay} profile). Keeps the handler→facade layering:
     * the handler asks the facade rather than reaching into the sink itself.
     */
    public boolean answerEchoEnabled() {
        return replayAnswerSink.enabled();
    }

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
        // UC-60 — append a pill per LIVE background subagent. A subagent is a sub-session
        // (in-process under the lead, UC-59), NOT a tmux pane, so the pane-based swarm
        // enumeration above can never see it; the helper's --list-subagents resolves them
        // from <mainStem>/subagents/agent-*.jsonl instead. The id space (subagent:<id>) is
        // disjoint from main/swarm ids (AC6). working maps to pendingActivity so the pill
        // carries the same working/idle badge a team-agent pill does (AC2); a subagent is
        // never "pendingQuestion" (it does not raise AskUserQuestion to the user).
        for (TranscriptTailService.SubagentInfo s : tail.listSubagents(n)) {
            String id = SUBAGENT_ID_PREFIX + s.id();
            String label = (s.label() == null || s.label().isBlank()) ? id : s.label();
            TargetInfo pill = new TargetInfo(id, "subagent", label, null, null, null, null, null, "main", null, null);
            out.add(pill.withPending(s.working(), false));
        }
        return out;
    }

    /**
     * AC6/AC20 — start the long-lived transcript tail for {@code targetId} (or the
     * main session). The caller (the handler) owns the returned handle's pump and
     * lifecycle.
     */
    public TranscriptTailService.Tail startTail(int n, String targetId) throws IOException {
        // UC-60 — a subagent pill streams its OWN agent-<id>.jsonl. Intercept BEFORE
        // resolveBridgeTarget: a subagent has no tmux pane, so resolveBridgeTarget would
        // fall back to main and tail the LEAD's transcript (wrong content). Build the
        // subagent tail target directly instead (AC3 — view the subagent's transcript).
        if (isSubagentTarget(targetId)) {
            audit.logEvent(AuditAction.CONVERSATION_OPEN, "ok", "n", n, "targetId", targetId);
            return tail.start(n, TailTarget.subagent(subagentIdOf(targetId)), backfillLines());
        }
        TailTarget target = toTailTarget(resolveBridgeTarget(n, targetId));
        audit.logEvent(AuditAction.CONVERSATION_OPEN, "ok", "n", n, "targetId", targetId == null ? "main" : targetId);
        return tail.start(n, target, backfillLines());
    }

    /**
     * UC-97 — re-derive the CURRENT pane pending-state for a client warm-attach
     * {@code resync-pending}, so a pending sheet the client lost to a transient (while the ask
     * is still live) can be re-emitted without an exit/re-enter. Resolves {@code targetId} to
     * the SAME tail coordinates {@link #startTail} uses (so the helper captures the correct
     * pane) and runs the one-shot {@code --resync-pending} (pane-based full payload — the
     * transcript is blind to a live blocking ask, UC-49/UC-50). Returns the raw control payload
     * ({@code "pending-question<TAB><json>"} / {@code "pending-clear"}) or {@code null} on a
     * miss. A subagent target has no tmux pane to read a pending prompt from, so it yields
     * {@code null} (nothing to re-derive). Read-only + one-shot: no {@code @Transactional} (no
     * DB), consistent with the other tail one-shots. Never throws.
     */
    public String resyncPending(int n, String targetId) {
        if (isSubagentTarget(targetId)) {
            return null;
        }
        return tail.resyncPending(n, toTailTarget(resolveBridgeTarget(n, targetId)));
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

    /**
     * UC-79 (AC2/AC6) — fetch the next OLDER page of {@code targetId}'s transcript, ending
     * just below {@code beforeLine}, and map each raw transcript line to its conversation
     * frames via the SAME {@link ConversationEventMapper#map} the live tail uses — so paged
     * history renders identically to backfilled history (same dedupe keys, ordering, and
     * tool-pair merging). Resolves the target's tail coordinates, runs the helper's bounded
     * {@code --fetch-page} read, splits each {@code <source>\t<raw>} envelope, and collects
     * the frames in transcript (oldest→newest) order. Returns an {@link OlderPage} with the
     * mapped frames, the new oldest-line cursor, and {@code atStart}. Best-effort and never
     * throws: a miss/timeout yields an empty page pinned at {@code beforeLine} with
     * {@code atStart=true}, so the client stops paging rather than hanging (AC4).
     */
    public OlderPage fetchOlderPage(int n, String targetId, int beforeLine, int pageSize) {
        TailTarget target = toTailTarget(resolveBridgeTarget(n, targetId));
        TranscriptTailService.PageLines page = tail.fetchPageLines(n, target, beforeLine, pageSize);
        List<ConversationServerMessage> frames = new ArrayList<>();
        for (String envelope : page.lines()) {
            int tab = envelope.indexOf('\t');
            String source = tab < 0 ? "main" : envelope.substring(0, tab);
            String raw = tab < 0 ? envelope : envelope.substring(tab + 1);
            frames.addAll(mapper.map(source, raw));
        }
        audit.logEvent(
                AuditAction.CONVERSATION_FETCH_PAGE,
                "ok",
                "n",
                n,
                "targetId",
                targetId == null ? "main" : targetId,
                "beforeLine",
                beforeLine,
                "frames",
                frames.size());
        return new OlderPage(frames, page.cursor(), page.atStart());
    }

    /** UC-79 — the older-page size (transcript lines) fetched per {@code load-older} (AC2/AC7). */
    public int conversationPageLines() {
        return props.streams().conversationPageLines();
    }

    /** AC8/AC9 — inject a composer submission into {@code targetId}'s session. */
    public void injectComposer(int n, String targetId, String text, ClientIdentity identity) throws IOException {
        // UC-60 (the Major fix) — a subagent pill is READ-ONLY: it has no pane, so an
        // inject would misroute to the lead. Short-circuit (no-op) + audit, never inject.
        if (guardSubagentInput(AuditAction.CONVERSATION_INPUT, n, targetId, identity)) {
            return;
        }
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

    /**
     * UC-67 — surface Claude Code's interactive {@code /mcp} menu in the session's
     * live <b>main</b> pane so a human can complete an MCP server's authentication
     * there. This only INITIATES the flow: {@code claude mcp} has no headless auth
     * subcommand, so the server can never complete an OAuth login on the user's
     * behalf — it injects {@code /mcp} (a slash command, submitted as a composer
     * line) into the orchestrator pane and the user finishes in that session; the
     * MCP screen reflects the post-auth state on its next refresh. Cross-domain
     * callers (the {@code mcp} facade) reach this via a facade-to-facade call, per
     * {@code profile-java-server-architecture}.
     */
    @Override
    public void openMcpMenu(int n, ClientIdentity identity) throws IOException {
        // Always the main (orchestrator) pane — MCP config + the /mcp TUI live with
        // the session's primary claude, not a teammate tile.
        injection.injectComposer(n, toInjectTarget(resolveBridgeTarget(n, SwarmEnumerationService.MAIN_ID)), "/mcp");
        audit.logEvent(
                AuditAction.MCP_LOGIN, "ok", "n", n, "fingerprint", identity == null ? "" : identity.fingerprintHex());
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
        // UC-60 — read-only subagent pill: no-op + audit, never inject (see injectComposer).
        if (guardSubagentInput(AuditAction.CONVERSATION_ANSWER, n, targetId, identity)) {
            return;
        }
        // UC-85 — deterministic gate: there is no live tmux pane to inject into. Record the
        // answer (which releases the fixture tail's await-answer gate so the recorded
        // post-answer frames replay) and skip injection, mirroring the UC-60 no-op branch.
        if (replayAnswerSink.enabled()) {
            replayAnswerSink.recordAnswer(n, selections, freeText);
            audit.logEvent(
                    AuditAction.CONVERSATION_ANSWER,
                    "replay",
                    "n",
                    n,
                    "targetId",
                    targetId == null ? "main" : targetId,
                    "multiSelect",
                    multiSelect);
            return;
        }
        InjectTarget target = toInjectTarget(resolveBridgeTarget(n, targetId));
        // UC-55 — serialize with any in-flight wizard-option recovery on the same pane.
        synchronized (paneLock(n)) {
            injection.injectAnswer(n, target, optionCount, multiSelect, selections, otherIndex, freeText);
        }
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
        // UC-60 — read-only subagent pill: no-op + audit, never inject (see injectComposer).
        if (guardSubagentInput(AuditAction.CONVERSATION_ANSWER, n, targetId, identity)) {
            return;
        }
        // UC-85 — deterministic gate: record the batch (releasing the fixture tail's
        // await-answer gate) and skip tmux injection. See injectAnswer.
        if (replayAnswerSink.enabled()) {
            replayAnswerSink.recordAnswerBatch(n, answers == null ? 0 : answers.size());
            audit.logEvent(
                    AuditAction.CONVERSATION_ANSWER,
                    "replay",
                    "n",
                    n,
                    "targetId",
                    targetId == null ? "main" : targetId,
                    "batch",
                    answers == null ? 0 : answers.size());
            return;
        }
        InjectTarget target = toInjectTarget(resolveBridgeTarget(n, targetId));
        // UC-55 — serialize with any in-flight wizard-option recovery on the same pane.
        synchronized (paneLock(n)) {
            injection.injectAnswerBatch(n, target, answers);
        }
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

    /**
     * UC-55 — recover the FULL per-tab option set of a multi-question pane-derived
     * {@code AskUserQuestion} so the whole batch becomes in-app answerable (AC2/AC5/AC10).
     * UC-50 delivers a multi-question wizard header-only (one pane capture shows only the
     * FOCUSED tab's options). Here the server, holding the per-session pane lock (so it
     * can never interleave with an answer injection), steps the live pane through every
     * tab with the read-only {@code Right} arrow, captures+parses each focused tab via the
     * helper's {@code --parse-pane} mode, then restores the original tab with {@code Left}
     * — leaving the pane exactly as found (load-bearing: {@code injectAnswerBatch} assumes
     * the wizard opens at tab 0). The recovered items carry each tab's real options; a tab
     * that fails to recover is left header-only, so {@link ConversationEventMapper#answerable}
     * keeps the batch {@code answerable=false} (the narrow genuinely-unrecoverable exception)
     * rather than rendering a tab with no options. {@code headerOnly} with ≤1 question is
     * returned unchanged (a single question is already fully recovered by UC-50). Never
     * throws — a stepping failure degrades to the best-effort partial recovery.
     */
    public List<ConversationServerMessage.QuestionItem> recoverWizardOptions(
            int n, String targetId, List<ConversationServerMessage.QuestionItem> headerOnly) {
        if (headerOnly == null || headerOnly.size() <= 1) {
            return headerOnly;
        }
        BridgeTarget bt = resolveBridgeTarget(n, targetId);
        InjectTarget it = toInjectTarget(bt);
        TailTarget tt = toTailTarget(bt);
        int tabs = headerOnly.size();
        List<ConversationServerMessage.QuestionItem> full = new ArrayList<>(tabs);
        int recovered = 0;
        synchronized (paneLock(n)) {
            int stepped = 0;
            try {
                for (int k = 0; k < tabs; k++) {
                    if (k > 0) {
                        injection.stepWizardForward(n, it);
                        stepped++;
                    }
                    String json = tail.captureFocusedTabJson(n, tt);
                    ConversationServerMessage.QuestionItem item =
                            mapper.parseFocusedTab(json, headerOnly.get(k).header());
                    if (item != null) {
                        full.add(item);
                        recovered++;
                    } else {
                        full.add(headerOnly.get(k)); // unrecovered tab stays header-only
                    }
                }
            } catch (IOException io) {
                LOG.info("recoverWizardOptions stepping failed for n={} target={}: {}", n, targetId, io.toString());
                for (int k = full.size(); k < tabs; k++) {
                    full.add(headerOnly.get(k));
                }
            } finally {
                // Restore focus to tab 0 (Left × the number of forward steps actually taken).
                for (int k = 0; k < stepped; k++) {
                    try {
                        injection.stepWizardBack(n, it);
                    } catch (IOException io) {
                        LOG.warn("recoverWizardOptions restore step-back failed for n={}: {}", n, io.toString());
                        break;
                    }
                }
            }
        }
        LOG.debug("recoverWizardOptions n={} recovered {}/{} tabs", n, recovered, tabs);
        return full;
    }

    private Object paneLock(int n) {
        return paneLocks.computeIfAbsent(n, k -> new Object());
    }

    /** Interrupt (ESC) the active turn on {@code targetId}'s session. */
    public void interrupt(int n, String targetId, ClientIdentity identity) throws IOException {
        // UC-60 — read-only subagent pill: no-op + audit, never inject (see injectComposer).
        if (guardSubagentInput(AuditAction.CONVERSATION_INTERRUPT, n, targetId, identity)) {
            return;
        }
        injection.interrupt(n, toInjectTarget(resolveBridgeTarget(n, targetId)));
        audit.logEvent(
                AuditAction.CONVERSATION_INTERRUPT, "ok", "n", n, "targetId", targetId == null ? "main" : targetId);
    }

    /**
     * UC-60 — the single inject-guard for a {@code subagent:} target. Returns {@code true}
     * (and the caller MUST {@code return} without injecting) when {@code targetId} is a
     * subagent pill, logging the blocked attempt with result {@code "blocked-subagent"}
     * under {@code action}; returns {@code false} for any normal pane/main target so the
     * inject proceeds. Centralised so all four input paths
     * (composer/answer/answer-batch/interrupt) share one authoritative contract, which is
     * what the challenger reviews for the no-op behavior.
     */
    private boolean guardSubagentInput(AuditAction action, int n, String targetId, ClientIdentity identity) {
        if (!isSubagentTarget(targetId)) {
            return false;
        }
        audit.logEvent(
                action,
                "blocked-subagent",
                "n",
                n,
                "targetId",
                targetId,
                "fingerprint",
                identity == null ? "" : identity.fingerprintHex());
        LOG.info("UC-60 blocked {} into read-only subagent target {} (n={})", action.wire(), targetId, n);
        return true;
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

    /**
     * UC-79 — internal (domain-layer) view of one older page fetched by {@link
     * #fetchOlderPage}: the mapped {@code frames} (transcript oldest→newest order; the
     * client prepends them at the front of the list), the new {@code newOldestLine}
     * cursor (the handler stores it as the connection's oldest-line cursor), and
     * {@code atStart} (true at the transcript beginning so paging stops). The handler
     * emits a {@code page-start}, the frames, then a {@code page-end(atStart)}.
     */
    public record OlderPage(List<ConversationServerMessage> frames, int newOldestLine, boolean atStart) {}
}
