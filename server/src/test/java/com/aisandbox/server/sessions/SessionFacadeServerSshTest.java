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
import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.dto.LifecycleAction;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.facade.SessionFacade;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.facade.internal.SpawnMutex;
import com.aisandbox.server.sessions.service.HostShellSessionService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.sessions.service.TerminatingSessions;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * UC-62 — the {@link SessionFacade} {@code server-ssh} branches: create-or-focus,
 * the host-shell Remove path, and the lifecycle 404. Mockito-only (no Spring),
 * mirroring {@link SessionFacadeTest}; the host-shell service is wired via the
 * late-binding {@code setHostShell} setter the production code uses.
 *
 * <p>AC mapping:
 *
 * <ul>
 *   <li><b>AC2 / AC13</b> — {@link #createServerSsh_ensures_tmux_invalidates_and_returns_pinned_row()}:
 *       the facade delegates the singleton guard to
 *       {@link HostShellSessionService#ensureCreated()} (idempotent) and returns
 *       the pinned row.</li>
 *   <li><b>AC11 + verify-no-interaction</b> —
 *       {@link #deleteSession_serverSsh_kills_host_tmux_and_never_runs_clean()}:
 *       Remove on the reserved id kills the host tmux and NEVER shells
 *       {@code clean.sh} (which only guards {@code n < 0} and would otherwise
 *       {@code docker}-clean the nonexistent {@code ai-sandbox-0}).</li>
 *   <li><b>AC8 + verify-no-interaction</b> —
 *       {@link #lifecycle_serverSsh_throws_not_found_and_never_runs_lifecycle_script()}:
 *       any lifecycle action on the reserved id 404s BEFORE parsing the token or
 *       touching {@code lifecycle.sh}.</li>
 *   <li><b>AC12 (regression)</b> —
 *       {@link #deleteSession_claude_still_runs_clean_unaffected_by_uc62()} and
 *       {@link #lifecycle_claude_still_runs_lifecycle_unaffected_by_uc62()}: a
 *       normal Claude session's delete / lifecycle is unchanged by the UC-62
 *       branches.</li>
 * </ul>
 */
class SessionFacadeServerSshTest {

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

    private static SessionRecord recordWithState(int n, String state) {
        return new SessionRecord(n, "", "(idle)", state, 0L, 0, Instant.EPOCH, null, false, false);
    }

    private static SessionFacade facade(
            SessionRegistryService registry, ScriptExecutorService exec, HostShellSessionService hostShell) {
        SessionFacade f = new SessionFacade(
                registry,
                exec,
                new SpawnMutex(),
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                new TerminatingSessions(),
                props());
        if (hostShell != null) {
            f.setHostShell(hostShell);
        }
        return f;
    }

    @Test
    void createServerSsh_ensures_tmux_invalidates_and_returns_pinned_row() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        when(hostShell.row())
                .thenReturn(new SessionRecord(
                        SpecialSessions.SERVER_SSH_N,
                        "",
                        "(idle)",
                        "running",
                        0L,
                        0,
                        Instant.EPOCH,
                        null,
                        false,
                        false,
                        SpecialSessions.TYPE_SERVER_SSH));

        SessionRecord row = facade(registry, exec, hostShell).createServerSsh();

        verify(hostShell).ensureCreated(); // server-side singleton guard (AC2/AC13)
        verify(registry).invalidate(); // next GET re-lists the now-present row (AC7)
        assertThat(row.n()).isEqualTo(SpecialSessions.SERVER_SSH_N);
        assertThat(row.type()).isEqualTo(SpecialSessions.TYPE_SERVER_SSH);
        // The create path NEVER spawns a Docker container.
        verify(exec, never()).spawn(any(), any());
    }

    @Test
    void createServerSsh_requires_a_wired_host_shell_service() {
        // Defensive: without setHostShell (an unwired facade) create fails loudly
        // rather than NPEing deeper in.
        SessionFacade f = facade(mock(SessionRegistryService.class), mock(ScriptExecutorService.class), null);
        assertThatThrownBy(f::createServerSsh)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host-shell service not wired");
    }

    @Test
    void deleteSession_serverSsh_kills_host_tmux_and_never_runs_clean() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        HostShellSessionService hostShell = mock(HostShellSessionService.class);

        boolean ok = facade(registry, exec, hostShell).deleteSession(SpecialSessions.SERVER_SSH_N, false);

        assertThat(ok).as("Remove on the host-shell row reports success (AC11)").isTrue();
        verify(hostShell).kill(); // AC11 — the host tmux MUST be killed
        verify(exec, never()).clean(anyInt(), any()); // verify-no-interaction — no clean.sh on ai-sandbox-0
        verify(registry).invalidate(); // the row vanishes from the next enumeration
        // The host-shell delete never consults registry.exists (it is unconditional).
        verify(registry, never()).exists(anyInt());
    }

    @Test
    void lifecycle_serverSsh_throws_not_found_and_never_runs_lifecycle_script() throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        SessionFacade f = facade(registry, exec, hostShell);

        // Even a perfectly valid token (stop) 404s on the reserved id, BEFORE the
        // token parse or lifecycle.sh — the host-shell menu offers only Remove (AC8).
        assertThatThrownBy(() -> f.lifecycle(SpecialSessions.SERVER_SSH_N, LifecycleAction.STOP.flag()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(String.valueOf(SpecialSessions.SERVER_SSH_N));

        verify(exec, never()).lifecycle(any(), anyInt(), any());
        verify(hostShell, never()).kill();
    }

    @Test
    void deleteSession_claude_still_runs_clean_unaffected_by_uc62() throws Exception {
        // AC12 regression — a normal Claude delete is unchanged: existence-gated,
        // then clean.sh runs; the host-shell service is never touched.
        SessionRegistryService registry = mock(SessionRegistryService.class);
        when(registry.exists(5)).thenReturn(true);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        when(exec.clean(eq(5), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));
        HostShellSessionService hostShell = mock(HostShellSessionService.class);

        boolean ok = facade(registry, exec, hostShell).deleteSession(5, false);

        assertThat(ok).isTrue();
        verify(exec).clean(eq(5), any());
        verify(hostShell, never()).kill();
    }

    @Test
    void lifecycle_claude_still_runs_lifecycle_unaffected_by_uc62() throws Exception {
        // AC12 regression — a normal Claude lifecycle action is unchanged.
        SessionRegistryService registry = mock(SessionRegistryService.class);
        when(registry.list()).thenReturn(List.of(recordWithState(5, "running")));
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        when(exec.lifecycle(eq(LifecycleAction.STOP), eq(5), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));
        HostShellSessionService hostShell = mock(HostShellSessionService.class);

        boolean ok = facade(registry, exec, hostShell).lifecycle(5, LifecycleAction.STOP.flag());

        assertThat(ok).isTrue();
        verify(exec).lifecycle(eq(LifecycleAction.STOP), eq(5), any());
        verify(hostShell, never()).kill();
    }
}
