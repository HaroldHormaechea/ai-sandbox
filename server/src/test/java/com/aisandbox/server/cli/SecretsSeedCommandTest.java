package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.cli.secrets.FakeConsoleIO;
import com.aisandbox.server.cli.secrets.FakeProcessRunner;
import com.aisandbox.server.cli.secrets.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * UC06 — orchestrator coverage for {@code aisandboxctl secrets seed}.
 *
 * <p>Pinned ACs (per the approved QA proposal):
 *
 * <ul>
 *   <li>AC1 — root check; uid≠0 ⇒ exit 2 + stderr message.</li>
 *   <li>AC9 — fixed step order ssh → identity → gh → claude.</li>
 *   <li>AC10 — non-interactive with all required flags = zero prompts.</li>
 *   <li>AC12 — no-TTY + missing flag ⇒ exit 2 + stderr lists every
 *       missing flag (including the opt-out alternatives).</li>
 *   <li>AC13 — re-run policy: refuse without {@code --force}, list
 *       every conflict path; {@code --force} overwrites all four
 *       outputs.</li>
 *   <li>AC14 — UC05 manual-drop scenario; pre-existing files trigger
 *       the same refuse + list behaviour.</li>
 *   <li>AC15 — guard against {@code --secrets-dir} /
 *       {@code --templates-dir} that would land under
 *       {@code /opt/ai-sandbox-server/} (install dir is read-only).</li>
 *   <li>AC22 — final stdout summary lists every target with mode and
 *       the configured system user.</li>
 * </ul>
 *
 * <p>Per-step coverage (AC2-AC7, AC16) lives in the dedicated
 * {@code secrets/*Test.java} files; this harness exercises the
 * orchestrator. The test uses the package-private seams the developer
 * added: {@code setRootCheck}, {@code setProcessRunner},
 * {@code setConsoleIO}, {@code setSshDir} (mirrors
 * {@code PkiInitCommandTest}'s pattern).
 */
class SecretsSeedCommandTest {

    /** Opaque key fixture — content is irrelevant; ssh-keygen is faked. */
    private static final byte[] KEY_BYTES = "<ssh-key-fixture-bytes-opaque-to-orchestrator>\n".getBytes();

    /** Build a Seed with the standard test seams pre-wired. */
    private static SecretsSeedCommand.Seed seed(FakeProcessRunner runner, FakeConsoleIO io, Path sshDir) {
        SecretsSeedCommand.Seed s = new SecretsSeedCommand.Seed();
        s.setRootCheck(() -> true);
        s.setProcessRunner(runner);
        s.setConsoleIO(io);
        s.setSshDir(sshDir);
        return s;
    }

    /** Stage the standard install-layout paths under {@code tmp/}. */
    private static Layout layout(Path tmp) throws IOException {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path opt = tmp.resolve("opt/ai-sandbox-server");
        // Compose context — required for any --no-gh=false /
        // --no-claude-preinit=false test that triggers EnsureSandboxImage.
        Files.createDirectories(opt.resolve("host"));
        Files.writeString(opt.resolve("host/docker-compose.yml"), "# stub compose context\n");
        return new Layout(etc.resolve("secrets"), etc.resolve("templates"), opt);
    }

    private record Layout(Path secretsDir, Path templatesDir, Path installDir) {
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
                "--secrets-dir", secretsDir.toString(),
                "--templates-dir", templatesDir.toString(),
                "--install-dir", installDir.toString(),
            };
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Onboarded {@code .claude.json} + {@code .credentials.json}, as the UC-19 value check requires. */
    private static final String ONBOARDED_CLAUDE_JSON =
            "{\"hasCompletedOnboarding\":true,\"oauthAccount\":{\"emailAddress\":\"dev@example.com\"}}";

    private static final String CREDENTIALS_JSON = "{\"claudeAiOauth\":{\"accessToken\":\"tok\"}}";

    /** UC-19: a {@code --claude-config-source} tree must be onboarded (value-checked). */
    private static Path onboardedClaudeSource(Path dir, String settingsJson) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), settingsJson);
        Files.writeString(dir.resolve(".claude.json"), ONBOARDED_CLAUDE_JSON);
        Files.writeString(dir.resolve(".credentials.json"), CREDENTIALS_JSON);
        return dir;
    }

    /** Part E rewrites settings.json: assert the preserved boolean key + the agent-teams keys. */
    private static void assertAgentTeamsMerged(Path settingsFile, String preservedKey) throws IOException {
        JsonNode n = MAPPER.readTree(settingsFile.toFile());
        assertThat(n.path(preservedKey).asBoolean())
                .as("part-E preserves %s", preservedKey)
                .isTrue();
        assertThat(n.path("teammateMode").asText()).isEqualTo("tmux");
        assertThat(n.path("env").path("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS").asText())
                .isEqualTo("1");
    }

    /** Default unencrypted-key probe + image-present probe for all-flag runs. */
    private static FakeProcessRunner permissiveRunner() {
        FakeProcessRunner r = new FakeProcessRunner();
        r.captureResponse = argv -> new ProcessRunner.Result(0, ""); // unencrypted / image present
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

    // ── AC1 — root check ───────────────────────────────────────────

    @Test
    void init_root_check_blocks_when_uid_not_zero(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        SecretsSeedCommand.Seed s = seed(runner, io, tmp.resolve("ssh-dir"));
        s.setRootCheck(() -> false); // override the seed() default

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(s),
                args(
                        layout,
                        "--git-key",
                        writeKey(tmp, "k").toString(),
                        "--git-name",
                        "A",
                        "--git-email",
                        "a@b.co",
                        "--no-gh",
                        "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        assertThat(errBuf.toString()).contains("must run as root").contains("sudo");
        // Nothing was created — the early return MUST fire before any
        // filesystem mutation.
        assertThat(layout.secretsDir()).doesNotExist();
        assertThat(layout.templatesDir()).doesNotExist();
        // No shell-outs.
        assertThat(runner.captureCalls).isEmpty();
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── AC10 — fully non-interactive ──────────────────────────────

    @Test
    void runsWithAllRequiredFlagsAndZeroPrompts(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        Path srcKey = writeKey(tmp, "src-key");

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        // TTY exists, but flags fully cover every step so nothing prompts.
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "Alice",
                        "--git-email",
                        "alice@example.com",
                        "--no-gh",
                        "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        // Zero prompts: input queue untouched, console "printed" stream
        // is empty (steps print step headers only on the interactive
        // path, and flag-driven steps emit nothing through ConsoleIO).
        assertThat(io.printed)
                .as("no console prompts on a fully flag-driven run")
                .isEmpty();
        assertThat(io.inputLines).isEmpty();
        assertThat(io.passwords).isEmpty();

        // The four outputs land where expected (gh-token skipped).
        assertThat(layout.gitKeyOut()).exists();
        assertThat(Files.readAllBytes(layout.gitKeyOut())).isEqualTo(KEY_BYTES);
        assertThat(layout.gitconfigOut()).exists();
        assertThat(Files.readString(layout.gitconfigOut()))
                .isEqualTo("[user]\n\tname = Alice\n\temail = alice@example.com\n");
        assertThat(layout.ghTokenOut()).doesNotExist();
        // Claude opt-out leaves an empty template dir for the RO mount target.
        assertThat(layout.claudeOut()).isDirectory();

        // needsDocker = false on full opt-out → EnsureSandboxImage never
        // probes. Only ssh-keygen -y (encryption check) ran.
        assertThat(runner.inheritCalls).isEmpty();
        long dockerInspectCalls = runner.captureCalls.stream()
                .filter(c -> c.size() >= 2 && "docker".equals(c.get(0)) && "image".equals(c.get(1)))
                .count();
        assertThat(dockerInspectCalls)
                .as("docker image inspect must NOT fire when --no-gh + --no-claude-preinit are both set")
                .isZero();
    }

    // ── AC12 — no-TTY + missing flag fail-fast ────────────────────

    @Test
    void failsFastWhenNoTtyAndFlagsMissing_listsEveryMissingFlag(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = false; // ← the critical lever

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                args(layout), // NO step flags supplied at all
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        String stderr = errBuf.toString();
        assertThat(stderr).contains("stdin is not a TTY");
        assertThat(stderr)
                .contains("--git-key")
                .contains("--git-name")
                .contains("--git-email")
                .contains("--gh-token-file (or --no-gh)")
                .contains("--claude-config-source (or --no-claude-preinit)");

        // No files written.
        assertThat(layout.gitKeyOut()).doesNotExist();
        assertThat(layout.gitconfigOut()).doesNotExist();
        assertThat(layout.ghTokenOut()).doesNotExist();
        // No shell-outs.
        assertThat(runner.captureCalls).isEmpty();
        assertThat(runner.inheritCalls).isEmpty();
    }

    @Test
    void noTty_withPartialOptOuts_listsOnlyTheStillMissingFlags(@TempDir Path tmp) throws Exception {
        // --no-gh and --no-claude-preinit excuse the gh / claude flags.
        // Only the three core flags should remain in the missing list.
        Layout layout = layout(tmp);
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = false;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                args(layout, "--no-gh", "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        String stderr = errBuf.toString();
        assertThat(stderr).contains("--git-key").contains("--git-name").contains("--git-email");
        assertThat(stderr)
                .as("opt-out flags excuse their own step from the missing list")
                .doesNotContain("--gh-token-file")
                .doesNotContain("--claude-config-source");
    }

    // ── AC13 / AC14 — refuse-without-force, conflict listing ──────

    @Test
    void refusesWhenUC05ManualDropAlreadyPresent_listsAllFourConflictPaths(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        Path srcKey = writeKey(tmp, "src-key");

        // UC05 manual-drop fixture: all four outputs already populated
        // by an operator's hand-rolled install. UC06 must refuse to
        // overwrite without --force AND surface every conflict path.
        Files.createDirectories(layout.secretsDir());
        Files.createDirectories(layout.claudeOut());
        Files.writeString(layout.gitKeyOut(), "uc05-key");
        Files.writeString(layout.gitconfigOut(), "uc05-gitconfig");
        Files.writeString(layout.ghTokenOut(), "uc05-gh-token");
        Files.writeString(layout.claudeOut().resolve("settings.json"), "uc05-claude");

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "Alice",
                        "--git-email",
                        "alice@example.com",
                        "--no-gh", // opt-outs MUST NOT shrink the conflict set
                        "--no-claude-preinit"),
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        String stderr = errBuf.toString();
        assertThat(stderr).contains("refusing to overwrite").contains("--force");

        // AC13 — all four conflict paths surfaced.
        assertThat(stderr).contains("conflict: " + layout.gitKeyOut());
        assertThat(stderr).contains("conflict: " + layout.gitconfigOut());
        assertThat(stderr).contains("conflict: " + layout.ghTokenOut());
        assertThat(stderr).contains("conflict: " + layout.claudeOut());

        // Original UC05 content untouched.
        assertThat(Files.readString(layout.gitKeyOut())).isEqualTo("uc05-key");
        assertThat(Files.readString(layout.gitconfigOut())).isEqualTo("uc05-gitconfig");
        assertThat(Files.readString(layout.ghTokenOut())).isEqualTo("uc05-gh-token");
        assertThat(Files.readString(layout.claudeOut().resolve("settings.json")))
                .isEqualTo("uc05-claude");
    }

    @Test
    void emptyClaudeOutDir_isNot_a_conflict(@TempDir Path tmp) throws Exception {
        // An empty claudeOut dir is a no-op mount target — NOT a
        // conflict. The orchestrator's isEmptyDir() check enforces this.
        Layout layout = layout(tmp);
        Path srcKey = writeKey(tmp, "src-key");
        Files.createDirectories(layout.claudeOut());

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
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

        // No conflict — run succeeds.
        assertThat(exit).isZero();
    }

    @Test
    void forceOverwritesAllFourTargets(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        Path srcKey = writeKey(tmp, "src-key");
        Path ghToken = tmp.resolve("gh-pat");
        Files.writeString(ghToken, "<fresh-token>\n");
        Path claudeSrc = onboardedClaudeSource(tmp.resolve("src-claude"), "{\"new\":true}");

        // Seed stale content at all four targets — simulating a prior
        // run that should be overwritten on --force.
        Files.createDirectories(layout.secretsDir());
        Files.createDirectories(layout.claudeOut());
        Files.writeString(layout.gitKeyOut(), "STALE");
        Files.writeString(layout.gitconfigOut(), "STALE");
        Files.writeString(layout.ghTokenOut(), "STALE");
        Files.writeString(layout.claudeOut().resolve("settings.json"), "STALE");
        Files.writeString(layout.claudeOut().resolve("orphan.txt"), "should-be-overwritten-or-replaced");

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "Bob",
                        "--git-email",
                        "bob@example.com",
                        "--gh-token-file",
                        ghToken.toString(),
                        "--claude-config-source",
                        claudeSrc.toString(),
                        "--force"),
                outBuf,
                errBuf);

        assertThat(exit).isZero();

        // All four targets overwritten with fresh content.
        assertThat(Files.readAllBytes(layout.gitKeyOut())).isEqualTo(KEY_BYTES);
        assertThat(Files.readString(layout.gitconfigOut()))
                .isEqualTo("[user]\n\tname = Bob\n\temail = bob@example.com\n");
        assertThat(Files.readString(layout.ghTokenOut())).isEqualTo("<fresh-token>\n");
        // UC-19: re-seeded from the onboarded source; part E merged the agent-teams keys.
        assertThat(layout.claudeOut().resolve(".claude.json")).exists();
        assertAgentTeamsMerged(layout.claudeOut().resolve("settings.json"), "new");

        // stderr enumerated the overwritten paths.
        String stderr = errBuf.toString();
        assertThat(stderr).contains("--force given; overwriting");
        assertThat(stderr).contains("overwrite: " + layout.gitKeyOut());
        assertThat(stderr).contains("overwrite: " + layout.gitconfigOut());
        assertThat(stderr).contains("overwrite: " + layout.ghTokenOut());
        assertThat(stderr).contains("overwrite: " + layout.claudeOut());
    }

    // ── AC15 — no writes under /opt/ai-sandbox-server/ ────────────

    @Test
    void neverWritesUnderInstallDir_rejectsSecretsDirUnderOpt(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                new String[] {
                    "--secrets-dir",
                    "/opt/ai-sandbox-server/secrets",
                    "--templates-dir",
                    layout.templatesDir().toString(),
                    "--install-dir",
                    layout.installDir().toString(),
                    "--no-gh",
                    "--no-claude-preinit",
                },
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        String stderr = errBuf.toString();
        assertThat(stderr)
                .contains("cannot live under")
                .contains("/opt/ai-sandbox-server")
                .contains("install dir is read-only");
        // Guard fires before any file ops — secrets/templates dirs
        // under the tempdir layout MUST NOT have been created.
        assertThat(layout.secretsDir()).doesNotExist();
        assertThat(layout.templatesDir()).doesNotExist();
    }

    @Test
    void neverWritesUnderInstallDir_rejectsTemplatesDirUnderOpt(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                new String[] {
                    "--secrets-dir",
                    layout.secretsDir().toString(),
                    "--templates-dir",
                    "/opt/ai-sandbox-server/templates",
                    "--install-dir",
                    layout.installDir().toString(),
                    "--no-gh",
                    "--no-claude-preinit",
                },
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        assertThat(errBuf.toString()).contains("cannot live under").contains("/opt/ai-sandbox-server");
    }

    // ── AC9 — fixed step order ────────────────────────────────────

    @Test
    void runsStepsInFixedOrder_sshThenIdentityThenGhThenClaude(@TempDir Path tmp) throws Exception {
        // Mixed-flag run: SSH + identity flag-driven, gh + claude
        // interactive. needsDocker=true so EnsureSandboxImage probes
        // before step (a); we get a clear capture sequence:
        //   1) docker image inspect    (EnsureSandboxImage)
        //   2) ssh-keygen -y …         (SshKeyStep encryption probe)
        // and an inherit sequence:
        //   1) docker run ... gh auth   (GhTokenStep)
        //   2) docker run ... claude    (ClaudePreInitStep)
        //
        // Plus, when each interactive step fires we assert that the
        // outputs of all earlier steps already exist on disk — proving
        // the strict ssh→identity→gh→claude sequencing.
        Layout layout = layout(tmp);
        Path srcKey = writeKey(tmp, "src-key");
        List<String> orderViolations = new ArrayList<>();

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, ""); // image present + key unencrypted
        runner.inheritResponse = argv -> {
            String shellCmd = argv.contains("-c") ? argv.get(argv.indexOf("-c") + 1) : "";
            try {
                if (shellCmd.contains("gh auth")) {
                    // GhTokenStep starts — SSH + Identity outputs MUST already exist.
                    if (!Files.exists(layout.gitKeyOut())) {
                        orderViolations.add("gh-step started before git-key existed");
                    }
                    if (!Files.exists(layout.gitconfigOut())) {
                        orderViolations.add("gh-step started before gitconfig existed");
                    }
                    if (Files.exists(layout.ghTokenOut())) {
                        orderViolations.add("gh-step started but gh-token already existed");
                    }
                    // Simulate captured token landing in the bind-mount.
                    Files.writeString(layout.ghTokenOut(), "<fixture-gh-token>\n");
                    return 0;
                }
                if (shellCmd.contains("claude")) {
                    // ClaudePreInitStep starts — SSH + Identity + Gh outputs MUST exist.
                    if (!Files.exists(layout.gitKeyOut())) {
                        orderViolations.add("claude-step started before git-key existed");
                    }
                    if (!Files.exists(layout.gitconfigOut())) {
                        orderViolations.add("claude-step started before gitconfig existed");
                    }
                    if (!Files.exists(layout.ghTokenOut())) {
                        orderViolations.add("claude-step started before gh-token existed");
                    }
                    // Simulate Claude CLI populating the scratch with the onboarded
                    // state the UC-19 value check requires (the sibling ~/.claude.json
                    // redirected into the mount + the login token).
                    int vIdx = argv.indexOf("-v");
                    Path scratch = Path.of(argv.get(vIdx + 1).split(":")[0]);
                    Files.writeString(scratch.resolve(".claude.json"), ONBOARDED_CLAUDE_JSON);
                    Files.writeString(scratch.resolve(".credentials.json"), CREDENTIALS_JSON);
                    return 0;
                }
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };

        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--git-key",
                        srcKey.toString(),
                        "--git-name",
                        "Alice",
                        "--git-email",
                        "alice@example.com"
                        // gh + claude both go interactive
                        ),
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        assertThat(orderViolations)
                .as("AC9 — fixed step order ssh → identity → gh → claude")
                .isEmpty();

        // EnsureSandboxImage ran BEFORE SshKeyStep's probe.
        int ensureIdx = indexOfCapture(runner, "docker", "image");
        int sshIdx = indexOfCapture(runner, "ssh-keygen", "-y");
        assertThat(ensureIdx)
                .as("docker image inspect (ensure-image) must fire when needsDocker=true")
                .isGreaterThanOrEqualTo(0);
        assertThat(sshIdx).isGreaterThan(ensureIdx);

        // Inherit-IO sequence: gh first, claude second.
        int ghIdx = indexOfInheritShell(runner, "gh auth");
        int claudeIdx = indexOfInheritShell(runner, "claude --dangerously");
        assertThat(ghIdx).isGreaterThanOrEqualTo(0);
        assertThat(claudeIdx).isGreaterThan(ghIdx);

        // All four outputs landed.
        assertThat(layout.gitKeyOut()).exists();
        assertThat(layout.gitconfigOut()).exists();
        assertThat(layout.ghTokenOut()).exists();
        assertThat(layout.claudeOut().resolve("settings.json")).exists();
    }

    // ── AC22 — stdout summary ─────────────────────────────────────

    @Test
    void outputSummary_includesAllFourTargets_withModeAndOwner(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        Path srcKey = writeKey(tmp, "src-key");
        Path ghToken = tmp.resolve("gh-pat");
        Files.writeString(ghToken, "<fixture-token>\n");
        Path claudeSrc = onboardedClaudeSource(tmp.resolve("src-claude"), "{}");

        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
                args(
                        layout,
                        "--user",
                        "ai-sandbox-server",
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
        // Header + system user.
        assertThat(stdout).contains("aisandboxctl secrets seed: complete.");
        assertThat(stdout).contains("user").contains("ai-sandbox-server");
        // Per-target lines with the target path + mode 0600 / 0750.
        assertThat(stdout).contains(layout.gitKeyOut().toString()).contains("(mode rw-------)");
        assertThat(stdout).contains(layout.gitconfigOut().toString());
        assertThat(stdout).contains(layout.ghTokenOut().toString());
        assertThat(stdout).contains(layout.claudeOut().toString());
        // Claude dir is mode 0750.
        assertThat(stdout).contains("(mode rwxr-x---");
    }

    @Test
    void outputSummary_marks_skipped_steps_when_opted_out(@TempDir Path tmp) throws Exception {
        Layout layout = layout(tmp);
        Path srcKey = writeKey(tmp, "src-key");
        FakeProcessRunner runner = permissiveRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                new CommandLine(seed(runner, io, tmp.resolve("ssh-dir"))),
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
        String stdout = outBuf.toString();
        assertThat(stdout).contains("gh-token").contains("(skipped via --no-gh)");
        assertThat(stdout).contains("claude").contains("(skipped via --no-claude-preinit");
    }

    // ── helpers ───────────────────────────────────────────────────

    private static int indexOfCapture(FakeProcessRunner runner, String first, String second) {
        for (int i = 0; i < runner.captureCalls.size(); i++) {
            List<String> c = runner.captureCalls.get(i);
            if (c.size() >= 2 && first.equals(c.get(0)) && second.equals(c.get(1))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfInheritShell(FakeProcessRunner runner, String shellSubstring) {
        for (int i = 0; i < runner.inheritCalls.size(); i++) {
            List<String> c = runner.inheritCalls.get(i);
            int cIdx = c.indexOf("-c");
            if (cIdx >= 0 && cIdx + 1 < c.size() && c.get(cIdx + 1).contains(shellSubstring)) {
                return i;
            }
        }
        return -1;
    }
}
