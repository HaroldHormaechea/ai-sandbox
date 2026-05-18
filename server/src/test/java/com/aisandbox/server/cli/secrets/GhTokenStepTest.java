package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC06 § AC5 — gh-token step coverage. Pinned behaviours:
 *
 * <ul>
 *   <li>Flag-driven {@code --gh-token-file PATH} byte-copies into
 *       {@code <secrets-dir>/gh-token} at mode 0600.</li>
 *   <li>Interactive path runs the documented single-container argv:
 *       {@code docker run --rm -it --user 0 -v <secrets-dir>:/etc/secrets
 *       --entrypoint sh ai-context:latest -c "gh auth login --hostname
 *       github.com --git-protocol ssh --skip-ssh-key --web && gh auth
 *       token > /etc/secrets/gh-token"}. This is the "one container,
 *       two commands" auth-state fix from the proposal.</li>
 *   <li>Docker exit ≠ 0 surfaces with remediation pointing at
 *       {@code --gh-token-file} and {@code --no-gh}.</li>
 *   <li>Empty / missing post-docker output is detected (defends
 *       against a phantom-success where the inner shell pipeline
 *       silently failed).</li>
 * </ul>
 */
class GhTokenStepTest {

    @Test
    void flag_driven_copies_token_file_at_mode_0600(@TempDir Path tmp) throws Exception {
        Path secretsDir = tmp.resolve("secrets");
        Files.createDirectories(secretsDir);
        Path src = tmp.resolve("pre-generated-pat");
        // Opaque fixture — the step just byte-copies whatever is in
        // the file, so the content is irrelevant to coverage. We
        // deliberately avoid any real GitHub-token prefix so the
        // gitleaks `github-oauth` rule doesn't match this source file.
        Files.writeString(src, "<fixture-token-bytes-opaque-to-step>\n");
        Path out = secretsDir.resolve("gh-token");

        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        new GhTokenStep(runner, io).run(src, out, secretsDir, /* ownership */ null);

        // Byte-identical copy.
        assertThat(Files.readString(out)).isEqualTo("<fixture-token-bytes-opaque-to-step>\n");
        // Mode 0600.
        assertThat(Files.getPosixFilePermissions(out))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        // No docker shell-out for the flag-driven path.
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(runner.captureCalls).isEmpty();
    }

    @Test
    void flag_driven_missing_source_raises_ioexception(@TempDir Path tmp) {
        Path secretsDir = tmp.resolve("secrets");
        Path missing = tmp.resolve("not-there");
        Path out = secretsDir.resolve("gh-token");
        FakeProcessRunner runner = new FakeProcessRunner();

        assertThatThrownBy(() -> new GhTokenStep(runner, new FakeConsoleIO()).run(missing, out, secretsDir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gh token file not found")
                .hasMessageContaining("--gh-token-file");
    }

    @Test
    void interactive_runs_documented_single_container_argv(@TempDir Path tmp) throws Exception {
        Path secretsDir = tmp.resolve("secrets");
        Files.createDirectories(secretsDir);
        Path out = secretsDir.resolve("gh-token");

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            // Simulate the inner pipeline writing the captured token
            // to the bind-mounted secrets dir.
            try {
                Files.writeString(out, "<container-captured-token-fixture>\n");
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        new GhTokenStep(runner, io).run(/* flag */ null, out, secretsDir, null);

        // Output present with mode 0600 (chmod applied after capture).
        assertThat(Files.readString(out)).isEqualTo("<container-captured-token-fixture>\n");
        assertThat(Files.getPosixFilePermissions(out))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        // One docker run inheritIO; argv shape matches the proposal.
        assertThat(runner.inheritCalls).hasSize(1);
        List<String> argv = runner.inheritCalls.get(0);
        assertThat(argv).startsWith("docker", "run", "--rm", "-it");
        assertThat(argv).containsSequence("--user", "0");
        assertThat(argv).containsSequence("-v", secretsDir.toAbsolutePath() + ":/etc/secrets");
        assertThat(argv).containsSequence("--entrypoint", "sh");
        assertThat(argv).contains("ai-context:latest");
        // -c arg holds the actual shell pipeline. Both commands must
        // be present, joined by &&, and the redirect target must point
        // inside the bind-mounted /etc/secrets — never to the host
        // path (the in-container write is the whole point).
        int cIdx = argv.indexOf("-c");
        assertThat(cIdx).isGreaterThan(0);
        String shellCmd = argv.get(cIdx + 1);
        assertThat(shellCmd)
                .contains("gh auth login")
                .contains("--hostname github.com")
                .contains("--git-protocol ssh")
                .contains("--skip-ssh-key")
                .contains("--web")
                .contains("&& gh auth token")
                .contains("> /etc/secrets/gh-token");
    }

    @Test
    void interactive_docker_failure_surfaces_with_remediation(@TempDir Path tmp) throws Exception {
        Path secretsDir = tmp.resolve("secrets");
        Files.createDirectories(secretsDir);
        Path out = secretsDir.resolve("gh-token");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> 7; // docker run failed
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        assertThatThrownBy(() -> new GhTokenStep(runner, io).run(null, out, secretsDir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("docker exit=7")
                .hasMessageContaining("--gh-token-file")
                .hasMessageContaining("--no-gh");
    }

    @Test
    void interactive_phantom_success_with_empty_token_file_is_detected(@TempDir Path tmp) throws Exception {
        Path secretsDir = tmp.resolve("secrets");
        Files.createDirectories(secretsDir);
        Path out = secretsDir.resolve("gh-token");

        FakeProcessRunner runner = new FakeProcessRunner();
        // Docker "succeeds" but the inner pipeline left an empty file
        // (or none at all). Step MUST reject — operators would not
        // notice until session-spawn time otherwise.
        runner.inheritResponse = argv -> {
            try {
                Files.writeString(out, "");
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        assertThatThrownBy(() -> new GhTokenStep(runner, io).run(null, out, secretsDir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gh auth token produced no output")
                .hasMessageContaining("missing or empty");
    }
}
