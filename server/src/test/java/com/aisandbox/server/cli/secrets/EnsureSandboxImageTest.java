package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC06 § AC16 — verifies the {@code ai-context:latest} pre-flight check.
 *
 * <p>Pinned behaviour:
 *
 * <ul>
 *   <li>Probe is exactly {@code docker image inspect ai-context:latest};
 *       exit 0 ↔ present, anything else ↔ missing.</li>
 *   <li>When missing, the build call is
 *       {@code docker compose -f <installDir>/host/docker-compose.yml
 *       --project-directory <installDir>/host build claude-sandbox} —
 *       same invocation {@code release-install-smoke} uses, so the CI
 *       proves the exact argv shape boots cleanly.</li>
 *   <li>{@code ai-context:latest} is the image <i>tag</i>;
 *       {@code claude-sandbox} is the Compose <i>service</i>. Mismatch
 *       was the footgun the analyst's pre-impl gate caught.</li>
 *   <li>Missing compose file, failed build, and "build succeeded but
 *       image still absent" all surface as {@code IOException}s with
 *       remediation-aware messages.</li>
 * </ul>
 */
class EnsureSandboxImageTest {

    private static Path installWithCompose(Path tmp) throws IOException {
        Path installDir = tmp.resolve("opt/ai-sandbox-server");
        Path hostDir = installDir.resolve("host");
        Files.createDirectories(hostDir);
        Files.writeString(hostDir.resolve("docker-compose.yml"), "# stub compose context\n");
        return installDir;
    }

    @Test
    void image_already_present_skips_compose_build(@TempDir Path tmp) throws Exception {
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        // Probe → exit 0 ↔ image present.
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "");

        new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir);

        // Probe fired exactly once with the documented argv.
        assertThat(runner.captureCalls).hasSize(1);
        assertThat(runner.captureCalls.get(0))
                .containsSequence("docker", "image", "inspect", EnsureSandboxImage.IMAGE_TAG);

        // No build call.
        assertThat(runner.inheritCalls).isEmpty();
    }

    @Test
    void missing_image_triggers_compose_build_with_documented_argv(@TempDir Path tmp) throws Exception {
        Path installDir = installWithCompose(tmp);

        FakeProcessRunner runner = new FakeProcessRunner();
        AtomicInteger probeCount = new AtomicInteger();
        runner.captureResponse = argv -> {
            int n = probeCount.incrementAndGet();
            // First probe: image absent (exit 125 — docker's
            // "no such image" style). Second probe (after build):
            // image present (exit 0).
            return new ProcessRunner.Result(n == 1 ? 125 : 0, n == 1 ? "Error: no such image" : "");
        };
        runner.inheritResponse = argv -> 0; // build succeeds

        FakeConsoleIO io = new FakeConsoleIO();
        new EnsureSandboxImage(runner, io).run(installDir);

        // Two probes (before + after build).
        assertThat(runner.captureCalls).hasSize(2);

        // Build argv: docker compose -f <hostDir>/docker-compose.yml
        // --project-directory <hostDir> build claude-sandbox.
        assertThat(runner.inheritCalls).hasSize(1);
        List<String> build = runner.inheritCalls.get(0);
        Path hostDir = installDir.resolve("host");
        Path composeFile = hostDir.resolve("docker-compose.yml");
        assertThat(build).containsSequence("docker", "compose");
        assertThat(build).containsSequence("-f", composeFile.toString());
        assertThat(build).containsSequence("--project-directory", hostDir.toString());
        // Service name (NOT the image tag) is the build arg.
        assertThat(build).endsWith("build", EnsureSandboxImage.COMPOSE_SERVICE);
        assertThat(EnsureSandboxImage.COMPOSE_SERVICE)
                .as("compose service name must be 'claude-sandbox' (tag is ai-context:latest)")
                .isEqualTo("claude-sandbox");
        assertThat(EnsureSandboxImage.IMAGE_TAG).isEqualTo("ai-context:latest");

        // Operator-visible step header surfaced before the build.
        assertThat(io.allOutput()).contains("building " + EnsureSandboxImage.IMAGE_TAG);
    }

    @Test
    void missing_compose_file_raises_with_layout_message(@TempDir Path tmp) throws Exception {
        Path installDir = tmp.resolve("opt/ai-sandbox-server");
        Files.createDirectories(installDir); // no host/ subdir

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, ""); // image missing

        assertThatThrownBy(() -> new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ai-context:latest is not present")
                .hasMessageContaining("install dir layout looks broken");

        // No build attempted when the compose file is missing.
        assertThat(runner.inheritCalls).isEmpty();
    }

    @Test
    void failed_compose_build_raises(@TempDir Path tmp) throws Exception {
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, ""); // image missing
        runner.inheritResponse = argv -> 2; // build failed

        assertThatThrownBy(() -> new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("docker compose build claude-sandbox failed")
                .hasMessageContaining("exit=2");
    }

    @Test
    void successful_build_but_image_still_absent_is_detected(@TempDir Path tmp) throws Exception {
        // Phantom-success defence: the build returned 0 but the post-
        // build probe still can't find the image. Most likely cause is
        // a compose file whose `image:` field disagrees with what the
        // step probes for — exactly the tag/service footgun the
        // analyst flagged.
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, ""); // ALWAYS missing
        runner.inheritResponse = argv -> 0; // build claims success

        assertThatThrownBy(() -> new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("reported success but")
                .hasMessageContaining(EnsureSandboxImage.IMAGE_TAG)
                .hasMessageContaining("is still not present");
    }
}
