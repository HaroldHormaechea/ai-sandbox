package com.aisandbox.server.stream.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo;
import com.aisandbox.server.stream.facade.StreamFacade.AuthorizeResult;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.InputInjectionService;
import com.aisandbox.server.stream.service.InputInjectionService.InjectTarget;
import com.aisandbox.server.stream.service.SwarmEnumerationService;
import com.aisandbox.server.stream.service.TmuxBridgeService.BridgeTarget;
import com.aisandbox.server.stream.service.TranscriptTailService;
import com.aisandbox.server.stream.service.TranscriptTailService.PendingState;
import com.aisandbox.server.stream.service.TranscriptTailService.SubagentInfo;
import com.aisandbox.server.stream.service.TranscriptTailService.Tail;
import com.aisandbox.server.stream.service.TranscriptTailService.TailTarget;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC-37 — {@link ConversationFacade} is the conversation domain's use-case
 * boundary. These tests assert it (a) reuses the binary stream's authorize /
 * cap gate (AC21), (b) augments NON-selected targets with pending-badge flags
 * via a bounded scan (AC16/AC18), (c) resolves a target to its tmux coordinates
 * and falls back to {@code main} on a vanished id, and (d) audits every input
 * event (AC8/AC11). All collaborators are mocked, so the facade's orchestration
 * is exercised without spawning a real {@code docker compose} process.
 */
class ConversationFacadeTest {

    private StreamFacade streamFacade;
    private SwarmEnumerationService swarm;
    private TranscriptTailService tail;
    private InputInjectionService injection;
    private ConversationEventMapper mapper;
    private AuditLogger audit;
    private ConversationFacade facade;

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

    private static ClientIdentity identity() {
        return new ClientIdentity("alice", "a".repeat(64), BigInteger.ONE);
    }

    private static TargetInfo target(String id) {
        return new TargetInfo(id, "swarm", id, id, "general-purpose", "blue", "team", null, "main", "0", "1");
    }

    @BeforeEach
    void setUp() {
        streamFacade = mock(StreamFacade.class);
        swarm = mock(SwarmEnumerationService.class);
        tail = mock(TranscriptTailService.class);
        injection = mock(InputInjectionService.class);
        mapper = mock(ConversationEventMapper.class);
        audit = mock(AuditLogger.class);
        facade = new ConversationFacade(streamFacade, swarm, tail, injection, mapper, audit, props());
    }

    // ──────────────────────── AC21 — authorize reuse ─────────────────────────

    @Test
    void authorizeOpen_delegates_to_the_binary_stream_facade() {
        AuthorizeResult allowed = new StreamFacade.Allowed();
        when(streamFacade.authorizeOpen(7, identity())).thenReturn(allowed);
        assertThat(facade.authorizeOpen(7, identity())).isSameAs(allowed);
    }

    @Test
    void backfillLines_reads_the_conversation_default_from_properties() {
        assertThat(facade.backfillLines()).isEqualTo(props().streams().conversationBackfillLines());
    }

    // ──────────────────────── AC16/AC18 — enumerate + badge ──────────────────

    @Test
    void enumerate_badges_non_selected_targets_and_never_scans_the_selected_one() {
        TargetInfo main = new TargetInfo("main", "main", "main", null, null, null, null, null, "main", null, null);
        TargetInfo a = target("swarm:main:0.1");
        TargetInfo b = target("swarm:main:0.2");
        when(swarm.enumerate(7)).thenReturn(List.of(main, a, b));
        when(tail.scanPending(eq(7), any())).thenReturn(PendingState.PENDING_QUESTION, PendingState.IDLE);

        List<TargetInfo> out = facade.enumerateConversationTargets(7, "main");

        assertThat(out).hasSize(3);
        // The selected (main) target is never scanned and never badged.
        assertThat(out.get(0).pendingQuestion()).isFalse();
        assertThat(out.get(0).pendingActivity()).isFalse();
        // First non-selected: PENDING_QUESTION → both flags set.
        assertThat(out.get(1).pendingQuestion()).isTrue();
        assertThat(out.get(1).pendingActivity()).isTrue();
        // Second non-selected: IDLE → no badge.
        assertThat(out.get(2).pendingQuestion()).isFalse();
    }

