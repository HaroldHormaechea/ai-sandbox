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
 * UC06 § AC16 + UC-38 — verifies the {@code ai-context:latest} pre-flight
 * check and the new version-identity / staleness behaviour.
 *
 * <p>Pinned behaviour:
 *
 * <ul>
 *   <li>Staleness is read from the OCI label {@link
 *       EnsureSandboxImage#LABEL_KEY} via {@code docker image inspect
 *       --format '{{ index .Config.Labels "<key>" }}' ai-context:latest}
 *       — no container is run. A non-zero inspect ⇒ {@code ABSENT};
 *       label == version ⇒ {@code CURRENT}; any other label (different
 *       version, empty, or Docker's {@code <no value>}) ⇒ {@code
 *       STALE}.</li>
 *   <li>{@link EnsureSandboxImage#run(Path, String)} builds ONLY when
 *       the image is absent (lazy onboarding path); a present-but-stale
 *       image is left in place for the upgrade / {@code --rebuild-image}
 *       path.</li>
 *   <li>{@link EnsureSandboxImage#rebuild(Path, String)} forces an
 *       unconditional build even when a current image is present
 *       (AC10).</li>
 *   <li>Every build stamps the version via {@code --build-arg
 *       IMAGE_VERSION=<version>} (UC-38 AC1/AC2) and targets the Compose
 *       <i>service</i> {@code claude-sandbox} (NOT the {@code
 *       ai-context:latest} tag).</li>
 *   <li>Missing compose file, failed build, and "build succeeded but
 *       image still absent" all surface as {@code IOException}s with
 *       remediation-aware messages.</li>
 * </ul>
 */
class EnsureSandboxImageTest {

    /** Package version stamped onto / compared against the image in these tests. */
    private static final String VERSION = "server-v1.2.3";

    private static Path installWithCompose(Path tmp) throws IOException {
        Path installDir = tmp.resolve("opt/ai-sandbox-server");
        Path hostDir = installDir.resolve("host");
        Files.createDirectories(hostDir);
        Files.writeString(hostDir.resolve("docker-compose.yml"), "# stub compose context\n");
        return installDir;
    }

    /** True when this capture call is the label-reading classify inspect (carries {@code --format}). */
    private static boolean isClassifyInspect(List<String> argv) {
        return argv.contains("--format");
    }

    // ── run(): builds only when ABSENT ─────────────────────────────────

    @Test
    void current_image_skips_compose_build(@TempDir Path tmp) throws Exception {
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        // classify → label == version ⇒ CURRENT ⇒ no build.
        runner.captureResponse = argv -> new ProcessRunner.Result(0, VERSION + "\n");

        new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir, VERSION);

        // Exactly one classify inspect; it carried the documented label-reading argv.
        assertThat(runner.captureCalls).hasSize(1);
        List<String> inspect = runner.captureCalls.get(0);
        assertThat(inspect)
                .containsSequence(
                        "docker",
                        "image",
                        "inspect",
                        "--format",
                        "{{ index .Config.Labels \"" + EnsureSandboxImage.LABEL_KEY + "\" }}",
                        EnsureSandboxImage.IMAGE_TAG);
        // No build call.
        assertThat(runner.inheritCalls).isEmpty();
    }

    @Test
    void stale_image_is_left_in_place_by_run(@TempDir Path tmp) throws Exception {
        // run() is the lazy onboarding path: a present-but-stale image is
        // NOT rebuilt here — that's --rebuild-image's / the upgrade path's job.
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "server-v0.9.0\n"); // different ⇒ STALE

        new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir, VERSION);

        assertThat(runner.captureCalls).hasSize(1);
        assertThat(runner.inheritCalls)
                .as("run() must NOT rebuild a stale image")
                .isEmpty();
    }

    @Test
    void absent_image_triggers_compose_build_with_build_arg_and_service(@TempDir Path tmp) throws Exception {
        Path installDir = installWithCompose(tmp);

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> {
            if (isClassifyInspect(argv)) {
                // classify → inspect fails ⇒ ABSENT.
                return new ProcessRunner.Result(125, "Error: no such image");
            }
            // post-build imagePresent() plain inspect ⇒ present.
            return new ProcessRunner.Result(0, "");
        };
        runner.inheritResponse = argv -> 0; // build succeeds

        FakeConsoleIO io = new FakeConsoleIO();
        new EnsureSandboxImage(runner, io).run(installDir, VERSION);

        // classify inspect + post-build imagePresent inspect.
        assertThat(runner.captureCalls).hasSize(2);

        // Build argv: docker compose -f <hostDir>/docker-compose.yml
        // --project-directory <hostDir> build --build-arg IMAGE_VERSION=<v> claude-sandbox.
        assertThat(runner.inheritCalls).hasSize(1);
        List<String> build = runner.inheritCalls.get(0);
        Path hostDir = installDir.resolve("host");
        Path composeFile = hostDir.resolve("docker-compose.yml");
        assertThat(build).containsSequence("docker", "compose");
        assertThat(build).containsSequence("-f", composeFile.toString());
        assertThat(build).containsSequence("--project-directory", hostDir.toString());
        // UC-38 — the version is stamped via --build-arg IMAGE_VERSION=<v>,
        // and the argv now ENDS with that build-arg followed by the service.
        assertThat(build)
                .endsWith(
                        "build",
                        "--build-arg",
                        EnsureSandboxImage.BUILD_ARG + "=" + VERSION,
                        EnsureSandboxImage.COMPOSE_SERVICE);
        assertThat(EnsureSandboxImage.COMPOSE_SERVICE)
                .as("compose service name must be 'claude-sandbox' (tag is ai-context:latest)")
                .isEqualTo("claude-sandbox");
        assertThat(EnsureSandboxImage.IMAGE_TAG).isEqualTo("ai-context:latest");
        assertThat(EnsureSandboxImage.BUILD_ARG).isEqualTo("IMAGE_VERSION");
        assertThat(EnsureSandboxImage.LABEL_KEY).isEqualTo("com.ai-sandbox.image-version");

        // Operator-visible step header surfaced before the build.
        assertThat(io.allOutput()).contains("building " + EnsureSandboxImage.IMAGE_TAG);
    }

    // ── rebuild(): forces a build even when current (AC10) ─────────────

    @Test
    void rebuild_forces_build_even_when_image_is_current(@TempDir Path tmp) throws Exception {
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        // Image is present + current the whole time; rebuild must still build.
        runner.captureResponse = argv -> new ProcessRunner.Result(0, VERSION + "\n");
        runner.inheritResponse = argv -> 0;

        FakeConsoleIO io = new FakeConsoleIO();
        new EnsureSandboxImage(runner, io).rebuild(installDir, VERSION);

        // No classify probe on the rebuild path — it goes straight to build().
        assertThat(runner.inheritCalls).hasSize(1);
        List<String> build = runner.inheritCalls.get(0);
        // Label stamping (AC1/AC2) — the version reaches the build via --build-arg.
        assertThat(build).containsSequence("--build-arg", EnsureSandboxImage.BUILD_ARG + "=" + VERSION);
        assertThat(build).endsWith("--build-arg", EnsureSandboxImage.BUILD_ARG + "=" + VERSION, "claude-sandbox");
        assertThat(io.allOutput()).contains("rebuilding " + EnsureSandboxImage.IMAGE_TAG);
        assertThat(io.allOutput()).contains("forced");
    }

    @Test
    void rebuild_stamps_the_exact_injected_version(@TempDir Path tmp) throws Exception {
        // A different version label must flow through verbatim — the build
        // is what stamps the image's identity (AC2: package version is SoT).
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "");
        runner.inheritResponse = argv -> 0;

        new EnsureSandboxImage(runner, new FakeConsoleIO()).rebuild(installDir, "server-v9.9.9");

        List<String> build = runner.inheritCalls.get(0);
        assertThat(build).containsSequence("--build-arg", "IMAGE_VERSION=server-v9.9.9");
    }

    // ── classify(): the staleness contract (AC4) ───────────────────────

    @Test
    void classify_absent_when_inspect_fails() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, "Error: No such image");

        EnsureSandboxImage.Staleness s = new EnsureSandboxImage(runner, new FakeConsoleIO()).classify(VERSION);

        assertThat(s).isEqualTo(EnsureSandboxImage.Staleness.ABSENT);
        // Uses the label-reading inspect, no container run.
        List<String> inspect = runner.captureCalls.get(0);
        assertThat(inspect).contains("inspect", "--format");
        assertThat(inspect).doesNotContain("run");
    }

    @Test
    void classify_current_when_label_equals_version() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner();
        // Docker may emit trailing whitespace/newline; classify trims.
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "  " + VERSION + "  \n");

        assertThat(new EnsureSandboxImage(runner, new FakeConsoleIO()).classify(VERSION))
                .isEqualTo(EnsureSandboxImage.Staleness.CURRENT);
    }

    @Test
    void classify_stale_when_label_differs() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "server-v0.9.0\n");

        assertThat(new EnsureSandboxImage(runner, new FakeConsoleIO()).classify(VERSION))
                .isEqualTo(EnsureSandboxImage.Staleness.STALE);
    }

    @Test
    void classify_stale_when_label_missing_empty() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "\n"); // empty label

        assertThat(new EnsureSandboxImage(runner, new FakeConsoleIO()).classify(VERSION))
                .isEqualTo(EnsureSandboxImage.Staleness.STALE);
    }

    @Test
    void classify_stale_when_label_is_docker_no_value() throws Exception {
        // A present image built WITHOUT the label inspects to Docker's
        // literal "<no value>" for a missing template key ⇒ STALE.
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(0, "<no value>\n");

        assertThat(new EnsureSandboxImage(runner, new FakeConsoleIO()).classify(VERSION))
                .isEqualTo(EnsureSandboxImage.Staleness.STALE);
    }

    // ── error paths (UC06 § AC16, preserved under the new signature) ───

    @Test
    void missing_compose_file_raises_with_layout_message(@TempDir Path tmp) throws Exception {
        Path installDir = tmp.resolve("opt/ai-sandbox-server");
        Files.createDirectories(installDir); // no host/ subdir

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, ""); // image absent

        assertThatThrownBy(() -> new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir, VERSION))
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
        runner.captureResponse = argv -> new ProcessRunner.Result(1, ""); // image absent
        runner.inheritResponse = argv -> 2; // build failed

        assertThatThrownBy(() -> new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir, VERSION))
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

        assertThatThrownBy(() -> new EnsureSandboxImage(runner, new FakeConsoleIO()).rebuild(installDir, VERSION))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("reported success but")
                .hasMessageContaining(EnsureSandboxImage.IMAGE_TAG)
                .hasMessageContaining("is still not present");
    }

    @Test
    void run_builds_once_when_absent_then_present(@TempDir Path tmp) throws Exception {
        // Regression guard for the classify→build→imagePresent call sequence:
        // exactly one build, two inspects (classify + post-build verify).
        Path installDir = installWithCompose(tmp);
        FakeProcessRunner runner = new FakeProcessRunner();
        AtomicInteger classifyProbes = new AtomicInteger();
        runner.captureResponse = argv -> {
            if (isClassifyInspect(argv)) {
                classifyProbes.incrementAndGet();
                return new ProcessRunner.Result(125, ""); // ABSENT
            }
            return new ProcessRunner.Result(0, ""); // imagePresent ⇒ present
        };
        runner.inheritResponse = argv -> 0;

        new EnsureSandboxImage(runner, new FakeConsoleIO()).run(installDir, VERSION);

        assertThat(classifyProbes.get()).isEqualTo(1);
        assertThat(runner.inheritCalls).hasSize(1);
        assertThat(runner.captureCalls).hasSize(2);
    }
}
