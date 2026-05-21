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
import org.mockito.invocation.InvocationOnMock;

/**
 * AC24 + UC04 AC37 + UC-15 AC2/AC6 — enumeration is a sequence of
 * {@code docker compose} (and, on legacy binaries, {@code docker ps})
 * calls. Covers both supported output shapes (JSON array, NDJSON),
 * the title-normalisation cases, the UC04 state mapping
 * ({@code running | starting | stopped}), the {@code --all} flag that
 * surfaces stopped projects, and the UC-15 fallback path for runners
 * whose docker-compose binary rejects {@code --all}.
 *
 * <h2>UC-15 — 4-arg / 3-arg stub split</h2>
 *
 * <p>Production code reaches {@link ProcessExecutor} via two overloads
 * after the UC-15 diff:
 *
 * <ul>
 *   <li>The {@code docker compose ls}, {@code docker ps} fallback, and
 *       {@code containerId(...)} call sites go through the 4-arg
 *       {@code run(argv, workingDir, env, timeout)} overload because
 *       UC-15 pins {@code LC_ALL=C} so the stderr {@code --all}
 *       substring check stays locale-stable.</li>
 *   <li>The {@code docker inspect} and tmux {@code display-message}
 *       calls still go through the 3-arg
 *       {@code run(argv, workingDir, timeout)} overload (no env override
 *       needed).</li>
 * </ul>
 *
 * <p>Mockito treats overload signatures as distinct stubs (the
 * production {@code run(argv, dir, timeout)} delegate-to-4-arg path is
 * replaced by the mock), so the stubs below match the OVERLOAD the
 * production call site actually reaches. The split is the only
 * semantic change to the pre-UC-15 tests; assertions are unchanged.
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
        // UC-15 — 4-arg overload (LC_ALL=C pinned).
        when(exec.run(
                        argThat(argv ->
                                argv != null && argv.size() >= 3 && "ls".equals(argv.get(2)) && argv.contains("--all")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, arrayJson, ""));
        // Container id lookups — modern path, 4-arg overload (LC_ALL=C).
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "abc123\n", ""));
        // Single combined inspect: label|status|running per UC04 § B4 — 3-arg.
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
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, ndjson, ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
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
        // UC-15 — the ls call goes through the 4-arg overload; a non-125
        // exit code does NOT trigger the docker-ps fallback (only
        // {125, 64} + stderr `--all` does). A generic exit=1 still returns
        // empty, matching the pre-UC-15 contract.
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "boom"));

        assertThat(new DockerEnumerationService(exec).enumerate()).isEmpty();
    }

    @Test
    void normalises_idle_titles() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-7\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-9\"}]", ""));
        // ps returns empty → no container id.
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
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
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-12\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-15\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
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
            when(exec.run(
                            argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                            any(),
                            any(),
                            any()))
                    .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-1\"}]", ""));
            when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-1\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
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

    // ──────────────────────────────────────────────────────────────────────
    // UC-15 — sticky-flag detection + docker-ps fallback path
    // ──────────────────────────────────────────────────────────────────────

    /**
     * UC-15 AC2 — on a runner whose docker-compose binary speaks the
     * modern argv, the supported path is taken: a single
     * {@code docker compose ls --all --format json} call returns the
     * project list and no fallback {@code docker ps} probe is issued.
     * The sticky flag is cached TRUE; subsequent calls keep the modern
     * argv. This test pins the argv shape of the single ls invocation.
     */
    @Test
    void all_flag_supported_uses_modern_argv() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);

        String arrayJson = """
                [{"Name":"ai-sandbox-2","Status":"running"}]
                """;
        // Modern argv (compose ls --all) on the 4-arg overload (LC_ALL=C).
        when(exec.run(
                        argThat(argv -> argv != null
                                && argv.size() >= 4
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("compose")
                                && argv.get(2).equals("ls")
                                && argv.contains("--all")
                                && argv.contains("--format")
                                && argv.contains("json")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, arrayJson, ""));
        // Modern containerId argv (compose -p X ps -q --all claude-sandbox).
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("compose") && argv.contains("ps")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|running|true", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        DockerEnumerationService svc = new DockerEnumerationService(exec);
        List<SessionRecord> got = svc.enumerate();

        assertThat(got).hasSize(1);
        assertThat(got.get(0).n()).isEqualTo(2);
        assertThat(got.get(0).state()).isEqualTo("running");

        // AC2 — modern argv is the only ls path; exactly one invocation
        // matching `docker compose ls --all --format json`.
        verify(exec, times(1))
                .run(
                        argThat(argv -> argv != null
                                && argv.size() >= 4
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("compose")
                                && argv.get(2).equals("ls")
                                && argv.contains("--all")),
                        any(),
                        any(),
                        any());
        // AC2 — no fallback `docker ps -a --filter label=…` invocation.
        verify(exec, never())
                .run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && argv.contains("--filter")
                                && argv.stream().anyMatch(s -> s.startsWith("label=com.docker.compose.project"))),
                        any(),
                        any(),
                        any());
    }

    /**
     * UC-15 AC2 / AC6 — empirically-anchored flag detection. When the
     * runner's docker-compose binary rejects {@code --all} with exit=125
     * + stderr containing {@code "unknown flag: --all"}, the service
     * MUST:
     *
     * <ol>
     *   <li>Latch the sticky flag to FALSE for this instance.</li>
     *   <li>Fall back to {@code docker ps -a --format json --filter
     *       label=com.docker.compose.project} ONCE on the SAME
     *       {@link DockerEnumerationService#enumerate()} call.</li>
     *   <li>Dedupe by project label and only surface entries matching
     *       {@code ^ai-sandbox-\d+$}.</li>
     *   <li>On a subsequent {@code enumerate()}, SKIP the probe and go
     *       straight to the fallback — verifying the sticky flag avoids
     *       re-probing every request.</li>
     * </ol>
     *
     * <p>Anchored against the empirical signature from the production
     * potato-server logs ({@code exit=125}, stderr literal
     * {@code "unknown flag: --all"}).
     */
    @Test
    void rejects_all_flag_falls_back_to_docker_ps() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);

        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenAnswer((InvocationOnMock inv) ->
                        new ProcessExecutor.Result(125, "", "unknown flag: --all\nSee 'docker compose ls --help'.\n"));

        // Docker-ps fallback NDJSON with two ai-sandbox projects + one
        // unrelated project (filtered out by PROJECT_RE) + a duplicate
        // row for project 4 (dedup-checks the putIfAbsent path).
        String fallbackNdjson =
                """
                {"Names":"ai-sandbox-4-claude-sandbox-1","Labels":{"com.docker.compose.project":"ai-sandbox-4","com.docker.compose.service":"claude-sandbox"}}
                {"Names":"unrelated-project_x_1","Labels":{"com.docker.compose.project":"unrelated-project"}}
                {"Names":"ai-sandbox-4-claude-sandbox-2","Labels":{"com.docker.compose.project":"ai-sandbox-4","com.docker.compose.service":"claude-sandbox"}}
                {"Names":"ai-sandbox-7-claude-sandbox-1","Labels":{"com.docker.compose.project":"ai-sandbox-7","com.docker.compose.service":"claude-sandbox"}}
                """;
        when(exec.run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && argv.contains("--filter")
                                && argv.stream().anyMatch(s -> s.startsWith("label=com.docker.compose.project"))
                                // The "list all containers" call has no -q; the
                                // containerId fallback adds -q. Distinguish here.
                                && !argv.contains("-q")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, fallbackNdjson, ""));

        // containerId fallback argv (docker ps -a -q --filter label=…).
        when(exec.run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && argv.contains("-q")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));

        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "the-label|running|true", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        DockerEnumerationService svc = new DockerEnumerationService(exec);

        List<SessionRecord> first = svc.enumerate();

        // Dedup: project 4 has two ps rows but appears once. Both 4 and 7
        // surface. Unrelated project filtered out by PROJECT_RE.
        assertThat(first).extracting(SessionRecord::n).containsExactly(4, 7);

        // Exactly one ls call + one fallback ps enumeration call on first enumerate().
        verify(exec, times(1))
                .run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any());
        verify(exec, times(1))
                .run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && !argv.contains("-q")),
                        any(),
                        any(),
                        any());

        // ── Second enumerate(): sticky flag is FALSE, so ls is NOT probed
        // again; only the docker-ps fallback fires for the enumeration.
        List<SessionRecord> second = svc.enumerate();
        assertThat(second).extracting(SessionRecord::n).containsExactly(4, 7);

        // Still only ONE ls call across both enumerate() invocations.
        verify(exec, times(1))
                .run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any());
        // Two fallback ps enumeration calls (one per enumerate()).
        verify(exec, times(2))
                .run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && !argv.contains("-q")),
                        any(),
                        any(),
                        any());
    }

    /**
     * UC-15 AC2 — once the sticky flag has flipped to FALSE,
     * {@code containerId(...)} (invoked indirectly by
     * {@link DockerEnumerationService#enumerate()}) MUST switch from
     * the modern {@code docker compose -p <project> ps -q --all
     * claude-sandbox} argv to the legacy {@code docker ps -a -q
     * --filter label=com.docker.compose.project=<project> --filter
     * label=com.docker.compose.service=claude-sandbox} argv.
     *
     * <p>Strategy: drive a single {@code enumerate()} call through the
     * fallback path (latching the sticky flag) and pin the argv shape
     * of the {@code containerId} call via {@code verify(...)} on the
     * docker-ps argv shape.
     */
    @Test
    void container_id_uses_docker_ps_when_compose_ps_lacks_all() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);

        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(125, "", "unknown flag: --all\n"));

        String fallbackNdjson =
                """
                {"Names":"ai-sandbox-7-claude-sandbox-1","Labels":{"com.docker.compose.project":"ai-sandbox-7","com.docker.compose.service":"claude-sandbox"}}
                """;
        when(exec.run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && !argv.contains("-q")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, fallbackNdjson, ""));
        when(exec.run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && argv.contains("-q")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|running|true", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        DockerEnumerationService svc = new DockerEnumerationService(exec);

        List<SessionRecord> got = svc.enumerate();
        assertThat(got).extracting(SessionRecord::n).containsExactly(7);

        // AC2 — containerId MUST use the legacy docker-ps argv (NOT the
        // modern `docker compose -p … ps -q --all claude-sandbox`).
        verify(exec, times(1))
                .run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && argv.get(0).equals("docker")
                                && argv.get(1).equals("ps")
                                && argv.contains("-a")
                                && argv.contains("-q")
                                && argv.contains("label=com.docker.compose.project=ai-sandbox-7")
                                && argv.contains("label=com.docker.compose.service=claude-sandbox")
                                // Must NOT be the modern `docker compose -p … ps -q --all`
                                // argv (which would have "compose" + "-p" + "--all").
                                && !argv.contains("compose")
                                && !argv.contains("--all")),
                        any(),
                        any(),
                        any());
        // And the modern containerId argv MUST NOT be invoked at all
        // once the sticky flag is FALSE.
        verify(exec, never())
                .run(
                        argThat(argv -> argv != null
                                && argv.contains("compose")
                                && argv.contains("-p")
                                && argv.contains("ps")
                                && argv.contains("-q")
                                && argv.contains("--all")),
                        any(),
                        any(),
                        any());
    }
}