    @Test
    void enumerate_marks_activity_only_when_scan_reports_pending_activity() {
        TargetInfo a = target("swarm:main:0.1");
        when(swarm.enumerate(7)).thenReturn(List.of(a));
        when(tail.scanPending(eq(7), any())).thenReturn(PendingState.PENDING_ACTIVITY);

        TargetInfo out = facade.enumerateConversationTargets(7, "main").get(0);
        assertThat(out.pendingActivity()).isTrue();
        assertThat(out.pendingQuestion()).isFalse();
    }

    // ──────────────────────── AC6/AC20 — start tail + audit ──────────────────

    @Test
    void startTail_resolves_main_starts_the_tail_and_audits_open() throws Exception {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        Tail handle = mock(Tail.class);
        when(tail.start(eq(7), any(), anyInt())).thenReturn(handle);

        Tail got = facade.startTail(7, null);

        assertThat(got).isSameAs(handle);
        verify(tail).start(eq(7), any(), eq(facade.backfillLines()));
        verify(audit).logEvent(eq(AuditAction.CONVERSATION_OPEN), eq("ok"), eq("n"), eq(7), eq("targetId"), eq("main"));
    }

    // ──────────────────────── UC-60 — subagent pills (enumerate/start/guards) ─

    @Test
    void enumerate_appends_one_pill_per_live_subagent_disjoint_from_main_and_swarm() {
        // AC1/AC2/AC6 — each LIVE subagent the helper reports becomes an additive pill with
        // a disjoint subagent:<id> id, kind "subagent", its label as the title, and the
        // working flag carried as pendingActivity (the same badge a team pill uses). The
        // existing main/swarm targets and their ids are untouched (no duplication/mislabel).
        TargetInfo main = new TargetInfo("main", "main", "main", null, null, null, null, null, "main", null, null);
        TargetInfo pane = target("swarm:main:0.1");
        when(swarm.enumerate(7)).thenReturn(List.of(main, pane));
        when(tail.scanPending(eq(7), any())).thenReturn(PendingState.IDLE);
        when(tail.listSubagents(7))
                .thenReturn(List.of(
                        new SubagentInfo("a1", "code-reviewer", true), new SubagentInfo("b2", "verifier", false)));

        List<TargetInfo> out = facade.enumerateConversationTargets(7, "main");

        // main + swarm pane + two subagent pills, in that order (subagents appended last).
        assertThat(out).hasSize(4);
        assertThat(out)
                .extracting(TargetInfo::id)
                .containsExactly("main", "swarm:main:0.1", "subagent:a1", "subagent:b2");
        // No id collides across the spaces (AC6 — no duplication).
        assertThat(out).extracting(TargetInfo::id).doesNotHaveDuplicates();

        TargetInfo p1 = out.get(2);
        assertThat(p1.kind()).isEqualTo("subagent");
        assertThat(p1.title()).isEqualTo("code-reviewer");
        // working → pendingActivity; a subagent never raises a question → pendingQuestion stays false.
        assertThat(p1.pendingActivity()).isTrue();
        assertThat(p1.pendingQuestion()).isFalse();

        TargetInfo p2 = out.get(3);
        assertThat(p2.pendingActivity()).isFalse();
        assertThat(p2.pendingQuestion()).isFalse();
    }

