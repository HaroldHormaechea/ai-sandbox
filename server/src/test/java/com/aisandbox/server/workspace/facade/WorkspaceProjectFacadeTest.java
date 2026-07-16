package com.aisandbox.server.workspace.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.workspace.dto.WorkspaceProject;
import com.aisandbox.server.workspace.service.WorkspaceProjectService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * UC-98 — the read-only workspace-project facade is a thin use-case boundary
 * over {@link WorkspaceProjectService}: it lists the catalogue for the
 * {@code GET /v1/workspace/projects} controller (AC1) and resolves a selected
 * id against the <em>live</em> listing for the spawn-time membership check that
 * degrades a stale/deleted selection to "no project" (AC10).
 */
class WorkspaceProjectFacadeTest {

    @Test
    void listProjects_delegates_to_the_service() {
        WorkspaceProjectService svc = mock(WorkspaceProjectService.class);
        when(svc.list()).thenReturn(List.of(new WorkspaceProject("a", "a"), new WorkspaceProject("b", "b")));

        List<WorkspaceProject> out = new WorkspaceProjectFacade(svc).listProjects();

        assertThat(out).extracting(WorkspaceProject::id).containsExactly("a", "b");
    }

    @Test
    void find_returns_the_matching_project_when_it_is_in_the_live_listing() {
        // AC10 — a still-present id resolves, so the caller may inject.
        WorkspaceProjectService svc = mock(WorkspaceProjectService.class);
        when(svc.list()).thenReturn(List.of(new WorkspaceProject("proj-1", "proj-1")));

        Optional<WorkspaceProject> found = new WorkspaceProjectFacade(svc).find("proj-1");

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("proj-1");
    }

    @Test
    void find_is_empty_for_a_stale_or_deleted_id() {
        // AC10 — the folder vanished between listing and lookup → no match, so the
        // caller degrades gracefully to "no project" (no prompt injected).
        WorkspaceProjectService svc = mock(WorkspaceProjectService.class);
        when(svc.list()).thenReturn(List.of(new WorkspaceProject("still-here", "still-here")));

        assertThat(new WorkspaceProjectFacade(svc).find("was-deleted")).isEmpty();
    }

    @Test
    void find_is_empty_for_a_null_id() {
        // AC9 — a "None" selection never reaches find(), but guard it anyway.
        WorkspaceProjectService svc = mock(WorkspaceProjectService.class);

        assertThat(new WorkspaceProjectFacade(svc).find(null)).isEmpty();
    }
}
