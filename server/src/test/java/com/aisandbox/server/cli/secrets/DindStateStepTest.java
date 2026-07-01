package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-94 — isolated coverage for {@link DindStateStep}, the install-time
 * delegation seam that shells out to the two bundled repair/provision
 * scripts.
 *
 * <p>This test is deliberately hermetic: it injects a
 * {@link FakeProcessRunner} so it asserts <b>exactly</b> the argv the step
 * hands the {@code ProcessRunner} — {@code bash <script> --secrets-dir …
 * --owner …} and {@code bash <script> --state-root … --owner …} — without
 * ever launching the real scripts, chowning anything, or touching a host
 * secrets dir. It therefore runs green on the dev host regardless of the
 * pre-existing CLI-command-test host artifact (the onboard/secrets-seed
 * integration harnesses that need a real service user). The anti-drift
 * contract that these argv match the postinst invocations is pinned
 * separately by {@code DebPostinstContractTest}; the shell scripts' own
 * behaviour is pinned by {@code server/src/test/e2e/uc30-server-install-unit.sh}.
 *
 * <p>Mapped acceptance criteria: AC#2 (subuid/subgid provisioning wired to
 * the server-owned secrets dir), AC#5 (state-root repair invoked
 * root-privileged), AC#4 (owner passed through as a derived
 * {@code <user>:<group>} string, never a hard-coded uid — the step is
 * owner-agnostic, so it can never inject a literal 997).
 */
class DindStateStepTest {

    private static final String OWNER = "ai-sandbox-server:ai-sandbox-server";

    /** Materialise both bundled scripts as empty regular files so {@code present()} passes. */
    private static Path dindDirWithScripts(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ensure-host-subid.sh"), "# stub\n");
        Files.writeString(dir.resolve("repair-state-root.sh"), "# stub\n");
        return dir;
    }

    @Test
    void run_repairs_state_root_first_then_provisions_subid_with_derived_owner(@TempDir Path tmp) throws Exception {
        Path dindDir = dindDirWithScripts(tmp.resolve("host/devtools.d/dind"));
        Path secretsDir = tmp.resolve("etc/secrets");
        Path stateRoot = tmp.resolve("var/sessions");

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        new DindStateStep(runner, io).run(dindDir, secretsDir, stateRoot, OWNER);

        // Exactly two shell-outs, repair BEFORE ensure (so a wrongly-typed
        // legacy secrets/dind debris dir is cleared before the canonical
        // files are written).
        assertThat(runner.captureCalls).hasSize(2);

        List<String> repair = runner.captureCalls.get(0);
        assertThat(repair)
                .as("AC#5 — repair-state-root.sh invoked via `bash <path>` with --state-root + derived --owner")
                .containsExactly(
                        "bash",
                        dindDir.resolve("repair-state-root.sh").toString(),
                        "--state-root",
                        stateRoot.toString(),
                        "--owner",
                        OWNER);

        List<String> ensure = runner.captureCalls.get(1);
        assertThat(ensure)
                .as("AC#2 — ensure-host-subid.sh invoked via `bash <path>` with --secrets-dir + derived --owner")
                .containsExactly(
                        "bash",
                        dindDir.resolve("ensure-host-subid.sh").toString(),
                        "--secrets-dir",
                        secretsDir.toString(),
                        "--owner",
                        OWNER);

        // AC#4 — no argv element is a bare numeric uid: the owner is always the
        // derived name:group string the caller passed, never a hard-coded 997.
        assertThat(runner.captureCalls.stream().flatMap(List::stream)).doesNotContain("997", "997:0");
    }

    @Test
    void ensureSubid_shells_out_only_to_the_ensure_script(@TempDir Path tmp) throws Exception {
        Path dindDir = dindDirWithScripts(tmp.resolve("dind"));
        Path secretsDir = tmp.resolve("etc/secrets");

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        // The `secrets seed` command calls only this half (Part B-seed).
        new DindStateStep(runner, io).ensureSubid(dindDir.resolve("ensure-host-subid.sh"), secretsDir, OWNER);

        assertThat(runner.captureCalls).hasSize(1);
        assertThat(runner.captureCalls.get(0))
                .containsExactly(
                        "bash",
                        dindDir.resolve("ensure-host-subid.sh").toString(),
                        "--secrets-dir",
                        secretsDir.toString(),
                        "--owner",
                        OWNER);
    }

    @Test
    void repairStateRoot_shells_out_only_to_the_repair_script(@TempDir Path tmp) throws Exception {
        Path dindDir = dindDirWithScripts(tmp.resolve("dind"));
        Path stateRoot = tmp.resolve("var/sessions");

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        new DindStateStep(runner, io).repairStateRoot(dindDir.resolve("repair-state-root.sh"), stateRoot, OWNER);

        assertThat(runner.captureCalls).hasSize(1);
        assertThat(runner.captureCalls.get(0))
                .containsExactly(
                        "bash",
                        dindDir.resolve("repair-state-root.sh").toString(),
                        "--state-root",
                        stateRoot.toString(),
                        "--owner",
                        OWNER);
    }

    @Test
    void missing_bundled_script_is_skipped_with_a_warning_never_a_shell_out(@TempDir Path tmp) throws Exception {
        // A partial install with NO bundled scripts: present() fails, so neither
        // half shells out and the wizard is not aborted (mirrors DevToolsStep).
        Path dindDir = tmp.resolve("host/devtools.d/dind"); // deliberately not created
        Path secretsDir = tmp.resolve("etc/secrets");
        Path stateRoot = tmp.resolve("var/sessions");

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        // The skip warning is emitted to System.err (NOT the ConsoleIO stream)
        // so a fully flag-driven `secrets seed` run stays silent on ConsoleIO —
        // the invariant SecretsSeedCommandTest relies on. Capture stderr around
        // the call and restore it in a finally.
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBuf, true));
        try {
            new DindStateStep(runner, io).run(dindDir, secretsDir, stateRoot, OWNER);
        } finally {
            System.setErr(origErr);
        }

        assertThat(runner.captureCalls)
                .as("a missing bundled script must NOT shell out")
                .isEmpty();
        assertThat(errBuf.toString())
                .as("the missing-script path warns on stderr and skips")
                .contains("bundled script not found");
        assertThat(io.allOutput())
                .as("the skip warning must NOT leak to ConsoleIO (flag-driven ⇒ silent ConsoleIO invariant)")
                .doesNotContain("bundled script not found");
    }

    @Test
    void non_zero_script_exit_warns_but_does_not_throw(@TempDir Path tmp) throws Exception {
        // Best-effort contract: a failing repair/provision emits a stderr
        // warning (the spawn-time guard is the fallback) but never aborts.
        Path dindDir = dindDirWithScripts(tmp.resolve("dind"));
        Path secretsDir = tmp.resolve("etc/secrets");
        Path stateRoot = tmp.resolve("var/sessions");

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, "boom: could not chown");
        FakeConsoleIO io = new FakeConsoleIO();

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBuf, true));
        try {
            assertThatCode(() -> new DindStateStep(runner, io).run(dindDir, secretsDir, stateRoot, OWNER))
                    .doesNotThrowAnyException();
        } finally {
            System.setErr(origErr);
        }

        // Both halves still ran; the non-zero exit surfaced as a warning.
        assertThat(runner.captureCalls).hasSize(2);
        assertThat(errBuf.toString())
                .contains("repair-state-root.sh exited 1")
                .contains("ensure-host-subid.sh exited 1")
                .contains("boom: could not chown");
    }
}
