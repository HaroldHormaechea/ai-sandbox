package com.aisandbox.server.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.dto.SessionRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC-62 — argv / env / lifecycle contract of {@link HostShellSessionService},
 * the in-process manager of the single always-on server host-shell ("SERVER SSH
 * SESSION"). Pure-Java, argv-only: {@link ProcessExecutor} is mocked (mirrors
 * {@link TmuxBridgeSessionSetupTest}), so no real tmux is required. The live
 * tmux SEMANTICS this relies on are proven separately by
 * {@code HostShellSessionLiveTmuxTest}.
 *
 * <p>AC mapping:
 *
 * <ul>
 *   <li><b>AC2 / AC13</b> — {@link #ensureCreated_idempotent_does_not_create_a_second_when_present()}
 *       (the exists-gate inside the lock) and
 *       {@link #ensureCreated_under_concurrency_creates_exactly_one_tmux()} (the
 *       {@code ReentrantLock} + exists-gate yields ONE {@code new-session}
 *       under N concurrent callers — the server-side singleton).</li>
 *   <li><b>AC5 / restricted-PATH gotcha</b> —
 *       {@link #ensureCreated_runs_bare_host_tmux_new_session_with_explicit_path_and_term()}
 *       pins the bare {@code tmux -S <socket> new-session …} argv (NO docker
 *       prefix) AND asserts the env overlay carries an explicit non-blank
 *       {@code PATH} + {@code TERM=xterm-256color} (the systemd minimal-PATH
 *       protection — the service never relies on an inherited PATH).</li>
 *   <li><b>AC11</b> — {@link #kill_runs_kill_session()} destroys the host tmux;
 *       best-effort on a non-zero exit / {@link IOException}.</li>
 *   <li><b>AC6</b> — {@link #row_carries_reserved_id_and_server_ssh_type()}
 *       pins the row's reserved id + {@code server-ssh} type.</li>
 *   <li><b>master switch</b> — disabled ⇒ {@code exists()} false with NO
 *       subprocess, {@code ensureCreated()} / {@code kill()} no-ops.</li>
 * </ul>
 */
class HostShellSessionServiceTest {

    private static final Path SOCK = Path.of("/tmp/uc62-test/server-ssh.sock");
    private static final String NAME = "ai-sandbox-server-ssh";

    /** ServerProperties carrying ONLY a {@link ServerProperties.ServerSsh}; the rest is unused by the service. */
    private static ServerProperties props(boolean enabled, Path socket, String name, String shell, Path workdir) {
        return new ServerProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ServerProperties.ServerSsh(enabled, socket, name, shell, workdir));
    }

    private static ServerProperties enabledProps() {
        return props(true, SOCK, NAME, "/bin/bash", Path.of("/srv/work"));
    }

    @Test
    void enabled_reflects_config() {
        assertThat(new HostShellSessionService(mock(ProcessExecutor.class), enabledProps()).enabled())
                .isTrue();
        assertThat(new HostShellSessionService(mock(ProcessExecutor.class), props(false, SOCK, NAME, "/bin/bash", null))
                        .enabled())
                .isFalse();
    }

    @Test
    void exists_true_on_exit0_and_keys_on_socket_and_name() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));

        HostShellSessionService svc = new HostShellSessionService(exec, enabledProps());
        assertThat(svc.exists()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(exec).run(argv.capture(), any(), env.capture(), any());

        assertThat(argv.getValue())
                .as("bare host tmux has-session, socket-scoped to the configured socket")
                .containsExactly("tmux", "-S", SOCK.toString(), "has-session", "-t", NAME);
        // The host tmux is NOT a container — never a docker/compose exec prefix.
        assertThat(argv.getValue()).doesNotContain("docker", "compose", "exec");
    }

    @Test
    void exists_false_on_nonzero() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "no server running"));
        assertThat(new HostShellSessionService(exec, enabledProps()).exists()).isFalse();
    }

    @Test
    void exists_false_on_ioexception_and_never_breaks_enumeration() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenThrow(new IOException("tmux unreachable"));
        // An IOException is swallowed → "absent", so SessionRegistryService enumeration is never broken.
        assertThat(new HostShellSessionService(exec, enabledProps()).exists()).isFalse();
    }

    @Test
    void exists_false_when_disabled_without_touching_the_executor() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        HostShellSessionService svc = new HostShellSessionService(exec, props(false, SOCK, NAME, "/bin/bash", null));
        assertThat(svc.exists()).isFalse();
        verify(exec, never()).run(any(), any(), any(), any());
    }

    @Test
    void ensureCreated_runs_bare_host_tmux_new_session_with_explicit_path_and_term() throws Exception {
        // has-session is STATEFUL (UC-63): absent (exit 1) for the pre-create
        // exists-gate, present (exit 0) once new-session has run. An honest tmux
        // models this — and the new UC-63 post-create has-session probe then
        // confirms presence, so the happy path no longer throws.
        AtomicBoolean present = new AtomicBoolean(false);
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (argv.contains("has-session")) {
                return new ProcessExecutor.Result(present.get() ? 0 : 1, "", "");
            }
            // new-session succeeds AND really brings the session up (honest tmux).
            present.set(true);
            return new ProcessExecutor.Result(0, "", "");
        });

        new HostShellSessionService(exec, enabledProps()).ensureCreated();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Path> wd = ArgumentCaptor.forClass(Path.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        // 3 calls (UC-63): has-session (exists gate) → new-session → has-session
        // (post-create presence probe — the exit-code-lie guard).
        verify(exec, org.mockito.Mockito.times(3)).run(argv.capture(), wd.capture(), env.capture(), any());

        List<String> newSession = argv.getAllValues().stream()
                .filter(a -> a.contains("new-session"))
                .findFirst()
                .orElseThrow();
        assertThat(newSession)
                .as("bare host tmux new-session running a detached login shell — no docker/compose prefix")
                .containsExactly("tmux", "-S", SOCK.toString(), "new-session", "-d", "-s", NAME, "/bin/bash", "-l");
        assertThat(newSession).doesNotContain("docker", "compose", "exec");

        // restricted-PATH gotcha (challenger #2 + memory): the service ALWAYS sets
        // an explicit, non-blank PATH and TERM rather than relying on the (possibly
        // minimal, systemd) inherited environment.
        int idx = argv.getAllValues().indexOf(newSession);
        Map<String, String> newSessionEnv = env.getAllValues().get(idx);
        assertThat(newSessionEnv).containsKey("PATH");
        assertThat(newSessionEnv.get("PATH")).isNotBlank();
        assertThat(newSessionEnv).containsEntry("TERM", "xterm-256color");

        // The shell launches in the configured working dir (the server's own cwd).
        assertThat(wd.getAllValues().get(idx)).isEqualTo(Path.of("/srv/work"));
    }

    @Test
    void ensureCreated_idempotent_does_not_create_a_second_when_present() throws Exception {
        // has-session → present (exit 0): a second tap focuses the existing row, never creates a second (AC2).
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            return new ProcessExecutor.Result(argv.contains("has-session") ? 0 : 0, "", "");
        });

        new HostShellSessionService(exec, enabledProps()).ensureCreated();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec, org.mockito.Mockito.atLeastOnce()).run(argv.capture(), any(), any(), any());
        assertThat(argv.getAllValues().stream().anyMatch(a -> a.contains("new-session")))
                .as("no new-session is emitted when the host tmux already exists (singleton focus)")
                .isFalse();
    }

    @Test
    void ensureCreated_noop_when_disabled() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        new HostShellSessionService(exec, props(false, SOCK, NAME, "/bin/bash", null)).ensureCreated();
        verify(exec, never()).run(any(), any(), any(), any());
    }

    @Test
    void ensureCreated_throws_ioexception_when_new_session_fails() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (argv.contains("has-session")) {
                return new ProcessExecutor.Result(1, "", "");
            }
            return new ProcessExecutor.Result(2, "", "tmux: cannot create socket");
        });

        assertThatThrownBy(() -> new HostShellSessionService(exec, enabledProps()).ensureCreated())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("tmux new-session for server-ssh failed");
    }

    @Test
    void ensureCreated_throws_when_new_session_exits_zero_but_session_absent() throws Exception {
        // UC-63 AC8b/AC3 — the tmux exit-code LIE. `tmux new-session` against a
        // missing socket-parent dir writes "error creating <sock> (No such file
        // or directory)" to stderr yet exits 0. ensureCreated() MUST NOT trust
        // that 0: its post-create has-session probe stays absent (exit 1), so it
        // throws IOException and exists() stays false — POST /v1/sessions/server-ssh
        // then surfaces a 5xx rather than a false 200.
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (argv.contains("has-session")) {
                // The session NEVER actually comes up — both the pre-create gate
                // and the post-create probe report absent.
                return new ProcessExecutor.Result(1, "", "");
            }
            // new-session LIES: exit 0 with the tmux "error creating" stderr.
            return new ProcessExecutor.Result(0, "", "error creating " + SOCK + " (No such file or directory)");
        });

        HostShellSessionService svc = new HostShellSessionService(exec, enabledProps());

        assertThatThrownBy(svc::ensureCreated)
                .as("AC3 — a 0-exit new-session that did not actually create the session must still throw")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("reported success but the session is not present");

        // AC8b — no false-positive: exists() stays false after the lie.
        assertThat(svc.exists())
                .as("AC8b — exists() stays false when new-session lied (no false 200)")
                .isFalse();
    }

    @Test
    void ensureCreated_under_concurrency_creates_exactly_one_tmux() throws Exception {
        // AC13 — the server-side singleton under concurrency: the ReentrantLock +
        // exists-gate must collapse N near-simultaneous creates into ONE
        // new-session. A stateful fake models real tmux: has-session reflects
        // whether a new-session has already run.
        AtomicBoolean present = new AtomicBoolean(false);
        AtomicInteger newSessions = new AtomicInteger(0);
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (argv.contains("has-session")) {
                return new ProcessExecutor.Result(present.get() ? 0 : 1, "", "");
            }
            if (argv.contains("new-session")) {
                newSessions.incrementAndGet();
                present.set(true);
                return new ProcessExecutor.Result(0, "", "");
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        HostShellSessionService svc = new HostShellSessionService(exec, enabledProps());

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    svc.ensureCreated();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(failures.get()).as("no concurrent ensureCreated threw").isZero();
        assertThat(newSessions.get())
                .as("AC13 — exactly one host tmux created despite %d concurrent creates", threads)
                .isEqualTo(1);
    }

    @Test
    void kill_runs_kill_session() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));

        new HostShellSessionService(exec, enabledProps()).kill();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), any(), any(), any());
        assertThat(argv.getValue())
                .as("AC11 — Remove destroys the host tmux via bare tmux kill-session")
                .containsExactly("tmux", "-S", SOCK.toString(), "kill-session", "-t", NAME);
    }

    @Test
    void kill_is_best_effort_on_nonzero_exit() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(1, "", "session not found: already gone"));
        // An already-gone session satisfies the post-condition — must not throw.
        new HostShellSessionService(exec, enabledProps()).kill();
    }

    @Test
    void kill_is_best_effort_on_ioexception() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenThrow(new IOException("tmux unreachable"));
        new HostShellSessionService(exec, enabledProps()).kill();
    }

    @Test
    void kill_noop_when_disabled() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        new HostShellSessionService(exec, props(false, SOCK, NAME, "/bin/bash", null)).kill();
        verify(exec, never()).run(any(), any(), any(), any());
    }

    @Test
    void row_carries_reserved_id_and_server_ssh_type() {
        SessionRecord row = new HostShellSessionService(mock(ProcessExecutor.class), enabledProps()).row();
        assertThat(row.n()).as("AC6 — reserved id 0").isEqualTo(SpecialSessions.SERVER_SSH_N);
        assertThat(row.type()).as("AC6 — server-ssh discriminator").isEqualTo(SpecialSessions.TYPE_SERVER_SSH);
        assertThat(row.state()).isEqualTo("running");
        assertThat(row.working()).isFalse();
        assertThat(row.pendingQuestion()).isFalse();
    }

    @Test
    void socket_and_session_name_accessors_reflect_config() {
        HostShellSessionService svc = new HostShellSessionService(mock(ProcessExecutor.class), enabledProps());
        assertThat(svc.socketPathString()).isEqualTo(SOCK.toString());
        assertThat(svc.sessionName()).isEqualTo(NAME);
    }

    @Test
    void socket_defaults_under_host_state_root_when_unset() throws Exception {
        // socketPath null → derived as <sessions.hostStateRoot>/server-ssh.sock.
        ServerProperties.Sessions sessions = new ServerProperties.Sessions(Path.of("/var/lib/ai-sandbox"));
        ServerProperties p = new ServerProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sessions,
                null,
                new ServerProperties.ServerSsh(true, null, null, null, null));
        HostShellSessionService svc = new HostShellSessionService(mock(ProcessExecutor.class), p);
        assertThat(svc.socketPathString()).isEqualTo("/var/lib/ai-sandbox/server-ssh.sock");
        assertThat(svc.sessionName()).isEqualTo("ai-sandbox-server-ssh");
    }
}
