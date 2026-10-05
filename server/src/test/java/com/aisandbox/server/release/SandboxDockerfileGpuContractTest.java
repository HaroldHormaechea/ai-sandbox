package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * UC-101 § AC4 — the base sandbox image MUST stay free of the CUDA
 * userspace: non-GPU users pay no image-size cost, and the toolkit is
 * provisioned on demand by the opt-in {@code aisandbox-gpu} capability at
 * spawn, NOT baked into the image.
 *
 * <p>Rather than an (impossible-in-CI) byte-identical image-size
 * assertion, this parses the {@code SandboxDockerfile} as text — the same
 * strategy as {@link SandboxDockerfileContractTest} — and pins TWO
 * contract facts:
 *
 * <ul>
 *   <li>the {@code aisandbox-gpu} helper IS {@code COPY}d into the image
 *       (it is a KB-sized shell script — the provisioner, not the
 *       toolkit);</li>
 *   <li>NO build instruction installs a CUDA / NVIDIA package — no
 *       {@code apt(-get) install … cuda/nvidia…}, no {@code pip install
 *       nvidia-*}, no {@code cuda-toolkit}. Comment lines (which legitimately
 *       mention "CUDA" to explain the AC4 intent) are stripped before the
 *       scan so the assertion keys on actual build steps, not prose.</li>
 * </ul>
 *
 * <p>If a future edit bakes CUDA into the base image this test turns RED,
 * guarding AC4's "image unchanged for non-GPU users" guarantee.
 */
class SandboxDockerfileGpuContractTest {

    /** Test JVM cwd is {@code server/}; the Dockerfile lives at the repo root. */
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("user.dir")).getParent();

    private static final Path DOCKERFILE = REPO_ROOT.resolve("SandboxDockerfile");

    private static List<String> dockerfileLines() throws IOException {
        assumeTrue(
                Files.isRegularFile(DOCKERFILE),
                "SandboxDockerfile not found at " + DOCKERFILE + " — test must run with cwd=server/");
        return Files.readAllLines(DOCKERFILE);
    }

    /** True when {@code line}, once trimmed, is blank or a {@code #} comment. */
    private static boolean isCommentOrBlank(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("#");
    }

    @Test
    void dockerfile_copies_the_aisandbox_gpu_helper() throws IOException {
        List<String> lines = dockerfileLines();
        boolean copied = lines.stream()
                .filter(l -> !isCommentOrBlank(l))
                .anyMatch(l -> l.matches("\\s*COPY\\s+container-bin/aisandbox-gpu\\s+\\S+\\s*"));
        assertThat(copied)
                .as("UC-101 AC4 — SandboxDockerfile MUST COPY the KB-sized container-bin/aisandbox-gpu provisioner")
                .isTrue();
    }

    @Test
    void dockerfile_bakes_no_cuda_or_nvidia_package() throws IOException {
        List<String> lines = dockerfileLines();

        // Any non-comment line installing a cuda/nvidia package would bake the
        // userspace into the base image → AC4 violation. We scan build steps
        // only (comments stripped) because the file legitimately *documents*
        // CUDA in comments to explain why it is absent.
        Pattern aptInstall =
                Pattern.compile("\\b(apt-get|apt)\\s+install\\b.*\\b(cuda|nvidia)", Pattern.CASE_INSENSITIVE);
        Pattern pipInstall = Pattern.compile("\\bpip3?\\s+install\\b.*\\b(cuda|nvidia)", Pattern.CASE_INSENSITIVE);
        Pattern cudaPkg = Pattern.compile(
                "\\b(cuda-toolkit|cuda-runtime|nvidia-cuda|nvidia-container)", Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            if (isCommentOrBlank(line)) {
                continue;
            }
            assertThat(aptInstall.matcher(line).find())
                    .as("UC-101 AC4 — no apt install of a CUDA/NVIDIA package in the base image: <%s>", line)
                    .isFalse();
            assertThat(pipInstall.matcher(line).find())
                    .as("UC-101 AC4 — no pip install of an NVIDIA/CUDA wheel in the base image: <%s>", line)
                    .isFalse();
            assertThat(cudaPkg.matcher(line).find())
                    .as("UC-101 AC4 — no CUDA/NVIDIA package reference in a build step: <%s>", line)
                    .isFalse();
        }
    }
}
