package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.cli.secrets.DevToolsConfig;
import com.aisandbox.server.cli.secrets.FakeConsoleIO;
import com.aisandbox.server.cli.secrets.FakeProcessRunner;
import com.aisandbox.server.cli.secrets.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * UC-26 — coverage for the {@code aisandboxctl reconfigure} subcommand.
 *
 * <p>The command has two roles documented in the proposal:
 *
 * <ul>
 *   <li><b>Bare</b> — interactive picker. Reads the persisted
 *       {@code .ai-sandbox-devtools} ledger, renders the checklist via
 *       {@link com.aisandbox.server.cli.secrets.DevToolsStep}, persists
 *       the result. AC#4 — re-run path; AC#7 — propagates to NEW sessions
 *       only.</li>
 *   <li><b>--doctor</b> — runs {@code aisandbox-dind doctor} inside
 *       enumerated sessions. AC#9 verification (a) — confirms the
 *       rootless daemon is healthy in each running session.</li>
 * </ul>
 *
 * <p>The harness uses {@link FakeProcessRunner} / {@link FakeConsoleIO}
 * test seams via {@link ReconfigureCommand#setProcessRunner} /
 * {@link ReconfigureCommand#setConsoleIO}, mirroring the other CLI tests
 * in this package. No real {@code docker} invocations happen.
 *
 * <p><b>Root-check parity (fix-back Round 1).</b> {@link ReconfigureCommand}
 * now mirrors {@code OnboardCommand}: a {@code setRootCheck(BooleanSupplier)}
 * seam gates the interactive ledger-writing picker behind a root probe
 * ({@code isPosix() && !rootCheck} ⇒ exit 2 + "must run as root"), while the
 * read-only {@code --doctor} path returns <i>before</i> the guard so a
 * non-root operator in the {@code docker} group can still run diagnostics.
 * Because this host runs as a non-root user, the default real {@code isRoot()}
 * probe would now trip the guard for every bare-path test, so {@link #cmd}
 * stubs the probe to {@code true} (simulating root) — exactly as
 * {@code OnboardCommandTest.onboard()} does. The dedicated guard behaviour is
 * exercised by the {@code root_check_*} cases below, which override the seam.
 */
class ReconfigureCommandTest {

    /**
     * Build a {@link CommandLine} with the test seams pre-wired, simulating a
     * root invocation (the common case for the bare ledger-writing picker).
     */
    private static CommandLine cmd(FakeProcessRunner runner, FakeConsoleIO io) {
        return cmd(runner, io, /* root */ true);
    }

    /** Build a {@link CommandLine} with the root-probe seam stubbed to {@code root}. */
    private static CommandLine cmd(FakeProcessRunner runner, FakeConsoleIO io, boolean root) {
        ReconfigureCommand c = new ReconfigureCommand();
        c.setProcessRunner(runner);
        c.setConsoleIO(io);
        c.setRootCheck(() -> root);
        return new CommandLine(c);
    }

    /** Permissive runner — every shell-out succeeds with empty output. */
    private static FakeProcessRunner okRunner() {
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

    // ── Subcommand registration ─────────────────────────────────────

    @Test
    void reconfigure_is_a_subcommand_of_aisandboxctl() {
        // AC#4 — `aisandboxctl reconfigure` is the install-mode equivalent
        // of `./setup.sh --reconfigure`. The subcommand MUST be reachable
        // from the parent command's `aisandboxctl` aggregator (via the
        // `@Command(subcommands = ...)` annotation on AisandboxctlCommand).
        CommandLine parent = new CommandLine(new AisandboxctlCommand());
        CommandLine sub = parent.getSubcommands().get("reconfigure");
        assertThat(sub)
                .as("AC#4 — `aisandboxctl reconfigure` MUST be a registered subcommand")
                .isNotNull();
        // Picocli's `getCommand()` returns `Object`, which is ambiguous against
        // AssertJ's `assertThat(IntPredicate)` / `assertThat(Predicate<T>)`
        // overloads — cast through `Object` to land on the Object overload.
        Object backing = sub.getCommand();
        assertThat(backing).isInstanceOf(ReconfigureCommand.class);
    }

    @Test
    void usage_help_mentions_both_roles() {
        // Usage discoverability — the description text must mention both
        // the bare interactive role and the --doctor diagnostic role so
        // operators reading `aisandboxctl reconfigure --help` find both.
        ReconfigureCommand c = new ReconfigureCommand();
        String usage = new CommandLine(c).getUsageMessage();
        assertThat(usage).contains("--doctor");
        // Also covers the parent class's intent of being a re-run picker.
        assertThat(usage.toLowerCase()).contains("checklist");
    }

    // ── Bare path — interactive picker ──────────────────────────────

    @Test
    void bare_run_with_tty_writes_ledger_via_devtools_step(@TempDir Path tmp) throws Exception {
        // Drive the wizard interactively: toggle dind on, confirm the
        // warning, commit. The persisted ledger must contain dind.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);

        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("1"); // toggle dind on
        io.inputLines.add("y"); // confirm the trust-boundary warning
        io.inputLines.add(""); // commit

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(okRunner(), io), new String[] {"--sessions-dir", sessionsDir.toString()}, outBuf, errBuf);

        assertThat(exit).isZero();

        // Ledger persisted under sessions-dir.
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");
        assertThat(ledger).exists();
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
    }

    @Test
    void bare_run_pre_fills_with_current_ledger_state(@TempDir Path tmp) throws Exception {
        // AC#4 — re-run jumps with current state pre-selected. Seed dind
        // ON, then run reconfigure and commit immediately (no toggles) →
        // ledger stays as-was.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);

        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add(""); // commit immediately

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(okRunner(), io), new String[] {"--sessions-dir", sessionsDir.toString()}, outBuf, errBuf);

        assertThat(exit).isZero();
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");

        // The rendered checklist must reflect the pre-selected state.
        boolean preSelectedRow = io.printed.stream().anyMatch(line -> line.contains("[x]"));
        assertThat(preSelectedRow)
                .as("AC#4 — re-run MUST pre-fill the checklist with the current state")
                .isTrue();
    }

    // ── Non-TTY deferral ────────────────────────────────────────────

    @Test
    void bare_run_without_tty_defers_with_exit_2_and_re_run_instruction(@TempDir Path tmp) throws Exception {
        // The bare path is interactive-only; under no-TTY it MUST signal
        // a non-zero exit (so an automation invoking it knows to retry
        // from a terminal) AND emit a concrete re-run instruction.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);

        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = false;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(okRunner(), io), new String[] {"--sessions-dir", sessionsDir.toString()}, outBuf, errBuf);

        assertThat(exit).isEqualTo(2);
        String all = io.allOutput();
        assertThat(all).contains("terminal");
        assertThat(all).contains("aisandboxctl reconfigure");

        // The ledger MUST NOT have been touched.
        assertThat(sessionsDir.resolve(".ai-sandbox-devtools")).doesNotExist();
    }

    @Test
    void no_devtools_flag_short_circuits_without_writing_ledger(@TempDir Path tmp) throws Exception {
        // --no-devtools is the explicit opt-out; bare reconfigure with the
        // flag should print a "ledger left untouched" line and exit 0.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);

        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(okRunner(), io),
                new String[] {"--sessions-dir", sessionsDir.toString(), "--no-devtools"},
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        assertThat(io.allOutput()).contains("ledger left untouched");
        assertThat(sessionsDir.resolve(".ai-sandbox-devtools")).doesNotExist();
    }

    // ── Root check (fix-back Round 1, parity with OnboardCommand) ────

    @Test
    void non_root_interactive_picker_exits_2_and_does_not_write_ledger(@TempDir Path tmp) throws Exception {
        // Fix-back #2 — the interactive picker writes the ledger under
        // <sessions-dir> (/var/lib/ai-sandbox-server/sessions in install
        // mode, owned by ai-sandbox-server mode 0750). A non-root operator's
        // Files.write would otherwise surface as a raw EACCES NIO exception;
        // the guard MUST instead print the friendly "must run as root" line
        // and exit 2 BEFORE the picker runs — so no ledger is written and no
        // checklist is even rendered. Mirrors OnboardCommandTest's
        // root_check_blocks_when_uid_not_zero.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);

        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        // Script picker input that MUST NOT be consumed (the guard fires first).
        io.inputLines.add("1");
        io.inputLines.add("y");
        io.inputLines.add("");

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(okRunner(), io, /* root */ false),
                new String[] {"--sessions-dir", sessionsDir.toString()},
                outBuf,
                errBuf);

        assertThat(exit).isEqualTo(2);
        // The friendly message lands on stderr (System.err, not ConsoleIO).
        assertThat(errBuf.toString()).contains("must run as root").contains("sudo");
        // Ledger untouched — the write path never executed.
        assertThat(sessionsDir.resolve(".ai-sandbox-devtools")).doesNotExist();
        // The picker never ran: no checklist rendered, scripted input untouched.
        assertThat(io.printed).isEmpty();
        assertThat(io.inputLines).hasSize(3);
    }

    @Test
    void root_interactive_picker_proceeds_and_writes_ledger(@TempDir Path tmp) throws Exception {
        // Fix-back #2 — the complement: with the root probe stubbed true the
        // guard is skipped and the picker runs to completion, persisting the
        // ledger. (Same wiring the bare-path tests rely on via cmd().)
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);

        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("1"); // toggle dind on
        io.inputLines.add("y"); // confirm the trust-boundary warning
        io.inputLines.add(""); // commit

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(okRunner(), io, /* root */ true),
                new String[] {"--sessions-dir", sessionsDir.toString()},
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        assertThat(errBuf.toString()).doesNotContain("must run as root");
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");
        assertThat(ledger).exists();
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
    }

    @Test
    void non_root_doctor_path_is_not_gated_by_root_check(@TempDir Path tmp) throws Exception {
        // Fix-back #2 — DEVIATION (justified): --doctor is read-only (it only
        // shells `docker compose exec … aisandbox-dind doctor`), and a
        // non-root operator in the docker group can legitimately run it. So
        // the guard must NOT gate --doctor: a non-root invocation runs the
        // diagnostic and returns its exit code, never the root-rejection 2.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);

        FakeProcessRunner runner = okRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(runner, io, /* root */ false),
                new String[] {"--sessions-dir", sessionsDir.toString(), "--doctor", "--session", "1"},
                outBuf,
                errBuf);

        // Read-only path runs despite non-root: exit 0 (runner is permissive),
        // never the root-rejection exit 2, and no "must run as root" message.
        assertThat(exit).isZero();
        assertThat(errBuf.toString()).doesNotContain("must run as root");
        // The diagnostic actually shelled out (proves we passed the guard).
        assertThat(runner.captureCalls).hasSize(1);
        assertThat(runner.captureCalls.get(0)).contains("aisandbox-dind", "doctor");
    }

    // ── --doctor path ───────────────────────────────────────────────

    @Test
    void doctor_with_no_ledger_reports_nothing_to_inspect(@TempDir Path tmp) throws Exception {
        // No persisted ledger → no devtools enabled → --doctor short-
        // circuits with a "DinD not enabled" message and exit 0 (no docker
        // shell-outs).
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);

        FakeProcessRunner runner = okRunner();
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(runner, io), new String[] {"--sessions-dir", sessionsDir.toString(), "--doctor"}, outBuf, errBuf);

        assertThat(exit).isZero();
        assertThat(io.allOutput()).contains("DinD is not enabled");
        // No docker shell-outs.
        assertThat(runner.captureCalls).isEmpty();
    }

    @Test
    void doctor_with_dind_enabled_and_target_session_shells_aisandbox_dind_doctor(@TempDir Path tmp) throws Exception {
        // With dind enabled in the ledger AND --session N, --doctor must
        // shell `docker compose -p ai-sandbox-N exec -T claude-sandbox
        // aisandbox-dind doctor` exactly once. The runner is permissive
        // (exit 0 + empty output), so the command itself exits 0.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);

        FakeProcessRunner runner = okRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(runner, io),
                new String[] {"--sessions-dir", sessionsDir.toString(), "--doctor", "--session", "1"},
                outBuf,
                errBuf);

        assertThat(exit).isZero();
        // Exactly one docker shell-out, with the expected argv.
        assertThat(runner.captureCalls).hasSize(1);
        List<String> argv = runner.captureCalls.get(0);
        assertThat(argv)
                .containsExactly(
                        "docker",
                        "compose",
                        "-p",
                        "ai-sandbox-1",
                        "exec",
                        "-T",
                        "claude-sandbox",
                        "aisandbox-dind",
                        "doctor");
        assertThat(io.allOutput()).contains("ai-sandbox-1");
    }

    @Test
    void doctor_propagates_worst_exit_code_across_sessions(@TempDir Path tmp) throws Exception {
        // Enumerate two ai-sandbox-* projects (via the fake's docker
        // compose ls JSON output). The first --doctor target reports
        // healthy (0), the second reports unhealthy (5). The command's
        // exit code MUST be the worst (5) so an automation watching for
        // a non-zero exit notices the unhealthy session.
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> {
            // docker compose ls --format json → return two ai-sandbox
            // projects in NDJSON-equivalent array form.
            if (argv.contains("ls") && argv.contains("--format")) {
                return new ProcessRunner.Result(
                        0,
                        "["
                                + "{\"Name\":\"ai-sandbox-1\",\"Status\":\"running\"},"
                                + "{\"Name\":\"ai-sandbox-2\",\"Status\":\"running\"}]");
            }
            // The doctor invocation for ai-sandbox-1 succeeds; ai-sandbox-2 fails.
            if (argv.contains("ai-sandbox-1")) {
                return new ProcessRunner.Result(0, "rootless daemon healthy");
            }
            if (argv.contains("ai-sandbox-2")) {
                return new ProcessRunner.Result(5, "doctor: rootless daemon not running");
            }
            return new ProcessRunner.Result(0, "");
        };
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(runner, io), new String[] {"--sessions-dir", sessionsDir.toString(), "--doctor"}, outBuf, errBuf);

        assertThat(exit).isEqualTo(5);
        // The output reflects both projects.
        assertThat(io.allOutput()).contains("ai-sandbox-1").contains("ai-sandbox-2");
    }

    @Test
    void doctor_with_no_running_projects_returns_zero(@TempDir Path tmp) throws Exception {
        // Enumerate returns []; --doctor prints "no projects running" and
        // exits 0 (no projects to inspect != unhealthy).
        Path sessionsDir = tmp.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> {
            if (argv.contains("ls") && argv.contains("--format")) {
                return new ProcessRunner.Result(0, "[]");
            }
            return new ProcessRunner.Result(0, "");
        };
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int exit = executeCapturing(
                cmd(runner, io), new String[] {"--sessions-dir", sessionsDir.toString(), "--doctor"}, outBuf, errBuf);

        assertThat(exit).isZero();
        assertThat(io.allOutput()).contains("No ai-sandbox-*");
    }
}
