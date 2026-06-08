package com.aisandbox.server.stream.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.aisandbox.server.stream.service.TranscriptTailService.PendingState;
import com.aisandbox.server.stream.service.TranscriptTailService.Tail;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    // ──────────────────────── UC-41 AC5/AC9 — fetchToolDetail ────────────────

    @Test
    void fetchToolDetail_renders_the_untruncated_detail_and_audits_ok() {
        when(swarm.resolveTarget(7, SwarmEnumerationService.MAIN_ID)).thenReturn(BridgeTarget.main());
        List<String> lines = List.of("main\t{\"some\":\"json\"}");
        when(tail.fetchDetailLines(eq(7), any(), eq("tu1"))).thenReturn(lines);
        when(mapper.renderDetail("tu1", lines))
                .thenReturn(new ConversationEventMapper.DetailRender("Bash", "ls -la /workspace", "drwxr-xr-x", false, true));

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
}
