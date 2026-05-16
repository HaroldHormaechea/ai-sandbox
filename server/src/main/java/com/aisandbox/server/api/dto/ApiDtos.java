package com.aisandbox.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

/**
 * REST request / response DTOs. Disjoint from the internal record types
 * in {@code com.aisandbox.server.sessions.dto},
 * {@code com.aisandbox.server.clients.dto} etc., per
 * {@code profile-java-server-architecture} rule 5. Mappers live in
 * {@code com.aisandbox.server.api.mapper}; this class is annotation-heavy
 * for OpenAPI specifically because that's where API-shaping annotations
 * are allowed.
 */
@JsonInclude(Include.NON_NULL)
public final class ApiDtos {

    private ApiDtos() {}

    @Schema(description = "A summary row returned by GET /v1/sessions.")
    public record SessionSummary(
            int n,
            @Schema(description = "Free-form label echoed from com.ai-sandbox.label") String label,
            @Schema(description = "Tmux window title, or '(idle)' / '(unavailable)'") String tmuxTitle,
            @Schema(description = "running | exited") String state,
            long uptimeSec,
            int activeStreams,
            Instant startedAt) {}

    @Schema(description = "Detail returned by GET /v1/sessions/{n}.")
    public record SessionDetailDto(
            SessionSummary summary,
            String workspaceHostPath,
            String claudeConfigHostPath,
            List<String> connectedClients) {}

    @Schema(description = "Body of POST /v1/sessions.")
    public record SpawnRequest(
            @Schema(description = "Optional label set as com.ai-sandbox.label on the container.")
                    @Pattern(regexp = "[A-Za-z0-9._:/+\\- ]{1,64}")
                    String label,
            @Schema(description = "shared | isolated", defaultValue = "shared") String workspaceMode,
            @Schema(description = "shared | isolated", defaultValue = "shared") String claudeConfigMode) {}

    @Schema(description = "Body of POST /v1/clients.")
    public record AddClientRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]+", message = "Name must be filename-safe") String name,
            @NotBlank String certPem) {}

    @Schema(description = "Item in GET /v1/clients.")
    public record ClientSummary(String name, String cn, String fingerprint, String serial, Instant addedAt) {}

    @Schema(description = "Response of GET /v1/healthz.")
    public record HealthResponse(boolean dockerOk, boolean scriptsOk, boolean tlsOk) {}
}
