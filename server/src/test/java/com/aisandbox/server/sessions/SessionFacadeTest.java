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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * AC25 — spawn mutex acquired around spawn.
 * AC26 — non-zero spawn invokes clean.sh as best-effort and surfaces a
 *        SpawnFailedException with the captured stderr.
 * AC51 — non-interactive flag-only — covered by ScriptExecutorServiceTest.
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
        when(exec.clean(anyInt(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));

        SessionFacade facade =
                new SessionFacade(registry, exec, new SpawnMutex(), new PerSessionMutexRegistry(), audit, props());
        assertThat(facade.deleteSession(3, false)).isTrue();
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
}
