package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aisandbox.server.cli.secrets.EnsureSandboxImage;
import com.aisandbox.server.cli.secrets.ServerVersion;
import com.aisandbox.server.stream.service.InputInjectionService;
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

    /**
     * UC-97 AC8/AC9 — Claude Code MUST be PINNED in the image build, replacing the old
     * unpinned {@code latest-at-build} install. The pane-chrome scraper
     * ({@code container-bin/aisandbox-conversation-tail} {@code PENDING_QUESTION_CHROME})
     * and the keystroke-injection walk ({@link InputInjectionService#PINNED_CLAUDE_VERSION})
     * are TUNED to a specific TUI/transcript shape; an unpinned upgrade silently drifts that
     * shape out from under them and breaks pending-question detection/delivery (UC-50, UC-97).
     *
     * <p>This is the deterministic drift anchor: the {@code SandboxDockerfile} ARG default MUST
     * equal the in-code pin {@link InputInjectionService#PINNED_CLAUDE_VERSION}, so a future bump
     * that edits only one of the two turns this test RED before release (AC9 — a bump is a single
     * deliberate, gate-verified change kept in lock-step).
     */
    @Test
    void dockerfile_pins_claude_code_version_in_lockstep_with_the_code_constant() throws IOException {
        String text = dockerfile();
        assertThat(text)
                .as(
                        "UC-97 AC8 — SandboxDockerfile MUST declare `ARG CLAUDE_CODE_VERSION=%s` (== InputInjectionService.PINNED_CLAUDE_VERSION)",
                        InputInjectionService.PINNED_CLAUDE_VERSION)
                .containsPattern("(?m)^\\s*ARG\\s+CLAUDE_CODE_VERSION\\s*=\\s*"
                        + Pattern.quote(InputInjectionService.PINNED_CLAUDE_VERSION) + "\\s*$");
    }

    /**
     * UC-97 AC8 — the install line MUST consume the pinned build arg
     * ({@code @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}}), never an unpinned or
     * {@code @latest} install. Asserting the interpolation form (not a hard-coded version)
     * keeps the single version lever the ARG above.
     */
    @Test
    void dockerfile_installs_claude_code_from_the_pinned_build_arg() throws IOException {
        String text = dockerfile();
        assertThat(text)
                .as("UC-97 AC8 — Claude Code MUST be installed at the pinned ARG version")
                .containsPattern("@anthropic-ai/claude-code@\\$\\{CLAUDE_CODE_VERSION\\}");
        assertThat(text)
                .as("UC-97 AC8 — Claude Code MUST NOT be installed unpinned via @latest")
                .doesNotContain("@anthropic-ai/claude-code@latest");
    }
}
