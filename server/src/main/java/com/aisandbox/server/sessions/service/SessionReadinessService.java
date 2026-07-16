package com.aisandbox.server.sessions.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-98 — probes a session's spawn-time readiness marker
 * ({@code /tmp/aisandbox-ready}) so the post-spawn workspace-project prompt is
 * injected only AFTER the container's entrypoint has finished provisioning and
 * the tmux {@code main} session (with Claude) is up (AC6, cf. UC-95).
 *
 * <p>The marker is written unconditionally by {@code entrypoint.sh} once the
 * session is ready; server-side polling is the right mechanism here because the
 * injection is independent of the user attaching (AC6). This mirrors the same
 * {@code docker compose -p ai-sandbox-<n> exec -T claude-sandbox test -f
 * /tmp/aisandbox-ready} probe that {@link DockerEnumerationService} uses for the
 * {@code running}/{@code provisioning} distinction.
 *
 * <p>Conservative by design: ANY failure to confirm the marker (exec error,
 * non-zero exit because the file is absent, container not yet exec-able,
 * timeout) is treated as <em>not ready</em> — a session is never optimistically
 * called ready. This is a plain domain service (no {@code @Transactional}, no
 * repository — the project is process-backed, not DB-backed).
 */
@Service
public class SessionReadinessService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionReadinessService.class);

    /** Per-probe exec timeout — a single {@code test -f} is fast; keep the probe from hanging. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final ProcessExecutor executor;

    public SessionReadinessService(ProcessExecutor executor) {
        this.executor = executor;
    }

    /** The compose project name for session {@code n} (mirrors DockerEnumerationService). */
    private static String project(int n) {
        return "ai-sandbox-" + n;
    }

    /**
     * A single readiness probe: {@code true} only when the marker file exists in
     * session {@code n}'s container. Any error / non-zero exit → {@code false}.
     */
    public boolean isReady(int n) {
        try {
            ProcessExecutor.Result r = executor.run(
                    List.of(
                            "docker",
                            "compose",
                            "-p",
                            project(n),
                            "exec",
                            "-T",
                            "claude-sandbox",
                            "test",
                            "-f",
                            "/tmp/aisandbox-ready"),
                    null,
                    PROBE_TIMEOUT);
            return r.exitCode() == 0;
        } catch (IOException io) {
            LOG.debug("isReady({}): {}", n, io.toString());
            return false;
        }
    }

    /**
     * Poll {@link #isReady(int)} until the marker appears or {@code timeout}
     * elapses, waiting {@code interval} between probes.
     *
     * @return {@code true} as soon as the session is observed ready; {@code
     *     false} if it never became ready within {@code timeout} (or the wait was
     *     interrupted). Never throws — a failure to confirm readiness must not
     *     propagate into (and fail) the caller's post-spawn choreography.
     */
    public boolean awaitReady(int n, Duration timeout, Duration interval) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long intervalMillis = Math.max(1L, interval.toMillis());
        while (true) {
            if (isReady(n)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
