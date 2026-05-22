package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.cli.secrets.FakeConsoleIO;
import com.aisandbox.server.cli.secrets.FakeProcessRunner;
import com.aisandbox.server.cli.secrets.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * UC-17 — orchestrator coverage for {@code aisandboxctl onboard}, the
 * re-runnable per-component onboarding wizard.
 *
 * <p>{@link OnboardCommand} composes {@code pki init} + the four
 * {@code secrets seed} steps behind a single command with a
 * <b>per-component presence check</b>. This harness pins the
 * onboard-specific orchestration logic against the use case's
 * acceptance criteria:
 *
 * <ul>
 *   <li>AC1 — non-interactive (no-TTY) + a still-missing component's
 *       flag absent ⇒ that component is <b>deferred</b> with a re-run
 *       instruction; the command exits 0 and never hangs (the
 *       package-install-must-not-block contract). This is the key
 *       behavioural difference from {@code secrets seed}, which exits 2
 *       on a no-TTY missing flag.</li>
 *   <li>AC3 — per-component check: an already-present artifact (pki,
 *       git-key, gitconfig, gh-token, claude template) is left
 *       untouched unless {@code --force}; only the missing pieces are
 *       gathered.</li>
 *   <li>AC8 — {@code --no-claude-preinit} skips the Claude step and
 *       still leaves an empty template dir as the RO bind-mount
 *       target.</li>
 *   <li>AC9 — idempotent: re-running without {@code --force} does not
 *       overwrite; {@code --force} re-gathers every component.</li>
 *   <li>Root check — uid≠0 ⇒ exit 2 + stderr message, no FS mutation.</li>
 *   <li>{@code --no-image-build} fail-fast — when an interactive
 *       Docker-using step would need the image build but it is
 *       disabled, the command exits 2 <i>before</i> any
 *       {@code docker run}, so a headless install never hangs.</li>
 * </ul>
 *
 * <p>Uses the package-private seams the developer exposed (mirroring
 * {@link SecretsSeedCommandTest} / {@link PkiInitCommandTest}):
 * {@code setRootCheck}, {@code setProcessRunner}, {@code setConsoleIO},
 * {@code setSshDir}, {@code setSystemUserAdmin}. The PKI step is reused
 * verbatim (real {@code SelfSignedServerCertGenerator}); the
 * {@code useradd}/{@code getent} shell-outs are replaced by a counting
 * {@link FakeSystemUserAdmin}, and the {@code ai-sandbox-server} chown
 * is skipped on hosts without that user (warn-and-skip in
 * {@link Ownership#resolve}).
 */
class OnboardCommandTest {

    /** Opaque key fixture — ssh-keygen is faked, so the content is irrelevant. */
    private static final byte[] KEY_BYTES = "<ssh-key-fixture-bytes-opaque-to-orchestrator>\n".getBytes();

    /** Counting fake for {@link SystemUserAdmin} — never shells out to useradd/getent. */
    private static final class FakeSystemUserAdmin implements SystemUserAdmin {
        boolean exists;
        final AtomicInteger createCalls = new AtomicInteger();

        FakeSystemUserAdmin(boolean initiallyExists) {
            this.exists = initiallyExists;
        }

        @Override
        public boolean userExists(String name) {
            return exists;
        }

        @Override
        public void createSystemUser(String name) {
            createCalls.incrementAndGet();
            exists = true;
        }
    }

    /** Build an OnboardCommand with the standard test seams pre-wired. */
    private static OnboardCommand onboard(FakeProcessRunner runner, FakeConsoleIO io, Path sshDir) {
        OnboardCommand c = new OnboardCommand();
        c.setRootCheck(() -> true);
        c.setProcessRunner(runner);
        c.setConsoleIO(io);
        c.setSshDir(sshDir);
        // A fake user-admin so any test that exercises the PKI path (force /
        // missing-pki) doesn't shell out to useradd / getent. Harmless for
        // PKI-present tests (the pki step is skipped, so it's never called).
        c.setSystemUserAdmin(new FakeSystemUserAdmin(/* initiallyExists */ false));
        return c;
    }

    /** Stage the standard install-layout paths under {@code tmp/}. */
    private static Layout layout(Path tmp) throws IOException {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path opt = tmp.resolve("opt/ai-sandbox-server");
        Path var = tmp.resolve("var/lib/ai-sandbox-server");
        Path log = tmp.resolve("var/log/ai-sandbox-server");
        // Compose context — present so any test that DID trigger an image
        // build would have a valid context (the tests below never reach a
        // real build, but the layout mirrors SecretsSeedCommandTest).
        Files.createDirectories(opt.resolve("host"));
        Files.writeString(opt.resolve("host/docker-compose.yml"), "# stub compose context\n");
        return new Layout(
                etc.resolve("pki"),
                etc.resolve("clients"),
                var.resolve("enrollment"),
                etc.resolve("secrets"),
                etc.resolve("templates"),
                var.resolve("sessions"),
                log,
                etc.resolve("config.yaml"),
                opt);
    }

    private record Layout(
            Path pkiDir,
            Path clientsDir,
            Path enrollmentDir,
            Path secretsDir,
            Path templatesDir,
            Path sessionsDir,
            Path logDir,
            Path configFile,
            Path installDir) {

        Path crt() {
            return pkiDir.resolve("server.crt");
        }

        Path key() {
            return pkiDir.resolve("server.key");
        }

        Path gitKeyOut() {
            return secretsDir.resolve("git-key");
        }

        Path gitconfigOut() {
            return secretsDir.resolve("gitconfig");
        }

        Path ghTokenOut() {
            return secretsDir.resolve("gh-token");
        }

        Path claudeOut() {
            return templatesDir.resolve("claude-config");
        }

        String[] dirArgs() {
            return new String[] {
                "--pki-dir", pkiDir.toString(),
                "--clients-dir", clientsDir.toString(),
                "--enrollment-dir", enrollmentDir.toString(),
                "--secrets-dir", secretsDir.toString(),
                "--templates-dir", templatesDir.toString(),
                "--sessions-dir", sessionsDir.toString(),
                "--log-dir", logDir.toString(),
                "--config", configFile.toString(),
                "--install-dir", installDir.toString(),
            };
        }

        /** Pre-create cert+key+config so the per-component check treats PKI as present. */
        void seedPkiPresent() throws IOException {
            Files.createDirectories(pkiDir);
            Files.writeString(crt(), "----- fake cert -----\n");
            Files.writeString(key(), "----- fake key -----\n");
            Files.writeString(configFile, "# fake baked config\n");
        }

        /** Pre-create all four secret artifacts (claude with a non-empty file). */
        void seedSecretsPresent() throws IOException {
            Files.createDirectories(secretsDir);
            Files.createDirectories(claudeOut());
            Files.writeString(gitKeyOut(), "present-git-key");
            Files.writeString(gitconfigOut(), "present-gitconfig");
            Files.writeString(ghTokenOut(), "present-gh-token");
            Files.writeString(claudeOut().resolve("settings.json"), "present-claude");
        }
    }

    /** Concatenate dirArgs() with per-test extras. */
    private static String[] args(Layout layout, String... extras) {
        String[] base = layout.dirArgs();
        String[] out = new String[base.length + extras.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extras, 0, out, base.length, extras.length);
        return out;
    }

    private static Path writeKey(Path tmp, String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.write(p, KEY_BYTES);
        return p;
    }

    /** Default permissive runner: image present + ssh key unencrypted. */
    private static FakeProcessRunner permissiveRunner() {
        FakeProcessRunner r = new FakeProcessRunner();
        r.captureResponse = argv -> new ProcessRunner.Result(0, "");
        return r;
    }

    /** Capture stdout + stderr around the command's execute. */
    private static int executeCapturing(
            CommandLine cmd, String[] argv, ByteArrayOutputStream outBuf, ByteArrayOutputStream errBuf) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(outBuf, true));
        System.setErr(new PrintStream(errBuf, true));
        try {
            return cmd.execute(argv);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    private static long dockerInspectCalls(FakeProcessRunner runner) {
        return runner.captureCalls.stream()
                .filter(c -> c.size() >= 2 && "docker".equals(c.get(0)) && "image".equals(c.get(1)))
                .count();
    }

    // ── Root check ─────────────────────────────────────────────────

    @Test
    void root_check_blocks_when_uid_not_zero(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        OnboardCommand c = onboard(runner, io, tmp.resolve("ssh-dir"));
        c.setRootCheck(() -> false); // override the onboard() default

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(new CommandLine(c), args(layout), outBuf, errBuf);

        assertThat(exit).isEqualTo(2);
        assertThat(errBuf.toString()).contains("must run as root").contains("sudo");
        // No secret was gathered.
        assertThat(layout.gitKeyOut()).doesNotExist();
        assertThat(runner.captureCalls).isEmpty();
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── AC3 — per-component check: everything present, nothing touched ──

    @Test
    void all_components_present_without_force_leaves_everything_untouched(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        layout.seedSecretsPresent();

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))), args(layout), outBuf, errBuf);

        assertThat(exit).isZero();
        String stdout = outBuf.toString();
        assertThat(stdout).contains("aisandboxctl onboard: complete.");
        // Every component reported as unchanged.
        assertThat(stdout)
                .contains("unchanged")
                .contains("pki")
                .contains("git-key (present)")
                .contains("gitconfig (present)")
                .contains("gh-token (present)")
                .contains("claude template (present)");
        // Nothing configured this run.
        assertThat(stdout).doesNotContain("configured:");

        // Original content preserved byte-for-byte.
        assertThat(Files.readString(layout.gitKeyOut())).isEqualTo("present-git-key");
        assertThat(Files.readString(layout.gitconfigOut())).isEqualTo("present-gitconfig");
        assertThat(Files.readString(layout.ghTokenOut())).isEqualTo("present-gh-token");
        assertThat(Files.readString(layout.claudeOut().resolve("settings.json")))
                .isEqualTo("present-claude");

        // No step shelled out (no ssh-keygen probe, no docker).
        assertThat(runner.captureCalls).isEmpty();
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── AC3 — one component missing → only that step runs ──────────

    @Test
    void missing_git_key_is_gathered_while_present_components_are_left_untouched(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        // Everything present EXCEPT git-key.
        Files.createDirectories(layout.secretsDir());
        Files.createDirectories(layout.claudeOut());
        Files.writeString(layout.gitconfigOut(), "present-gitconfig");
        Files.writeString(layout.ghTokenOut(), "present-gh-token");
        Files.writeString(layout.claudeOut().resolve("settings.json"), "present-claude");

        Path srcKey = writeKey(tmp, "src-key");
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                args(layout, "--git-key", srcKey.toString()),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        String stdout = outBuf.toString();
        assertThat(stdout).contains("configured").contains("git-key");
        assertThat(stdout)
                .contains("gitconfig (present)")
                .contains("gh-token (present)")
                .contains("claude template (present)");

        // git-key gathered from the supplied source.
        assertThat(Files.readAllBytes(layout.gitKeyOut())).isEqualTo(KEY_BYTES);
        // Present components untouched.
        assertThat(Files.readString(layout.gitconfigOut())).isEqualTo("present-gitconfig");
        assertThat(Files.readString(layout.ghTokenOut())).isEqualTo("present-gh-token");

        // Only the SSH-key step ran: its sole shell-out is the ssh-keygen -y
        // encryption probe. No interactive docker, no gh, no claude.
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(runner.captureCalls)
                .as("only the ssh-keygen encryption probe should have run")
                .allSatisfy(c -> assertThat(c.get(0)).isEqualTo("ssh-keygen"));
        assertThat(dockerInspectCalls(runner)).isZero();
    }

    // ── AC1 — non-interactive deferral (the crux) ──────────────────

    @Test
    void non_interactive_missing_flags_defers_cleanly_exit_zero_and_never_hangs(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        // No secrets present; no flags supplied; NO TTY.
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = false; // ← the AC1 lever

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))), args(layout), outBuf, errBuf);

        // AC1 — defers, does NOT fail the install (exit 0, not 2).
        assertThat(exit).isZero();
        String stdout = outBuf.toString();
        assertThat(stdout).contains("deferred");
        assertThat(stdout)
                .contains("SSH key")
                .contains("Git identity")
                .contains("gh token")
                .contains("Claude pre-init");
        // Each deferral carries a concrete re-run instruction.
        assertThat(stdout).contains("--git-key").contains("--git-name").contains("--gh-token-file");

        // Nothing gathered, nothing written for the deferred components.
        assertThat(layout.gitKeyOut()).doesNotExist();
        assertThat(layout.gitconfigOut()).doesNotExist();
        assertThat(layout.ghTokenOut()).doesNotExist();
        // Claude opt-skip still leaves an empty template dir as the mount target.
        assertThat(layout.claudeOut()).isDirectory();

        // AC1 — never hangs: no interactive docker run was attempted.
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(dockerInspectCalls(runner)).isZero();
    }

    @Test
    void non_interactive_with_partial_flags_defers_only_the_still_missing_component(@TempDir Path tmp)
            throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = false;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        // git identity supplied, gh + claude opted out, but git-key still missing.
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--git-name",
                        "Alice",
                        "--git-email",
                        "alice@example.com",
                        "--no-gh",
                        "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        String stdout = outBuf.toString();
        // Only the SSH key is deferred.
        assertThat(stdout).contains("SSH key");
        assertThat(stdout).doesNotContain("Git identity —"); // identity was gathered, not deferred
        // git identity gathered from the flags.
        assertThat(Files.readString(layout.gitconfigOut()))
                .isEqualTo("[user]\n\tname = Alice\n\temail = alice@example.com\n");
        // gh + claude opted out.
        assertThat(stdout).contains("gh-token (--no-gh)").contains("claude template (--no-claude-preinit)");
        assertThat(layout.gitKeyOut()).doesNotExist();
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── AC8 — --no-claude-preinit leaves an empty template dir ─────

    @Test
    void no_claude_preinit_creates_empty_template_dir_as_mount_target(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        Path srcKey = writeKey(tmp, "src-key");
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "A",
                        "--git-email",
                        "a@b.co",
                        "--no-gh",
                        "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        assertThat(outBuf.toString()).contains("claude template (--no-claude-preinit)");
        // Empty template dir exists for the RO bind-mount to attach to.
        assertThat(layout.claudeOut()).isDirectory();
        try (var s = Files.list(layout.claudeOut())) {
            assertThat(s.findAny())
                    .as("claude template dir is empty when --no-claude-preinit")
                    .isEmpty();
        }
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── --no-image-build fail-fast on a docker-needing step ────────

    @Test
    void no_image_build_with_interactive_docker_step_fails_fast_before_any_docker_run(@TempDir Path tmp)
            throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        // gh-token absent + no --gh-token-file + TTY ⇒ gh would go interactive
        // (docker run). --no-claude-preinit isolates gh as the docker driver.
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                args(layout, "--no-image-build", "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        String stderr = errBuf.toString();
        assertThat(stderr).contains("ai-context:latest").contains("--no-image-build");
        // Fail-fast: no docker image build / inspect, no interactive docker run.
        assertThat(runner.inheritCalls)
                .as("must not hang on an interactive docker run")
                .isEmpty();
        assertThat(dockerInspectCalls(runner)).isZero();
    }

    @Test
    void no_image_build_with_all_flag_driven_steps_succeeds_without_docker(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        Path srcKey = writeKey(tmp, "src-key");
        Path ghToken = tmp.resolve("gh-pat");
        Files.writeString(ghToken, "<flag-token>\n");
        Path claudeSrc = tmp.resolve("src-claude");
        Files.createDirectories(claudeSrc);
        Files.writeString(claudeSrc.resolve("settings.json"), "{\"flag\":true}");

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--no-image-build",
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "Bob",
                        "--git-email",
                        "bob@example.com",
                        "--gh-token-file",
                        ghToken.toString(),
                        "--claude-config-source",
                        claudeSrc.toString()),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        // All flag-driven steps ran without needing a docker image build.
        assertThat(Files.readAllBytes(layout.gitKeyOut())).isEqualTo(KEY_BYTES);
        assertThat(Files.readString(layout.ghTokenOut())).isEqualTo("<flag-token>\n");
        assertThat(Files.readString(layout.claudeOut().resolve("settings.json")))
                .isEqualTo("{\"flag\":true}");
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(dockerInspectCalls(runner)).isZero();
    }

    // ── AC9 — --force re-gathers every component ───────────────────

    @Test
    void force_regathers_all_present_components_including_pki(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        layout.seedPkiPresent();
        layout.seedSecretsPresent();

        Path srcKey = writeKey(tmp, "src-key");
        Path ghToken = tmp.resolve("gh-pat");
        Files.writeString(ghToken, "<fresh-token>\n");
        Path claudeSrc = tmp.resolve("src-claude");
        Files.createDirectories(claudeSrc);
        Files.writeString(claudeSrc.resolve("settings.json"), "{\"fresh\":true}");

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--force",
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "Carol",
                        "--git-email",
                        "carol@example.com",
                        "--gh-token-file",
                        ghToken.toString(),
                        "--claude-config-source",
                        claudeSrc.toString()),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        String stdout = outBuf.toString();
        // Everything (re-)configured, nothing reported unchanged.
        assertThat(stdout)
                .contains("configured")
                .contains("pki")
                .contains("git-key")
                .contains("gh-token");
        assertThat(stdout).doesNotContain("unchanged :");

        // PKI re-minted by `pki init --force` (real cert generator).
        assertThat(layout.crt()).exists();
        assertThat(layout.key()).exists();
        assertThat(layout.configFile()).exists();
        // The fake baked-config placeholder was overwritten by pki init's real output.
        assertThat(Files.readString(layout.configFile())).doesNotContain("# fake baked config");

        // All four secrets overwritten with fresh content.
        assertThat(Files.readAllBytes(layout.gitKeyOut())).isEqualTo(KEY_BYTES);
        assertThat(Files.readString(layout.gitconfigOut()))
                .isEqualTo("[user]\n\tname = Carol\n\temail = carol@example.com\n");
        assertThat(Files.readString(layout.ghTokenOut())).isEqualTo("<fresh-token>\n");
        assertThat(Files.readString(layout.claudeOut().resolve("settings.json")))
                .isEqualTo("{\"fresh\":true}");

        // Flag-driven everywhere ⇒ no interactive docker.
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(dockerInspectCalls(runner)).isZero();
    }

    // ── PKI missing → provisioned via `pki init` ───────────────────

    @Test
    void missing_pki_is_provisioned_via_pki_init(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        // NOTE: no seedPkiPresent() — cert/key/config all absent.
        Path srcKey = writeKey(tmp, "src-key");
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "Dan",
                        "--git-email",
                        "dan@example.com",
                        "--no-gh",
                        "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        assertThat(outBuf.toString()).contains("configured").contains("pki");
        // pki init minted a real self-signed cert + key and wrote config.yaml.
        assertThat(layout.crt()).exists();
        assertThat(layout.key()).exists();
        assertThat(layout.configFile()).exists();
        // Secrets gathered alongside.
        assertThat(Files.readAllBytes(layout.gitKeyOut())).isEqualTo(KEY_BYTES);
        assertThat(layout.gitconfigOut()).exists();
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── Guard: writable dirs may not live under the read-only install dir ──

    @Test
    void rejects_secrets_dir_under_install_dir(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(onboard(runner, io, tmp.resolve("ssh-dir"))),
                new String[] {
                    "--secrets-dir", "/opt/ai-sandbox-server/secrets",
                    "--templates-dir", layout.templatesDir().toString(),
                    "--install-dir", layout.installDir().toString(),
                },
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        assertThat(errBuf.toString())
                .contains("cannot live under")
                .contains("/opt/ai-sandbox-server")
                .contains("install dir is read-only");
    }
}
