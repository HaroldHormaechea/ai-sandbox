package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC05 § AC25,AC26,AC27 — wire-level assertions for the bundled
 * {@code lib.sh} → {@code ai_sandbox_compose()} helper and its
 * downstream effect on {@code spawn.sh} / {@code clean.sh}.
 *
 * <p>The strategy is end-to-end at the script level: copy the repo's
 * {@code lib.sh}, {@code spawn.sh}, {@code clean.sh}, and a thin
 * harness onto a temp directory, drop a fake {@code docker} shim that
 * echoes its argv on stdout, then run each script with the
 * UC05 env vars set/unset and assert the captured docker argv.
 *
 * <p>UC-07 § AC7 — this test does NOT require Docker-in-Docker; it
 * builds its own fake {@code docker} shim per test, so the {@code IT}
 * suffix was misclassifying it as DinD-gated. Renamed to
 * {@code HostScriptComposeEnvTest} as part of the v0.0.8 {@code *IT}
 * audit so the build runs it under the default {@code :server:test}
 * task on every PR. The {@link EnabledOnOs} gate (Linux / macOS only,
 * because POSIX permission control and {@code /bin/sh} script
 * invocation are mandatory) is preserved unchanged.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class HostScriptComposeEnvTest {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("user.dir")).getParent();

    /**
     * AC25 — with both env vars set, {@code ai_sandbox_compose} prepends
     * {@code -f <file> --project-directory <dir>} to every {@code docker
     * compose} invocation. Verified by sourcing {@code lib.sh} and
     * invoking the helper through a fake docker shim that echoes its
     * argv.
     */
    @Test
    void ai_sandbox_compose_prepends_compose_file_and_project_directory_flags(@TempDir Path tmp) throws Exception {
        Path bin = mkBinDir(tmp);
        Path log = tmp.resolve("docker.log");
        installFakeDocker(bin, log);

        // Run a one-liner that sources lib.sh and calls the helper.
        Path libSh = REPO_ROOT.resolve("lib.sh");
        assertThat(libSh).exists();

        Map<String, String> env = new HashMap<>();
        env.put("AI_SANDBOX_COMPOSE_FILE", "/opt/ai-sandbox-server/host/docker-compose.yml");
        env.put("AI_SANDBOX_HOST_STATE_ROOT", "/var/lib/ai-sandbox-server/sessions");
        env.put("PATH", bin.toString() + ":" + System.getenv("PATH"));

        int rc = runShell(tmp, env, "source '" + libSh + "' && ai_sandbox_compose -p ai-sandbox-1 up -d");
        assertThat(rc).isZero();

        // The shim echoes each arg on its own line. Joining gives us a
        // stable string to assert on.
        List<String> argv = Files.readAllLines(log);
        // The first arg is "compose" because we invoked "docker compose
        // <flags...>" — the shim is exec'd as "docker" so argv starts
        // with "compose" (the subcommand) then the prepended flags.
        assertThat(argv)
                .as("fake docker captured argv for ai_sandbox_compose")
                .containsExactly(
                        "compose",
                        "-f",
                        "/opt/ai-sandbox-server/host/docker-compose.yml",
                        "--project-directory",
                        "/var/lib/ai-sandbox-server/sessions",
                        "-p",
                        "ai-sandbox-1",
                        "up",
                        "-d");
    }

    /**
     * Developer-mode parity — both env vars unset, no extra flags get
     * prepended; the helper resolves to a bare {@code docker compose}.
     */
    @Test
    void ai_sandbox_compose_with_no_env_vars_is_a_bare_docker_compose(@TempDir Path tmp) throws Exception {
        Path bin = mkBinDir(tmp);
        Path log = tmp.resolve("docker.log");
        installFakeDocker(bin, log);

        Path libSh = REPO_ROOT.resolve("lib.sh");
        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toString() + ":" + System.getenv("PATH"));
        // Explicitly unset the UC05 vars even if the parent shell exported
        // them — `env -u` would be cleaner but we don't get to control the
        // child's env-passthrough at that level.
        env.put("AI_SANDBOX_COMPOSE_FILE", "");
        env.put("AI_SANDBOX_HOST_STATE_ROOT", "");

        int rc = runShell(tmp, env, "source '" + libSh + "' && ai_sandbox_compose -p ai-sandbox-7 down -v");
        assertThat(rc).isZero();

        assertThat(Files.readAllLines(log))
                .as("bare docker compose argv with both UC05 env vars unset")
                .containsExactly("compose", "-p", "ai-sandbox-7", "down", "-v");
    }

    /**
     * Critical regression guard #1 — with {@code AI_SANDBOX_HOST_STATE_ROOT}
     * set, {@code spawn.sh} cd's into that directory BEFORE acquiring
     * the lock and writing the counter. After a successful spawn the
     * counter MUST land at {@code <root>/.ai-sandbox-counter}; NO
     * counter file can materialise in the script's own parent
     * directory (which is read-only under the install layout).
     */
    @Test
    void spawn_sh_with_host_state_root_writes_counter_into_root_not_parent(@TempDir Path tmp) throws Exception {
        // Stage a fake "host/" dir holding lib.sh + spawn.sh.
        Path hostDir = tmp.resolve("host");
        Files.createDirectories(hostDir);
        copyExec(REPO_ROOT.resolve("lib.sh"), hostDir.resolve("lib.sh"));
        copyExec(REPO_ROOT.resolve("spawn.sh"), hostDir.resolve("spawn.sh"));

        Path hostStateRoot = tmp.resolve("var-lib-ai-sandbox-server-sessions");
        // Don't pre-create — spawn.sh's `mkdir -p $AI_SANDBOX_HOST_STATE_ROOT`
        // should create it.

        Path bin = mkBinDir(tmp);
        Path log = tmp.resolve("docker.log");
        installFakeDocker(bin, log);

        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toString() + ":" + System.getenv("PATH"));
        env.put("AI_SANDBOX_COMPOSE_FILE", hostDir.resolve("docker-compose.yml").toString());
        env.put("AI_SANDBOX_HOST_STATE_ROOT", hostStateRoot.toString());

        // Run spawn.sh non-interactively. The fake docker swallows
        // `compose -p ai-sandbox-N up -d` and exits 0, so the counter
        // increments to 1.
        int rc = runShell(
                tmp,
                env,
                "'" + hostDir.resolve("spawn.sh") + "' --non-interactive --shared-workspace --shared-claude-config");
        assertThat(rc).isZero();

        // ↳ Counter landed under host-state-root.
        Path counter = hostStateRoot.resolve(".ai-sandbox-counter");
        assertThat(counter).as("counter under host-state-root").exists();
        assertThat(Files.readString(counter).trim()).isEqualTo("1");

        // ↳ Counter did NOT land in the script's parent dir (Critical
        // regression guard #1 from the analyst's proposal). Anything
        // matching `.ai-sandbox-counter*` under `hostDir` would mean the
        // script wrote to its install location instead of host-state.
        try (var ds = Files.newDirectoryStream(hostDir, ".ai-sandbox-counter*")) {
            assertThat(ds.iterator().hasNext())
                    .as("no counter / lockdir leaked into the script's parent (regression guard)")
                    .isFalse();
        }
    }

    /**
     * Developer-mode regression guard — with {@code
     * AI_SANDBOX_HOST_STATE_ROOT} unset, {@code spawn.sh} writes the
     * counter into its own parent dir (the repo root), independent of
     * where the per-session workspace lives.
     *
     * <p>Developer mode requires the dev workspace root to be configured —
     * interactively via {@code ./setup.sh}, or explicitly via
     * {@code AI_SANDBOX_DEV_WORKSPACE_ROOT}. A non-TTY run refuses to
     * default silently (so a stray {@code cp -a . workspace} can never
     * recurse into the repo and fill the disk — the recursion guard at
     * {@code spawn.sh} lines 162-190). This test runs non-interactively,
     * so it supplies the root explicitly (a temp dir outside the staged
     * repo) and asserts the counter still lands beside the script.
     */
    @Test
    void spawn_sh_without_host_state_root_writes_counter_into_script_parent(@TempDir Path tmp) throws Exception {
        Path hostDir = tmp.resolve("repo");
        Files.createDirectories(hostDir);
        copyExec(REPO_ROOT.resolve("lib.sh"), hostDir.resolve("lib.sh"));
        copyExec(REPO_ROOT.resolve("spawn.sh"), hostDir.resolve("spawn.sh"));

        Path bin = mkBinDir(tmp);
        Path log = tmp.resolve("docker.log");
        installFakeDocker(bin, log);

        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toString() + ":" + System.getenv("PATH"));
        // UC05 host-install vars (AI_SANDBOX_HOST_STATE_ROOT / _COMPOSE_FILE)
        // stay unset — this is the developer-mode path. Developer mode now
        // requires the dev workspace root to be chosen up front; on a non-TTY
        // run spawn.sh refuses to default silently, so supply it explicitly
        // (the documented non-interactive equivalent of running ./setup.sh
        // once). Pointed outside the staged repo so the recursion guard stays
        // quiet; the counter location under test is independent of it.
        Path devWorkspaceRoot = tmp.resolve("dev-workspace");
        Files.createDirectories(devWorkspaceRoot);
        env.put("AI_SANDBOX_DEV_WORKSPACE_ROOT", devWorkspaceRoot.toString());

        int rc = runShell(
                tmp,
                env,
                "'" + hostDir.resolve("spawn.sh") + "' --non-interactive --shared-workspace --shared-claude-config");
        assertThat(rc).isZero();

        // Counter landed in script's parent dir (legacy behaviour).
        Path counter = hostDir.resolve(".ai-sandbox-counter");
        assertThat(counter).as("counter in script parent dir (developer mode)").exists();
        assertThat(Files.readString(counter).trim()).isEqualTo("1");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Path mkBinDir(Path tmp) throws IOException {
        Path bin = tmp.resolve("bin");
        Files.createDirectories(bin);
        return bin;
    }

    /**
     * Drops a fake {@code docker} on PATH that echoes its argv into
     * {@code log} (one per line) and exits 0. The shim is mode 0755.
     */
    private static void installFakeDocker(Path bin, Path log) throws IOException {
        String body = "#!/bin/sh\n" + "for a in \"$@\"; do printf '%s\\n' \"$a\" >> '" + log + "'; done\n" + "exit 0\n";
        Path docker = bin.resolve("docker");
        Files.writeString(docker, body);
        chmod0755(docker);
    }

    private static void copyExec(Path src, Path dst) throws IOException {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        chmod0755(dst);
    }

    private static void chmod0755(Path p) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {
            // Test is gated to POSIX OSes; this should never trip.
        }
    }

    /**
     * Spawn {@code /bin/bash -c <cmd>} with the supplied env (PATH is
     * merged from the supplied entry) and working dir, wait up to
     * 30s, return the exit code. Stdout / stderr are propagated to the
     * test's own streams for diagnostics.
     *
     * <p>Uses bash (not {@code /bin/sh}) because {@code lib.sh} relies
     * on bash-specific syntax (function definitions with parentheses
     * inside braces, {@code [[ ]]}, etc.) — its shebang is
     * {@code #!/usr/bin/env bash} and the source must match.
     */
    private static int runShell(Path cwd, Map<String, String> env, String cmd) throws Exception {
        List<String> argv = new ArrayList<>(List.of("/bin/bash", "-c", cmd));
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);
        Map<String, String> procEnv = pb.environment();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                procEnv.remove(e.getKey());
            } else {
                procEnv.put(e.getKey(), e.getValue());
            }
        }
        // Keep TERM unset to avoid color codes in script output.
        procEnv.remove("TERM");
        Process p = pb.start();
        // Drain stdout/err so the process doesn't block on a full pipe.
        Thread out = new Thread(() -> drain(p.getInputStream(), System.out));
        Thread err = new Thread(() -> drain(p.getErrorStream(), System.err));
        out.setDaemon(true);
        err.setDaemon(true);
        out.start();
        err.start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("shell command timed out: " + cmd);
        }
        out.join(1000);
        err.join(1000);
        return p.exitValue();
    }

    private static void drain(java.io.InputStream in, java.io.PrintStream sink) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                sink.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // best-effort drain
        }
    }
}
