package com.aisandbox.server.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.models.dto.ModelDescriptor;
import com.aisandbox.server.models.facade.ModelCatalogFacade;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * UC-66 AC2/AC3 — wire shape of {@code GET /v1/models}. The controller is the
 * client's only window onto the catalogue, so this pins that it renders a bare
 * JSON array of {@code {id,label}} objects (the shape {@code ModelsApi} on
 * Android decodes with the list serializer) and that it delegates strictly to
 * the {@link ModelCatalogFacade} (per {@code profile-java-server-architecture}).
 *
 * <p>Lightweight {@code WebTestClient.bindToController} wiring — no full Spring
 * context boot — mirroring {@link com.aisandbox.server.enrollment.api.EnrollmentControllerTest}.
 * The catalogue's config-binding (defaults vs. operator override) is pinned at
 * the unit layer by {@code ModelCatalogPropertiesTest}; the properties→DTO map
 * by {@code ModelCatalogServiceTest}; the DTO→API map by {@code ApiMappersTest}.
 */
class ModelControllerTest {

    private static WebTestClient clientFor(ModelCatalogFacade facade) {
        return WebTestClient.bindToController(new ModelController(facade)).build();
    }

    @Test
    void returns_the_configured_catalogue_as_a_bare_array_of_id_label() {
        ModelCatalogFacade facade = mock(ModelCatalogFacade.class);
        when(facade.listModels())
                .thenReturn(List.of(
                        new ModelDescriptor("opus", "Opus 4.8"),
                        new ModelDescriptor("sonnet", "Sonnet 4.5"),
                        new ModelDescriptor("haiku", "Haiku (latest)")));

        clientFor(facade)
                .get()
                .uri("/v1/models")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                // AC2 — a bare JSON array, each entry carrying id + human label.
                .jsonPath("$.length()")
                .isEqualTo(3)
                .jsonPath("$[0].id")
                .isEqualTo("opus")
                .jsonPath("$[0].label")
                .isEqualTo("Opus 4.8")
                .jsonPath("$[1].id")
                .isEqualTo("sonnet")
                .jsonPath("$[1].label")
                .isEqualTo("Sonnet 4.5")
                .jsonPath("$[2].id")
                .isEqualTo("haiku");
    }

    @Test
    void empty_catalogue_renders_an_empty_array_not_a_404() {
        // AC3 — the endpoint exists even when the facade reports nothing; the
        // client renders its "No models available" empty state off a 200 + [].
        ModelCatalogFacade facade = mock(ModelCatalogFacade.class);
        when(facade.listModels()).thenReturn(List.of());

        clientFor(facade)
                .get()
                .uri("/v1/models")
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
        ModelCatalogFacade facade = mock(ModelCatalogFacade.class);
        when(facade.listModels()).thenReturn(List.of(new ModelDescriptor("claude-opus-4-8", "Opus 4.8")));

        clientFor(facade)
                .get()
                .uri("/v1/models")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].id")
                .isEqualTo("claude-opus-4-8")
                .jsonPath("$[0].label")
                .isEqualTo("Opus 4.8");
    }
}
