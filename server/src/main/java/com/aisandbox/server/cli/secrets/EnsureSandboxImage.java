package com.aisandbox.server.cli.secrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * UC06 § AC16 — guarantees the {@code ai-context:latest} image exists
 * before steps (c) and (d) try to {@code docker run} it.
 *
 * <p>Probe via {@code docker image inspect ai-context:latest >/dev/null
 * 2>&1}; on miss, fall through to
 * {@code docker compose -f <install-dir>/host/docker-compose.yml
 * --project-directory <install-dir>/host build claude-sandbox}, which
 * is the same invocation {@code release-install-smoke}'s "Build bundled
 * compose context" step uses (so we exercise the exact path the CI
 * already proves builds cleanly).
 *
 * <p><b>Tag vs service.</b> The image is published under the tag
 * {@code ai-context:latest} (the {@code image:} field in
 * {@code docker-compose.yml}); the Compose <i>service</i> is named
 * {@code claude-sandbox}. {@code docker image inspect} takes the
 * <i>tag</i>; {@code docker compose ... build} takes the <i>service</i>.
 * Mismatching these is a footgun the analyst's pre-impl gate 1
 * resolved.
 */
public final class EnsureSandboxImage {

    /** Image tag emitted by the bundled docker-compose.yml's {@code image:} field. */
    public static final String IMAGE_TAG = "ai-context:latest";

    /** Compose service name; takes the spot {@code docker compose ... build <service>} consumes. */
    public static final String COMPOSE_SERVICE = "claude-sandbox";

    private final ProcessRunner runner;
    private final ConsoleIO io;

    public EnsureSandboxImage(ProcessRunner runner, ConsoleIO io) {
        this.runner = runner;
        this.io = io;
    }

    /**
     * Ensure {@link #IMAGE_TAG} is available locally, building it from
     * the bundled compose context if not.
     *
     * @param installDir root of the unpacked release zip (the
     *     directory holding {@code host/}, {@code lib/},
     *     {@code systemd/}, …). The bundled compose file is at
     *     {@code <installDir>/host/docker-compose.yml}.
     */
    public void run(Path installDir) throws IOException, InterruptedException {
        if (imagePresent()) {
            return;
        }
        Path hostDir = installDir.resolve("host");
        Path composeFile = hostDir.resolve("docker-compose.yml");
        if (!Files.isRegularFile(composeFile)) {
            throw new IOException("ai-context:latest is not present and the bundled compose context" + " at "
                    + composeFile + " is missing — install dir layout looks broken");
        }
        io.println("");
        io.println("  step 0/4 — building " + IMAGE_TAG + " (one-time, may take a few minutes)");
        int rc = runner.runInheritIO(List.of(
                "docker",
                "compose",
                "-f",
                composeFile.toString(),
                "--project-directory",
                hostDir.toString(),
                "build",
                COMPOSE_SERVICE));
        if (rc != 0) {
            throw new IOException("docker compose build " + COMPOSE_SERVICE + " failed (exit=" + rc + ")");
        }
        if (!imagePresent()) {
            throw new IOException("docker compose build " + COMPOSE_SERVICE + " reported success but " + IMAGE_TAG
                    + " is still not present");
        }
    }

    private boolean imagePresent() throws IOException, InterruptedException {
        ProcessRunner.Result res = runner.runAndCapture(List.of("docker", "image", "inspect", IMAGE_TAG));
        return res.exitCode() == 0;
    }
}
