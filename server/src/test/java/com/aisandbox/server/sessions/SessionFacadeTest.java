package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.dto.ClaudeConfigMode;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.dto.WorkspaceMode;
import com.aisandbox.server.sessions.facade.SessionFacade;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.facade.internal.SpawnMutex;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.sessions.service.TerminatingSessions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * AC25 — spawn mutex acquired around spawn.
 * AC26 — non-zero spawn invokes clean.sh as best-effort and surfaces a
 *        SpawnFailedException with the captured stderr.
 * AC51 — non-interactive flag-only — covered by ScriptExecutorServiceTest.
 *
 * <p>BUG 2 (session-create-delete-fix) — {@code deleteSession} is now
 * existence-gated when {@code force == false}. The facade-layer pins:
 *
 * <ul>
 *   <li>force=false + {@code registry.exists(n)==true}  → {@code clean.sh}
 *       runs (the fixed happy path — previously RED because the mock
 *       returned the default {@code false}).</li>
 *   <li>force=false + {@code registry.exists(n)==false} → {@link
 *       NoSuchElementException} (→ 404) and {@code clean.sh} is NOT
 *       invoked.</li>
 *   <li>force=false + {@code registry.exists(n)} throws {@link IOException}
 *       (enumeration outage) → the IOException propagates verbatim (→ 5xx),
 *       NOT a {@link NoSuchElementException}; a 404 on an outage would be a
 *       false "doesn't exist".</li>
 *   <li>force=true → the existence probe is skipped entirely and
 *       {@code clean.sh} runs unconditionally (operator escape hatch).</li>
 * </ul>
 */
class SessionFacadeTest {

    private static ServerProperties props() {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
    }

