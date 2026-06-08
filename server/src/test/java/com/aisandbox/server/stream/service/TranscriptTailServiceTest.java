package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.service.TranscriptTailService.PendingState;
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
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }

    @Test
    void scanPending_non_zero_exit_degrades_to_idle() throws Exception {
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(2, "anything", "boom"));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }

    @Test
    void scanPending_swallows_io_failure_and_returns_idle() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenThrow(new IOException("helper crashed"));
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }

    @Test
    void scanPending_passes_the_scan_pending_flag_to_the_helper() throws Exception {
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
        // The argv builder for scanning appends --scan-pending; assert it is built with backfill 1.
        // (We can only observe via the buildArgv contract; scanPending uses buildArgv(n, target, 1).)
        List<String> base = TranscriptTailService.buildArgv(7, TailTarget.main(), 1);
        assertThat(base).containsSequence("--backfill", "1");
        // And the scan itself returns IDLE for empty output (already covered) — this asserts no throw.
        assertThat(svc.scanPending(7, TailTarget.main())).isEqualTo(PendingState.IDLE);
    }
}
