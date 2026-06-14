package com.aisandbox.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.config.ModelCatalogProperties.Entry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-66 — {@link ModelCatalogProperties} compact-constructor contract: the
 * baked {@link ModelCatalogProperties#DEFAULT_AVAILABLE} catalogue is used when
 * the {@code ai-sandbox.models.available} config block is absent or empty (the
 * common case — no config/fixture churn to ship the feature), and a supplied
 * list is defensively copied to an immutable one. This is the seam behind AC3
 * (config alters the catalogue without an app change).
 */
class ModelCatalogPropertiesTest {

    @Test
    void null_available_falls_back_to_the_baked_default_catalogue() {
        ModelCatalogProperties props = new ModelCatalogProperties(null);

        assertThat(props.available())
                .as("absent config block → baked default catalogue")
                .containsExactlyElementsOf(ModelCatalogProperties.DEFAULT_AVAILABLE);
        assertThat(props.available()).extracting(Entry::id).contains("opus", "sonnet", "haiku");
    }

    @Test
    void empty_available_falls_back_to_the_baked_default_catalogue() {
        ModelCatalogProperties props = new ModelCatalogProperties(List.of());

        assertThat(props.available()).containsExactlyElementsOf(ModelCatalogProperties.DEFAULT_AVAILABLE);
    }

    @Test
    void supplied_catalogue_is_used_verbatim_and_made_immutable() {
        // An operator override (config present) replaces the whole catalogue;
        // the order is preserved and the stored list is an immutable copy so a
        // later mutation of the caller's list cannot bleed in.
        List<Entry> supplied =
                new ArrayList<>(List.of(new Entry("custom-a", "Custom A"), new Entry("custom-b", "Custom B")));
        ModelCatalogProperties props = new ModelCatalogProperties(supplied);

        assertThat(props.available()).extracting(Entry::id).containsExactly("custom-a", "custom-b");

        // Mutating the source list after construction must not change the bound state.
        supplied.add(new Entry("late", "Late"));
        assertThat(props.available()).hasSize(2);

        // And the stored list itself is immutable.
        assertThatThrownBy(() -> props.available().add(new Entry("x", "X")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
