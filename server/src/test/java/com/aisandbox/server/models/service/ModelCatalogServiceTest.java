package com.aisandbox.server.models.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.config.ModelCatalogProperties;
import com.aisandbox.server.config.ModelCatalogProperties.Entry;
import com.aisandbox.server.models.dto.ModelDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-66 — {@link ModelCatalogService} maps the bound
 * {@link ModelCatalogProperties} entries into internal
 * {@link ModelDescriptor} DTOs, preserving id, label, and configured order
 * (AC3). The catalogue is config-backed, so the service is the read source —
 * no repository tier and no {@code @Transactional}
 * (per {@code profile-java-server-architecture}).
 */
class ModelCatalogServiceTest {

    @Test
    void maps_each_property_entry_to_a_descriptor_in_order() {
        ModelCatalogProperties props =
                new ModelCatalogProperties(List.of(new Entry("opus", "Opus 4.8"), new Entry("sonnet", "Sonnet 4.5")));

        List<ModelDescriptor> out = new ModelCatalogService(props).list();

        assertThat(out)
                .extracting(ModelDescriptor::id, ModelDescriptor::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("opus", "Opus 4.8"),
                        org.assertj.core.groups.Tuple.tuple("sonnet", "Sonnet 4.5"));
    }

    @Test
    void surfaces_the_baked_default_catalogue_when_config_block_is_absent() {
        // The compact constructor substitutes DEFAULT_AVAILABLE on a null/empty
        // list, so a server with no `ai-sandbox.models` config block still
        // serves a working catalogue (AC3 — config-driven, default-backed).
        ModelCatalogProperties props = new ModelCatalogProperties(null);

        List<ModelDescriptor> out = new ModelCatalogService(props).list();

        assertThat(out).isNotEmpty();
        assertThat(out).extracting(ModelDescriptor::id).contains("opus", "sonnet", "haiku");
        assertThat(out)
                .as("service order mirrors the configured/default order")
                .extracting(ModelDescriptor::id)
                .containsExactlyElementsOf(ModelCatalogProperties.DEFAULT_AVAILABLE.stream()
                        .map(Entry::id)
                        .toList());
    }
}
