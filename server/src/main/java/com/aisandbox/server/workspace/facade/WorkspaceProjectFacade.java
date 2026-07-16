package com.aisandbox.server.workspace.facade;

import com.aisandbox.server.workspace.dto.WorkspaceProject;
import com.aisandbox.server.workspace.service.WorkspaceProjectService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * UC-98 — use-case-level entry point for the workspace-project catalogue
 * ({@code GET /v1/workspace/projects}) and the spawn-time membership check.
 * Read-only facade (precedent: {@code ModelCatalogFacade}, {@code HealthFacade}).
 * No {@code @Transactional}: the catalogue is filesystem-backed, so there is no
 * transactional resource to bound.
 *
 * <p>Pure list / find — it holds no orchestration. The cross-domain caller
 * ({@code SessionFacade} in the {@code sessions} domain) reaches {@link #find}
 * facade-to-facade to re-validate a selected project id against the live
 * listing (AC10), per {@code profile-java-server-architecture} rule 6.
 */
@Component
public class WorkspaceProjectFacade {

    private final WorkspaceProjectService service;

    public WorkspaceProjectFacade(WorkspaceProjectService service) {
        this.service = service;
    }

    /** All currently-selectable workspace projects, sorted by name (AC1). */
    public List<WorkspaceProject> listProjects() {
        return service.list();
    }

    /**
     * Resolve a project by its selector id against the <em>live</em> listing.
     * Empty when no folder with that id currently exists — the caller degrades
     * a stale/deleted selection gracefully to "no project" (AC10).
     */
    public Optional<WorkspaceProject> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return service.list().stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
