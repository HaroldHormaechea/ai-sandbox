package com.aisandbox.server.models.service;

import com.aisandbox.server.config.ModelCatalogProperties;
import com.aisandbox.server.models.dto.ModelDescriptor;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * UC-66 — domain service for the model catalogue. Maps the bound
 * {@link ModelCatalogProperties} into internal {@link ModelDescriptor}
 * DTOs. There is no persistence tier here (the catalogue is config-backed),
 * so this service is the source of the read; no {@code @Transactional}
 * (per {@code profile-java-server-architecture}).
 */
@Service
public class ModelCatalogService {

    private final ModelCatalogProperties properties;

    public ModelCatalogService(ModelCatalogProperties properties) {
        this.properties = properties;
    }

    /** Returns the advertised models in configured order. */
    public List<ModelDescriptor> list() {
        return properties.available().stream()
                .map(e -> new ModelDescriptor(e.id(), e.label()))
                .toList();
    }
}