    @Test
    void spawn_success_invalidates_cache_and_audits_ok() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        when(exec.spawn(any(), any())).thenReturn(new ProcessExecutor.Result(0, "ai-sandbox-9 ready", ""));

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());

        int n = facade.spawnSession(new SpawnCommand("foo", WorkspaceMode.SHARED, ClaudeConfigMode.SHARED));

        assertThat(n).isEqualTo(9);
        verify(registry).invalidate();
        verify(exec, never()).clean(anyInt(), any());
    }

    @Test
    void spawn_failure_invokes_clean_and_throws() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        when(exec.spawn(any(), any())).thenReturn(new ProcessExecutor.Result(7, "ai-sandbox-4 emerging", "boom"));
        when(exec.clean(anyInt(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());

        assertThatThrownBy(() ->
                        facade.spawnSession(new SpawnCommand(null, WorkspaceMode.SHARED, ClaudeConfigMode.SHARED)))
                .isInstanceOf(SessionFacade.SpawnFailedException.class)
                .hasFieldOrPropertyWithValue("exitCode", 7)
                .hasFieldOrPropertyWithValue("consumedN", 4)
                .hasFieldOrPropertyWithValue("stderr", "boom");

        verify(exec).clean(eq(4), any());
    }

    @Test
    void spawn_failure_without_parseable_N_does_not_invoke_clean() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        when(exec.spawn(any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "early failure"));

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());

        assertThatThrownBy(() ->
                        facade.spawnSession(new SpawnCommand(null, WorkspaceMode.SHARED, ClaudeConfigMode.SHARED)))
                .isInstanceOf(SessionFacade.SpawnFailedException.class);

        verify(exec, never()).clean(anyInt(), any());
    }

    @Test
    void delete_invokes_clean_and_returns_true_on_exit_zero() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        // BUG 2 fix: deleteSession(3, false) now consults registry.exists(3)
        // BEFORE running clean.sh. The session must be present for the
        // happy path; without this stub the unstubbed mock returns the
        // default `false`, the gate throws NoSuchElementException, and this
        // test goes RED (the exact regression the Fix C gate introduces).
        when(registry.exists(3)).thenReturn(true);
        when(exec.clean(anyInt(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());
        assertThat(facade.deleteSession(3, false)).isTrue();
        verify(registry).exists(3);
        verify(exec).clean(eq(3), any());
    }

    @Test
    void delete_returns_false_on_non_zero_clean_exit() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        when(exec.clean(anyInt(), any())).thenReturn(new ProcessExecutor.Result(2, "", "fail"));

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());
        assertThat(facade.deleteSession(3, true)).isFalse();
    }

    // ── BUG 2 — existence-gated delete (force=false) ─────────────────────

    /**
     * force=false + the session does NOT exist → the gate throws
     * {@link NoSuchElementException} (mapped to 404 by the controller)
     * and {@code clean.sh} is never run. This is the central BUG 2 pin:
     * a phantom / already-gone N must NOT reach {@code clean.sh} (whose
     * exit-1 used to surface as a 500).
     */
    @Test
    void delete_absent_session_force_false_throws_not_found_and_skips_clean() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        when(registry.exists(42)).thenReturn(false);

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());

        assertThatThrownBy(() -> facade.deleteSession(42, false))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("42");

        verify(registry).exists(42);
        verify(exec, never()).clean(anyInt(), any());
    }

    /**
     * force=false + an enumeration OUTAGE ({@code registry.exists} throws
     * {@link IOException}) → the IOException propagates verbatim. It must
     * NOT be swallowed into a {@link NoSuchElementException}: an outage is
     * "unknown", not "absent", so the controller turns it into a 5xx, never
     * a false 404. {@code clean.sh} is not run.
     */
    @Test
    void delete_enumeration_outage_force_false_propagates_ioexception_not_not_found() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        when(registry.exists(3)).thenThrow(new IOException("docker enumeration unavailable"));

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());

        assertThatThrownBy(() -> facade.deleteSession(3, false))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("docker enumeration unavailable");

        verify(exec, never()).clean(anyInt(), any());
    }

    /**
     * force=true → the existence probe is SKIPPED entirely and
     * {@code clean.sh} runs unconditionally — the operator escape hatch
     * for stuck containers / degraded enumeration. {@code registry.exists}
     * must never be consulted.
     */
    @Test
    void delete_force_true_skips_existence_check_and_runs_clean() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        when(exec.clean(anyInt(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));

        SessionFacade facade =
                new SessionFacade(
                        registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, new TerminatingSessions(), props());

        assertThat(facade.deleteSession(5, true)).isTrue();
        verify(registry, never()).exists(anyInt());
        verify(exec).clean(eq(5), any());
    }

    // ── UC-28 — in-flight-delete registry: mark before clean, clear in finally ─

    /**
     * UC-28 AC7 — the session is flagged {@code terminating} BEFORE
     * {@code clean.sh} runs (so a concurrent {@code GET /v1/sessions} poll
     * observes {@code terminating} for the whole teardown window) and the flag
     * is cleared after a SUCCESSFUL teardown. The cache is invalidated twice —
     * once when the flag is set (so the next poll re-enumerates and sees
     * {@code terminating}) and once in the {@code finally} (so the row vanishes
     * once {@code clean.sh} succeeded).
     */
    @Test
    void delete_marks_terminating_before_clean_and_clears_after_success() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        TerminatingSessions terminating = new TerminatingSessions();
        when(registry.exists(3)).thenReturn(true);

        // Capture whether the session was already flagged terminating at the
        // exact moment clean.sh is invoked — proving the mark precedes clean.
        AtomicBoolean flaggedDuringClean = new AtomicBoolean(false);
        when(exec.clean(eq(3), any())).thenAnswer(inv -> {
            flaggedDuringClean.set(terminating.isTerminating(3));
            return new ProcessExecutor.Result(0, "", "");
        });

        SessionFacade facade = new SessionFacade(
                registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, terminating, props());

        assertThat(facade.deleteSession(3, false)).isTrue();

        assertThat(flaggedDuringClean)
                .as("the session MUST be flagged terminating BEFORE clean.sh runs (AC2/AC9)")
                .isTrue();
        assertThat(terminating.isTerminating(3))
                .as("a successful teardown clears the terminating flag in finally (AC7)")
                .isFalse();
        // Once at mark-time, once in finally.
        verify(registry, times(2)).invalidate();
    }

    /**
     * UC-28 AC8 — a teardown that FAILS ({@code clean.sh} exits non-zero) still
     * clears the terminating flag in the {@code finally} so the row reverts to
     * its real server-reported status rather than wedging on {@code terminating}
     * forever.
     */
    @Test
    void delete_clears_terminating_in_finally_on_clean_nonzero() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        TerminatingSessions terminating = new TerminatingSessions();
        when(exec.clean(eq(4), any())).thenReturn(new ProcessExecutor.Result(2, "", "compose down failed"));

        SessionFacade facade = new SessionFacade(
                registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, terminating, props());

        // force=true to skip the existence gate and run clean unconditionally.
        assertThat(facade.deleteSession(4, true)).isFalse();
        assertThat(terminating.isTerminating(4))
                .as("a FAILED teardown must still clear terminating (AC8) — no wedged pill")
                .isFalse();
        verify(registry, times(2)).invalidate();
    }

    /**
     * UC-28 AC8 — even when {@code clean.sh} THROWS (transport / IO error mid
     * teardown), the terminating flag is cleared in the {@code finally} and the
     * IOException propagates (so the controller maps it to a 5xx). A thrown
     * teardown must not leave the session pinned terminating.
     */
    @Test
    void delete_clears_terminating_in_finally_when_clean_throws() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        TerminatingSessions terminating = new TerminatingSessions();
        when(exec.clean(eq(6), any())).thenThrow(new IOException("docker daemon gone mid-teardown"));

        SessionFacade facade = new SessionFacade(
                registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, terminating, props());

        assertThatThrownBy(() -> facade.deleteSession(6, true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("docker daemon gone mid-teardown");
        assertThat(terminating.isTerminating(6))
                .as("a THROWN teardown must still clear terminating in finally (AC8)")
                .isFalse();
        verify(registry, times(2)).invalidate();
    }
}
