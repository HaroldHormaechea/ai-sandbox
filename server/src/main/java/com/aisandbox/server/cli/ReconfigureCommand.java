package com.aisandbox.server.cli;

import com.aisandbox.server.cli.secrets.ConsoleIO;
import com.aisandbox.server.cli.secrets.DevToolsConfig;
import com.aisandbox.server.cli.secrets.DevToolsStep;
import com.aisandbox.server.cli.secrets.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * UC-26 — {@code aisandboxctl reconfigure}.
 *
 * <p>The Java side of the "Select the development tools you want to install"
 * step. Two roles:
 *
 * <ul>
 *   <li>Bare (no flags) — interactive devtools picker. Reads the persisted
 *       ledger at {@code <sessions-dir>/.ai-sandbox-devtools}, renders the
 *       plain numbered checklist via {@link DevToolsStep}, prompts the
 *       inline trust-boundary confirmation when enabling a new spawn-time
 *       capability, and writes the updated ledger back. This is the
 *       install-mode equivalent of {@code ./setup.sh --reconfigure}.</li>
 *   <li>{@code --doctor} — diagnostic mode. For each enumerated /
 *       {@code --session} session, shells out to
 *       {@code docker compose ... exec aisandbox-dind doctor} so the
 *       operator can verify a DinD-enabled session is healthy without
 *       attaching to it. AC#9 verification (a).</li>
 * </ul>
 *
 * <p>The ledger lives at {@code <sessions-dir>/.ai-sandbox-devtools}:
 * spawn.sh reads it from the same path (it cd's into
 * {@code AI_SANDBOX_HOST_STATE_ROOT} before resolving relative paths), so the
 * wizard's write path equals the spawn-time read path by construction. Under
 * Spring management {@code sessionsDir} is sourced from
 * {@code ServerProperties.sessions.hostStateRoot()}; the CLI-direct path
 * (used by the {@code .deb} postinst) falls back to
 * {@code /var/lib/ai-sandbox-server/sessions}.
 *
 * <p>UC06 § AC25 install-time CLI exemption applies — this is a thin shell
 * over file I/O and (optionally) {@code docker compose exec}.
 */
@Command(
        name = "reconfigure",
        mixinStandardHelpOptions = true,
        description = {
            "Interactive picker for the per-host development-tools selection.",
            "",
            "Bare:        render the checklist, toggle entries, persist the ledger.",
            "  --doctor:  run `aisandbox-dind doctor` inside enumerated sessions",
            "             and print the output. Use --session <N> to target one.",
            "",
            "Re-run any time. Changes propagate to NEW sessions only — already-",
            "running sessions are unaffected by this command."
        })
public class ReconfigureCommand implements Callable<Integer> {

    @Option(
            names = "--sessions-dir",
            description = "Per-session host-state root (default ${DEFAULT-VALUE})."
                    + " Spring-managed runs source this from ServerProperties.sessions.hostStateRoot();"
                    + " the CLI-direct fallback is the value below.")
    Path sessionsDir = Path.of("/var/lib/ai-sandbox-server/sessions");

    @Option(
            names = "--doctor",
            description =
                    "Skip the interactive picker. Instead, exec `aisandbox-dind doctor` into each enumerated session"
                            + " and print the output.")
    boolean doctor;

    @Option(
            names = "--session",
            description = "When used with --doctor: only target session <N> (the ai-sandbox-<N> compose project).")
    Integer sessionN;

    @Option(
            names = "--no-devtools",
            description = "Forward to DevToolsStep so the picker short-circuits (rarely useful for the bare CLI;"
                    + " kept for parity with OnboardCommand --no-devtools).")
    boolean noDevtools;

    // ── test seams ─────────────────────────────────────────────────────────

    private ProcessRunner processRunner = new ProcessRunner.Default();
    private ConsoleIO consoleIO = new ConsoleIO.Default();

