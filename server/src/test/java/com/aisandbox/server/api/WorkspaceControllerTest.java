package com.aisandbox.server.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.workspace.dto.WorkspaceProject;
import com.aisandbox.server.workspace.facade.WorkspaceProjectFacade;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * UC-98 AC1/AC2 — wire shape of {@code GET /v1/workspace/projects}. The
 * controller is the client's only window onto the catalogue, so this pins that
 * it renders a bare JSON array of {@code {id,displayName}} objects (the shape
 * {@code WorkspaceProjectsApi} on Android decodes with the list serializer),
 * that the internal {@link WorkspaceProject} is mapped to the API DTO at the
 * boundary (profile rule 5), and that it delegates strictly to the
 * {@link WorkspaceProjectFacade}.
 *
 * <p>Lightweight {@code WebTestClient.bindToController} wiring — no full Spring
 * context boot — mirroring {@link ModelControllerTest}. mTLS enforcement is a
 * cross-cutting filter (pinned by {@code MtlsEnforcementFilterTest}); this test
 * pins the controller's response contract.
 */
class WorkspaceControllerTest {

    private static WebTestClient clientFor(WorkspaceProjectFacade facade) {
        return WebTestClient.bindToController(new WorkspaceController(facade)).build();
    }

    @Test
    void returns_the_catalogue_as_a_bare_array_of_id_displayName() {
        WorkspaceProjectFacade facade = mock(WorkspaceProjectFacade.class);
        when(facade.listProjects())
                .thenReturn(List.of(new WorkspaceProject("alpha", "alpha"), new WorkspaceProject("beta", "beta")));

        clientFor(facade)
                .get()
                .uri("/v1/workspace/projects")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                // AC1/AC2 — a bare JSON array, each entry carrying id + displayName.
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].id")
                .isEqualTo("alpha")
                .jsonPath("$[0].displayName")
                .isEqualTo("alpha")
                .jsonPath("$[1].id")
                .isEqualTo("beta")
                .jsonPath("$[1].displayName")
                .isEqualTo("beta");
    }

    @Test
    void empty_catalogue_renders_an_empty_array_not_a_404() {
        // AC1 — the endpoint exists even when the workspace root is absent/empty;
        // the client renders "None" + an empty list off a 200 + [].
        WorkspaceProjectFacade facade = mock(WorkspaceProjectFacade.class);
        when(facade.listProjects()).thenReturn(List.of());

        clientFor(facade)
                .get()
                .uri("/v1/workspace/projects")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
    }

    @Test
    void delegates_to_the_facade_only() {
        // The controller is a thin boundary: whatever the facade returns is what
        // the client sees, in order, mapped 1:1 to the API DTO.
        WorkspaceProjectFacade facade = mock(WorkspaceProjectFacade.class);
        when(facade.listProjects()).thenReturn(List.of(new WorkspaceProject("only", "only")));

        clientFor(facade)
                .get()
                .uri("/v1/workspace/projects")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo("only")
                .jsonPath("$[0].displayName")
                .isEqualTo("only");
    }
}
