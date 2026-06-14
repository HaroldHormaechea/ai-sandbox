package com.aisandbox.server.sessions.service;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.cli.secrets.EnsureSandboxImage;
import com.aisandbox.server.cli.secrets.EnsureSandboxImage.Staleness;
import com.aisandbox.server.cli.secrets.ServerVersion;
import com.aisandbox.server.config.ServerProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-77 — server-side warm-up of the {@code ai-context:latest} sandbox image.
 *
 * <p>The root cause this service fixes: the first interactive
 * {@code POST /v1/sessions} runs {@code spawn.sh}, which ends in
 * {@code docker compose up -d}; on a cold cache that {@code up} builds the
 * absent image, and the build legitimately exceeds the tight
 * {@code spawn-timeout-seconds} (60s), so the spawn is aborted and returned as
 * a hard 500. This service decouples the heavy build from the request path: it
 * builds the image ahead of time (kicked at startup by
 * {@code SandboxImageWarmupRunner}, and lazily re-kicked by the spawn gate),
 * stamping the SAME {@code --build-arg IMAGE_VERSION=<ServerVersion.current()>}
 * the onboard build uses, so the warm-built image is label-identical and
 * {@link EnsureSandboxImage#classify(int, String, String)} reports it CURRENT
 * (no double build).
 *
 * <p><b>Reuse, not reinvention.</b> The exact {@code docker} argv (inspect +
 * compose build) and the staleness decision come from the static helpers on
 * {@link EnsureSandboxImage}; this service only runs them through the server's
 * {@link ProcessExecutor} (instead of the CLI {@code ProcessRunner}) and
 * bounds the build by {@link ServerProperties.Limits#imageBuildTimeoutSeconds()}
 * rather than the per-request spawn timeout.
 *
 * <p><b>Concurrency.</b> {@link #state} is an {@link AtomicReference}; a single
 * CAS into {@link SandboxImageState#BUILDING} serialises concurrent warm
 * callers (the startup runner and any number of spawn-triggered async kicks),
 * so the image is built at most once. Losers of the CAS observe BUILDING/READY
 * and return without building.
 *
 * <p>Layering: a domain service ({@code profile-java-server-architecture}
 * rule 6) — called only by {@code SandboxImageFacade}. No {@code @Transactional}
 * (there is no data store). The dependency on {@code ..cli.secrets} is a pure
 * static-helper reuse (argv + classify), introducing no package cycle.
 */
@Service
public class SandboxImageService {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxImageService.class);

    private final ProcessExecutor executor;
    private final HostScriptLocator locator;
    private final ServerProperties props;
    private final AuditLogger audit;

    private final AtomicReference<SandboxImageState> state = new AtomicReference<>(SandboxImageState.ABSENT);

    public SandboxImageService(
            ProcessExecutor executor, HostScriptLocator locator, ServerProperties props, AuditLogger audit) {
        this.executor = executor;
        this.locator = locator;
        this.props = props;
        this.audit = audit;
    }

    /** Current image state as last observed/updated by {@link #warm()}. */
    public SandboxImageState state() {
        return state.get();
    }

    /**
     * Ensure the sandbox image is present and current, building it only when
     * it is {@link Staleness#ABSENT absent}. Idempotent and concurrency-safe:
     * a single CAS claims the build slot, so concurrent callers never trigger
     * a second build. A present-but-{@link Staleness#STALE stale} image is left
     * in place — refreshing a stale image is the upgrade path's job, not the
     * warm path's.
     *
     * <p>Synchronous: the calling thread runs the {@code docker compose build}.
     * The startup runner and the spawn gate both invoke this OFF the request
     * thread (the runner on a daemon thread; the gate via {@link #warmAsync()}),
     * so a request is never blocked on the heavy build.
     *
     * @return the resulting state ({@link SandboxImageState#READY READY} on
     *     success, {@link SandboxImageState#FAILED FAILED} on build/classify
     *     failure, or {@link SandboxImageState#BUILDING BUILDING} when another
     *     caller already owns the build).
     */
    public SandboxImageState warm() {
        SandboxImageState observed = state.get();
        if (observed == SandboxImageState.READY) {
            return SandboxImageState.READY;
        }
        if (observed == SandboxImageState.BUILDING) {
            // Another caller already owns the build slot.
            return SandboxImageState.BUILDING;
        }
        // observed is ABSENT or FAILED — try to claim the build slot.
        if (!state.compareAndSet(observed, SandboxImageState.BUILDING)) {
            // Lost the race; report whatever the winner has set so far.
            return state.get();
        }

        String version = ServerVersion.current();
        long startNanos = System.nanoTime();
        try {
            Staleness staleness = classifyImage(version);
            if (staleness != Staleness.ABSENT) {
                // CURRENT or STALE — image is present; no build on the warm path.
                LOG.info(
                        "Sandbox image {} already present ({}); warm build skipped",
                        EnsureSandboxImage.IMAGE_TAG,
                        staleness);
                state.set(SandboxImageState.READY);
                return SandboxImageState.READY;
            }
            LOG.info(
                    "Sandbox image {} absent — starting warm build (bounded at {}s)",
                    EnsureSandboxImage.IMAGE_TAG,
                    props.limits().imageBuildTimeoutSeconds());
            buildImage(version);
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.info("Sandbox image {} warm build succeeded in {} ms", EnsureSandboxImage.IMAGE_TAG, ms);
            // AC3 — a long cold build is logged/audited DISTINCTLY from a hard
            // spawn_failed, so operators can tell "warming up" from "broken".
            audit.logEvent(
                    AuditAction.SANDBOX_IMAGE_WARM, "ok", "image", EnsureSandboxImage.IMAGE_TAG, "durationMs", ms);
            state.set(SandboxImageState.READY);
            return SandboxImageState.READY;
        } catch (IOException e) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            LOG.warn(
                    "Sandbox image {} warm build failed after {} ms: {}",
                    EnsureSandboxImage.IMAGE_TAG,
                    ms,
                    e.toString());
            audit.logEvent(
                    AuditAction.SANDBOX_IMAGE_WARM,
                    "fail",
                    "image",
                    EnsureSandboxImage.IMAGE_TAG,
                    "durationMs",
                    ms,
                    "error",
                    e.getClass().getSimpleName());
            state.set(SandboxImageState.FAILED);
            return SandboxImageState.FAILED;
        }
    }

    /**
     * Kick {@link #warm()} on a daemon thread and return immediately — used by
     * the spawn gate so the request thread never runs the heavy build. A no-op
     * when a build is already in flight or the image is already ready (warm()
     * is itself CAS-guarded, so a redundant kick is harmless).
     */
    public void warmAsync() {
        SandboxImageState s = state.get();
        if (s == SandboxImageState.READY || s == SandboxImageState.BUILDING) {
            return;
        }
        Thread t = new Thread(this::warm, "sandbox-image-warm-async");
        t.setDaemon(true);
        t.start();
    }

    /** Run the label-reading inspect through the server executor and classify it. */
    private Staleness classifyImage(String version) throws IOException {
        Duration timeout = Duration.ofSeconds(props.limits().spawnTimeoutSeconds());
        ProcessExecutor.Result res = executor.run(EnsureSandboxImage.inspectLabelArgv(), locator.repoRoot(), timeout);
        String labelTrim = res.stdout() == null ? "" : res.stdout().trim();
        return EnsureSandboxImage.classify(res.exitCode(), labelTrim, version);
    }

    /**
     * Run the {@code docker compose build} (bounded by the warm-build timeout,
     * NOT the spawn timeout) and verify the image is present afterwards. Uses
     * the server's host-scripts repo root as both the compose file location and
     * the {@code --project-directory}, matching the runtime layout the onboard
     * build targets ({@code <installDir>/host}).
     */
    private void buildImage(String version) throws IOException {
        Path repoRoot = locator.repoRoot();
        Path composeFile = repoRoot.resolve("docker-compose.yml");
        Duration buildTimeout = Duration.ofSeconds(props.limits().imageBuildTimeoutSeconds());
        ProcessExecutor.Result build =
                executor.run(EnsureSandboxImage.buildArgv(composeFile, repoRoot, version), repoRoot, buildTimeout);
        if (build.exitCode() != 0) {
            throw new IOException("warm build of " + EnsureSandboxImage.IMAGE_TAG + " failed (exit=" + build.exitCode()
                    + "): " + build.stderr());
        }
        Duration probeTimeout = Duration.ofSeconds(props.limits().spawnTimeoutSeconds());
        ProcessExecutor.Result present = executor.run(EnsureSandboxImage.inspectPresentArgv(), repoRoot, probeTimeout);
        if (present.exitCode() != 0) {
            throw new IOException(
                    "warm build reported success but " + EnsureSandboxImage.IMAGE_TAG + " is still not present");
        }
    }
}