    @Test
    void enumerate_subagent_pill_with_a_blank_label_falls_back_to_its_id_for_the_title() {
        when(swarm.enumerate(7))
                .thenReturn(List.of(
                        new TargetInfo("main", "main", "main", null, null, null, null, null, "main", null, null)));
        when(tail.scanPending(eq(7), any())).thenReturn(PendingState.IDLE);
        when(tail.listSubagents(7)).thenReturn(List.of(new SubagentInfo("c3", "  ", false)));

        TargetInfo pill = facade.enumerateConversationTargets(7, "main").get(1);
        assertThat(pill.id()).isEqualTo("subagent:c3");
        // A blank label never renders an empty pill — it falls back to the full id.
        assertThat(pill.title()).isEqualTo("subagent:c3");
    }

    @Test
    void enumerate_with_no_live_subagents_adds_no_extra_pills() {
        // AC7 — a session with no subagents shows no extra pills (regression guard).
        TargetInfo main = new TargetInfo("main", "main", "main", null, null, null, null, null, "main", null, null);
        when(swarm.enumerate(7)).thenReturn(List.of(main));
        when(tail.listSubagents(7)).thenReturn(List.of());

        assertThat(facade.enumerateConversationTargets(7, "main")).containsExactly(main);
    }

    @Test
    void startTail_for_a_subagent_target_builds_a_subagent_tail_without_resolving_a_pane() throws Exception {
        // AC3 — a subagent pill streams its OWN agent-<id>.jsonl. The facade must intercept
        // the subagent: id BEFORE resolveBridgeTarget (a subagent has no pane; resolving would
        // misroute to main), build a subagent TailTarget, and audit OPEN with the full id.
        Tail handle = mock(Tail.class);
        when(tail.start(eq(7), any(), anyInt())).thenReturn(handle);

        Tail got = facade.startTail(7, "subagent:abc123");

        assertThat(got).isSameAs(handle);
        // Never resolves a pane for a subagent target.
        verify(swarm, never()).resolveTarget(eq(7), any());
        ArgumentCaptor<TailTarget> captor = ArgumentCaptor.forClass(TailTarget.class);
        verify(tail).start(eq(7), captor.capture(), eq(facade.backfillLines()));
        TailTarget used = captor.getValue();
        assertThat(used.isSubagent()).isTrue();
        assertThat(used.subagentId()).isEqualTo("abc123");
        assertThat(used.hasPane()).isFalse();
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_OPEN),
                        eq("ok"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("subagent:abc123"));
    }

    // ── The Major fix — every INPUT path is a no-op + audit for a subagent: id ──
    // A subagent runs in-process under the lead with NO pane, so an inject would
    // misroute to the LEAD's pane. The server is the authoritative guard (the Android
    // read-only composer is only a UX echo): composer/answer/answer-batch/interrupt
    // must short-circuit BEFORE resolveBridgeTarget — never resolve, never inject —
    // and audit the blocked attempt with result "blocked-subagent".

    @Test
    void injectComposer_into_a_subagent_target_is_a_noop_and_audits_blocked() throws Exception {
        facade.injectComposer(7, "subagent:a1", "hello lead?", identity());

        verify(injection, never()).injectComposer(anyInt(), any(), any());
        verify(swarm, never()).resolveTarget(anyInt(), any());
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_INPUT),
                        eq("blocked-subagent"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("subagent:a1"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    @Test
    void injectAnswer_into_a_subagent_target_is_a_noop_and_audits_blocked() throws Exception {
        facade.injectAnswer(7, "subagent:a1", 2, false, List.of(0), -1, null, identity());

        verify(injection, never()).injectAnswer(anyInt(), any(), anyInt(), anyBoolean(), any(), anyInt(), any());
        verify(swarm, never()).resolveTarget(anyInt(), any());
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_ANSWER),
                        eq("blocked-subagent"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("subagent:a1"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    @Test
    void injectAnswerBatch_into_a_subagent_target_is_a_noop_and_audits_blocked() throws Exception {
        facade.injectAnswerBatch(7, "subagent:a1", List.of(), identity());

        verify(injection, never()).injectAnswerBatch(anyInt(), any(), any());
        verify(swarm, never()).resolveTarget(anyInt(), any());
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_ANSWER),
                        eq("blocked-subagent"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("subagent:a1"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    @Test
    void interrupt_of_a_subagent_target_is_a_noop_and_audits_blocked() throws Exception {
        facade.interrupt(7, "subagent:a1", identity());

        verify(injection, never()).interrupt(anyInt(), any());
        verify(swarm, never()).resolveTarget(anyInt(), any());
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_INTERRUPT),
                        eq("blocked-subagent"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("subagent:a1"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    @Test
    void a_normal_target_is_NOT_treated_as_a_subagent_by_the_guard() throws Exception {
        // The guard must be precise: a swarm/main id still injects normally (no false-block).
        when(swarm.resolveTarget(7, "swarm:main:0.1")).thenReturn(new BridgeTarget(null, "main", "0", "1"));

        facade.injectComposer(7, "swarm:main:0.1", "real input", identity());

        verify(injection).injectComposer(eq(7), any(InjectTarget.class), eq("real input"));
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_INPUT),
                        eq("ok"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("swarm:main:0.1"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    // ──────────────────────── UC-41 AC5/AC9 — fetchToolDetail ────────────────

    @Test
    void fetchToolDetail_renders_the_untruncated_detail_and_audits_ok() {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        List<String> lines = List.of("main\t{\"some\":\"json\"}");
        when(tail.fetchDetailLines(eq(7), any(), eq("tu1"))).thenReturn(lines);
        when(mapper.renderDetail("tu1", lines))
                .thenReturn(new ConversationEventMapper.DetailRender(
                        "Bash", "ls -la /workspace", "drwxr-xr-x", false, true));

        ConversationFacade.ToolDetailView view = facade.fetchToolDetail(7, "main", "tu1", identity());

        assertThat(view.available()).isTrue();
        assertThat(view.toolUseId()).isEqualTo("tu1");
        assertThat(view.toolName()).isEqualTo("Bash");
        assertThat(view.input()).isEqualTo("ls -la /workspace");
        assertThat(view.result()).isEqualTo("drwxr-xr-x");
        assertThat(view.isError()).isFalse();
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_FETCH_DETAIL),
                        eq("ok"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("main"),
                        eq("toolUseId"),
                        eq("tu1"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    @Test
    void fetchToolDetail_carries_the_error_flag_through_to_the_view() {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        List<String> lines = List.of("main\t{\"some\":\"json\"}");
        when(tail.fetchDetailLines(eq(7), any(), eq("tuErr"))).thenReturn(lines);
        when(mapper.renderDetail("tuErr", lines))
                .thenReturn(new ConversationEventMapper.DetailRender("Bash", "false", "boom", true, true));

        ConversationFacade.ToolDetailView view = facade.fetchToolDetail(7, "main", "tuErr", identity());

        assertThat(view.available()).isTrue();
        assertThat(view.isError()).isTrue();
        assertThat(view.result()).isEqualTo("boom");
    }

    @Test
    void fetchToolDetail_on_a_miss_returns_unavailable_and_audits_miss() {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        when(tail.fetchDetailLines(eq(7), any(), eq("gone"))).thenReturn(List.of());
        when(mapper.renderDetail(eq("gone"), any()))
                .thenReturn(new ConversationEventMapper.DetailRender(null, "", "", false, false));

        ConversationFacade.ToolDetailView view = facade.fetchToolDetail(7, "main", "gone", identity());

        assertThat(view.available()).isFalse();
        assertThat(view.toolUseId()).isEqualTo("gone");
        assertThat(view.input()).isEmpty();
        assertThat(view.result()).isEmpty();
        assertThat(view.isError()).isFalse();
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_FETCH_DETAIL),
                        eq("miss"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("main"),
                        eq("toolUseId"),
                        eq("gone"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    // ──────────────────────── AC8 — composer inject + audit ──────────────────

    @Test
    void injectComposer_resolves_target_injects_and_audits_input() throws Exception {
        when(swarm.resolveTarget(7, "swarm:main:0.1")).thenReturn(new BridgeTarget(null, "main", "0", "1"));

        facade.injectComposer(7, "swarm:main:0.1", "hello", identity());

        verify(injection).injectComposer(eq(7), any(InjectTarget.class), eq("hello"));
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_INPUT),
                        eq("ok"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("swarm:main:0.1"),
                        eq("fingerprint"),
                        eq("a".repeat(64)));
    }

    // ──────────────────────── UC-67 — openMcpMenu surfaces /mcp + audits ─────

    @Test
    void openMcpMenu_injects_slash_mcp_into_the_main_pane_and_audits_login() throws Exception {
        // UC-67 AC6 — login is a facade-to-facade hand-off from McpFacade: it surfaces
        // Claude Code's interactive /mcp menu in the session's LIVE MAIN pane (never a
        // teammate tile) so the human can finish auth there. It must resolve the MAIN
        // target, inject the literal "/mcp" composer line, and audit MCP_LOGIN.
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());

        facade.openMcpMenu(7, identity());

        // Resolves MAIN (not a passed-in target id) and injects the slash command.
        verify(swarm).resolveTarget(7, SwarmEnumerationService.MAIN_ID);
        verify(injection).injectComposer(eq(7), any(InjectTarget.class), eq("/mcp"));
        verify(audit)
                .logEvent(eq(AuditAction.MCP_LOGIN), eq("ok"), eq("n"), eq(7), eq("fingerprint"), eq("a".repeat(64)));
    }

    @Test
    void openMcpMenu_tolerates_a_null_identity() throws Exception {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());

        facade.openMcpMenu(7, null);

        verify(injection).injectComposer(eq(7), any(InjectTarget.class), eq("/mcp"));
        verify(audit).logEvent(eq(AuditAction.MCP_LOGIN), eq("ok"), eq("n"), eq(7), eq("fingerprint"), eq(""));
    }

    // ──────────────────────── AC11 — answer inject + audit ───────────────────

    @Test
    void injectAnswer_delegates_to_injection_and_audits_answer() throws Exception {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());

        facade.injectAnswer(7, "main", 3, true, List.of(0, 2), 3, "free", identity());

        verify(injection)
                .injectAnswer(eq(7), any(InjectTarget.class), eq(3), eq(true), eq(List.of(0, 2)), eq(3), eq("free"));
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_ANSWER),
                        eq("ok"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("main"),
                        eq("multiSelect"),
                        eq(true));
    }

    @Test
    void interrupt_delegates_and_audits() throws Exception {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        facade.interrupt(7, "main", identity());
        verify(injection).interrupt(eq(7), any(InjectTarget.class));
        verify(audit)
                .logEvent(eq(AuditAction.CONVERSATION_INTERRUPT), eq("ok"), eq("n"), eq(7), eq("targetId"), eq("main"));
    }

    @Test
    void auditClose_records_a_conversation_close_event() {
        facade.auditClose(7, identity(), 1000, "bye");
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_CLOSE),
                        eq("ok"),
                        eq("n"),
                        eq(7),
                        eq("fingerprint"),
                        eq("a".repeat(64)),
                        eq("closeCode"),
                        eq(1000),
                        eq("reason"),
                        eq("bye"));
    }

    // ──────────────────────── target resolution fallback ─────────────────────

    @Test
    void a_vanished_target_id_falls_back_to_main_without_throwing() throws Exception {
        // The id no longer resolves — resolveTarget throws; the facade must still
        // inject (against main) rather than propagate the failure.
        when(swarm.resolveTarget(eq(7), eq("swarm:gone:9.9"))).thenThrow(new RuntimeException("no such pane"));

        facade.injectComposer(7, "swarm:gone:9.9", "still works", identity());

        verify(injection).injectComposer(eq(7), any(InjectTarget.class), eq("still works"));
    }

    @Test
    void main_resolution_failure_degrades_to_BridgeTarget_main() throws Exception {
        // Even resolving MAIN can race; the facade swallows it and uses main coords.
        when(swarm.resolveTarget(eq(7), eq(SwarmEnumerationService.MAIN_ID)))
                .thenThrow(new RuntimeException("enumeration raced"));

        facade.interrupt(7, null, identity());

        verify(injection).interrupt(eq(7), any(InjectTarget.class));
        verify(swarm, never()).resolveTarget(eq(7), eq("other"));
    }

    // ──────────────────── UC-55 — multi-question wizard option recovery ───────
    // recoverWizardOptions steps the LIVE pane through every tab (read-only Right arrow),
    // captures + parses each focused tab's options, then restores focus (Left) — leaving
    // the pane exactly as found. The recovered items carry each tab's real options so the
    // batch becomes in-app answerable (AC2/AC5/AC10). All collaborators mocked.

    private static ConversationServerMessage.QuestionItem headerOnly(String header) {
        return new ConversationServerMessage.QuestionItem("", header, false, List.of());
    }

    private static ConversationServerMessage.QuestionItem withOptions(String header, String label) {
        return new ConversationServerMessage.QuestionItem(
                "Q-" + header, header, false, List.of(new ConversationServerMessage.Option(label, "")));
    }

    @Test
    void recoverWizardOptions_steps_every_tab_then_restores_focus_and_returns_full_options() throws Exception {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        when(tail.captureFocusedTabJson(eq(7), any(TailTarget.class))).thenReturn("j0", "j1", "j2");
        ConversationServerMessage.QuestionItem r0 = withOptions("Color", "Red");
        ConversationServerMessage.QuestionItem r1 = withOptions("Size", "Large");
        ConversationServerMessage.QuestionItem r2 = withOptions("Shape", "Round");
        when(mapper.parseFocusedTab(any(), any())).thenReturn(r0, r1, r2);

        List<ConversationServerMessage.QuestionItem> out = facade.recoverWizardOptions(
                7, "main", List.of(headerOnly("Color"), headerOnly("Size"), headerOnly("Shape")));

        // Every tab recovered with its real options.
        assertThat(out).containsExactly(r0, r1, r2);
        // Walk = Right ×(tabs-1) to read tabs 1 and 2, then Left ×(tabs-1) to restore tab 0,
        // and ALL forwards precede ALL backs (focus is restored only after reading is done).
        var io = inOrder(injection);
        io.verify(injection, times(2)).stepWizardForward(eq(7), any(InjectTarget.class));
        io.verify(injection, times(2)).stepWizardBack(eq(7), any(InjectTarget.class));
        // Three captures (one per tab), no answer-injection keystrokes.
        verify(tail, times(3)).captureFocusedTabJson(eq(7), any(TailTarget.class));
        verify(injection, never()).injectAnswer(anyInt(), any(), anyInt(), anyBoolean(), any(), anyInt(), any());
    }

    @Test
    void recoverWizardOptions_single_or_empty_batch_is_returned_unchanged_without_touching_the_pane() throws Exception {
        // A single question is already fully recovered by UC-50; never perturb the pane.
        List<ConversationServerMessage.QuestionItem> single = List.of(withOptions("Only", "A"));
        assertThat(facade.recoverWizardOptions(7, "main", single)).isSameAs(single);
        assertThat(facade.recoverWizardOptions(7, "main", null)).isNull();

        verify(injection, never()).stepWizardForward(anyInt(), any());
        verify(injection, never()).stepWizardBack(anyInt(), any());
        verify(tail, never()).captureFocusedTabJson(anyInt(), any());
    }

    @Test
    void recoverWizardOptions_leaves_an_unrecovered_tab_header_only() throws Exception {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        when(tail.captureFocusedTabJson(eq(7), any(TailTarget.class))).thenReturn("j0", "", "j2");
        ConversationServerMessage.QuestionItem r0 = withOptions("Color", "Red");
        ConversationServerMessage.QuestionItem r2 = withOptions("Shape", "Round");
        // Middle tab fails to parse (capture miss) → null; first and last recover.
        when(mapper.parseFocusedTab(any(), any())).thenReturn(r0, null, r2);

        ConversationServerMessage.QuestionItem h1 = headerOnly("Size");
        List<ConversationServerMessage.QuestionItem> out =
                facade.recoverWizardOptions(7, "main", List.of(headerOnly("Color"), h1, withOptions("Shape", "x")));

        // The unrecovered tab stays the original header-only item (so the batch stays
        // answerable=false rather than rendering a tab with no options).
        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isSameAs(r0);
        assertThat(out.get(1)).isSameAs(h1);
        assertThat(out.get(2)).isSameAs(r2);
    }

    @Test
    void recoverWizardOptions_restores_focus_even_when_a_step_throws() throws Exception {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        when(tail.captureFocusedTabJson(eq(7), any(TailTarget.class))).thenReturn("j0", "j1");
        when(mapper.parseFocusedTab(any(), any())).thenReturn(withOptions("Color", "Red"));
        // First forward (to tab 1) succeeds; the second (to tab 2) throws mid-walk.
        doNothing()
                .doThrow(new IOException("pane vanished"))
                .when(injection)
                .stepWizardForward(eq(7), any(InjectTarget.class));

        // Never throws — degrades to best-effort partial recovery …
        List<ConversationServerMessage.QuestionItem> out = facade.recoverWizardOptions(
                7, "main", List.of(headerOnly("Color"), headerOnly("Size"), headerOnly("Shape")));
        assertThat(out).hasSize(3);

        // … and the finally block still restores focus (Left) for the one forward step that
        // actually succeeded (stepped=1) — the pane must be left as found for injectAnswerBatch.
        verify(injection, times(2)).stepWizardForward(eq(7), any(InjectTarget.class));
        verify(injection, times(1)).stepWizardBack(eq(7), any(InjectTarget.class));
    }

    @Test
    void recoverWizardOptions_holds_the_pane_lock_so_an_answer_injection_cannot_interleave() throws Exception {
        // Single-writer invariant: recovery and answer injection drive send-keys into the
        // SAME pane, so the per-session pane lock must serialize them. We hold the lock inside
        // the recovery walk (block on a latch during the first pane step) and prove a
        // concurrent injectAnswer cannot reach injection.injectAnswer until recovery releases.
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        when(tail.captureFocusedTabJson(eq(7), any(TailTarget.class))).thenReturn("j0", "j1");
        when(mapper.parseFocusedTab(any(), any())).thenReturn(withOptions("Color", "Red"));

        java.util.concurrent.CountDownLatch insideRecovery = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean injectReached = new java.util.concurrent.atomic.AtomicBoolean(false);
        // The first pane step blocks (holding the lock) until the test releases it.
        org.mockito.Mockito.doAnswer(inv -> {
                    insideRecovery.countDown();
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    return null;
                })
                .when(injection)
                .stepWizardForward(eq(7), any(InjectTarget.class));
        org.mockito.Mockito.doAnswer(inv -> {
                    injectReached.set(true);
                    return null;
                })
                .when(injection)
                .injectAnswer(anyInt(), any(), anyInt(), anyBoolean(), any(), anyInt(), any());

        Thread recoverer = new Thread(
                () -> facade.recoverWizardOptions(7, "main", List.of(headerOnly("Color"), headerOnly("Size"))));
        recoverer.start();
        assertThat(insideRecovery.await(2, java.util.concurrent.TimeUnit.SECONDS))
                .isTrue();

        Thread answerer = new Thread(() -> {
            try {
                facade.injectAnswer(7, "main", 2, false, List.of(0), -1, null, identity());
            } catch (Exception ignored) {
                // not expected
            }
        });
        answerer.start();

        // While recovery holds the lock, the answer injection must be blocked.
        Thread.sleep(200);
        assertThat(injectReached)
                .as("injectAnswer must not run while recovery holds the pane lock")
                .isFalse();

        // Release recovery → the answer injection proceeds.
        release.countDown();
        recoverer.join(2000);
        answerer.join(2000);
        assertThat(injectReached)
                .as("injectAnswer proceeds once the pane lock is free")
                .isTrue();
    }

    // ──────────────────────── UC-79 AC2/AC6 — fetchOlderPage ─────────────────

    @Test
    void fetchOlderPage_maps_each_envelope_line_and_carries_the_cursor_and_atStart() {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        // The service returns the older envelope lines (oldest→newest) plus the new cursor.
        TranscriptTailService.PageLines page =
                new TranscriptTailService.PageLines(List.of("main\t{\"a\":1}", "main\t{\"b\":2}"), 50, false);
        when(tail.fetchPageLines(eq(7), any(), eq(150), eq(100))).thenReturn(page);
        // The SAME mapper the live tail uses — so paged history renders identically.
        ConversationServerMessage f1 = new ConversationServerMessage.AssistantText("u1", false, "main", "older-1");
        ConversationServerMessage f2 = new ConversationServerMessage.AssistantText("u2", false, "main", "older-2");
        when(mapper.map("main", "{\"a\":1}")).thenReturn(List.of(f1));
        when(mapper.map("main", "{\"b\":2}")).thenReturn(List.of(f2));

        ConversationFacade.OlderPage out = facade.fetchOlderPage(7, "main", 150, 100);

        // Frames collected in transcript (oldest→newest) order; cursor + atStart carried through.
        assertThat(out.frames()).containsExactly(f1, f2);
        assertThat(out.newOldestLine()).isEqualTo(50);
        assertThat(out.atStart()).isFalse();
        verify(audit)
                .logEvent(
                        eq(AuditAction.CONVERSATION_FETCH_PAGE),
                        eq("ok"),
                        eq("n"),
                        eq(7),
                        eq("targetId"),
                        eq("main"),
                        eq("beforeLine"),
                        eq(150),
                        eq("frames"),
                        eq(2));
    }

    @Test
    void fetchOlderPage_splits_the_source_prefix_before_mapping() {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        // A subagent-sourced envelope must be mapped with its own source, not "main".
        when(tail.fetchPageLines(eq(7), any(), eq(80), eq(100)))
                .thenReturn(new TranscriptTailService.PageLines(List.of("subagent:abc\t{\"x\":1}"), 0, true));
        ConversationServerMessage f = new ConversationServerMessage.AssistantText("u", true, "subagent:abc", "s");
        when(mapper.map("subagent:abc", "{\"x\":1}")).thenReturn(List.of(f));

        ConversationFacade.OlderPage out = facade.fetchOlderPage(7, "main", 80, 100);

        assertThat(out.frames()).containsExactly(f);
        assertThat(out.atStart()).isTrue();
        verify(mapper).map("subagent:abc", "{\"x\":1}");
    }

    @Test
    void fetchOlderPage_empty_page_yields_no_frames_and_pins_atStart() {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        when(tail.fetchPageLines(eq(7), any(), eq(120), eq(100)))
                .thenReturn(new TranscriptTailService.PageLines(List.of(), 120, true));

        ConversationFacade.OlderPage out = facade.fetchOlderPage(7, "main", 120, 100);

        assertThat(out.frames()).isEmpty();
        assertThat(out.newOldestLine()).isEqualTo(120);
        assertThat(out.atStart()).isTrue();
        verify(mapper, never()).map(any(), any());
    }

    @Test
    void conversationPageLines_exposes_the_configured_older_page_size() {
        assertThat(facade.conversationPageLines()).isEqualTo(ServerProperties.CONVERSATION_PAGE_LINES_DEFAULT);
    }
}
