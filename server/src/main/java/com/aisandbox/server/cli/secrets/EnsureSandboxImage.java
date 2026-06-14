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
 *
 * <p><b>UC-38 — version identity.</b> Every build stamps the package
 * version onto the image as the OCI label {@link #LABEL_KEY} (passed via
 * {@code --build-arg IMAGE_VERSION=<version>}; {@code SandboxDockerfile}
 * defaults the arg to {@code dev}). {@link #classify(String)} reads that
 * label back without running a container and reports whether the local
 * image is {@link Staleness#ABSENT absent}, {@link Staleness#CURRENT
 * current}, or {@link Staleness#STALE stale} (any non-equal label,
 * including a missing one). The runtime tag stays {@code ai-context:latest}
 * — staleness is decided from the label, never the tag.
 *
 * <p>Two build entry points share a private {@code build(...)}:
 * {@link #run(Path, String)} builds only when the image is
 * <i>absent</i> (the lazy onboarding path — a present-but-stale image is
 * left for the upgrade path to refresh), while {@link #rebuild(Path,
 * String)} forces an unconditional rebuild (the explicit
 * {@code --rebuild-image} flag and the staleness-gated upgrade path).
 */
public final class EnsureSandboxImage {

    /** Image tag emitted by the bundled docker-compose.yml's {@code image:} field. */
    public static final String IMAGE_TAG = "ai-context:latest";

    /** Compose service name; takes the spot {@code docker compose ... build <service>} consumes. */
    public static final String COMPOSE_SERVICE = "claude-sandbox";

    /** OCI label carrying the image's version identity (UC-38). */
    public static final String LABEL_KEY = "com.ai-sandbox.image-version";

    /** Build arg {@code SandboxDockerfile} consumes to stamp {@link #LABEL_KEY}. */
    public static final String BUILD_ARG = "IMAGE_VERSION";

    /** Result of comparing the local image's {@link #LABEL_KEY} label to a package version. */
    public enum Staleness {
        /** No local {@link #IMAGE_TAG} image at all. */
        ABSENT,
        /** Present and its label equals the package version. */
        CURRENT,
        /** Present but its label differs (including a missing label). */
        STALE
    }

    // ── Canonical argv builders + pure classifier (UC-77) ───────────────
    //
    // These static helpers are the single source of truth for the exact
    // {@code docker} command vectors and the staleness decision. Both the
    // CLI / onboarding instance methods below AND the server-side
    // {@code SandboxImageService} (which warms the same image ahead of the
    // first interactive spawn) build their argv here, so the warm-built
    // image is byte-for-byte label-identical to the onboard build —
    // guaranteeing {@link #classify(int, String, String)} reports CURRENT
    // and no double build occurs.

    /**
     * Argv for the label-reading {@code docker image inspect} that drives
     * {@link Staleness} classification — never runs a container. Pair its
     * captured result with {@link #classify(int, String, String)}.
     */
    public static List<String> inspectLabelArgv() {
        return List.of(
                "docker",
                "image",
                "inspect",
                "--format",
                "{{ index .Config.Labels \"" + LABEL_KEY + "\" }}",
                IMAGE_TAG);
    }

    /**
     * Argv for the plain {@code docker image inspect <tag>} presence probe
     * (no {@code --format}); a zero exit means {@link #IMAGE_TAG} exists.
     */
    public static List<String> inspectPresentArgv() {
        return List.of("docker", "image", "inspect", IMAGE_TAG);
    }

    /**
     * Argv for the {@code docker compose ... build} that produces
     * {@link #IMAGE_TAG}, stamping {@code version} via
     * {@code --build-arg IMAGE_VERSION=<version>} and targeting the
     * {@link #COMPOSE_SERVICE} service. The element order is pinned —
     * existing argv assertions (EnsureSandboxImageTest, OnboardCommandTest)
     * and the CI {@code release-install-smoke} step depend on it verbatim.
     *
     * @param composeFile the {@code -f} compose file
     * @param projectDir the {@code --project-directory}
     * @param version stamped via {@link #BUILD_ARG}
     */
    public static List<String> buildArgv(Path composeFile, Path projectDir, String version) {
        return List.of(
                "docker",
                "compose",
                "-f",
                composeFile.toString(),
                "--project-directory",
                projectDir.toString(),
                "build",
                "--build-arg",
                BUILD_ARG + "=" + version,
                COMPOSE_SERVICE);
    }

    /**
     * Pure staleness decision over the result of an
     * {@link #inspectLabelArgv()} inspect. A non-zero {@code inspectExitCode}
     * ⇒ {@link Staleness#ABSENT}; otherwise the trimmed label is compared to
     * {@code version} — equal ⇒ {@link Staleness#CURRENT}, anything else
     * (a different version, an empty label, or Docker's {@code <no value>})
     * ⇒ {@link Staleness#STALE}.
     *
     * @param inspectExitCode exit code of the label inspect
     * @param labelTrim the trimmed label value captured from the inspect
     * @param version the package version to compare against
     */
    public static Staleness classify(int inspectExitCode, String labelTrim, String version) {
        if (inspectExitCode != 0) {
            return Staleness.ABSENT;
        }
        return version.equals(labelTrim) ? Staleness.CURRENT : Staleness.STALE;
    }

    private final ProcessRunner runner;
    private final ConsoleIO io;

    public EnsureSandboxImage(ProcessRunner runner, ConsoleIO io) {
        this.runner = runner;
        this.io = io;
    }

    /**
     * Ensure {@link #IMAGE_TAG} is available locally, building it from
     * the bundled compose context only when it is <i>absent</i>. A
     * present-but-stale image is intentionally left in place — refreshing
     * a stale image is the explicit {@code --rebuild-image} flag's and the
     * {@code .deb} upgrade path's job, not the lazy onboarding build's.
     *
     * @param installDir root of the unpacked release zip (the
     *     directory holding {@code host/}, {@code lib/},
     *     {@code systemd/}, …). The bundled compose file is at
     *     {@code <installDir>/host/docker-compose.yml}.
     * @param version package version to stamp as {@link #LABEL_KEY} when a
     *     build happens.
     */
    public void run(Path installDir, String version) throws IOException, InterruptedException {
        if (classify(version) != Staleness.ABSENT) {
            return;
        }
        build(installDir, version, "step 0/4 — building " + IMAGE_TAG + " (one-time, may take a few minutes)");
    }

    /**
     * Force an unconditional rebuild of {@link #IMAGE_TAG}, stamping
     * {@code version} as {@link #LABEL_KEY}. Rebuilds even when a current
     * image is already present (AC10). Used by {@code onboard
     * --rebuild-image} and the staleness-gated upgrade path.
     *
     * @param installDir root of the unpacked release zip.
     * @param version package version to stamp as {@link #LABEL_KEY}.
     */
    public void rebuild(Path installDir, String version) throws IOException, InterruptedException {
        build(installDir, version, "rebuilding " + IMAGE_TAG + " (forced; may take a few minutes)");
    }

    /**
     * Classify the local {@link #IMAGE_TAG} image against {@code version}
     * via {@code docker image inspect --format '{{ index .Config.Labels
     * "<key>" }}'}. A non-zero inspect exit means the image is
     * {@link Staleness#ABSENT}; otherwise the trimmed label is compared to
     * {@code version} — equal ⇒ {@link Staleness#CURRENT}, anything else
     * (a different version, an empty label, or Docker's {@code <no value>}
     * for a missing label) ⇒ {@link Staleness#STALE}.
     */
    public Staleness classify(String version) throws IOException, InterruptedException {
        ProcessRunner.Result res = runner.runAndCapture(inspectLabelArgv());
        return classify(res.exitCode(), res.stdoutTrim(), version);
    }

    private void build(Path installDir, String version, String banner) throws IOException, InterruptedException {
        Path hostDir = installDir.resolve("host");
        Path composeFile = hostDir.resolve("docker-compose.yml");
        if (!Files.isRegularFile(composeFile)) {
            throw new IOException("ai-context:latest is not present and the bundled compose context" + " at "
                    + composeFile + " is missing — install dir layout looks broken");
        }
        io.println("");
        io.println("  " + banner);
        int rc = runner.runInheritIO(buildArgv(composeFile, hostDir, version));
        if (rc != 0) {
            throw new IOException("docker compose build " + COMPOSE_SERVICE + " failed (exit=" + rc + ")");
        }
        if (!imagePresent()) {
            throw new IOException("docker compose build " + COMPOSE_SERVICE + " reported success but " + IMAGE_TAG
                    + " is still not present");
        }
    }

    private boolean imagePresent() throws IOException, InterruptedException {
        ProcessRunner.Result res = runner.runAndCapture(inspectPresentArgv());
        return res.exitCode() == 0;
    }
}
