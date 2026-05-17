package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.service.DockerEnumerationService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC24 + UC04 AC37 — enumeration is a sequence of {@code docker compose}
 * calls; this test covers both supported output shapes (JSON array,
 * NDJSON), the title-normalisation cases, and the UC04 state mapping
 * ({@code running | starting | stopped}) including the
 * {@code --all} flag that surfaces stopped projects.
 */
class DockerEnumerationServiceTest {

    @Test
    void parses_json_array_output() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        String arrayJson =
                """
                [{"Name":"ai-sandbox-1","Status":"running"},
                 {"Name":"ai-sandbox-3","Status":"running"},
                 {"Name":"unrelated-project","Status":"running"}]
                """;
        // UC04 AC37 — `docker compose ls` now goes with `--all` so stopped
        // projects also surface; assert the flag is present.
        when(exec.run(
                        argThat(argv ->
                                argv != null && argv.size() >= 3 && "ls".equals(argv.get(2)) && argv.contains("--all")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, arrayJson, ""));
        // Container id lookups + combined inspect + tmux title — happy path.
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "abc123\n", ""));
        // Single combined inspect: label|status|running per UC04 § B4.
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "my-label|running|true", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "claude", ""));

        DockerEnumerationService svc = new DockerEnumerationService(exec);
        List<SessionRecord> got = svc.enumerate();

        assertThat(got).hasSize(2);
        assertThat(got).extracting(SessionRecord::n).containsExactly(1, 3);
        assertThat(got).allSatisfy(r -> assertThat(r.label()).isEqualTo("my-label"));
        // 'claude' is normalised to '(idle)'.
        assertThat(got).allSatisfy(r -> assertThat(r.tmuxTitle()).isEqualTo("(idle)"));
        // UC04 AC37 — three-state model.
        assertThat(got).allSatisfy(r -> assertThat(r.state()).isEqualTo("running"));
    }

    @Test
    void parses_ndjson_output() throws Exception {
        // KNOWN LIMITATION (developer flagged): NDJSON path is best-effort.
        // This test fails today if the NDJSON branch is broken.
        ProcessExecutor exec = mock(ProcessExecutor.class);
        String ndjson =
                """
                {"Name":"ai-sandbox-2","Status":"running"}
                {"Name":"ai-sandbox-5","Status":"running"}
                """;
        when(exec.run(
                        argThat(argv ->
                                argv != null && argv.size() >= 3 && "ls".equals(argv.get(2)) && argv.contains("--all")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, ndjson, ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        // Empty label, status=running, running=true.
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "|running|true", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        List<SessionRecord> got = new DockerEnumerationService(exec).enumerate();

        assertThat(got).extracting(SessionRecord::n).containsExactly(2, 5);
        assertThat(got).extracting(SessionRecord::tmuxTitle).containsExactly("doing-thing", "doing-thing");
        assertThat(got).allSatisfy(r -> assertThat(r.state()).isEqualTo("running"));
    }

    @Test
    void returns_empty_when_compose_ls_fails() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "boom"));

        assertThat(new DockerEnumerationService(exec).enumerate()).isEmpty();
    }

    @Test
    void normalises_idle_titles() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-7\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        // Combined inspect: label `<no value>` (mapped to ""), running.
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "<no value>|running|true", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "bash", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.label()).isEqualTo("");
        assertThat(r.tmuxTitle()).isEqualTo("(idle)");
        assertThat(r.state()).isEqualTo("running");
    }

    @Test
    void title_unavailable_when_container_id_missing() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-9\"}]", ""));
        // ps returns empty → no container id.
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "\n", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
        // UC04 AC37 — no container ⇒ state "stopped" (the prior contract was "exited").
        assertThat(r.state()).isEqualTo("stopped");
    }

    // ── UC04 AC37 — new tests for stopped/starting state mapping + tmux skip ─

    @Test
    void stopped_containers_surface_with_stopped_state_and_no_tmux_call() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv ->
                                argv != null && argv.size() >= 3 && "ls".equals(argv.get(2)) && argv.contains("--all")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-12\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "stopped-cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "my-stopped-label|exited|false", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.n()).isEqualTo(12);
        assertThat(r.label()).isEqualTo("my-stopped-label");
        assertThat(r.state()).isEqualTo("stopped");
        // AC37 — tmux is skipped for non-running containers (exec on a
        // stopped container errors and inflates enumeration latency).
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
        verify(exec, never()).run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any());
    }

    @Test
    void starting_containers_map_to_starting_state() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-15\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "starting-cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "|created|false", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.state()).isEqualTo("starting");
        // tmux is skipped for non-running too.
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
        verify(exec, never()).run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any());
    }

    @Test
    void state_mapping_table_uc04_ac37() throws Exception {
        // Drive the package-private mapper through enumerate() — for
        // each docker status, build a fresh mock and assert the SessionRecord
        // surface. mapState() itself stays package-private so the test
        // exercises the public path the Android client actually sees.
        java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
        table.put("running", "running");
        table.put("created", "starting");
        table.put("restarting", "starting");
        table.put("exited", "stopped");
        table.put("dead", "stopped");
        table.put("paused", "stopped");
        // Defensive — anything unknown becomes stopped.
        table.put("zombie", "stopped");
        table.put("", "stopped");

        for (var e : table.entrySet()) {
            String dockerStatus = e.getKey();
            String expected = e.getValue();
            ProcessExecutor exec = mock(ProcessExecutor.class);
            when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                    .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-1\"}]", ""));
            when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                    .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
            boolean runningBool = "running".equals(dockerStatus);
            when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                    .thenReturn(new ProcessExecutor.Result(0, "|" + dockerStatus + "|" + runningBool, ""));
            when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                    .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

            SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
            assertThat(r.state()).as("dockerStatus=%s", dockerStatus).isEqualTo(expected);
        }
    }

    @Test
    void inspect_argv_uses_combined_format_string_uc04_ac37() throws Exception {
        // Pin the argv shape of the single combined inspect call — the
        // implementation switched away from two round-trips (label +
        // tmux title) to one inspect that returns `label|status|running`.
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-1\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|running|true", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        new DockerEnumerationService(exec).enumerate();

        // Format must request all three fields, separated by pipes, in
        // one call. No second inspect.
        verify(exec, times(1))
                .run(
                        argThat(argv -> argv != null
                                && argv.contains("inspect")
                                && argv.stream()
                                        .anyMatch(s -> s.contains("|")
                                                && s.contains(".State.Status")
                                                && s.contains(".State.Running"))),
                        any(),
                        any());
    }
}
