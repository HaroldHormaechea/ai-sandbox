package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.Ownership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * UC-26 — install-time step that captures the operator's "Select the
 * development tools you want to install" selections.
 *
 * <p>Java parallel of {@code setup.sh}'s {@code run_devtools_step}: renders a
 * plain numbered checklist of opt-in capabilities (see {@link DevToolsConfig#CATALOG}),
 * lets the operator toggle entries by number, prompts for the inline
 * trust-boundary confirmation when ENABLING a new spawn-time capability, and
 * persists the result via {@link DevToolsConfig#writeEnabled(Path, Set)}.
 *
 * <p>Reuse vector — this step is appended to {@code OnboardCommand}'s pipeline
 * (so the {@code .deb} auto-onboard captures dev-tool selections too) and is
 * the body of {@code aisandboxctl reconfigure}'s interactive path.
 *
 * <h2>Non-interactive deferral</h2>
 *
 * When {@link ConsoleIO#hasTty()} is false and no override flag was supplied,
 * the step does NOT prompt — it just leaves the ledger untouched and reports
 * "deferred" upstream. The interactive checklist is operator-driven and has
 * no scriptable "skip every prompt" mode beyond that.
 *
 * <h2>Why not a {@code @Service}</h2>
 *
 * UC06 § AC25 install-time CLI exemption applies (documented in
 * {@code PROJECT_BRIEF.md} {@code ## Profiles}). The step does file I/O and
 * console prompting; it is not a Service in the
 * Controller/Facade/Service/Repository sense. Testability comes from
 * constructor-injected {@link ConsoleIO} and {@link ProcessRunner} seams.
 */
public final class DevToolsStep {

    private final ConsoleIO io;

    /**
     * @param io console seam — production = {@link ConsoleIO.Default};
     *     tests inject a fake that pretends to have a TTY and feeds canned
     *     input lines.
     * @param runner reserved for future capabilities that need to shell out
     *     (e.g. probing for an installed Rust toolchain). v1's DinD entry is
     *     a no-op at config time — the daemon is provisioned at session
     *     start by {@code aisandbox-dind install} — so the runner is held
     *     here for the API contract only.
     */
    @SuppressWarnings("unused")
    public DevToolsStep(ConsoleIO io, ProcessRunner runner) {
        this.io = io;
    }

    /** Outcome of {@link #run(Path, boolean, boolean, Ownership)}. */
    public enum Outcome {
        /** The operator made changes (or accepted current state via Enter / /skip). */
        APPLIED,
        /** Non-interactive, no flag-driven override — step did not run. */
        DEFERRED,
        /** {@code --no-devtools} was set; step intentionally skipped. */
        SKIPPED,
    }

    /**
     * Run the step.
     *
     * @param ledgerPath path to {@code .ai-sandbox-devtools}.
     * @param noDevtools {@code --no-devtools} opt-out — when {@code true},
     *     the step short-circuits and returns {@link Outcome#SKIPPED}. The
     *     ledger is left untouched (so the existing selection persists).
     * @param hasTty cached {@link ConsoleIO#hasTty()} from the caller; we
     *     accept it as a param so the call site can apply other policies
     *     (e.g. debconf-driven flows) without re-probing.
     * @param ownership pre-resolved owner/group for the ledger file, or
     *     {@code null} when running as a normal user.
     * @return the outcome — drives the caller's done/deferred summary.
     */
    public Outcome run(Path ledgerPath, boolean noDevtools, boolean hasTty, Ownership ownership) throws IOException {
        if (noDevtools) {
            return Outcome.SKIPPED;
        }
        if (!hasTty) {
            // Non-interactive + no flag-driven override → defer. The caller
            // emits a "re-run later from a TTY" line in the deferred summary.
            return Outcome.DEFERRED;
        }
        if (DevToolsConfig.CATALOG.isEmpty()) {
            // Defensive: empty catalog → nothing to ask.
            io.println("  No development-tool capabilities are registered.");
            return Outcome.APPLIED;
        }

        Set<String> enabled = new LinkedHashSet<>(DevToolsConfig.readEnabled(ledgerPath));

        io.println("");
        io.println("  Select the development tools you want to install");
        io.println("  ---------------------------------------------------");
        io.println("  Each entry below is a toggle. Type a number to flip it.");
        io.println("  Press Enter on an empty line to commit, /skip to leave");
        io.println("  the current selection unchanged, or /exit to abort.");
        io.println("");

        List<DevToolsConfig.Capability> catalog = DevToolsConfig.CATALOG;

        while (true) {
            for (int i = 0; i < catalog.size(); i++) {
                DevToolsConfig.Capability c = catalog.get(i);
                String mark = enabled.contains(c.id()) ? "x" : " ";
                io.println(String.format("    [%s] %d. %s", mark, i + 1, c.label()));
            }
            io.println("");
            io.print("  > ");
            String resp = io.readLine();
            if (resp == null) {
                // EOF mid-prompt — treat as commit-current.
                break;
            }
            resp = resp.strip();
            if (resp.isEmpty() || "/skip".equals(resp)) {
                break;
            }
            if ("/exit".equals(resp)) {
                io.println("  Exiting development-tools step.");
                return Outcome.APPLIED;
            }
            Integer idx = parseIndex(resp, catalog.size());
            if (idx == null) {
                io.println("  Type a number 1.." + catalog.size() + ", /skip, or /exit.");
                continue;
            }
            DevToolsConfig.Capability target = catalog.get(idx);
            if (enabled.contains(target.id())) {
                enabled.remove(target.id());
                io.println("  Disabled: " + target.label());
            } else {
                // Enabling → surface the trust-boundary warning before commit.
                String warning = target.warning();
                if (warning != null && !warning.isBlank()) {
                    io.println("");
                    io.println("  ! " + warning);
                    io.println("");
                    io.print("  Continue? [y/N]: ");
                    String confirm = io.readLine();
                    if (confirm == null || !confirm.strip().toLowerCase().matches("y|yes")) {
                        io.println("  Cancelled. " + target.id() + " left disabled.");
                        continue;
                    }
                }
                enabled.add(target.id());
                io.println("  Enabled: " + target.label());
            }
        }

        DevToolsConfig.writeEnabled(ledgerPath, enabled);

        // Best-effort mode + ownership — the ledger holds no secret material,
        // but matching the rest of the install-time files (mode 0644 / owned
        // by ai-sandbox-server) keeps it consistent.
        try {
            Files.setPosixFilePermissions(ledgerPath, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (Windows) — nothing to do.
        }
        if (ownership != null) {
            ownership.chown(ledgerPath);
        }

        if (enabled.isEmpty()) {
            io.println("  Development tools: none enabled (sessions remain identical to the default).");
        } else {
            io.println("  Development tools persisted: " + String.join(", ", enabled));
            io.println("  Changes apply to NEW sessions only — existing sessions are unaffected.");
        }
        return Outcome.APPLIED;
    }

    /** Parse a 1..size index from a user-typed string; {@code null} if invalid. */
    private static Integer parseIndex(String s, int size) {
        try {
            int n = Integer.parseInt(s);
            if (n >= 1 && n <= size) {
                return n - 1;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return null;
    }

    /** Helper for callers (Reconfigure --doctor) that just need the enabled-ids set. */
    public static List<String> currentlyEnabled(Path ledgerPath) throws IOException {
        return new ArrayList<>(DevToolsConfig.readEnabled(ledgerPath));
    }
}
