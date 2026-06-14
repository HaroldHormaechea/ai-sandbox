package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.models.facade.ModelCatalogFacade;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-66 — mTLS-gated catalogue of Claude models the client may select in the
 * conversation "Model" picker. Global (not per-session): the same catalogue
 * applies to every conversation/target. Calls the
 * {@link ModelCatalogFacade} only — never a service or the properties
 * directly (per {@code profile-java-server-architecture}).
 */
@RestController
@RequestMapping("/v1/models")
public class ModelController {

    private final ModelCatalogFacade facade;

    public ModelController(ModelCatalogFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public List<ApiDtos.ModelSummary> list() {
        return facade.listModels().stream().map(ApiMappers::toModelSummary).toList();
    }
}
