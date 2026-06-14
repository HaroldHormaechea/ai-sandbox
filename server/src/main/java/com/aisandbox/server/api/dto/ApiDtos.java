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
            @Schema(
                            description = "running | starting | provisioning | terminating | paused | stopped."
                                    + " provisioning (UC-27) = the container is up but still installing its"
                                    + " spawn-time toolchains, shown as 'Installing prerequisites' client-side."
                                    + " terminating (UC-28) = the session's teardown (clean.sh / docker compose"
                                    + " down) is in progress; shown with a destructive-red 'awaiting termination'"
                                    + " pill client-side and blocks further delete attempts."
                                    + " paused (UC-46) = the container is frozen via the cgroup freezer"
                                    + " (docker compose pause); resumable with the Unpause lifecycle action.",
                            allowableValues = {"running", "starting", "provisioning", "terminating", "paused", "stopped"
                            })
                    String state,
            long uptimeSec,
            int activeStreams,
            Instant startedAt,
            @Schema(
                            description = "UC-47 — the Claude conversation name for the session's main pane,"
                                    + " when one is known (active conversation on a running session). The client"
                                    + " shows it as the row's primary status line, falling back to tmuxTitle when"
                                    + " absent. Omitted from the JSON when null (class-level @JsonInclude(NON_NULL)):"
                                    + " an idle / between-conversations / non-running session simply has no field.")
                    String conversationName,
            @Schema(
                            description = "UC-48 — true when Claude is actively working in this session's main"
                                    + " pane (a turn is mid-flight); false when idle, awaiting an answer, or"
                                    + " non-running. The client shows an animated working spinner in the row's"
                                    + " status area while true (double-gated on state==running). Hysteresis-"
                                    + "debounced server-side so a brief between-turns idle does not strobe it.",
                            defaultValue = "false")
                    boolean working,
            @Schema(
                            description = "UC-49 — true when the session's main pane is showing an AskUserQuestion"
                                    + " awaiting an answer (single or multi-question); false when idle, working, or"
                                    + " non-running. Mutually exclusive with working (a pending question is"
                                    + " 'waiting', never 'working'). The client shows a '?' badge in the row's"
                                    + " status area while true (double-gated on state==running) and suppresses the"
                                    + " working spinner. Derived from the visible pane, not the transcript.",
                            defaultValue = "false")
                    boolean pendingQuestion,
            @Schema(
                            description = "UC-62 — session kind. 'claude' for an ordinary sandbox/Docker session;"
                                    + " 'server-ssh' for the single always-on server host-shell row (a tmux login"
                                    + " shell on the management-server host, reserved id 0). The client pins the"
                                    + " server-ssh row to the top of the list, badges it 'SERVER SSH SESSION',"
                                    + " offers only Remove, and routes its taps to the terminal (never the Claude"
                                    + " conversation view).",
                            allowableValues = {"claude", "server-ssh"},
                            defaultValue = "claude")
                    String type) {}

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

    @Schema(description = "UC-66 — an advertised Claude model returned by GET /v1/models.")
    public record ModelSummary(
            @Schema(description = "Model id/alias sent to Claude Code via `/model <id>`.", example = "opus") String id,
            @Schema(description = "Human-readable label for the model picker.", example = "Opus 4.8") String label) {}

    /**
     * Body of {@code POST /v1/enrollment} (UC04 AC33). The endpoint is
     * the only mTLS-exempt path on the server; redeeming a one-time
     * token returns a PKCS#12 octet-stream the Android client imports
     * into the Android KeyStore (the operator carries no key material
     * between the server and the device).
     *
     * <p>The body is hard-capped at 256 bytes upstream (AC33). The
     * declared {@code @Pattern} is the on-paper constraint only; the
     * actual byte cap is enforced by
     * {@code RequestSizeLimitFilter} so payloads that don't even reach
     * Jackson are rejected with {@link ErrorCode#PAYLOAD_TOO_LARGE}.
     */
    @Schema(description = "Body of POST /v1/enrollment (UC04).")
    public record EnrollmentRequest(
            @Schema(
                            description = "Opaque single-use token issued by `aisandboxctl client invite <name>`.",
                            example = "8a1f3c0e9b4d…",
                            minLength = 32,
                            maxLength = 256)
                    @NotBlank
                    @Pattern(
                            regexp = "[A-Za-z0-9._-]{32,256}",
                            message = "Token must be 32–256 chars of [A-Za-z0-9._-]")
                    String token) {}
}
