package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.Ownership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * UC-27 — install-time step that captures the operator's "Select the
 * development tools you want to install" selections.
 *
 * <p>This is now a thin DELEGATION wrapper. The interactive picker lives in one
 * place — the pure-shell raw-mode selector {@code devtools-select.sh} (which
 * sources {@code lib.sh} + {@code devtools-ui.sh}). Both {@code ./setup.sh} and
 * this Java install-time path run the IDENTICAL selector, so the {@code .deb}
 * TTY auto-onboard captures dev-tool selections through the same raw-mode UI as
 * a host {@code ./setup.sh} run (AC#1,#14). The Java side holds no capability
 * catalog (AC#4) — the shell selector persists the ledger itself.
 *
 * <h2>Why {@code runInheritIO}</h2>
 *
 * The selector is a raw-mode cursor UI (it puts the terminal into
 * {@code -icanon}, reads single bytes, draws ANSI). It MUST inherit the
 * controlling terminal's stdin/stdout/stderr — {@link ProcessRunner#runAndCapture}
 * pipes them and would break the raw-mode TTY. Degrade-to-DEFERRED keys off the
 * child's exit code; there is no timeout (the operator may take their time).
 *
 * <h2>Outcome contract (unchanged)</h2>
 *
 * APPLIED / DEFERRED / SKIPPED, so the caller's done/deferred summary is
 * unchanged from the UC-26 numbered-checklist version.
 *
 * <p>UC06 § AC25 install-time CLI exemption applies.
 */
public final class DevToolsStep {

    private final ConsoleIO io;
    private final ProcessRunner runner;

    /**
     * @param io console seam — used for the not-found / deferred messages.
     * @param runner process seam — production = {@link ProcessRunner.Default};
     *     tests inject a fake that records the argv vector (and returns a canned
     *     exit code) instead of actually launching the selector.
     */
    public DevToolsStep(ConsoleIO io, ProcessRunner runner) {
        this.io = io;
        this.runner = runner;
    }

    /** Outcome of {@link #run(Path, Path, boolean, boolean, Ownership)}. */
    public enum Outcome {
        /** The operator committed a selection (selector exited 0). */
        APPLIED,
        /** Non-interactive / cancelled / selector unavailable — ledger untouched. */
        DEFERRED,
        /** {@code --no-devtools} was set; step intentionally skipped. */
        SKIPPED,
    }

    /**
     * Run the step by delegating to the shared shell selector.
     *
     * @param ledgerPath path to {@code .ai-sandbox-devtools}; passed to the
     *     selector as its argument so it reads/writes the right file.
     * @param selectorScript absolute path to {@code devtools-select.sh} in the
     *     bundled {@code host/} dir.
     * @param noDevtools {@code --no-devtools} opt-out → {@link Outcome#SKIPPED},
     *     ledger untouched.
     * @param hasTty cached {@link ConsoleIO#hasTty()} from the caller. The
     *     selector itself also refuses a non-TTY, but we short-circuit to
     *     {@link Outcome#DEFERRED} before spawning so a headless install never
     *     launches the child at all.
     * @param ownership pre-resolved owner/group for the ledger, or {@code null}.
     * @return the outcome — drives the caller's done/deferred summary.
     */
    public Outcome run(Path ledgerPath, Path selectorScript, boolean noDevtools, boolean hasTty, Ownership ownership)
            throws IOException {
        if (noDevtools) {
            return Outcome.SKIPPED;
        }
        if (!hasTty) {
            // Non-interactive → defer (the caller prints a "re-run from a TTY"
            // line). The raw-mode selector cannot run without a terminal.
            return Outcome.DEFERRED;
        }
        if (selectorScript == null || !Files.isRegularFile(selectorScript)) {
            io.println("  Dev-tools selector not found at " + selectorScript
                    + " — skipping. Run ./setup.sh on the host to configure dev tools.");
            return Outcome.DEFERRED;
        }

        int rc;
        try {
            // bash <selector> <ledger> — the selector exports AISB_DEVTOOLS_FILE
            // from arg 1, renders the raw-mode picker, and persists on commit.
            rc = runner.runInheritIO(List.of("bash", selectorScript.toString(), ledgerPath.toString()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Outcome.DEFERRED;
        }

        // Exit codes (devtools-select.sh): 0 committed; 130 cancelled; 3 no-TTY;
        // 1 internal. Anything non-zero leaves the ledger unchanged → DEFERRED
        // (degrade-to-DEFERRED on cancel/failure). Only a clean commit is APPLIED.
        if (rc == 0) {
            if (ownership != null && Files.isRegularFile(ledgerPath)) {
                ownership.chown(ledgerPath);
            }
            return Outcome.APPLIED;
        }
        return Outcome.DEFERRED;
    }

    /** Helper for callers (Reconfigure --doctor) that just need the enabled-ids set. */
    public static List<String> currentlyEnabled(Path ledgerPath) throws IOException {
        return new ArrayList<>(DevToolsConfig.readEnabled(ledgerPath));
    }
}
