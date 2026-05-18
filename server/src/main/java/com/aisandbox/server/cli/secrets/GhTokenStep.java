package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.Ownership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * UC06 § AC5 — step (c) of {@code aisandboxctl secrets seed}: write a
 * single-line GitHub PAT at {@code <secrets-dir>/gh-token} (mode
 * 0600, owned by {@code ai-sandbox-server}).
 *
 * <p>Three modes:
 *
 * <ul>
 *   <li>{@code --no-gh} — orchestrator skips this step entirely; the
 *       container's {@code entrypoint.sh} already warn-and-continues
 *       when {@code /etc/secrets/gh-token} is missing.</li>
 *   <li>{@code --gh-token-file PATH} — byte-for-byte copy (no
 *       transformation; the file is treated as authoritative).</li>
 *   <li>interactive — one ephemeral {@code claude-sandbox} container
 *       runs both {@code gh auth login --web} and
 *       {@code gh auth token} so the auth state never has to survive
 *       across containers (this is the "two-container auth-state
 *       issue" the proposal calls out). The container runs with
 *       {@code --user 0} so it can write the captured token into
 *       the bind-mounted secrets dir; the step then chmods/chowns the
 *       file on the host side.</li>
 * </ul>
 *
 * <p>The {@code --web} flag forces device-flow: {@code gh} prints the
 * verification URL + 8-digit code on its stderr, which inheritIO
 * surfaces to the operator's terminal so they can complete the flow
 * from any browser (UC06 AC5 + the "headless OAuth" clarification).
 */
final class GhTokenStep {

    /**
     * Image tag for the ephemeral container. The image is built by the
     * release-zip's bundled {@code docker-compose.yml} (Compose
     * service name {@code claude-sandbox}, image tag
     * {@code ai-context:latest} — see AC16). {@link EnsureSandboxImage}
     * has already verified / built the image before this step runs.
     */
    private static final String SANDBOX_IMAGE = "ai-context:latest";

    private final ProcessRunner runner;
    private final ConsoleIO io;

    GhTokenStep(ProcessRunner runner, ConsoleIO io) {
        this.runner = runner;
        this.io = io;
    }

    /**
     * Run the gh-token step.
     *
     * @param ghTokenFileFlag value of {@code --gh-token-file} or
     *     {@code null} for interactive.
     * @param outputPath destination ({@code <secrets-dir>/gh-token}).
     * @param secretsDir parent of {@code outputPath}; bind-mounted
     *     RW into the ephemeral container so the captured token can
     *     land directly there.
     * @param ownership pre-resolved owner/group or {@code null}.
     */
    void run(Path ghTokenFileFlag, Path outputPath, Path secretsDir, Ownership ownership)
            throws IOException, InterruptedException {

        if (ghTokenFileFlag != null) {
            if (!Files.isRegularFile(ghTokenFileFlag)) {
                throw new IOException("gh token file not found at " + ghTokenFileFlag + " (--gh-token-file)");
            }
            Files.copy(ghTokenFileFlag, outputPath, StandardCopyOption.REPLACE_EXISTING);
        } else {
            interactiveLogin(outputPath, secretsDir);
        }

        Files.setPosixFilePermissions(outputPath, PosixFilePermissions.fromString("rw-------"));
        if (ownership != null) {
            ownership.chown(outputPath);
        }
    }

    private void interactiveLogin(Path outputPath, Path secretsDir) throws IOException, InterruptedException {
        io.println("");
        io.println("  step 3/4 — gh authentication");
        io.println("  Launching " + SANDBOX_IMAGE + " for `gh auth login --web`.");
        io.println("  When prompted, open the printed URL in any browser, enter the 8-digit code,");
        io.println("  and approve. The captured token lands directly at " + outputPath + ".");
        io.println("");

        String tokenFileName = outputPath.getFileName().toString();
        // --user 0 is deliberate: the bind-mounted secrets dir is owned
        // by the ai-sandbox-server system user on the host (mode 0700),
        // so the container needs root to write into it. The token file
        // ends up owned by root on the host; the caller chowns it to
        // ai-sandbox-server immediately after this method returns.
        List<String> argv = List.of(
                "docker",
                "run",
                "--rm",
                "-it",
                "--user",
                "0",
                "-v",
                secretsDir.toAbsolutePath() + ":/etc/secrets",
                "--entrypoint",
                "sh",
                SANDBOX_IMAGE,
                "-c",
                "gh auth login --hostname github.com --git-protocol ssh --skip-ssh-key --web"
                        + " && gh auth token > /etc/secrets/"
                        + tokenFileName);

        int rc = runner.runInheritIO(argv);
        if (rc != 0) {
            throw new IOException(
                    "gh auth login / token capture failed (docker exit=" + rc + "). Re-run secrets seed --force"
                            + " once the upstream issue is resolved, or pass --gh-token-file to import a"
                            + " pre-generated PAT, or --no-gh to skip this step.");
        }
        if (!Files.isRegularFile(outputPath) || Files.size(outputPath) == 0) {
            throw new IOException("gh auth token produced no output at " + outputPath
                    + "; the docker run claimed success but the file is missing or empty");
        }
    }
}
