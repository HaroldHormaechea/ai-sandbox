package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-27 — coverage for {@link DevToolsStep}, the install-time delegation wrapper
 * around the shared pure-shell raw-mode selector.
 *
 * <p>Post-UC-27 the interactive picker lives in exactly one place: the shell
 * script {@code devtools-select.sh} (AC#1, AC#4). {@link DevToolsStep} no longer
 * renders a checklist or writes the ledger itself — it shells out to the selector
 * via {@link ProcessRunner#runInheritIO} (raw-mode TTY requires inherited
 * stdio; {@code runAndCapture} would pipe stdio and break the cursor UI) and maps
 * the selector's exit code onto its {@link DevToolsStep.Outcome}:
 *
 * <ul>
 *   <li>{@code --no-devtools} → {@link DevToolsStep.Outcome#SKIPPED} (no spawn).</li>
 *   <li>no TTY → {@link DevToolsStep.Outcome#DEFERRED} <i>before</i> spawning
 *       (a raw-mode selector cannot run headless — the pitfall called out in the
 *       use case).</li>
 *   <li>selector missing → {@link DevToolsStep.Outcome#DEFERRED} with a "run
 *       ./setup.sh on the host" hint.</li>
 *   <li>selector exits 0 → {@link DevToolsStep.Outcome#APPLIED}.</li>
 *   <li>selector exits non-zero (130 cancel, 3 no-TTY, 1 internal) →
 *       {@link DevToolsStep.Outcome#DEFERRED} (degrade-to-DEFERRED; the ledger
 *       is left in whatever state the selector left it).</li>
 * </ul>
 *
 * <p>The selector itself is faked through {@link FakeProcessRunner#inheritResponse}:
 * tests script its exit code (and, where they assert persistence, the ledger
 * side-effect a real selector would have produced on commit).
 */
class DevToolsStepTest {

    /** Materialise a stand-in selector file so {@code Files.isRegularFile} passes. */
    private static Path selector(Path dir) throws IOException {
        Path s = dir.resolve("devtools-select.sh");
        Files.writeString(s, "#!/usr/bin/env bash\n# fake selector\n");
        return s;
    }

    // ── --no-devtools opt-out ───────────────────────────────────────

    @Test
    void no_devtools_flag_short_circuits_with_skipped_and_never_spawns(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Path selector = selector(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();

        // --no-devtools wins even with a TTY and a present selector.
        DevToolsStep.Outcome outcome = new DevToolsStep(new FakeConsoleIO(), runner)
                .run(ledger, selector, /* noDevtools */ true, /* hasTty */ true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.SKIPPED);
        assertThat(ledger).doesNotExist();
        assertThat(runner.inheritCalls)
                .as("--no-devtools MUST NOT spawn the selector")
                .isEmpty();
    }

    // ── Non-TTY deferral (headless install) ─────────────────────────

    @Test
    void no_tty_defers_before_spawning_the_selector(@TempDir Path tmp) throws IOException {
        // Pitfall (raw-mode robustness) — a cursor selector cannot run without
        // a terminal, so the step short-circuits to DEFERRED and never launches
        // the child. The caller is responsible for the "re-run from a TTY" hint.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Path selector = selector(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();

        DevToolsStep.Outcome outcome = new DevToolsStep(new FakeConsoleIO(), runner)
                .run(ledger, selector, /* noDevtools */ false, /* hasTty */ false, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.DEFERRED);
        assertThat(ledger).doesNotExist();
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── Missing selector ────────────────────────────────────────────

    @Test
    void missing_selector_file_defers_with_a_hint(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Path absent = tmp.resolve("does-not-exist.sh");
        FakeConsoleIO io = new FakeConsoleIO();
        FakeProcessRunner runner = new FakeProcessRunner();

        DevToolsStep.Outcome outcome = new DevToolsStep(io, runner).run(ledger, absent, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.DEFERRED);
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(io.allOutput()).contains("selector not found");
    }

    @Test
    void null_selector_path_defers_without_crashing(@TempDir Path tmp) throws IOException {
        // The null-guard short-circuits before Files.isRegularFile, so a null
        // selector path degrades cleanly to DEFERRED rather than NPE-ing.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        FakeProcessRunner runner = new FakeProcessRunner();

        DevToolsStep.Outcome outcome =
                new DevToolsStep(new FakeConsoleIO(), runner).run(ledger, null, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.DEFERRED);
        assertThat(runner.inheritCalls).isEmpty();
    }

    // ── Happy path — selector commits ───────────────────────────────

    @Test
    void selector_exit_zero_yields_applied_and_shells_bash_selector_ledger(@TempDir Path tmp) throws IOException {
        // AC#1/AC#14 — the step delegates to the raw-mode selector. The exact
        // argv MUST be `bash <selector> <ledger>` so the selector reads/writes
        // the right file. Exit 0 → APPLIED.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Path selector = selector(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> 0;

        DevToolsStep.Outcome outcome =
                new DevToolsStep(new FakeConsoleIO(), runner).run(ledger, selector, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        assertThat(runner.inheritCalls).hasSize(1);
        assertThat(runner.inheritCalls.get(0)).containsExactly("bash", selector.toString(), ledger.toString());
    }

    @Test
    void selector_commit_side_effect_is_visible_through_the_ledger(@TempDir Path tmp) throws IOException {
        // AC#4/AC#7 — the SHELL selector persists the ledger; the Java step does
        // not. Simulate the selector writing the enabled set on commit, then
        // confirm DevToolsConfig.readEnabled sees it.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Path selector = selector(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            try {
                // argv = [bash, <selector>, <ledger>]
                DevToolsConfig.writeEnabled(Path.of(argv.get(2)), Set.of("java", "android"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return 0;
        };

        DevToolsStep.Outcome outcome =
                new DevToolsStep(new FakeConsoleIO(), runner).run(ledger, selector, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactlyInAnyOrder("java", "android");
    }

    // ── Degrade-to-DEFERRED on non-zero exit ────────────────────────

    @Test
    void selector_cancel_exit_130_degrades_to_deferred(@TempDir Path tmp) throws IOException {
        assertDegradesToDeferred(tmp, 130);
    }

    @Test
    void selector_no_tty_exit_3_degrades_to_deferred(@TempDir Path tmp) throws IOException {
        assertDegradesToDeferred(tmp, 3);
    }

    @Test
    void selector_internal_error_exit_1_degrades_to_deferred(@TempDir Path tmp) throws IOException {
        assertDegradesToDeferred(tmp, 1);
    }

    private static void assertDegradesToDeferred(Path tmp, int selectorExit) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Path selector = selector(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> selectorExit;

        DevToolsStep.Outcome outcome =
                new DevToolsStep(new FakeConsoleIO(), runner).run(ledger, selector, false, true, null);

        assertThat(outcome)
                .as("selector exit %d → DEFERRED (only a clean exit 0 is APPLIED)", selectorExit)
                .isEqualTo(DevToolsStep.Outcome.DEFERRED);
        // The selector WAS spawned (this is the post-spawn degrade path).
        assertThat(runner.inheritCalls).hasSize(1);
    }

    // ── currentlyEnabled static helper ──────────────────────────────

    @Test
    void currentlyEnabled_reads_the_enabled_ids_from_the_ledger(@TempDir Path tmp) throws IOException {
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        DevToolsConfig.writeEnabled(ledger, Set.of("dind"));

        List<String> enabled = DevToolsStep.currentlyEnabled(ledger);
        assertThat(enabled).containsExactly("dind");
    }

    @Test
    void currentlyEnabled_on_missing_ledger_is_empty(@TempDir Path tmp) throws IOException {
        assertThat(DevToolsStep.currentlyEnabled(tmp.resolve(".ai-sandbox-devtools")))
                .isEmpty();
    }
}
