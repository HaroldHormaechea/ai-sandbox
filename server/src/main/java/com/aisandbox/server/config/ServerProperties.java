package com.aisandbox.server.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the management server, mapped from
 * {@code /etc/ai-sandbox-server/config.yaml} (or a custom location passed
 * via {@code --spring.config.import=...}). Every field has a sensible
 * default so {@code aisandboxctl pki init} can drop a fully-functional
 * sample.
 *
 * <p>The shape mirrors the YAML schema documented in
 * {@code server/sample-config.yaml}. Changes here MUST be reflected there.
 *
 * <p>Validation runs at context start; a malformed config produces a
 * fail-fast error from {@link PropertiesValidationStartupCheck} (which
 * additionally enforces filesystem invariants).
 */
@Validated
@ConfigurationProperties(prefix = "ai-sandbox.server")
public record ServerProperties(
        @NotNull Tls tls,
        @NotNull Pki pki,
        @NotNull Clients clients,
        @NotNull Hostscripts hostscripts,
        @NotNull Limits limits,
        @NotNull Audit audit,
        @NotNull Shutdown shutdown,
        @NotNull Streams streams) {

    public record Tls(@Min(1) int port, @NotBlank String bindAddress) {}

    public record Pki(@NotNull Path dir) {}

    public record Clients(@NotNull Path dir) {}

    public record Hostscripts(@NotNull Path repoRoot) {}

    public record Limits(
            @Min(1) int perIpNewConnPerWindow,
            @Min(1) int perIpWindowSeconds,
            @Min(1) int perIpConcurrent,
            @Min(1) int spawnTimeoutSeconds,
            @Min(1) int maxRequestBytes) {}

    public record Audit(@NotNull Path file, @Min(1) int retentionDays) {}

    public record Shutdown(@Min(1) int restGraceSeconds, @Min(1) int totalGraceSeconds) {}

    public record Streams(
            @Min(1) int idleTimeoutSeconds,
            @Min(1) int perClientCap,
            @Min(1) int globalCap,
            @Min(1) int maxBinaryFrameBytes,
            @Min(1) int maxTextFrameBytes,
            @Min(1) int outputRingBytes,
            @Min(1) int keepalivePingSeconds,
            @Min(1) int keepalivePongTimeoutSeconds) {}
}
