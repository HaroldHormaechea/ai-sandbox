package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-26 — interactive coverage for {@link DevToolsStep}, the install-time
 * "Select the development tools you want to install" wizard step.
 *
 * <p>The step is the Java parallel of {@code setup.sh}'s
 * {@code run_devtools_step}. It is appended to {@code aisandboxctl onboard}'s
 * pipeline (so the {@code .deb} auto-onboard captures dev-tool selections
 * out of the box) and is the body of {@code aisandboxctl reconfigure}'s
 * interactive path.
 *
 * <p>The acceptance criteria this class covers:
 *
 * <ul>
 *   <li>AC#3 — enabling a capability triggers an inline trust-boundary
 *       warning + a {@code Continue? [y/N]} confirmation; decline returns
 *       to the checklist with the entry un-selected.</li>
 *   <li>AC#7 — selections persist to {@code .ai-sandbox-devtools}; the
 *       file's POSIX mode is 0644 (matches the documented install-time
 *       file-mode contract).</li>
 *   <li>AC#1/AC#2 — disabling an already-enabled capability does NOT
 *       trigger the trust-boundary warning (the warning fires on the
 *       boundary-WIDENING transition only).</li>
 *   <li>Non-TTY deferral — without a terminal the step short-circuits to
 *       {@link DevToolsStep.Outcome#DEFERRED} and the ledger is left
 *       untouched (no silent writes on a headless install).</li>
 * </ul>
 *
 * <p>The step has no shell-outs (no {@link ProcessRunner} use today), but
 * the constructor accepts one as a future-proofing seam; tests pass a fake
 * runner that records nothing.
 */
class DevToolsStepTest {

    private static DevToolsStep step(FakeConsoleIO io) {
        return new DevToolsStep(io, new FakeProcessRunner());
    }

    private static FakeConsoleIO ttyConsole() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        return io;
    }

    private static FakeConsoleIO noTtyConsole() {
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = false;
        return io;
    }

    // ── AC#3 — enabling DinD triggers warning + confirmation ────────

    @Test
    void enable_dind_with_yes_confirmation_persists_dind_to_the_ledger(@TempDir Path tmp) throws IOException {
        // Operator sees the checklist, types "1" to toggle dind on, the
        // wizard surfaces the warning + Continue? prompt, operator types
        // "y" → ledger now has dind, Outcome is APPLIED.
        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("1"); // toggle dind
        io.inputLines.add("y"); // confirm the trust-boundary warning
        io.inputLines.add(""); // empty line = commit

        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        DevToolsStep.Outcome outcome = step(io).run(ledger, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");

        // AC#3 — the trust-boundary warning + Continue? prompt MUST have
        // been emitted to the operator (matches the catalog warning row
        // plus the literal Continue? prompt label).
        String all = io.allOutput();
        assertThat(all)
                .as("AC#3 — wizard emits the trust-boundary warning before the y/N confirmation")
                .contains("trust boundary")
                .contains("Continue?");
        // And the post-commit summary fires for the new-sessions-only caveat (AC#7).
        assertThat(all)
                .as("AC#7 — wizard notes that changes apply to NEW sessions only")
                .contains("NEW sessions only");
    }

    @Test
    void enable_dind_with_capital_yes_also_confirms(@TempDir Path tmp) throws IOException {
        // Defence — the y/N matcher must accept both "y" and "yes" in any case.
        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("1");
        io.inputLines.add("YES");
        io.inputLines.add("");

        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        assertThat(step(io).run(ledger, false, true, null)).isEqualTo(DevToolsStep.Outcome.APPLIED);
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
    }

    // ── AC#3 — decline returns to the checklist un-selected ─────────

    @Test
    void enable_dind_with_no_confirmation_leaves_ledger_unchanged(@TempDir Path tmp) throws IOException {
        // Operator picks dind, sees the warning, types "n" → ledger stays
        // empty AND the wizard signals that dind was left disabled
        // (textual feedback so the operator knows the decline registered).
        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("1"); // toggle dind on
        io.inputLines.add("n"); // decline
        io.inputLines.add(""); // commit (no changes)

        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        DevToolsStep.Outcome outcome = step(io).run(ledger, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        // AC#3 — decline returns to the checklist with the entry UN-selected;
        // the persisted ledger therefore reflects "no devtools enabled".
        assertThat(DevToolsConfig.readEnabled(ledger))
                .as("AC#3 — declining the y/N prompt MUST leave dind disabled")
                .isEmpty();
        assertThat(io.allOutput())
                .as("decline emits a clear cancelled message so the operator knows it stuck")
                .contains("Cancelled")
                .contains("dind");
    }

    @Test
    void blank_response_to_confirmation_counts_as_decline(@TempDir Path tmp) throws IOException {
        // [y/N] convention — bare Enter on the confirmation defaults to N.
        // This is a guard against accidentally widening the trust boundary
        // by mashing Enter through the wizard.
        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("1"); // toggle dind on
        io.inputLines.add(""); // blank response to Continue? → default N
        io.inputLines.add(""); // commit

        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        step(io).run(ledger, false, true, null);
        assertThat(DevToolsConfig.readEnabled(ledger))
                .as("[y/N] default — bare Enter on Continue? MUST decline")
                .isEmpty();
    }

    // ── Disable existing — no warning fires ──────────────────────────

    @Test
    void disabling_an_existing_enabled_capability_emits_no_trust_boundary_warning(@TempDir Path tmp) throws IOException {
        // Pre-seed the ledger so dind starts ENABLED; toggling it off
        // must NOT re-fire the warning (warnings are for boundary-widening
        // events, not boundary-narrowing ones).
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);

        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("1"); // toggle dind OFF (already enabled)
        io.inputLines.add(""); // commit

        DevToolsStep.Outcome outcome = step(io).run(ledger, false, true, null);
        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        assertThat(DevToolsConfig.readEnabled(ledger)).isEmpty();

        // No "Continue?" prompt should have been emitted (we never
        // prompted because we were narrowing, not widening, the boundary).
        assertThat(io.allOutput())
                .as("AC#3 — the warning is enable-only; disabling MUST NOT prompt")
                .doesNotContain("Continue?");
        // Confirm the wizard told the operator the entry was disabled.
        assertThat(io.allOutput()).contains("Disabled");
    }

    // ── AC#4 — re-run pre-fills with current state ──────────────────

    @Test
    void rerun_renders_checklist_with_current_state_pre_selected(@TempDir Path tmp) throws IOException {
        // Pre-seed with dind enabled; the operator commits without flipping
        // anything (empty Enter). The post-commit summary reflects the
        // pre-selection (AC#4 — re-run jumps to the step with current state).
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);

        FakeConsoleIO io = ttyConsole();
        io.inputLines.add(""); // immediate commit — no changes

        step(io).run(ledger, false, true, null);

        // Ledger unchanged.
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");

        // The rendered checklist row for dind MUST carry the [x] marker
        // (rather than [ ]), which is how AC#4's "current state pre-
        // selected" presents to the operator.
        boolean preSelectedRow = io.printed.stream()
                .anyMatch(line -> line.contains("[x]") && line.contains("Docker-in-Docker"));
        assertThat(preSelectedRow)
                .as("AC#4 — re-run MUST render the dind row with [x] when the ledger already enables it")
                .isTrue();
    }

    // ── AC#7 — file mode 0644 on the written ledger ─────────────────

    @Test
    void written_ledger_has_mode_0644(@TempDir Path tmp) throws IOException {
        // The developer comment in DevToolsStep.java declares the ledger
        // ships at 0644 to match the rest of the install-time files. Pin
        // that contract so a future refactor can't silently relax it.
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return; // Non-POSIX filesystem (Windows) — assertion is vacuously satisfied.
        }
        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("1");
        io.inputLines.add("y");
        io.inputLines.add("");

        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        step(io).run(ledger, false, true, null);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(ledger);
        assertThat(perms)
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ);
    }

    // ── Non-TTY deferral (headless install path) ────────────────────

    @Test
    void non_tty_run_defers_without_touching_the_ledger(@TempDir Path tmp) throws IOException {
        // No TTY + no --no-devtools opt-out → DEFERRED. The caller (onboard /
        // reconfigure) is responsible for emitting the "re-run from a TTY"
        // instruction in its summary; the step's contract is just "leave
        // the ledger alone and return DEFERRED".
        Path ledger = tmp.resolve(".ai-sandbox-devtools");

        FakeConsoleIO io = noTtyConsole();
        DevToolsStep.Outcome outcome = step(io).run(ledger, false, false, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.DEFERRED);
        // No ledger written.
        assertThat(ledger).doesNotExist();
        // No prompts or output emitted (the step returns silently).
        assertThat(io.printed).isEmpty();
    }

    @Test
    void no_devtools_flag_short_circuits_with_skipped_outcome(@TempDir Path tmp) throws IOException {
        // --no-devtools is the explicit opt-out (used by the proposal-listed
        // OnboardCommand back-compat housekeeping and by reconfigure --no-devtools).
        // It is independent of TTY presence: even on a real terminal,
        // --no-devtools must short-circuit cleanly.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");

        FakeConsoleIO io = ttyConsole();
        DevToolsStep.Outcome outcome = step(io).run(ledger, true, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.SKIPPED);
        assertThat(ledger).doesNotExist();
        assertThat(io.printed).isEmpty();
    }

    @Test
    void no_devtools_with_pre_existing_ledger_leaves_it_untouched(@TempDir Path tmp) throws IOException {
        // Idempotency — --no-devtools must NOT trample a previously persisted
        // selection. An operator who passes --no-devtools to onboard --force
        // (to rerun every other component) should keep their devtools
        // selection.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);
        byte[] before = Files.readAllBytes(ledger);

        FakeConsoleIO io = ttyConsole();
        DevToolsStep.Outcome outcome = step(io).run(ledger, true, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.SKIPPED);
        assertThat(Files.readAllBytes(ledger))
                .as("--no-devtools MUST NOT rewrite the ledger")
                .isEqualTo(before);
    }

    // ── Input handling — invalid entries don't crash ─────────────────

    @Test
    void invalid_number_input_reprompts_without_crashing(@TempDir Path tmp) throws IOException {
        // Defensive — typing a number out of range or a non-numeric should
        // re-render the prompt with an error and let the operator try again.
        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("9"); // out of range
        io.inputLines.add("zog"); // garbage
        io.inputLines.add(""); // commit with nothing changed

        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        DevToolsStep.Outcome outcome = step(io).run(ledger, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        assertThat(ledger).exists();
        assertThat(Files.size(ledger)).as("no toggles → empty ledger written").isZero();
        // The operator-facing nudge text fires at least once.
        assertThat(io.allOutput()).contains("Type a number");
    }

    @Test
    void exit_command_returns_applied_without_writing_the_ledger(@TempDir Path tmp) throws IOException {
        // /exit aborts the step mid-prompt without persisting; outcome is
        // still APPLIED so the caller doesn't double-prompt the operator.
        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("/exit");

        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        DevToolsStep.Outcome outcome = step(io).run(ledger, false, true, null);

        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        assertThat(ledger)
                .as("/exit MUST NOT create a ledger file")
                .doesNotExist();
        assertThat(io.allOutput()).contains("Exiting");
    }

    @Test
    void skip_command_keeps_pre_existing_selection_unchanged(@TempDir Path tmp) throws IOException {
        // /skip is the "I'm just looking, don't change anything" affordance.
        // It MUST behave identically to a bare Enter on the first prompt:
        // committing the current state byte-for-byte.
        Path ledger = tmp.resolve(".ai-sandbox-devtools");
        Set<String> seeded = new LinkedHashSet<>();
        seeded.add("dind");
        DevToolsConfig.writeEnabled(ledger, seeded);
        byte[] before = Files.readAllBytes(ledger);

        FakeConsoleIO io = ttyConsole();
        io.inputLines.add("/skip");

        DevToolsStep.Outcome outcome = step(io).run(ledger, false, true, null);
        assertThat(outcome).isEqualTo(DevToolsStep.Outcome.APPLIED);
        // The persisted ledger MUST round-trip to the same enabled set.
        assertThat(DevToolsConfig.readEnabled(ledger)).containsExactly("dind");
        // The written bytes equal the seeded bytes (deterministic output).
        assertThat(Files.readAllBytes(ledger)).isEqualTo(before);
    }
}
