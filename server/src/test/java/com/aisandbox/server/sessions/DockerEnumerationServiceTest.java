package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.service.ConversationNameService;
import com.aisandbox.server.sessions.service.DockerEnumerationService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.TerminatingSessions;
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
        // UC-27 — enumerate() now probes the readiness marker for running
        // sessions; stub it present (exit 0) so they stay `running`. 3-arg
        // overload (no env override), mirroring readyMarkerPresent(...).
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
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

    /**
     * UC-46 AC5 — a Docker-{@code paused} container surfaces with the new
     * first-class {@code paused} wire state (NOT {@code stopped}), so {@code
     * GET /v1/sessions} reports it distinctly and the {@code StatusPill}
     * renders the paused treatment. Like other non-{@code running} states the
     * readiness + tmux-title probes are skipped (a frozen container can't
     * answer an {@code exec}).
     */
    @Test
    void paused_containers_surface_with_paused_state_and_no_tmux_call() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv ->
                                argv != null && argv.size() >= 3 && "ls".equals(argv.get(2)) && argv.contains("--all")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-13\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "paused-cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "my-paused-label|paused|false", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.n()).isEqualTo(13);
        assertThat(r.label()).isEqualTo("my-paused-label");
        assertThat(r.state())
                .as("UC-46 — docker `paused` maps to the first-class `paused` wire state, not `stopped`")
                .isEqualTo("paused");
        // Readiness/title probes are run only for `running`; never for paused.
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

    // ── UC-27 — provisioning state (Docker-running + ready-marker probe) ─────

    /**
     * UC-27 — a Docker-{@code running} session whose {@code /tmp/aisandbox-ready}
     * marker is present is a fully-up session: the state stays {@code running}
     * and the tmux window title is fetched. Also pins that enumerate() actually
     * issues the marker probe (the {@code test -f /tmp/aisandbox-ready} exec).
     */
    @Test
    void running_with_ready_marker_present_keeps_running_and_fetches_title() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-3\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|running|true", ""));
        // Ready marker PRESENT — `test -f` exits 0.
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.state()).isEqualTo("running");
        assertThat(r.tmuxTitle()).isEqualTo("doing-thing");
        // The readiness probe was actually issued for the running session.
        verify(exec, times(1))
                .run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any());
        // And the argv mirrors spawn.sh:273 — compose -p <project> exec -T test -f.
        verify(exec)
                .run(
                        argThat(argv -> argv != null
                                && argv.contains("compose")
                                && argv.contains("-p")
                                && argv.contains("exec")
                                && argv.contains("-T")
                                && argv.contains("claude-sandbox")
                                && argv.contains("test")
                                && argv.contains("-f")
                                && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any());
    }

    /**
     * UC-27 — a Docker-{@code running} session whose ready marker is ABSENT
     * (the {@code test -f} probe exits non-zero) is still installing its
     * spawn-time toolchains: the state is downgraded to {@code provisioning},
     * the tmux title is NOT fetched, and the title is {@code (unavailable)}.
     */
    @Test
    void running_with_ready_marker_absent_downgrades_to_provisioning_and_skips_title() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-8\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "amber-label|running|true", ""));
        // Ready marker ABSENT — `test -f` exits 1.
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(1, "", ""));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.n()).isEqualTo(8);
        assertThat(r.label()).isEqualTo("amber-label");
        assertThat(r.state()).isEqualTo("provisioning");
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
        // tmux title probe MUST be skipped for a provisioning session.
        verify(exec, never()).run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any());
    }

    /**
     * UC-27 — a transient failure of the readiness probe itself (the exec
     * throws {@link IOException}: daemon hiccup, container not yet exec-able)
     * is treated conservatively as "not ready": the session is reported
     * {@code provisioning}, never optimistically {@code running}, and the
     * title probe is skipped.
     */
    @Test
    void ready_marker_probe_ioexception_downgrades_to_provisioning() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-11\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|running|true", ""));
        // Ready-marker probe blows up transiently.
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenThrow(new java.io.IOException("docker daemon busy"));

        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.state()).isEqualTo("provisioning");
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
        // UC-28 — the transient Docker `removing` state (reported while
        // `docker compose down` tears the container down) maps to terminating.
        table.put("removing", "terminating");
        table.put("exited", "stopped");
        table.put("dead", "stopped");
        // UC-46 — `paused` is now its OWN first-class wire state (was bucketed
        // into `stopped` pre-UC-46) so the Android UI can offer Unpause/Stop
        // and render the distinct paused pill. The readiness/title probes are
        // never run for a non-`running` docker status, so it stays `paused`.
        table.put("paused", "paused");
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
            // Ready marker present so the `running` row stays `running`
            // (harmless for the non-running statuses — never probed).
            when(exec.run(
                            argThat(argv ->
                                    argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                            any(),
                            any()))
                    .thenReturn(new ProcessExecutor.Result(0, "", ""));
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
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
    // UC-28 — in-flight-delete registry override
    // ──────────────────────────────────────────────────────────────────────

    /**
     * UC-28 AC1/AC3/AC9 — a session flagged in the {@link TerminatingSessions}
     * registry is reported {@code terminating} regardless of its Docker
     * {@code .State.Status}. Here the container still inspects as
     * {@code running} with its ready-marker present (so without the override it
     * would be {@code running}), yet the registry flag wins: the state is
     * {@code terminating}, the title is {@code (unavailable)}, and the tmux
     * title probe is SKIPPED (the session is being torn down — no point asking
     * tmux). This is the deterministic signal the brief preferred over the brief
     * /racy raw {@code removing} window.
     */
    @Test
    void registry_flagged_session_reports_terminating_over_running() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-5\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "live-cid\n", ""));
        // Docker says the container is fully running …
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "my-label|running|true", ""));
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        // … but a delete is in flight for N=5.
        TerminatingSessions terminating = new TerminatingSessions();
        terminating.markTerminating(5);

        DockerEnumerationService svc = new DockerEnumerationService(exec, terminating);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.n()).isEqualTo(5);
        assertThat(r.state())
                .as("the in-flight-delete registry flag wins over the Docker `running` status (AC1/AC3/AC9)")
                .isEqualTo("terminating");
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
        // The tmux title probe MUST be skipped for a terminating session.
        verify(exec, never()).run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any());
    }

    /**
     * UC-28 — an in-flight delete still wins even when the container has
     * ALREADY vanished mid-{@code down} (no container id). A flagged N with no
     * cid reads {@code terminating}, not {@code stopped}, for the duration of
     * the teardown window — so the optimistic pill never flips to gray before
     * the row disappears.
     */
    @Test
    void registry_flagged_session_with_no_container_reports_terminating_not_stopped() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-9\"}]", ""));
        // ps returns empty → no container id (container already gone mid-down).
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "\n", ""));

        TerminatingSessions terminating = new TerminatingSessions();
        terminating.markTerminating(9);

        DockerEnumerationService svc = new DockerEnumerationService(exec, terminating);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.n()).isEqualTo(9);
        assertThat(r.state())
                .as("a flagged session whose container is already gone reads terminating, not stopped (AC3)")
                .isEqualTo("terminating");
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
    }

    /**
     * UC-28 — an UNFLAGGED session whose container inspects as {@code removing}
     * (the transient Docker teardown state) still surfaces as {@code terminating}
     * via {@link DockerEnumerationService#mapState(String)} alone — covering the
     * mapping path independent of the registry override (e.g. a teardown started
     * by another client / out-of-band).
     */
    @Test
    void unflagged_removing_container_maps_to_terminating() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-2\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "rm-cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|removing|false", ""));

        // 1-arg ctor → empty registry; the `removing` mapping is the only signal.
        SessionRecord r = new DockerEnumerationService(exec).enumerate().get(0);
        assertThat(r.state()).isEqualTo("terminating");
        assertThat(r.tmuxTitle()).isEqualTo("(unavailable)");
        // tmux title probe skipped for the non-running mapped state.
        verify(exec, never()).run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any());
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
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
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
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

    // ──────────────────────────────────────────────────────────────────────
    // UC-47 — conversation name on the enumeration hot path
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Stub the four docker calls for a single marker-confirmed RUNNING session
     * {@code ai-sandbox-<n>} so the conversation-name interaction is the only
     * variable under test.
     */
    private static ProcessExecutor runningSession(int n) throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-" + n + "\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|running|true", ""));
        when(exec.run(
                        argThat(argv -> argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready")),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "bash", "")); // → tmuxTitle (idle)
        return exec;
    }

    /**
     * UC-47 AC1 — a running session whose cached conversation name is warm
     * carries that name on its {@link SessionRecord}, and enumeration fires a
     * fire-and-forget refresh for the NEXT tick (the cache read itself is
     * non-blocking — AC6). The tmux title is still computed for fallback.
     */
    @Test
    void running_session_carries_the_cached_conversation_name_and_refreshes() throws Exception {
        ProcessExecutor exec = runningSession(3);
        ConversationNameService names = mock(ConversationNameService.class);
        when(names.cachedName(3)).thenReturn("Refactor the SessionRow");

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.n()).isEqualTo(3);
        assertThat(r.state()).isEqualTo("running");
        assertThat(r.conversationName()).isEqualTo("Refactor the SessionRow");
        // tmux title still computed (the client's fallback when no name).
        assertThat(r.tmuxTitle()).isEqualTo("(idle)");
        // AC6 — a fire-and-forget refresh was scheduled for this running session.
        verify(names).refreshAsync(eq(3), eq("ai-sandbox-3"));
        // prune was invoked with the enumerated set so vanished names are dropped.
        verify(names).prune(argThat(set -> set != null && set.contains(3)));
    }

    /**
     * UC-47 AC3 — a running session with NO cached name yet (cold cache / first
     * tick / lookup failure) carries a {@code null} conversationName, so the
     * Android row falls back to the tmux title without an empty/broken label. A
     * refresh is still scheduled so the NEXT tick warms.
     */
    @Test
    void running_session_with_cold_cache_carries_null_name_and_falls_back() throws Exception {
        ProcessExecutor exec = runningSession(4);
        ConversationNameService names = mock(ConversationNameService.class);
        when(names.cachedName(4)).thenReturn(null);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.conversationName()).isNull();
        assertThat(r.tmuxTitle()).isEqualTo("(idle)");
        verify(names).refreshAsync(eq(4), eq("ai-sandbox-4"));
    }

    /**
     * UC-47 AC3 / AC6 — a non-running (stopped) session never carries a
     * conversation name and never triggers a derive: only a marker-confirmed
     * running session can have an active conversation, and a derive into a
     * stopped container would error and waste enumeration latency.
     */
    @Test
    void stopped_session_never_reads_or_refreshes_a_conversation_name() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-12\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "stopped-cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|exited|false", ""));
        ConversationNameService names = mock(ConversationNameService.class);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.state()).isEqualTo("stopped");
        assertThat(r.conversationName()).isNull();
        verify(names, never()).refreshAsync(eq(12), any());
        verify(names, never()).cachedName(12);
        // prune still runs each enumeration so a vanished session's name is dropped.
        verify(names).prune(any());
    }

    /**
     * UC-47 back-compat — the 2-arg ctor substitutes a {@code null} name service
     * (pre-UC-47 fixtures). Enumeration must not NPE; every row simply carries a
     * {@code null} conversationName and falls back to the tmux title.
     */
    @Test
    void null_name_service_back_compat_ctor_yields_null_names_without_npe() throws Exception {
        ProcessExecutor exec = runningSession(5);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions());
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.n()).isEqualTo(5);
        assertThat(r.conversationName()).isNull();
        assertThat(r.tmuxTitle()).isEqualTo("(idle)");
        // UC-48 — the pre-UC-48 ctor leaves every row not-working (no spinner).
        assertThat(r.working()).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────
    // UC-48 — per-session working flag on the enumeration hot path
    // ──────────────────────────────────────────────────────────────────────

    /**
     * UC-48 AC1 — a RUNNING session whose cached (hysteresis-debounced) working
     * flag is true carries {@code working=true} on its {@link SessionRecord}, so
     * the Android row animates the spinner. The cached read is non-blocking (the
     * same warming refresh that fetches the name fetches the working flag).
     */
    @Test
    void running_session_carries_the_cached_working_flag() throws Exception {
        ProcessExecutor exec = runningSession(3);
        ConversationNameService names = mock(ConversationNameService.class);
        when(names.working(3)).thenReturn(true);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.state()).isEqualTo("running");
        assertThat(r.working())
                .as("AC1 — a running, working session reports working=true")
                .isTrue();
        verify(names).working(3);
        verify(names).refreshAsync(eq(3), eq("ai-sandbox-3"));
    }

    /**
     * UC-48 AC2 — a RUNNING session that is idle (cached working flag false)
     * carries {@code working=false}, so the row shows no spinner and a genuinely
     * idle session is visually distinct.
     */
    @Test
    void running_but_idle_session_carries_working_false() throws Exception {
        ProcessExecutor exec = runningSession(6);
        ConversationNameService names = mock(ConversationNameService.class);
        when(names.working(6)).thenReturn(false);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.state()).isEqualTo("running");
        assertThat(r.working())
                .as("AC2 — a running but idle session reports working=false")
                .isFalse();
    }

    /**
     * UC-48 AC7 — a NON-running (paused) session never reads the working flag and
     * always reports {@code working=false}, regardless of any stale cached
     * working state. The running-gate makes a stale {@code working=true} unable
     * to race a paused/terminating/stopped override into a spinning row.
     */
    @Test
    void paused_session_never_reads_working_and_reports_false() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-13\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "paused-cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|paused|false", ""));
        ConversationNameService names = mock(ConversationNameService.class);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.state()).isEqualTo("paused");
        assertThat(r.working())
                .as("AC7 — a non-running session never reports working=true")
                .isFalse();
        // The running-gate skips the working read entirely for a non-running row.
        verify(names, never()).working(13);
    }

    // ──────────────────────────────────────────────────────────────────────
    // UC-49 — per-session pending-question flag on the enumeration hot path
    // ──────────────────────────────────────────────────────────────────────

    /**
     * UC-49 AC1 — a RUNNING session whose cached pending-question flag is true
     * carries {@code pendingQuestion=true} on its {@link SessionRecord}, so the
     * Android row shows the "?" badge. The cached read is non-blocking (the same
     * warming refresh that fetches the name fetches the pending flag).
     */
    @Test
    void running_session_carries_the_cached_pending_question_flag() throws Exception {
        ProcessExecutor exec = runningSession(3);
        ConversationNameService names = mock(ConversationNameService.class);
        when(names.pendingQuestion(3)).thenReturn(true);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.state()).isEqualTo("running");
        assertThat(r.pendingQuestion())
                .as("AC1 — a running session with a pending question reports pendingQuestion=true")
                .isTrue();
        verify(names).pendingQuestion(3);
        verify(names).refreshAsync(eq(3), eq("ai-sandbox-3"));
    }

    /**
     * UC-49 — a RUNNING session with no pending question carries
     * {@code pendingQuestion=false} (no badge).
     */
    @Test
    void running_session_without_a_pending_question_carries_false() throws Exception {
        ProcessExecutor exec = runningSession(6);
        ConversationNameService names = mock(ConversationNameService.class);
        when(names.pendingQuestion(6)).thenReturn(false);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.pendingQuestion())
                .as("a running session with no pending question reports false")
                .isFalse();
    }

    /**
     * UC-49 AC8 — a NON-running (paused) session never reads the pending flag and
     * always reports {@code pendingQuestion=false}: the badge is never shown for a
     * paused / terminating / stopped row, and a stale cached pending can never race
     * a non-running override into a "?" badge.
     */
    @Test
    void paused_session_never_reads_pending_and_reports_false() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[{\"Name\":\"ai-sandbox-13\"}]", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("ps")), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "paused-cid\n", ""));
        when(exec.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "lbl|paused|false", ""));
        ConversationNameService names = mock(ConversationNameService.class);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions(), names);
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.state()).isEqualTo("paused");
        assertThat(r.pendingQuestion())
                .as("AC8 — a non-running session never reports pendingQuestion=true")
                .isFalse();
        // The running-gate skips the pending read entirely for a non-running row.
        verify(names, never()).pendingQuestion(13);
    }

    /**
     * UC-49 back-compat — the {@link TerminatingSessions}-only ctor (no name
     * service) leaves every row {@code pendingQuestion=false} without an NPE.
     */
    @Test
    void null_name_service_back_compat_ctor_yields_no_pending_without_npe() throws Exception {
        ProcessExecutor exec = runningSession(5);

        DockerEnumerationService svc = new DockerEnumerationService(exec, new TerminatingSessions());
        SessionRecord r = svc.enumerate().get(0);

        assertThat(r.pendingQuestion())
                .as("the pre-UC-49 ctor leaves every row not-pending (no badge)")
                .isFalse();
    }
}
