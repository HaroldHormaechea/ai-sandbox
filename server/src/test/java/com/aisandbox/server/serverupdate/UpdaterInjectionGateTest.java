package com.aisandbox.server.serverupdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * UC-84 AC12 — the headline adversarial parameter-injection gate. Drives the
 * REAL {@code ai-sandbox-updater.sh} through a hermetic shell harness (shipped
 * as a test resource) that PATH-shims every privileged tool, feeds the updater
 * malicious filenames / fake versions / fake URLs / poisoned env / shell-
 * metacharacter payloads through every channel it could read, and asserts the
 * updater consumed NONE of them — it installed only its own self-determined
 * latest {@code server-v*} target, attached no credential, and fired no payload.
 *
 * <p>The harness lives at {@code src/test/resources/uc84/updater-injection-gate.sh}
 * and does the actual asserting (it exits non-zero with a {@code GATE-FAIL:}
 * diagnostic on any breach); this test wires it into the {@code :server:test}
 * lane so the proof runs on every build, not just in CI. On a host without a
 * POSIX shell (or the updater script — e.g. when the test JVM cwd is not the
 * server module) the test {@code assumeTrue}-skips rather than false-failing.
 */
class UpdaterInjectionGateTest {

    /** Test JVM cwd is {@code server/}; the updater script lives at the repo root. */
    private static final Path SERVER_DIR = Path.of(System.getProperty("user.dir"));

    @Test
    void updater_ignores_all_injected_parameters_and_acts_only_on_its_derived_target() throws Exception {
        Path repoRoot = SERVER_DIR.getParent();
        assumeTrue(repoRoot != null, "cannot resolve repo root from cwd=" + SERVER_DIR);
        Path updater = repoRoot.resolve("ai-sandbox-updater.sh");
        assumeTrue(Files.isRegularFile(updater), "updater script not found at " + updater);

        String sh = findPosixShell();
        assumeTrue(sh != null, "no POSIX shell on PATH — cannot run the injection gate");

        // Materialise the harness from the classpath to a temp file.
        Path harness = Files.createTempFile("uc84-injection-gate", ".sh");
        try (InputStream in = getClass().getResourceAsStream("/uc84/updater-injection-gate.sh")) {
            assumeTrue(in != null, "harness resource /uc84/updater-injection-gate.sh missing");
            Files.copy(in, harness, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    sh,
                    harness.toAbsolutePath().toString(),
                    updater.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new AssertionError("injection gate harness timed out:\n" + output);
            }
            int rc = p.exitValue();

            // The harness prints a GATE-PASS line on success and GATE-FAIL: <reason> on any breach.
            assertThat(rc)
                    .as("adversarial injection gate must pass — harness output:%n%s", output)
                    .isZero();
            assertThat(output).contains("GATE-PASS");
            assertThat(output).doesNotContain("GATE-FAIL");
        } finally {
            Files.deleteIfExists(harness);
        }
    }

    private static String findPosixShell() {
        for (String candidate : new String[] {"/bin/sh", "/usr/bin/sh", "/bin/bash"}) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }
}
