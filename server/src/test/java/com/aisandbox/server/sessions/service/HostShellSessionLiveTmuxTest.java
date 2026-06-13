package com.aisandbox.server.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aisandbox.server.config.ServerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-62 — LIVE host-tmux check for {@link HostShellSessionService} against the
 * host's real {@code tmux} (no Docker, no pty4j), mirroring the harness style of
 * {@code TmuxZoomHostTmuxTest}. The mocked-argv test
 * ({@code HostShellSessionServiceTest}) pins WHICH commands / env the service
 * emits; this proves the tmux SEMANTICS those commands rely on actually hold —
 * something a mocked {@link ProcessExecutor} can never demonstrate.
 *
 * <p>Invariants asserted:
 *
 * <ul>
 *   <li><b>AC5</b> — the real {@code HostShellSessionService.ensureCreated()}
 *       brings up a reachable bare-tmux login shell on its configured socket
 *       (the host, not a container).</li>
 *   <li><b>AC2 / AC13</b> — a second {@code ensureCreated()} is idempotent:
 *       exactly ONE session exists on the socket.</li>
 *   <li><b>AC11</b> — {@code kill()} destroys the host tmux; afterwards
 *       {@code exists()} is false and {@code has-session} fails.</li>
 *   <li><b>restricted-PATH gotcha</b> (challenger #2 + the ai-sandbox PTY env
 *       memory) — a tmux login shell launched under a DELIBERATELY MINIMAL env
 *       ({@code PATH=/usr/bin:/bin}, {@code TERM=xterm-256color}, exactly the
 *       shape {@link HostShellSessionService}'s {@code baseEnv()} overlays)
 *       still comes up AND the shell inside it observes that explicit PATH —
 *       proving the env overlay reaches the login shell rather than leaking the
 *       JVM's inherited (full) PATH. If {@link ProcessExecutor} failed to apply
 *       the overlay, the captured PATH would be the inherited one and the
 *       assertion would fail.</li>
 * </ul>
 *
 * <p>Guarded by a JUnit assumption: where {@code tmux} is unavailable the test
 * skips; where present (this ai-sandbox session, Debian bookworm CI tmux 3.3a)
 * it runs with real assertions as part of {@code :server:test}.
 */
@DisplayName("UC-62 live host-tmux (HostShellSessionService) invariants")
class HostShellSessionLiveTmuxTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final ProcessExecutor exec = new ProcessExecutor();

    @TempDir
    Path tmp;

    private Path socket;
    private String sessionName;

    @AfterEach
    void tearDown() {
        if (socket != null) {
            // Best-effort: kill the whole tmux server on this private socket.
            try {
                exec.run(List.of("tmux", "-S", socket.toString(), "kill-server"), null, TIMEOUT);
            } catch (IOException ignored) {
                // best-effort teardown
            }
        }
    }

    private HostShellSessionService service() {
        // Unique socket + name per JVM so parallel/forked runs never collide.
        sessionName = "uc62-live-" + ProcessHandle.current().pid();
        socket = tmp.resolve("server-ssh.sock");
        // UC-64 — a non-null accessible home so ensureCreated() provisions it
        // 0700 and baseEnv() redirects HOME there (the existing live invariants
        // are unaffected by the value of HOME, only by it being accessible).
        ServerProperties props = new ServerProperties(
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
                new ServerProperties.ServerSsh(true, socket, sessionName, "/bin/bash", tmp, tmp.resolve("ssh-home")));
        return new HostShellSessionService(exec, props);
    }

    @Test
    @DisplayName("ensureCreated brings up a reachable host tmux; kill destroys it (AC5/AC11)")
    void ensureCreated_thenKill_roundTrips() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping live host-tmux check");
        HostShellSessionService svc = service();

        svc.ensureCreated();
        assertThat(svc.exists())
                .as("service reports the host tmux present after create")
                .isTrue();
        assertThat(hasSession())
                .as("raw tmux has-session confirms the live session on the socket")
                .isTrue();

        svc.kill();
        assertThat(svc.exists()).as("service reports absent after kill (AC11)").isFalse();
        assertThat(hasSession())
                .as("raw tmux has-session confirms destruction (AC11)")
                .isFalse();
    }

    @Test
    @DisplayName("ensureCreated auto-creates a MISSING socket-parent dir and brings up a reachable tmux (UC-63 AC8a)")
    void ensureCreated_autoCreatesMissingSocketParentDir_freshInstall() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping live host-tmux check");
        // UC-63 AC8a — the exact fresh-install gap. The socket's parent dir does
        // NOT exist yet: the other live tests point at the @TempDir root (always
        // present), which is precisely why the shipped bug went unnoticed. Here we
        // point the socket at a NON-pre-created subdir and DELIBERATELY do not
        // create it — ensureCreated() must provision it itself (AC1) and still
        // bring up a reachable host tmux (AC8a / AC4).
        sessionName = "uc63-freshinstall-" + ProcessHandle.current().pid();
        Path sessionsDir = tmp.resolve("sessions"); // intentionally NOT pre-created
        socket = sessionsDir.resolve("server-ssh.sock"); // tracked for teardown
        assertThat(Files.exists(sessionsDir))
                .as("precondition — the socket-parent dir MUST be absent before ensureCreated (the fresh-install gap)")
                .isFalse();

        ServerProperties props = new ServerProperties(
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
                new ServerProperties.ServerSsh(true, socket, sessionName, "/bin/bash", tmp, tmp.resolve("ssh-home")));
        HostShellSessionService svc = new HostShellSessionService(exec, props);

        svc.ensureCreated();

        assertThat(Files.isDirectory(sessionsDir))
                .as("AC1 — ensureCreated auto-created the missing socket-parent dir")
                .isTrue();
        assertThat(svc.exists())
                .as("AC8a — the host tmux is reachable after the dir was auto-created (no exit-code lie)")
                .isTrue();
        assertThat(hasSession())
                .as("AC8a — raw tmux has-session confirms a live session on the auto-created socket dir")
                .isTrue();
    }

    @Test
    @DisplayName("ensureCreated is idempotent — exactly one session on the socket (AC2/AC13)")
    void ensureCreated_idempotent_singleSession() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping live host-tmux check");
        HostShellSessionService svc = service();

        svc.ensureCreated();
        svc.ensureCreated();
        svc.ensureCreated();

        ProcessExecutor.Result ls = exec.run(
                List.of("tmux", "-S", socket.toString(), "list-sessions", "-F", "#{session_name}"), null, TIMEOUT);
        long count = ls.stdout().lines().filter(l -> !l.isBlank()).count();
        assertThat(count)
                .as("AC2/AC13 — three ensureCreated() calls yield exactly one live host tmux session")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("host login shell comes up and runs under a minimal explicit PATH/TERM (restricted-PATH gotcha)")
    void hostLoginShell_comesUpUnderMinimalExplicitEnv() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping live host-tmux check");
        // The exact env shape HostShellSessionService.baseEnv() overlays under a
        // systemd minimal environment: an EXPLICIT, deliberately MINIMAL PATH +
        // TERM and nothing else. The point of the gotcha is that under systemd a
        // bare `tmux` / the login shell fail unless PATH/TERM are set explicitly;
        // here we prove that with ONLY that explicit env (no inherited dev PATH)
        // tmux resolves, the login shell launches, AND it actually executes a
        // command (capture-pane sees the marker) — i.e. the overlay is sufficient.
        //
        // We do NOT assert the shell's resulting $PATH value: a login shell (`-l`)
        // sources /etc/profile, which legitimately rewrites PATH, so an
        // exact-value assertion would be environment-fragile. "Comes up and runs"
        // is the robust, honest invariant.
        Map<String, String> env = Map.of("PATH", "/usr/bin:/bin", "TERM", "xterm-256color");
        String name = "uc62-restricted-" + ProcessHandle.current().pid();
        socket = tmp.resolve("restricted.sock"); // tracked for teardown

        // Launch a detached login shell exactly like the service does (bare tmux,
        // non-absolute argv[0] → resolved via the explicit PATH).
        ProcessExecutor.Result created =
                exec.run(tmux("new-session", "-d", "-s", name, "/bin/bash", "-l"), tmp, env, TIMEOUT);
        assertThat(created.exitCode())
                .as("login-shell tmux comes up under the explicit minimal PATH/TERM (stderr=%s)", created.stderr())
                .isZero();
        assertThat(exec.run(tmux("has-session", "-t", name), null, env, TIMEOUT).exitCode())
                .as("the host login-shell session is reachable on the socket under the minimal env")
                .isZero();

        // Prove the login shell actually EXECUTES under the minimal env: send a
        // marker and read it back via capture-pane (also confirms TERM is accepted).
        exec.run(tmux("send-keys", "-t", name, "printf 'UC62-READY\\n'", "Enter"), null, env, TIMEOUT);
        assertThat(pollCaptureContains(name, env, "UC62-READY"))
                .as("the login shell ran a command under the minimal explicit env (overlay is sufficient)")
                .isTrue();
    }

    @Test
    @DisplayName(
            "UC-64 — hidden HOME repros 'Permission denied'; the redirected accessible HOME is clean (AC1/AC2/AC5)")
    void hiddenHome_reprosPermissionDenied_redirectedHome_isClean() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping live host-tmux check");
        assumeTrue(isPosix(), "non-POSIX filesystem — skipping the chmod-based ProtectHome repro");

        // ── Part A: NEGATIVE control — faithfully reproduce the shipped bug ──
        // Under ProtectHome=true the service's $HOME (/home/ai-sandbox-server) is
        // overmounted UNTRAVERSABLE. We emulate that exactly with a chmod-000
        // directory: any config lookup beneath it (tmux's ~/.tmux.conf, and the
        // login shell's ~/.bash_profile etc.) fails with EACCES → the literal
        // "Permission denied" the operator reported. Driven through the SAME bare
        // `tmux new-session … /bin/bash -l` argv + minimal explicit env the
        // service emits, so this is a faithful end-to-end repro, not a mock.
        Path hiddenHome = tmp.resolve("hidden-home");
        Files.createDirectories(hiddenHome);
        String negName = "uc64-neg-" + ProcessHandle.current().pid();
        Path negSocket = tmp.resolve("uc64-neg.sock");
        socket = negSocket; // tracked for @AfterEach teardown safety-net
        sessionName = negName;
        Files.setPosixFilePermissions(hiddenHome, PosixFilePermissions.fromString("---------")); // chmod 000
        try {
            Map<String, String> negEnv =
                    Map.of("PATH", "/usr/bin:/bin", "TERM", "xterm-256color", "HOME", hiddenHome.toString());
            // Wide pane (-x 200) so the long EACCES path line is not wrapped at
            // col 80 mid-word (capture-pane splits wrapped lines); we still join
            // lines before matching for full robustness.
            ProcessExecutor.Result negCreated = exec.run(
                    negTmux(negSocket, "new-session", "-d", "-x", "200", "-y", "50", "-s", negName, "/bin/bash", "-l"),
                    tmp,
                    negEnv,
                    TIMEOUT);
            assertThat(negCreated.exitCode())
                    .as("the tmux server still comes up; the EACCES surfaces in the pane, not as a new-session failure")
                    .isZero();
            // tmux 3.x defers config/startup errors to the first client, so flush
            // via an attach/capture round-trip (NOT an empty-HOME no-op): poll
            // capture-pane until the login shell's startup-file EACCES appears.
            String captured = pollCaptureUntilNonBlank(negSocket, negName, negEnv);
            assertThat(captured.replace("\n", ""))
                    .as(
                            "AC1 — NEGATIVE control: an untraversable (ProtectHome-like) HOME makes the host shell "
                                    + "hit EACCES on its config — the exact 'Permission denied' the operator saw. "
                                    + "Captured pane:\n%s",
                            captured)
                    .containsIgnoringCase("Permission denied");
        } finally {
            // Restore traversal so @TempDir cleanup can delete the tree, and kill
            // the negative tmux server explicitly.
            Files.setPosixFilePermissions(hiddenHome, PosixFilePermissions.fromString("rwx------"));
            try {
                exec.run(List.of("tmux", "-S", negSocket.toString(), "kill-server"), null, TIMEOUT);
            } catch (IOException ignored) {
                // best-effort
            }
        }

        // ── Part B: POSITIVE — the actual fix, driven through the real service ──
        // ensureCreated() must provision an accessible, writable 0700 HOME under
        // the writable tree and launch the host tmux with HOME redirected there.
        // The login shell then finds no (unreadable) config → NO "Permission
        // denied", the session is reachable, and a marker command runs.
        sessionName = "uc64-pos-" + ProcessHandle.current().pid();
        Path posSocket = tmp.resolve("uc64-pos.sock");
        socket = posSocket; // tracked for teardown
        Path accessibleHome = tmp.resolve("uc64-accessible-home"); // intentionally NOT pre-created
        ServerProperties props = new ServerProperties(
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
                new ServerProperties.ServerSsh(true, posSocket, sessionName, "/bin/bash", tmp, accessibleHome));
        HostShellSessionService svc = new HostShellSessionService(exec, props);

        svc.ensureCreated();

        // AC5/AC9c — ensureCreated() created the redirected HOME 0700 (owner-only).
        assertThat(Files.isDirectory(accessibleHome))
                .as("AC5 — ensureCreated() provisioned the redirected HOME dir")
                .isTrue();
        assertThat(Files.getPosixFilePermissions(accessibleHome))
                .as("AC5/AC9c — the redirected HOME is 0700: NOT group/other-readable (security posture)")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);
        assertThat(svc.homePathString())
                .as("AC8 — the redirected HOME is NOT the ProtectHome-hidden /home")
                .doesNotStartWith("/home/");

        assertThat(svc.exists())
                .as("AC5 — host tmux reachable after create on the redirected HOME")
                .isTrue();
        assertThat(exec.run(
                                List.of("tmux", "-S", posSocket.toString(), "has-session", "-t", sessionName),
                                null,
                                TIMEOUT)
                        .exitCode())
                .as("raw tmux has-session confirms the live session on the redirected HOME")
                .isZero();

        // The login shell ran cleanly: NO "Permission denied", and a marker
        // command executes (proving the shell is live under the redirected HOME).
        Map<String, String> posEnv =
                Map.of("PATH", "/usr/bin:/bin", "TERM", "xterm-256color", "HOME", accessibleHome.toString());
        exec.run(
                negTmux(posSocket, "send-keys", "-t", sessionName, "printf 'UC64-CLEAN\\n'", "Enter"),
                null,
                posEnv,
                TIMEOUT);
        assertThat(pollCaptureContainsOnSocket(posSocket, sessionName, posEnv, "UC64-CLEAN"))
                .as("AC1 — POSITIVE: the host shell runs a command under the redirected accessible HOME")
                .isTrue();
        String posPane = exec.run(negTmux(posSocket, "capture-pane", "-p", "-t", sessionName), null, posEnv, TIMEOUT)
                .stdout();
        assertThat(posPane)
                .as("AC1 — POSITIVE: NO 'Permission denied' on the redirected accessible HOME. Pane:\n%s", posPane)
                .doesNotContainIgnoringCase("Permission denied");
    }

    /** A bare tmux argv on an EXPLICIT socket (the two-part UC-64 test uses two sockets within one method). */
    private List<String> negTmux(Path sock, String... args) {
        List<String> argv = new ArrayList<>(List.of("tmux", "-S", sock.toString()));
        argv.addAll(List.of(args));
        return argv;
    }

    /**
     * Poll capture-pane on an explicit socket until the "Permission denied" EACCES
     * line appears (the shell + its config errors are async), returning the last
     * captured pane regardless so the caller can assert on the evidence.
     */
    private String pollCaptureUntilNonBlank(Path sock, String name, Map<String, String> env) throws IOException {
        String last = "";
        for (int i = 0; i < 60; i++) {
            ProcessExecutor.Result cap = exec.run(negTmux(sock, "capture-pane", "-p", "-t", name), null, env, TIMEOUT);
            if (cap.exitCode() == 0) {
                last = cap.stdout();
                // Join wrapped lines before matching: an 80-col pane splits the
                // long "…/.bash_profile: Permission denied" line mid-word.
                if (last.replace("\n", "").toLowerCase().contains("permission denied")) {
                    return last;
                }
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    /** Poll capture-pane on an explicit socket until {@code marker} appears on its own line. */
    private boolean pollCaptureContainsOnSocket(Path sock, String name, Map<String, String> env, String marker)
            throws IOException {
        for (int i = 0; i < 60; i++) {
            ProcessExecutor.Result cap = exec.run(negTmux(sock, "capture-pane", "-p", "-t", name), null, env, TIMEOUT);
            if (cap.exitCode() == 0
                    && cap.stdout().lines().anyMatch(l -> l.trim().equals(marker))) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    private boolean isPosix() {
        try {
            return Files.getFileStore(tmp)
                    .supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class);
        } catch (IOException e) {
            return false;
        }
    }

    /** Poll capture-pane until {@code marker} appears in the pane (the shell is async). */
    private boolean pollCaptureContains(String name, Map<String, String> env, String marker) throws IOException {
        for (int i = 0; i < 40; i++) {
            ProcessExecutor.Result cap = exec.run(tmux("capture-pane", "-p", "-t", name), null, env, TIMEOUT);
            // The echoed command line also contains the literal marker text, so we
            // require it to appear on a line of its OWN (printf output), not only
            // inside the `printf '…'` command echo.
            if (cap.exitCode() == 0
                    && cap.stdout().lines().anyMatch(l -> l.trim().equals(marker))) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    private List<String> tmux(String... args) {
        List<String> argv = new ArrayList<>(List.of("tmux", "-S", socket.toString()));
        argv.addAll(List.of(args));
        return argv;
    }

    private boolean hasSession() throws IOException {
        return exec.run(List.of("tmux", "-S", socket.toString(), "has-session", "-t", sessionName), null, TIMEOUT)
                        .exitCode()
                == 0;
    }

    private boolean tmuxAvailable() {
        try {
            return exec.run(List.of("tmux", "-V"), null, Duration.ofSeconds(5)).exitCode() == 0;
        } catch (IOException e) {
            return false;
        }
    }
}
