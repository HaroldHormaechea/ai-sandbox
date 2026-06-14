package com.aisandbox.server.models.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.models.dto.ModelDescriptor;
import com.aisandbox.server.models.service.ModelCatalogService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-66 — {@link ModelCatalogFacade} is the read-only use-case boundary for
 * {@code GET /v1/models}. It delegates to its own domain's
 * {@link ModelCatalogService} (same-domain shortcut, no repository) and is the
 * only layer the controller is allowed to call
 * (per {@code profile-java-server-architecture}). This pins that delegation.
 */
class ModelCatalogFacadeTest {

    @Test
    void listModels_delegates_to_the_service() {
        ModelCatalogService service = mock(ModelCatalogService.class);
        List<ModelDescriptor> catalogue = List.of(new ModelDescriptor("opus", "Opus 4.8"));
        when(service.list()).thenReturn(catalogue);

        ModelCatalogFacade facade = new ModelCatalogFacade(service);
        List<ModelDescriptor> out = facade.listModels();

        assertThat(out).isSameAs(catalogue);
        verify(service).list();
    }
}
