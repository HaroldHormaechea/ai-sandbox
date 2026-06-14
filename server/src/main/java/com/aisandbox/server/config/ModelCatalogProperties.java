package com.aisandbox.server.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UC-66 — catalogue of Claude models the server advertises to the Android
 * client for the conversation "Model" picker. Bound from the optional
 * {@code ai-sandbox.models} block of {@code config.yaml}; when the block is
 * absent (the common case) the baked default catalogue below is used, so no
 * config / sample-config / fixture churn is required to ship the feature.
 *
 * <p>Picked up by {@code @ConfigurationPropertiesScan("com.aisandbox.server.config")}
 * on {@code ServerApplication} — this record lives in the {@code config}
 * package specifically so that scan registers it without an explicit
 * {@code @EnableConfigurationProperties}.
 *
 * <p>The model {@code id} is what gets sent to Claude Code as
 * {@code /model <id>}; the {@code label} is the human-readable name shown in
 * the picker. The default set mirrors the aliases the embedded Claude Code
 * accepts ({@code opus} / {@code sonnet} / {@code haiku}) plus a couple of
 * pinned ids, so operators get a working list out of the box and can override
 * the whole catalogue via config when newer models ship.
 *
 * @param available ordered list of advertised models; defaults to
 *     {@link #DEFAULT_AVAILABLE} when the config block is missing or empty
 */
@ConfigurationProperties(prefix = "ai-sandbox.models")
public record ModelCatalogProperties(List<Entry> available) {

    /**
     * Baked default catalogue, used when {@code ai-sandbox.models.available}
     * is unset. Order is the order shown in the picker.
     */
    public static final List<Entry> DEFAULT_AVAILABLE = List.of(
            new Entry("opus", "Opus (latest)"),
            new Entry("sonnet", "Sonnet (latest)"),
            new Entry("haiku", "Haiku (latest)"),
            new Entry("claude-opus-4-8", "Opus 4.8"),
            new Entry("claude-sonnet-4-5", "Sonnet 4.5"));

    /**
     * Compact constructor: substitute the baked catalogue when binding
     * supplies no entries (block absent or empty list), and defensively copy
     * to an immutable list otherwise.
     */
    public ModelCatalogProperties {
        if (available == null || available.isEmpty()) {
            available = DEFAULT_AVAILABLE;
        } else {
            available = List.copyOf(available);
        }
    }

    /**
     * One advertised model.
     *
     * @param id    model id/alias sent to Claude Code via {@code /model <id>}
     * @param label human-readable name for the picker (e.g. "Opus 4.8")
     */
    public record Entry(String id, String label) {}
}
