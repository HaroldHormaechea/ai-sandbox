package com.aisandbox.server.sessions.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.dto.SessionDetail;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.facade.internal.SpawnMutex;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Use-case-level entry point for the sessions domain. Owns the spawn
 * mutex (AC25), the per-N mutex (AC25 / DELETE serialisation), the
 * timeout-driven 504 path, and the post-failure best-effort cleanup
 * (AC26 — spawn failure invokes {@code clean.sh <N>} before responding 500).
 */
@Component
public class SessionFacade {

    private static final Logger LOG = LoggerFactory.getLogger(SessionFacade.class);

    private final SessionRegistryService registry;
    private final ScriptExecutorService executor;
    private final SpawnMutex spawnMutex;
    private final PerSessionMutexRegistry perN;
    private final AuditLogger audit;
    private final Duration spawnTimeout;

    public SessionFacade(
            SessionRegistryService registry,
            ScriptExecutorService executor,
            SpawnMutex spawnMutex,
            PerSessionMutexRegistry perN,
            AuditLogger audit,
            ServerProperties props) {
        this.registry = registry;
        this.executor = executor;
        this.spawnMutex = spawnMutex;
        this.perN = perN;
        this.audit = audit;
        this.spawnTimeout = Duration.ofSeconds(props.limits().spawnTimeoutSeconds());
    }

    public List<SessionRecord> listSessions() throws IOException {
        return registry.list();
    }

    public Optional<SessionDetail> getSession(int n) throws IOException {
        return registry.list().stream()
                .filter(r -> r.n() == n)
                .findFirst()
                .map(r -> new SessionDetail(r, "", "", List.of()));
    }

    /**
     * Spawn a new session. Sync — blocks until {@code spawn.sh} returns
     * (or the timeout trips).
     *
     * @return the assigned N
     * @throws SpawnFailedException on non-zero exit from {@code spawn.sh}
     */
    public int spawnSession(SpawnCommand cmd) throws IOException, InterruptedException {
        spawnMutex.acquire();
        try {
            ProcessExecutor.Result result = executor.spawn(cmd, spawnTimeout);
            int assignedN = parseAssignedN(result.stdout(), result.stderr());
            if (result.exitCode() != 0) {
                if (assignedN > 0) {
                    try {
                        executor.clean(assignedN, spawnTimeout);
                    } catch (IOException io) {
                        LOG.warn("Spawn-cleanup also failed for session {}: {}", assignedN, io.toString());
                    }
                }
                audit.logEvent(
                        AuditAction.SESSION_SPAWN,
                        "fail",
                        "exitCode",
                        result.exitCode(),
                        "label",
                        String.valueOf(cmd.label()));
                throw new SpawnFailedException(result.exitCode(), result.stderr(), assignedN);
            }
            registry.invalidate();
            audit.logEvent(
                    AuditAction.SESSION_SPAWN,
                    "ok",
                    "n",
                    assignedN,
                    "label",
                    cmd.label() == null ? "" : cmd.label(),
                    "workspaceMode",
                    cmd.workspaceMode().name(),
                    "claudeConfigMode",
                    cmd.claudeConfigMode().name());
            return assignedN;
        } finally {
            spawnMutex.release();
        }
    }

    /**
     * Delete a session.
     *
     * <p>Existence-gated to fix BUG 2 (a phantom / already-gone {@code N}
     * used to surface {@code clean.sh}'s exit-1 as a 500; it now 404s):
     *
     * <ul>
     *   <li>{@code force == false} (default): {@link
     *       SessionRegistryService#exists(int)} is consulted <em>inside</em>
     *       the per-N lock so a concurrent spawn/clean cannot race the
     *       check. An absent {@code N} throws {@link NoSuchElementException}
     *       (mapped to 404 {@code session_not_found} by {@code
     *       ProblemDetailsAdvice.handleNotFound}) and {@code clean.sh} is
     *       NOT run. An enumeration outage ({@code exists()} throwing {@link
     *       IOException}) propagates as a 5xx via the generic fallback —
     *       NEVER a 404, since a 404 on an outage would be a false
     *       "doesn't exist".</li>
     *   <li>{@code force == true}: the existence check is skipped and {@code
     *       clean.sh} runs unconditionally — the operator escape hatch for
     *       stuck containers / degraded enumeration, decoupling deletion
     *       from the registry.</li>
     * </ul>
     *
     * @return true when {@code clean.sh} exited 0; false when it ran but
     *     exited non-zero (mapped to 500 by the controller)
     */
    public boolean deleteSession(int n, boolean force) throws IOException, InterruptedException {
        ReentrantLock l = perN.get(n);
        if (!l.tryLock(2_000L, TimeUnit.MILLISECONDS)) {
            throw new IOException("Timed out acquiring per-session mutex for N=" + n);
        }
        try {
            if (!force && !registry.exists(n)) {
                throw new NoSuchElementException("session " + n + " not found");
            }
            ProcessExecutor.Result r = executor.clean(n, spawnTimeout);
            audit.logEvent(
                    AuditAction.SESSION_KILL,
                    r.exitCode() == 0 ? "ok" : "fail",
                    "n",
                    n,
                    "force",
                    force,
                    "exitCode",
                    r.exitCode());
            registry.invalidate();
            return r.exitCode() == 0;
        } finally {
            l.unlock();
            perN.evict(n);
        }
    }

    /** Best-effort parsing of the assigned N from spawn.sh output. */
    static int parseAssignedN(String stdout, String stderr) {
        String all = (stderr == null ? "" : stderr) + "\n" + (stdout == null ? "" : stdout);
        var m = java.util.regex.Pattern.compile("ai-sandbox-(\\d+)").matcher(all);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    /** Exception thrown when spawn.sh exits non-zero (post-cleanup). */
    public static final class SpawnFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int exitCode;
        public final String stderr;
        public final int consumedN;

        public SpawnFailedException(int exitCode, String stderr, int consumedN) {
            super("spawn.sh exited " + exitCode);
            this.exitCode = exitCode;
            this.stderr = stderr;
            this.consumedN = consumedN;
        }
    }
}
