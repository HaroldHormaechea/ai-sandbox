package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());

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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());

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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());

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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());
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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());
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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());

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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());

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
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());

        assertThat(facade.deleteSession(5, true)).isTrue();
        verify(registry, never()).exists(anyInt());
        verify(exec).clean(eq(5), any());
    }
}
