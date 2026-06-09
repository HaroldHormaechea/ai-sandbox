package com.aisandbox.server.sessions.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.dto.LifecycleAction;
import com.aisandbox.server.sessions.dto.SessionDetail;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.facade.internal.SpawnMutex;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.sessions.service.TerminatingSessions;
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
    private final TerminatingSessions terminating;
    private final Duration spawnTimeout;

    public SessionFacade(
            SessionRegistryService registry,
            ScriptExecutorService executor,
            SpawnMutex spawnMutex,
            PerSessionMutexRegistry perN,
            AuditLogger audit,
            TerminatingSessions terminating,
            ServerProperties props) {
        this.registry = registry;
        this.executor = executor;
        this.spawnMutex = spawnMutex;
        this.perN = perN;
        this.audit = audit;
        this.terminating = terminating;
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
            // UC-28: mark the session terminating BEFORE clean.sh runs and
            // invalidate the cache so a concurrent GET /v1/sessions poll
            // observes `terminating` for the whole teardown window (the raw
            // Docker `removing` state is too brief/racy to rely on alone).
            terminating.markTerminating(n);
            registry.invalidate();
            try {
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
                return r.exitCode() == 0;
            } finally {
                // UC-28: clear the flag and invalidate AGAIN even if clean()
                // threw — otherwise the 1s registry cache could keep serving
                // `terminating` after the flag is gone, and a failed teardown
                // must revert to the session's real status (UC-28 AC8). Both
                // run unconditionally; success → the row vanishes from the
                // next enumeration, failure → it reverts to its real state.
                terminating.clear(n);
                registry.invalidate();
            }
        } finally {
            l.unlock();
            perN.evict(n);
        }
    }

    /**
     * UC-46 — drive a Docker-lifecycle action (stop/start/pause/unpause) on
     * session {@code n}. Mirrors {@link #deleteSession} for concurrency
     * (AC8): the SAME per-N mutex serialises a lifecycle action against a
     * concurrent delete / other lifecycle action on the same session, so two
     * overlapping requests can't corrupt container state.
     *
     * <p>The facade owns the token→{@link LifecycleAction} parse (rather than
     * the controller) so the {@code ..api} layer never depends on the internal
     * {@code ..sessions.dto} type — {@code profile-java-server-architecture}
     * rule 5, enforced by {@code LayeringTest}. The controller passes the raw
     * regex-pinned path token straight through.
     *
     * <p>Flow:
     *
     * <ol>
     *   <li>Parse {@code actionToken} via {@link LifecycleAction#fromToken}
     *       (unknown token → {@link IllegalArgumentException} → 400; the
     *       controller's path regex makes this unreachable in practice).</li>
     *   <li>Acquire {@code perN.get(n)} (2s tryLock; timeout → {@link
     *       IOException} → 5xx).</li>
     *   <li>Read the session's current state from {@link
     *       SessionRegistryService#list()} INSIDE the lock so the state used
     *       for the validity check can't race a concurrent transition. An
     *       absent {@code n} throws {@link NoSuchElementException} → 404
     *       {@code session_not_found} (AC4 "safely rejects").</li>
     *   <li>If {@code !action.isValidFrom(state)} throw {@link
     *       InvalidLifecycleTransitionException} → 409 {@code
     *       session_state_conflict} (AC3/AC4 — an out-of-state request is
     *       rejected, never a silent no-op).</li>
     *   <li>Run {@code lifecycle.sh} via the executor; invalidate the
     *       registry cache + evict the per-N mutex in {@code finally} so the
     *       next {@code GET /v1/sessions} re-enumerates the new Docker state
     *       (AC6 reconciliation).</li>
     * </ol>
     *
     * <p>No {@code @Transactional}: the project has no data store, and
     * {@code LayeringTest} enforces that {@code @Transactional} stays off
     * everything (there is no transactional resource to join).
     *
     * @param actionToken the wire path token {@code stop|start|pause|unpause}
     * @return {@code true} when {@code lifecycle.sh} exited 0; {@code false}
     *     when it ran but exited non-zero (mapped to 500 by the controller)
     * @throws IllegalArgumentException for an unknown action token (→ 400)
     * @throws NoSuchElementException when no session {@code n} exists (→ 404)
     * @throws InvalidLifecycleTransitionException when the action is invalid
     *     for the current state (→ 409)
     */
    public boolean lifecycle(int n, String actionToken) throws IOException, InterruptedException {
        LifecycleAction action = LifecycleAction.fromToken(actionToken);
        ReentrantLock l = perN.get(n);
        if (!l.tryLock(2_000L, TimeUnit.MILLISECONDS)) {
            throw new IOException("Timed out acquiring per-session mutex for N=" + n);
        }
        try {
            String state = registry.list().stream()
                    .filter(r -> r.n() == n)
                    .map(SessionRecord::state)
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("session " + n + " not found"));
            if (!action.isValidFrom(state)) {
                throw new InvalidLifecycleTransitionException(n, action, state);
            }
            try {
                ProcessExecutor.Result r = executor.lifecycle(action, n, spawnTimeout);
                audit.logEvent(
                        AuditAction.SESSION_LIFECYCLE,
                        r.exitCode() == 0 ? "ok" : "fail",
                        "action",
                        action.flag(),
                        "n",
                        n,
                        "exitCode",
                        r.exitCode());
                return r.exitCode() == 0;
            } finally {
                // The action changed (or attempted to change) the container's
                // Docker state — drop the 1s enumeration cache so the next
                // poll re-reads the authoritative state (AC6).
                registry.invalidate();
            }
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

    /**
     * UC-46 — thrown when a lifecycle action is requested for a session whose
     * current state does not permit it (per {@link
     * LifecycleAction#isValidFrom(String)}). Mapped to 409 {@code
     * session_state_conflict} by {@code ProblemDetailsAdvice}.
     */
    public static final class InvalidLifecycleTransitionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int n;
        public final transient LifecycleAction action;
        public final String currentState;

        public InvalidLifecycleTransitionException(int n, LifecycleAction action, String currentState) {
            super("cannot " + action.flag() + " session " + n + " in state '" + currentState + "'");
            this.n = n;
            this.action = action;
            this.currentState = currentState;
        }
    }
}
