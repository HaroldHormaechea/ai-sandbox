package com.aisandbox.server.models.facade;

import com.aisandbox.server.models.dto.ModelDescriptor;
import com.aisandbox.server.models.service.ModelCatalogService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * UC-66 — use-case-level entry point for {@code GET /v1/models}. Read-only
 * facade (precedent: {@code HealthFacade}, {@code ClientAllowlistFacade}).
 * No {@code @Transactional}: the catalogue is config-backed, so there is no
 * transactional resource to bound.
 */
@Component
public class ModelCatalogFacade {

    private final ModelCatalogService service;

    public ModelCatalogFacade(ModelCatalogService service) {
        this.service = service;
    }

    public List<ModelDescriptor> listModels() {
        return service.list();
    }
}
