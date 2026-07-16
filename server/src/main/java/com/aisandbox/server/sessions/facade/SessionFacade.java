package com.aisandbox.server.sessions.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.SpawnPromptInjector;
import com.aisandbox.server.sessions.dto.LifecycleAction;
import com.aisandbox.server.sessions.dto.SessionDetail;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.facade.internal.SpawnMutex;
import com.aisandbox.server.sessions.service.HostShellSessionService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.SandboxImageState;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import com.aisandbox.server.sessions.service.SessionReadinessService;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.sessions.service.TerminatingSessions;
import com.aisandbox.server.workspace.dto.WorkspaceProject;
import com.aisandbox.server.workspace.facade.WorkspaceProjectFacade;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * UC-62 — the host-shell service. Injected via a setter (not the
     * constructor) so the existing unit-test constructions of this facade
     * compile unchanged — the same late-binding pattern {@code StreamFacade}
     * uses for its swarm enumerator. It is a regular always-present
     * {@code @Service} in production; only the {@code server-ssh} paths
     * ({@link #createServerSsh()} and the {@link SpecialSessions#SERVER_SSH_N}
     * branches in delete/lifecycle) touch it, and those are never exercised by
     * the pre-UC-62 tests that leave it unset.
     */
    private volatile HostShellSessionService hostShell;

    /**
     * UC-77 — the sandbox-image warm-up facade. Injected via a setter (not the
     * constructor) so the existing unit-test constructions of this facade
     * compile unchanged — the same late-binding pattern {@link #hostShell}
     * uses. In production it is always present; when unset (pre-UC-77 tests that
     * don't wire it) the spawn gate is skipped and spawning behaves exactly as
     * before.
     */
    private volatile SandboxImageFacade sandboxImage;

    /**
     * UC-98 — the workspace-project catalogue facade, readiness probe, and the
     * prompt-injection port. All three are injected via {@code @Autowired(required
     * = false)} setters (same late-binding pattern as {@link #hostShell} /
     * {@link #sandboxImage}) so the pre-UC-98 unit constructions of this facade
     * compile unchanged. In production all three are always present; when any is
     * unset (pre-UC-98 tests) the post-spawn injection is skipped entirely and
     * spawning behaves exactly as before (AC3). Cross-domain reads go
     * facade-to-facade ({@code sessions → workspace}); the readiness probe is a
     * same-domain service; the inject is behind the {@link SpawnPromptInjector}
     * port implemented by the conversation domain (edge {@code stream →
     * sessions}) — all acyclic.
     */
    private volatile WorkspaceProjectFacade workspaceProjects;

    private volatile SessionReadinessService readiness;

    private volatile SpawnPromptInjector promptInjector;

    /**
     * UC-98 — how long to wait for a freshly-spawned session's readiness marker
     * before giving up on the setup-prompt injection, and how often to re-probe.
     * The marker appears after the container's UC-27 provisioning + tmux/Claude
     * come up, which can take a while, so the window is generous; the injection
     * is best-effort, so a timeout simply skips it (the session is unaffected).
     */
    private static final Duration READINESS_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration READINESS_POLL_INTERVAL = Duration.ofSeconds(2);

    /**
     * UC-98 — runs the post-spawn readiness-wait + inject OFF the request thread
     * so {@code POST /v1/sessions} returns as soon as {@code spawn.sh} does
     * (AC6: injection is independent of the response / the user attaching).
     * Defaults to a fresh daemon thread per task; a test injects a synchronous
     * executor via {@link #setPostSpawnExecutor} to assert the inject ordering
     * deterministically.
     */
    private volatile Executor postSpawnExecutor = task -> {
        Thread t = new Thread(task, "uc98-post-spawn-inject");
        t.setDaemon(true);
        t.start();
    };

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

    /** Late-binding injection of the UC-62 host-shell service (see field doc). */
    @Autowired(required = false)
    public void setHostShell(HostShellSessionService hostShell) {
        this.hostShell = hostShell;
    }

    /** Late-binding injection of the UC-77 sandbox-image facade (see field doc). */
    @Autowired(required = false)
    public void setSandboxImage(SandboxImageFacade sandboxImage) {
        this.sandboxImage = sandboxImage;
    }

    /** UC-98 — late-binding injection of the workspace-project catalogue facade (see field doc). */
    @Autowired(required = false)
    public void setWorkspaceProjects(WorkspaceProjectFacade workspaceProjects) {
        this.workspaceProjects = workspaceProjects;
    }

    /** UC-98 — late-binding injection of the readiness probe (see field doc). */
    @Autowired(required = false)
    public void setReadiness(SessionReadinessService readiness) {
        this.readiness = readiness;
    }

    /** UC-98 — late-binding injection of the prompt-injection port (see field doc). */
    @Autowired(required = false)
    public void setPromptInjector(SpawnPromptInjector promptInjector) {
        this.promptInjector = promptInjector;
    }

    /**
     * UC-98 — override the executor that runs the post-spawn readiness-wait +
     * inject. Visible for testing: a test passes a synchronous executor
     * ({@code Runnable::run}) so the inject ordering (only after {@code
     * awaitReady} is true, exactly once) can be asserted without a background
     * thread.
     */
    void setPostSpawnExecutor(Executor postSpawnExecutor) {
        this.postSpawnExecutor = postSpawnExecutor;
    }

    /**
     * UC-62 — create (or focus, if it already exists) the singleton server
     * host-shell session and return its pinned row.
     *
     * <p>The server-side singleton guard lives in
     * {@link HostShellSessionService#ensureCreated()} (idempotent + lock-
     * serialised), so two near-simultaneous {@code POST /v1/sessions/server-ssh}
     * requests yield exactly one tmux and one row (AC2, AC13); the second simply
     * focuses the existing one. The registry cache is invalidated so the next
     * {@code GET /v1/sessions} re-lists the now-present row.
     *
     * @return the host-shell row (reserved id, {@code type=server-ssh}).
     */
    public SessionRecord createServerSsh() throws IOException {
        HostShellSessionService hs = requireHostShell();
        hs.ensureCreated();
        registry.invalidate();
        audit.logEvent(
                AuditAction.SESSION_SPAWN,
                "ok",
                "n",
                SpecialSessions.SERVER_SSH_N,
                "type",
                SpecialSessions.TYPE_SERVER_SSH);
        return hs.row();
    }

    private HostShellSessionService requireHostShell() {
        HostShellSessionService hs = this.hostShell;
        if (hs == null) {
            throw new IllegalStateException("host-shell service not wired");
        }
        return hs;
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
        // UC-77 — sandbox-image gate. The first interactive spawn on a cold
        // cache used to run spawn.sh's `docker compose up`, which builds the
        // absent image inside the tight spawn timeout → hard 500. The request
        // path now NEVER runs the heavy build: the image is warmed ahead of
        // time (startup runner + this gate's async kick), and a spawn arriving
        // before it is ready is rejected fast with 503 sandbox_image_warming.
        // READY → proceed unchanged (warm path byte-identical to pre-UC-77).
        SandboxImageFacade img = this.sandboxImage;
        if (img != null) {
            SandboxImageState imgState = img.state();
            switch (imgState) {
                case READY -> {
                    // image present + current — fall through to the normal spawn.
                }
                case BUILDING -> throw new SandboxImageWarmingException(imgState);
                case ABSENT, FAILED -> {
                    // Kick the warm build off-request and reject fast; the request
                    // thread must never run the minutes-long build.
                    img.warmAsync();
                    throw new SandboxImageWarmingException(imgState);
                }
            }
        }
        spawnMutex.acquire();
        try {
            ProcessExecutor.Result result;
            try {
                result = executor.spawn(cmd, spawnTimeout);
            } catch (ProcessExecutor.ExecTimeoutException te) {
                // UC-77 — spawn.sh exceeded the (build-free, still-tight) spawn
                // timeout: a genuinely stuck `up`. Surface as 504 spawn_timeout,
                // distinct from a 500 spawn_failed (non-zero exit). With the image
                // warmed up-front, this is no longer triggered by a cold build.
                audit.logEvent(
                        AuditAction.SESSION_SPAWN,
                        "timeout",
                        "timeoutSeconds",
                        spawnTimeout.toSeconds(),
                        "label",
                        String.valueOf(cmd.label()));
                throw new SpawnTimeoutException(spawnTimeout.toSeconds(), te.getMessage());
            }
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
            // UC-98 — if a workspace project was selected, schedule the off-request
            // readiness-wait + one-shot setup-prompt injection. "None" (null) is a
            // no-op, so spawning without a project is byte-identical to pre-UC-98
            // (AC3). The scheduling never affects the spawn's success/response.
            scheduleWorkspaceProjectInjection(assignedN, cmd.workspaceProject());
            return assignedN;
        } finally {
            spawnMutex.release();
        }
    }

    /**
     * UC-98 — schedule the post-spawn workspace-project setup-prompt injection
     * for session {@code n}, if a project was selected.
     *
     * <p>Runs entirely off the request thread (AC6): the request has already
     * spawned the session, so this only pre-seeds the prompt before the user
     * attaches. Nothing here can fail the spawn — every path is guarded and
     * best-effort.
     *
     * <p>Flow:
     *
     * <ol>
     *   <li>"None" (null id) → do nothing (AC3).</li>
     *   <li>Collaborators unwired (pre-UC-98 tests) → skip silently.</li>
     *   <li>Re-validate the id against the LIVE listing at schedule time; an
     *       absent id (stale/deleted between listing and spawn) degrades to
     *       "no project" — skip + audit, spawn already CREATED (AC10).</li>
     *   <li>Otherwise submit a task that waits for readiness, re-validates the
     *       id ONE more time immediately before injecting (AC10), then injects
     *       + submits the fixed prompt EXACTLY once (AC4).</li>
     * </ol>
     */
    private void scheduleWorkspaceProjectInjection(int n, String projectId) {
        if (projectId == null) {
            return; // "None" — preserve today's spawn behaviour exactly (AC3).
        }
        // Null-guard the @Autowired(required=false) collaborators BEFORE any
        // thread could dereference them (challenger's non-blocking note): a
        // pre-UC-98 wiring with any of them unset simply skips injection.
        WorkspaceProjectFacade projects = this.workspaceProjects;
        SessionReadinessService ready = this.readiness;
        SpawnPromptInjector injector = this.promptInjector;
        if (projects == null || ready == null || injector == null) {
            LOG.debug("UC-98 post-spawn injection skipped for session {}: collaborators unwired", n);
            return;
        }
        // Re-validate at SCHEDULE time (AC10): the folder may have been deleted
        // between listing and spawn.
        Optional<WorkspaceProject> atSchedule = projects.find(projectId);
        if (atSchedule.isEmpty()) {
            audit.logEvent(AuditAction.SESSION_WORKSPACE_INJECT, "skip", "n", n, "project", projectId, "reason", "stale");
            return;
        }
        postSpawnExecutor.execute(() -> runWorkspaceProjectInjection(n, projectId, ready, projects, injector));
    }

    /**
     * UC-98 — the off-request task body: wait for readiness, re-validate, inject.
     * Never throws — a failure here must not crash the daemon thread or affect
     * the (already successful) spawn.
     */
    private void runWorkspaceProjectInjection(
            int n,
            String projectId,
            SessionReadinessService ready,
            WorkspaceProjectFacade projects,
            SpawnPromptInjector injector) {
        try {
            // AC6 — only inject once the session's readiness marker is up.
            if (!ready.awaitReady(n, READINESS_TIMEOUT, READINESS_POLL_INTERVAL)) {
                audit.logEvent(
                        AuditAction.SESSION_WORKSPACE_INJECT, "skip", "n", n, "project", projectId, "reason", "not-ready");
                return;
            }
            // AC10 — re-validate immediately before injecting; the folder may
            // have vanished during the (potentially long) readiness wait.
            Optional<WorkspaceProject> preInject = projects.find(projectId);
            if (preInject.isEmpty()) {
                audit.logEvent(
                        AuditAction.SESSION_WORKSPACE_INJECT, "skip", "n", n, "project", projectId, "reason", "stale");
                return;
            }
            // AC4/AC5 — the fixed prompt with the folder name substituted, typed
            // and submitted exactly once (the injector presses Enter).
            String folder = preInject.get().name();
            injector.inject(n, "We will work in the project " + folder + ".");
            audit.logEvent(AuditAction.SESSION_WORKSPACE_INJECT, "ok", "n", n, "project", folder);
        } catch (IOException | RuntimeException e) {
            // Non-fatal: the session is already spawned. Log + audit, never retry
            // or re-inject (AC4 — exactly once), never crash the thread.
            LOG.warn("UC-98 setup-prompt injection failed for session {} (project {}): {}", n, projectId, e.toString());
            audit.logEvent(
                    AuditAction.SESSION_WORKSPACE_INJECT, "fail", "n", n, "project", projectId, "error", e.toString());
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
            // UC-62 — the host-shell row is not a Docker container: Remove kills
            // the host tmux directly (AC11) and SKIPS clean.sh + the terminating
            // machinery (which would otherwise shell `docker` against the
            // nonexistent ai-sandbox-0, since clean.sh only guards n < 0). The
            // per-N lock is still held to serialise against a concurrent create.
            if (n == SpecialSessions.SERVER_SSH_N) {
                requireHostShell().kill();
                registry.invalidate();
                audit.logEvent(AuditAction.SESSION_KILL, "ok", "n", n, "type", SpecialSessions.TYPE_SERVER_SSH);
                return true;
            }
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
        // UC-62 — the host-shell row exposes ONLY Remove (no stop/start/pause/
        // unpause): its menu offers no lifecycle items and it is not a Docker
        // container. Reject any lifecycle action on it as 404 BEFORE parsing the
        // action or touching lifecycle.sh, which (guarding only n < 0) would
        // otherwise shell `docker` against the nonexistent ai-sandbox-0.
        if (n == SpecialSessions.SERVER_SSH_N) {
            throw new NoSuchElementException("session " + n + " not found");
        }
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
     * UC-77 — thrown when {@code spawn.sh} exceeds the per-request spawn
     * timeout (a genuinely stuck {@code up}, no longer a cold build — the image
     * is warmed ahead of time). Mapped to 504 {@code spawn_timeout} by
     * {@code ProblemDetailsAdvice}, distinct from the 500 {@code spawn_failed}
     * (non-zero exit) path, so a slow/stuck spawn is distinguishable from a hard
     * failure (AC3).
     */
    public static final class SpawnTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final long timeoutSeconds;

        public SpawnTimeoutException(long timeoutSeconds, String detail) {
            super("spawn.sh timed out after " + timeoutSeconds + "s" + (detail == null ? "" : " (" + detail + ")"));
            this.timeoutSeconds = timeoutSeconds;
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
