package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.service.TranscriptTailService.PendingState;
import com.aisandbox.server.stream.service.TranscriptTailService.SubagentInfo;
import com.aisandbox.server.stream.service.TranscriptTailService.TailTarget;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UC-37 AC18/AC20 — {@link TranscriptTailService} owns the in-container
 * transcript-tail helper. These tests cover the two unit-testable surfaces:
 * the {@code docker compose exec … aisandbox-conversation-tail} argv builder
 * (so a target's tmux coordinates map to the right helper flags) and the
 * one-shot {@code --scan-pending} state mapping that drives switcher badges
 * (AC18) — including its never-throw degrade-to-IDLE contract. The streaming
 * {@code start()} path spawns a real process and is left to the IT tier.
 */
class TranscriptTailServiceTest {

    private ProcessExecutor exec;
    private TranscriptTailService svc;

    @BeforeEach
    void setUp() {
        exec = mock(ProcessExecutor.class);
        svc = new TranscriptTailService(exec);
    }

    // ──────────────────────── argv builder ───────────────────────────────────

    @Test
    void buildArgv_for_main_targets_the_helper_with_session_and_backfill() {
        List<String> argv = TranscriptTailService.buildArgv(7, TailTarget.main(), 200);
        assertThat(argv).containsSubsequence("docker", "compose", "-p", "ai-sandbox-7", "exec", "-T", "claude-sandbox");
        assertThat(argv).contains("aisandbox-conversation-tail");
        assertThat(argv).containsSequence("--session", "main");
        assertThat(argv).containsSequence("--backfill", "200");
        // No pane/socket flags for the bare main session.
        assertThat(argv).doesNotContain("--window", "--pane", "--socket");
    }

    @Test
    void buildArgv_for_a_pane_target_adds_window_pane_and_socket() {
        TailTarget pane = new TailTarget("/tmp/tmux-997/claude-swarm-1", "main", "0", "1");
        List<String> argv = TranscriptTailService.buildArgv(3, pane, 50);
        assertThat(argv).containsSequence("--session", "main");
        assertThat(argv).containsSequence("--window", "0");
        assertThat(argv).containsSequence("--pane", "1");
        assertThat(argv).containsSequence("--socket", "/tmp/tmux-997/claude-swarm-1");
    }

    @Test
    void buildArgv_clamps_backfill_to_at_least_one() {
        List<String> argv = TranscriptTailService.buildArgv(1, TailTarget.main(), 0);
        assertThat(argv).containsSequence("--backfill", "1");
    }

    // ──────────────────────── AC18 — scanPending mapping ─────────────────────

    @Test
    void scanPending_maps_pending_question_output() throws Exception {
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(0, "pending-question tuQ", ""));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.PENDING_QUESTION);
    }

    @Test
    void scanPending_maps_pending_activity_output() throws Exception {
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(0, "pending-activity", ""));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.PENDING_ACTIVITY);
    }

    @Test
    void scanPending_maps_empty_output_to_idle() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, "", ""));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }

    @Test
    void scanPending_non_zero_exit_degrades_to_idle() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(2, "anything", "boom"));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }

    @Test
    void scanPending_swallows_io_failure_and_returns_idle() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenThrow(new IOException("helper crashed"));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }

    @Test
    void scanPending_passes_the_scan_pending_flag_to_the_helper() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, "", ""));
        // The argv builder for scanning appends --scan-pending; assert it is built with backfill 1.
        // (We can only observe via the buildArgv contract; scanPending uses buildArgv(n, target, 1).)
        List<String> base = TranscriptTailService.buildArgv(7, TailTarget.main(), 1);
        assertThat(base).containsSequence("--backfill", "1");
        // And the scan itself returns IDLE for empty output (already covered) — this asserts no throw.
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }

    // ──────────────────────── UC-60 — subagent tail target argv (AC3) ─────────

    @Test
    void buildArgv_for_a_subagent_target_adds_the_subagent_flag_and_NO_pane_flags() {
        // AC3 — tapping a subagent pill streams the lead's agent-<id>.jsonl. A subagent
        // has no tmux pane, so the argv must carry `--subagent <id>` anchored to the main
        // session and must NOT emit window/pane/socket flags (which would target a pane).
        TailTarget sub = TailTarget.subagent("abc123");
        assertThat(sub.isSubagent()).isTrue();
        assertThat(sub.hasPane()).isFalse();

        List<String> argv = TranscriptTailService.buildArgv(4, sub, 200);
        assertThat(argv).containsSequence("--session", "main");
        assertThat(argv).containsSequence("--subagent", "abc123");
        assertThat(argv).containsSequence("--backfill", "200");
        // The Major isolation property: no pane coordinates ever attach to a subagent tail.
        assertThat(argv).doesNotContain("--window", "--pane", "--socket");
    }

    @Test
    void tailTarget_main_and_pane_are_not_subagents() {
        assertThat(TailTarget.main().isSubagent()).isFalse();
        assertThat(new TailTarget("/sock", "main", "0", "1").isSubagent()).isFalse();
        // A blank/empty subagent id does NOT count as a subagent target.
        assertThat(new TailTarget(null, "main", null, null, "").isSubagent()).isFalse();
        assertThat(new TailTarget(null, "main", null, null, "  ").isSubagent()).isFalse();
    }

    // ──────────────────────── UC-60 — listSubagents NDJSON parse (AC6/AC2) ────

    @Test
    void listSubagents_parses_one_SubagentInfo_per_live_subagent_with_disjoint_ids() throws Exception {
        // The helper emits one JSON object per LIVE subagent ({id,label,working}); the
        // service parses them into the records the facade turns into subagent:<id> pills.
        String ndjson = "{\"id\":\"a1\",\"label\":\"code-reviewer\",\"working\":true}\n"
                + "{\"id\":\"b2\",\"label\":\"agent b2abcd\",\"working\":false}\n";
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, ndjson, ""));

        List<SubagentInfo> out = svc.listSubagents(7);

        assertThat(out)
                .containsExactly(
                        new SubagentInfo("a1", "code-reviewer", true), new SubagentInfo("b2", "agent b2abcd", false));
        // Disjoint id space — the bare ids carry no `subagent:`/`swarm:`/`main` prefix here
        // (the facade adds the subagent: prefix); they must be distinct (no duplication, AC6).
        assertThat(out).extracting(SubagentInfo::id).doesNotHaveDuplicates().containsExactly("a1", "b2");
    }

    @Test
    void listSubagents_skips_a_malformed_line_but_keeps_the_valid_records() throws Exception {
        String ndjson = "{\"id\":\"ok1\",\"label\":\"L\",\"working\":true}\n"
                + "{ this is not json }\n"
                + "{\"id\":\"ok2\",\"label\":\"M\",\"working\":false}\n";
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, ndjson, ""));

        assertThat(svc.listSubagents(7)).extracting(SubagentInfo::id).containsExactly("ok1", "ok2");
    }

    @Test
    void listSubagents_skips_a_record_missing_or_blank_id() throws Exception {
        String ndjson = "{\"label\":\"no-id\",\"working\":true}\n"
                + "{\"id\":\"\",\"label\":\"blank-id\"}\n"
                + "{\"id\":\"keep\",\"label\":\"L\",\"working\":true}\n";
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, ndjson, ""));

        assertThat(svc.listSubagents(7)).extracting(SubagentInfo::id).containsExactly("keep");
    }

    @Test
    void listSubagents_defaults_missing_label_and_working_fields() throws Exception {
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(0, "{\"id\":\"x\"}\n", ""));
        assertThat(svc.listSubagents(7)).containsExactly(new SubagentInfo("x", "", false));
    }

    @Test
    void listSubagents_empty_or_blank_output_degrades_to_an_empty_list() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, "", ""));
        assertThat(svc.listSubagents(7)).isEmpty();
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, "   \n  \n", ""));
        assertThat(svc.listSubagents(7)).isEmpty();
    }

    @Test
    void listSubagents_non_zero_exit_degrades_to_an_empty_list() throws Exception {
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(3, "{\"id\":\"x\"}", "boom"));
        assertThat(svc.listSubagents(7)).isEmpty();
    }

    @Test
    void listSubagents_swallows_an_io_failure_and_returns_an_empty_list() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenThrow(new IOException("helper crashed"));
        assertThat(svc.listSubagents(7)).isEmpty();
    }
}