    /** Test seam — inject a fake {@link ProcessRunner}. */
    void setProcessRunner(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    /** Test seam — inject a fake {@link ConsoleIO}. */
    void setConsoleIO(ConsoleIO consoleIO) {
        this.consoleIO = consoleIO;
    }

    @Override
    public Integer call() throws Exception {
        Path ledger = sessionsDir.resolve(".ai-sandbox-devtools");

        if (doctor) {
            return runDoctor(ledger);
        }

        // Interactive devtools picker. We don't pass an Ownership here — the
        // ledger lives under sessionsDir which is already owned by
        // ai-sandbox-server in install mode (created by postinst). If the
        // file is missing, the step creates it at the right mode.
        DevToolsStep step = new DevToolsStep(consoleIO, processRunner);
        DevToolsStep.Outcome outcome = step.run(ledger, noDevtools, consoleIO.hasTty(), null);
        switch (outcome) {
            case SKIPPED:
                consoleIO.println("  reconfigure: --no-devtools set; ledger left untouched.");
                break;
            case DEFERRED:
                consoleIO.println("  reconfigure: no terminal — interactive picker needs a TTY.");
                consoleIO.println("  Re-run `sudo aisandboxctl reconfigure` from a terminal.");
                return 2;
            case APPLIED:
                // The step printed its own summary.
                break;
        }
        return 0;
    }

    /**
     * {@code --doctor} body: enumerate (or filter to) ai-sandbox-* sessions
     * and shell {@code aisandbox-dind doctor} into each. We don't fail the
     * whole invocation when one session is unhealthy — every result is
     * printed and the worst exit code is returned, so the operator sees the
     * full picture (AC#9 verification (a)).
     */
    private int runDoctor(Path ledger) throws IOException, InterruptedException {
        List<String> enabled = DevToolsStep.currentlyEnabled(ledger);
        consoleIO.println("  reconfigure --doctor");
        consoleIO.println("  ledger          : " + ledger
                + (Files.isRegularFile(ledger) ? "" : " (missing — no devtools persisted yet)"));
        consoleIO.println("  enabled devtools: " + (enabled.isEmpty() ? "(none)" : String.join(", ", enabled)));
        consoleIO.println("");

        if (!enabled.contains("dind")) {
            consoleIO.println(
                    "  DinD is not enabled. --doctor is currently DinD-specific; nothing else to inspect today.");
            return 0;
        }

        int worst = 0;
        if (sessionN != null) {
            worst = doctorOne("ai-sandbox-" + sessionN);
        } else {
            List<String> projects = enumerateProjects();
            if (projects.isEmpty()) {
                consoleIO.println("  No ai-sandbox-* compose projects are currently running.");
                return 0;
            }
            for (String project : projects) {
                int rc = doctorOne(project);
                if (rc > worst) {
                    worst = rc;
                }
            }
        }
        return worst;
    }

    /**
     * Shell {@code aisandbox-dind doctor} into one compose project. The exec
     * is best-effort; an offline session reports its own error and we keep
     * going.
     */
    private int doctorOne(String project) throws IOException, InterruptedException {
        consoleIO.println("─── " + project + " ────────────────────────────────────────");
        ProcessRunner.Result r = processRunner.runAndCapture(List.of(
                "docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", "aisandbox-dind", "doctor"));
        consoleIO.println(r.output());
        if (r.exitCode() != 0) {
            consoleIO.println("  (exit=" + r.exitCode() + " — session may be down or DinD not started yet)");
        }
        return r.exitCode();
    }

    /**
     * Enumerate {@code ai-sandbox-N} compose projects via
     * {@code docker compose ls --format json}. We tolerate either a JSON
     * array (newer Compose) or NDJSON (older) without pulling in a JSON
     * library — the format is shallow enough that a regex is sufficient
     * for the {@code "Name":"ai-sandbox-N"} extraction.
     */
    private List<String> enumerateProjects() throws IOException, InterruptedException {
        ProcessRunner.Result r = processRunner.runAndCapture(List.of("docker", "compose", "ls", "--format", "json"));
        if (r.exitCode() != 0) {
            consoleIO.println("  docker compose ls failed (exit=" + r.exitCode() + "): " + r.stdoutTrim());
            return List.of();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"Name\"\\s*:\\s*\"(ai-sandbox-\\d+)\"")
                .matcher(r.output());
        java.util.LinkedHashSet<String> projects = new java.util.LinkedHashSet<>();
        while (m.find()) {
            projects.add(m.group(1));
        }
        return new java.util.ArrayList<>(projects);
    }

    /**
     * Convenience for callers that want to know whether anything would be
     * applied without invoking the step — e.g. a future "is the ledger in
     * sync with what the running sessions actually have" check. Defined
     * here (rather than in {@link DevToolsConfig}) so the static-only
     * config class stays free of higher-level orchestration helpers.
     */
    @SuppressWarnings("unused")
    private static long pendingChangeCount(Path ledger) throws IOException {
        return DevToolsConfig.readEnabled(ledger).size();
    }

    /** Reserved process timeout (used only by future async helpers). */
    @SuppressWarnings("unused")
    private static final long PROCESS_TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(5);
}
