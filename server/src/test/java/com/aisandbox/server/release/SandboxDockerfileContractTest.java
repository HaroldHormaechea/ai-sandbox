package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aisandbox.server.cli.secrets.EnsureSandboxImage;
import com.aisandbox.server.cli.secrets.ServerVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * UC-38 AC1 / AC2 — the version identity is ultimately stamped by the
 * {@code SandboxDockerfile}: a build-time {@code ARG IMAGE_VERSION} fed
 * into an OCI {@code LABEL}. Every {@link EnsureSandboxImage} /
 * {@link com.aisandbox.server.cli.OnboardCommand} test mocks the
 * {@code docker compose build} shell-out, so without this contract a
 * future edit could silently drop the {@code LABEL} (or rename the key)
 * and every mocked test would still pass while the real image lost its
 * version identity. This test parses the Dockerfile as text and pins:
 *
 * <ul>
 *   <li>{@code ARG IMAGE_VERSION=dev} — the default MUST equal
 *       {@link ServerVersion#DEV_FALLBACK} so a plain {@code docker
 *       compose build} (no {@code --build-arg}, e.g. the CI smoke build)
 *       labels the image {@code dev} and the runtime fallback agrees
 *       (no perpetual-staleness regression);</li>
 *   <li>{@code LABEL <key>="${IMAGE_VERSION}"} — the label key MUST be
 *       {@link EnsureSandboxImage#LABEL_KEY} (the exact key {@code
 *       classify()} reads back) and its value MUST come from the build
 *       arg, not a hard-coded string.</li>
 * </ul>
 *
 * <p>Path discovery mirrors the rest of this package: the test JVM cwd
 * is {@code server/}, so the repo root (holding {@code SandboxDockerfile})
 * is its parent.
 */
class SandboxDockerfileContractTest {

    /** Test JVM cwd is {@code server/}; the Dockerfile lives at the repo root. */
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("user.dir")).getParent();

    private static final Path DOCKERFILE = REPO_ROOT.resolve("SandboxDockerfile");

    private static String dockerfile() throws IOException {
        assumeTrue(
                DOCKERFILE != null && Files.isRegularFile(DOCKERFILE),
                "SandboxDockerfile not found at " + DOCKERFILE + " — test must run with cwd=server/");
        return Files.readString(DOCKERFILE);
    }

    @Test
    void dockerfile_declares_image_version_arg_defaulting_to_dev() throws IOException {
        String text = dockerfile();
        // (?m) so ^ anchors to a line start; the default MUST be the dev fallback.
        assertThat(text)
                .as("UC-38 AC2 — SandboxDockerfile MUST declare `ARG IMAGE_VERSION=dev`")
                .containsPattern("(?m)^\\s*ARG\\s+" + Pattern.quote(EnsureSandboxImage.BUILD_ARG) + "\\s*=\\s*"
                        + Pattern.quote(ServerVersion.DEV_FALLBACK) + "\\s*$");
    }

    @Test
    void dockerfile_stamps_label_from_the_build_arg_with_the_classify_key() throws IOException {
        String text = dockerfile();
        // LABEL <key>="${IMAGE_VERSION}" — key is the one classify() reads,
        // value interpolated from the build arg (never a hard-coded version).
        assertThat(text)
                .as(
                        "UC-38 AC1 — SandboxDockerfile MUST stamp LABEL %s from ${%s}",
                        EnsureSandboxImage.LABEL_KEY, EnsureSandboxImage.BUILD_ARG)
                .containsPattern("(?m)^\\s*LABEL\\s+" + Pattern.quote(EnsureSandboxImage.LABEL_KEY) + "=\"\\$\\{"
                        + Pattern.quote(EnsureSandboxImage.BUILD_ARG) + "\\}\"\\s*$");
    }
}
