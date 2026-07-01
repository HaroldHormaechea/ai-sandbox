package com.aisandbox.server.cli.secrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * UC-94 — install-time step that provisions the server-owned DinD
 * subuid/subgid delegation files and repairs stale root-owned per-session
 * bind-source debris under the host state root.
 *
 * <p>This is a thin DELEGATION wrapper, mirroring {@link SshKeyStep} /
 * {@link GitIdentityStep} / {@link DevToolsStep}: the real logic lives in TWO
 * bundled shell scripts so the deb {@code postinst} and the Java install-time
 * CLIs share a single implementation and cannot drift (UC-94 anti-drift, Part
 * B/D):
 *
 * <ul>
 *   <li>{@code devtools.d/dind/ensure-host-subid.sh} — sole writer of
 *       {@code <secrets-dir>/dind/{subuid,subgid}} (both delegation lines),
 *       chowned to the service user. Wired to the same path
 *       {@code ScriptExecutorService.composeEnv()} exports via
 *       {@code AI_SANDBOX_DIND_SUBUID/SUBGID_HOST_PATH} (§ AC2).</li>
 *   <li>{@code devtools.d/dind/repair-state-root.sh} — sole owner of the
 *       known per-session bind-source name-set; re-owns
 *       {@code workspace}/{@code workspace-*}/{@code claude-config}/
 *       {@code claude-config-*}/{@code claude-projects-*} to the service user
 *       and removes the legacy {@code <state-root>/secrets/dind} debris
 *       (§ AC5). Narrowly scoped — UC-62 host-shell siblings are untouched.</li>
 * </ul>
 *
 * <p>The scripts ship NON-EXECUTABLE in the deb, so they are invoked via
 * {@code bash <path>} (matching the postinst contract). The step shells out
 * through the injected {@link ProcessRunner} (the test seam): both scripts are
 * non-interactive, so {@link ProcessRunner#runAndCapture(List)} is used and any
 * non-zero exit is surfaced as a warning rather than aborting the wizard — these
 * are provisioning/repair conveniences, and a fresh install fails only later
 * (loudly) at {@code pki init} if the secrets dir is truly unwritable.
 *
 * <p>UC06 § AC25 install-time CLI exemption from
 * {@code profile-java-server-architecture}'s layering applies, exactly as for
 * the sibling {@code *Step} classes and the {@code onboard} / {@code secrets
 * seed} commands they back.
 */
public final class DindStateStep {

    private final ProcessRunner runner;
    private final ConsoleIO io;

    /**
     * @param runner process seam — production = {@link ProcessRunner.Default};
     *     tests inject a fake that records the argv vector (and returns a canned
     *     exit code) instead of actually launching the scripts.
     * @param io console seam — used only for the script-not-found warning.
     */
    public DindStateStep(ProcessRunner runner, ConsoleIO io) {
        this.runner = runner;
        this.io = io;
    }

    /**
     * Onboard path (Parts B + D): repair stale state-root debris FIRST, then
     * provision the subuid/subgid files. Repair-before-ensure so a wrongly-typed
     * {@code secrets/dind} debris dir is cleared before the canonical files are
     * written under the (separate, server-owned) secrets dir.
     *
     * @param dindDir the bundled {@code host/devtools.d/dind} directory holding
     *     the two scripts (typically {@code <install-dir>/host/devtools.d/dind}).
     * @param secretsDir server-owned secrets dir; the dind files land in
     *     {@code <secretsDir>/dind/}.
     * @param stateRoot per-session host-state root to repair (the dir whose
     *     children are the per-session bind sources).
     * @param owner {@code <user>:<group>} the created/repaired paths are chowned
     *     to (e.g. {@code ai-sandbox-server:ai-sandbox-server}). Never the
     *     hard-coded uid — the caller derives the service user name.
     */
    public void run(Path dindDir, Path secretsDir, Path stateRoot, String owner)
            throws IOException, InterruptedException {
        repairStateRoot(dindDir.resolve("repair-state-root.sh"), stateRoot, owner);
        ensureSubid(dindDir.resolve("ensure-host-subid.sh"), secretsDir, owner);
    }

    /**
     * Provision {@code <secretsDir>/dind/{subuid,subgid}} via
     * {@code ensure-host-subid.sh} (Part B / Part B-seed). The {@code secrets
     * seed} command calls only this half.
     */
    public void ensureSubid(Path ensureScript, Path secretsDir, String owner) throws IOException, InterruptedException {
        if (!present(ensureScript)) {
            return;
        }
        List<String> argv =
                List.of("bash", ensureScript.toString(), "--secrets-dir", secretsDir.toString(), "--owner", owner);
        warnOnFailure("ensure-host-subid.sh", argv, runner.runAndCapture(argv));
    }

    /**
     * Re-own the known per-session bind sources + remove legacy dind debris via
     * {@code repair-state-root.sh} (Part D). Idempotent and never fatal on the
     * shell side; here a non-zero exit only emits a warning.
     */
    public void repairStateRoot(Path repairScript, Path stateRoot, String owner)
            throws IOException, InterruptedException {
        if (!present(repairScript)) {
            return;
        }
        List<String> argv =
                List.of("bash", repairScript.toString(), "--state-root", stateRoot.toString(), "--owner", owner);
        warnOnFailure("repair-state-root.sh", argv, runner.runAndCapture(argv));
    }

    private boolean present(Path script) {
        if (script != null && Files.isRegularFile(script)) {
            return true;
        }
        // A partial / non-standard install missing the bundled helper is not
        // fatal — warn and skip, mirroring DevToolsStep's missing-selector path.
        // Emitted to System.err (NOT the ConsoleIO stream) so a fully
        // flag-driven `secrets seed` run stays silent on ConsoleIO — that
        // "flag-driven steps emit nothing through ConsoleIO" invariant is
        // asserted by SecretsSeedCommandTest. This matches warnOnFailure below
        // and SecretsSeedCommand's own catch block, which also use System.err.
        System.err.println("  DinD state step: bundled script not found at " + script + " — skipping.");
        return false;
    }

    private void warnOnFailure(String name, List<String> argv, ProcessRunner.Result res) {
        if (res.exitCode() != 0) {
            System.err.println(
                    "aisandboxctl: " + name + " exited " + res.exitCode()
                            + " (DinD subuid/state provisioning is best-effort; the spawn-time guard is the fallback). Output:");
            System.err.println(res.output() == null ? "" : res.output().stripTrailing());
        }
    }
}
