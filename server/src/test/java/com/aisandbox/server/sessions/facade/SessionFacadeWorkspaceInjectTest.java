package com.aisandbox.server.sessions.facade;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.aisandbox.server.sessions.SpawnPromptInjector;
import com.aisandbox.server.sessions.dto.ClaudeConfigMode;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.dto.WorkspaceMode;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.facade.internal.SpawnMutex;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import com.aisandbox.server.sessions.service.SessionReadinessService;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.sessions.service.TerminatingSessions;
import com.aisandbox.server.workspace.dto.WorkspaceProject;
import com.aisandbox.server.workspace.facade.WorkspaceProjectFacade;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * UC-98 — post-spawn workspace-project prompt-injection orchestration in
 * {@link SessionFacade}. Lives in the {@code ...facade} package so it can wire
 * the {@code @Autowired(required=false)} collaborators and inject a synchronous
 * {@code postSpawnExecutor} ({@code Runnable::run}) through the package-private
 * {@link SessionFacade#setPostSpawnExecutor} test seam — this makes the
 * readiness-wait + inject run inline on the calling thread so the ordering
 * ("only after {@code awaitReady} is true, exactly once") is deterministic.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>AC4/AC5/AC6 — a valid selection injects the fixed prompt EXACTLY once,
 *       with the folder name substituted, only after readiness is confirmed;</li>
 *   <li>AC3 — "None" (null id) probes nothing and injects nothing (spawn is
 *       byte-identical to pre-UC-98);</li>
 *   <li>AC10 — a stale/deleted id at schedule time OR at the pre-inject
 *       re-validation injects nothing, yet the spawn still succeeds;</li>
 *   <li>AC6 — a session that never becomes ready injects nothing;</li>
 *   <li>back-compat — unwired collaborators skip injection without failing the
 *       spawn.</li>
 * </ul>
 */
class SessionFacadeWorkspaceInjectTest {

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

    /** Collaborators bundled so each test wires exactly what it needs. */
    private record Rig(
            SessionFacade facade,
            WorkspaceProjectFacade projects,
            SessionReadinessService readiness,
            SpawnPromptInjector injector) {}

    private static Rig rig(boolean wireUc98) throws Exception {
        SessionRegistryService registry = mock(SessionRegistryService.class);
        ScriptExecutorService exec = mock(ScriptExecutorService.class);
        AuditLogger audit = mock(AuditLogger.class);
        // A successful spawn that assigns session 5.
        when(exec.spawn(any(), any())).thenReturn(new ProcessExecutor.Result(0, "ai-sandbox-5 ready", ""));

        SessionFacade facade = new SessionFacade(
                registry,
                exec,
                new SpawnMutex(),
                new PerSessionMutexRegistry(),
                audit,
                new TerminatingSessions(),
                props());

        WorkspaceProjectFacade projects = mock(WorkspaceProjectFacade.class);
        SessionReadinessService readiness = mock(SessionReadinessService.class);
        SpawnPromptInjector injector = mock(SpawnPromptInjector.class);

        if (wireUc98) {
            facade.setWorkspaceProjects(projects);
            facade.setReadiness(readiness);
            facade.setPromptInjector(injector);
            // Run the post-spawn task inline so ordering is deterministic.
            facade.setPostSpawnExecutor(Runnable::run);
        }
        return new Rig(facade, projects, readiness, injector);
    }

    private static SpawnCommand cmd(String workspaceProject) {
        return new SpawnCommand("label", WorkspaceMode.SHARED, ClaudeConfigMode.SHARED, workspaceProject);
    }

    @Test
    void valid_selection_injects_the_fixed_prompt_exactly_once_after_readiness() throws Exception {
        Rig r = rig(true);
        // id "proj-1"; folder/display name "cool-folder" — AC5 proves the prompt
        // substitutes the folder NAME (not the id).
        WorkspaceProject project = new WorkspaceProject("proj-1", "cool-folder");
        when(r.projects().find("proj-1")).thenReturn(Optional.of(project));
        when(r.readiness().awaitReady(eq(5), any(Duration.class), any(Duration.class)))
                .thenReturn(true);

        int n = r.facade().spawnSession(cmd("proj-1"));

        assertThat(n).isEqualTo(5);
        // AC6 — readiness confirmed before inject.
        verify(r.readiness()).awaitReady(eq(5), any(Duration.class), any(Duration.class));
        // AC4/AC5 — injected + submitted exactly once with the folder name substituted.
        verify(r.injector(), times(1)).inject(5, "We will work in the project cool-folder.");
    }

    @Test
    void none_selection_probes_nothing_and_injects_nothing() throws Exception {
        Rig r = rig(true);

        int n = r.facade().spawnSession(cmd(null));

        assertThat(n).isEqualTo(5);
        // AC3 — spawning with "None" is byte-identical to pre-UC-98: no lookup,
        // no readiness probe, no injection.
        verify(r.projects(), never()).find(any());
        verify(r.readiness(), never()).awaitReady(anyInt(), any(), any());
        verify(r.injector(), never()).inject(anyInt(), any());
    }

    @Test
    void stale_id_at_schedule_time_injects_nothing_but_spawn_succeeds() throws Exception {
        Rig r = rig(true);
        // AC10 — the folder was deleted between listing and spawn.
        when(r.projects().find("gone")).thenReturn(Optional.empty());

        int n = r.facade().spawnSession(cmd("gone"));

        assertThat(n).isEqualTo(5);
        verify(r.readiness(), never()).awaitReady(anyInt(), any(), any());
        verify(r.injector(), never()).inject(anyInt(), any());
    }

    @Test
    void id_that_vanishes_during_the_readiness_wait_injects_nothing() throws Exception {
        Rig r = rig(true);
        // AC10 — present at schedule time, gone by the pre-inject re-validation.
        when(r.projects().find("racy")).thenReturn(Optional.of(new WorkspaceProject("racy", "racy")), Optional.empty());
        when(r.readiness().awaitReady(eq(5), any(Duration.class), any(Duration.class)))
                .thenReturn(true);

        int n = r.facade().spawnSession(cmd("racy"));

        assertThat(n).isEqualTo(5);
        verify(r.injector(), never()).inject(anyInt(), any());
    }

    @Test
    void session_that_never_becomes_ready_injects_nothing() throws Exception {
        Rig r = rig(true);
        when(r.projects().find("proj-1")).thenReturn(Optional.of(new WorkspaceProject("proj-1", "proj-1")));
        // AC6 — readiness marker never appears within the timeout.
        when(r.readiness().awaitReady(eq(5), any(Duration.class), any(Duration.class)))
                .thenReturn(false);

        int n = r.facade().spawnSession(cmd("proj-1"));

        assertThat(n).isEqualTo(5);
        verify(r.injector(), never()).inject(anyInt(), any());
    }

    @Test
    void unwired_collaborators_skip_injection_without_failing_the_spawn() throws Exception {
        // Back-compat: a pre-UC-98 wiring (collaborators unset) with a project id
        // selected simply skips the injection and spawns normally.
        Rig r = rig(false);

        int n = r.facade().spawnSession(cmd("proj-1"));

        assertThat(n).isEqualTo(5);
        verify(r.injector(), never()).inject(anyInt(), any());
    }
}
