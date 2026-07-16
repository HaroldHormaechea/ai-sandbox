package com.aisandbox.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                    String type,
            @Schema(
                            description = "UC-69 — the first pending question's text, when the session's main"
                                    + " pane is showing an AskUserQuestion awaiting an answer (the first question"
                                    + " when several are raised together). The client uses it as the body of the"
                                    + " local push notification it posts on the pending transition, deep-linking"
                                    + " into the conversation. Omitted from the JSON when null (class-level"
                                    + " @JsonInclude(NON_NULL)): an idle / working / non-running session, or one"
                                    + " whose body was not parseable, simply has no field. Derived from the"
                                    + " visible pane, not the transcript.")
                    String pendingQuestionText) {}

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
            @Schema(description = "shared | isolated", defaultValue = "shared") String claudeConfigMode,
            @Schema(
                            description = "UC-98 — id of the selected workspace project (a folder name from"
                                    + " GET /v1/workspace/projects). Omit / null for the \"None\" default, which"
                                    + " preserves today's spawn behaviour exactly (no setup prompt injected). When a"
                                    + " real project is chosen, the server auto-injects and submits"
                                    + " \"We will work in the project {folder}.\" into the session's Claude once it is"
                                    + " ready, before the user attaches.",
                            example = "my-project")
                    @Pattern(regexp = "[A-Za-z0-9._+\\- ]{1,128}")
                    String workspaceProject) {}

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

    @Schema(description = "UC-98 — a selectable workspace project returned by GET /v1/workspace/projects.")
    public record WorkspaceProjectSummary(
            @Schema(
                            description = "Stable id of the project — sent back as SpawnRequest.workspaceProject.",
                            example = "my-project")
                    String id,
            @Schema(description = "Human-readable name shown in the drop-down (the folder name).", example = "my-project")
                    String displayName) {}

    @Schema(description = "UC-67 — one of a session's MCP servers, returned by GET /v1/sessions/{n}/mcp.")
    public record McpServerSummary(
            @Schema(description = "MCP server identifier (its key in the session's MCP config).", example = "atlassian")
                    String name,
            @Schema(
                            description = "Best-effort transport hint.",
                            allowableValues = {"stdio", "http", "sse", "unknown"},
                            example = "sse")
                    String transport,
            @Schema(
                            description = "Coarse connection state derived defensively from `claude mcp list`."
                                    + " connected = healthy; needs_auth = an interactive login the client must"
                                    + " initiate; failed = configured but the health check failed; pending = a"
                                    + " check is in flight; unknown = the status text was unrecognised.",
                            allowableValues = {"connected", "needs_auth", "failed", "pending", "unknown"},
                            example = "needs_auth")
                    String state,
            @Schema(
                            description = "Raw connection detail from the list line (command or URL); display-only.",
                            example = "https://mcp.atlassian.com/v1/sse")
                    String detail) {}

    @Schema(description = "UC-67 — outcome of an MCP control action (login/reconnect/refresh).")
    public record McpActionResult(
            @Schema(description = "The MCP server the action targeted.", example = "atlassian") String name,
            @Schema(
                            description = "The server's state after the action (from a fresh inventory).",
                            allowableValues = {"connected", "needs_auth", "failed", "pending", "unknown"},
                            example = "needs_auth")
                    String state,
            @Schema(
                            description = "Short, honest note about what happened. login only INITIATES the flow in"
                                    + " the live session — it never completes OAuth headlessly.",
                            example = "Opens MCP authentication in the live session — complete it there, then refresh.")
                    String message) {}

    /**
     * UC-82 — body of {@code POST /v1/sessions/{n}/mcp}: register a new MCP server.
     * Transport-dependent: a {@code stdio} server carries {@code command} (+ optional
     * {@code args} / {@code env}); an {@code http} / {@code sse} server carries
     * {@code url} (+ optional {@code headers}). The server validates which fields are
     * required per transport and rejects malformed input / duplicate names with
     * {@code application/problem+json} (AC6). All values are passed to process
     * execution as discrete argv elements — never a shell string (AC4).
     */
    @Schema(description = "Body of POST /v1/sessions/{n}/mcp (UC-82) — register a new MCP server.")
    public record McpAddRequest(
            @Schema(
                            description = "MCP server identifier. [A-Za-z0-9._-], 1-128 chars, no spaces,"
                                    + " no leading '-'.",
                            example = "atlassian")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9._-]{1,128}", message = "name must be config-safe with no spaces")
                    String name,
            @Schema(
                            description = "Transport/type of the MCP server.",
                            allowableValues = {"stdio", "http", "sse"},
                            example = "stdio")
                    String transport,
            @Schema(description = "stdio transport — the executable to run (required for stdio).", example = "npx")
                    String command,
            @Schema(description = "stdio transport — arguments passed to the command.") List<String> args,
            @Schema(
                            description = "http / sse transport — the server URL (required for those; http(s) only).",
                            example = "https://mcp.atlassian.com/v1/sse")
                    String url,
            @Schema(description = "stdio transport — environment variables for the command (K=V). Values are secret.")
                    Map<String, String> env,
            @Schema(description = "http / sse transport — \"Header: value\" strings. Values are secret.")
                    List<String> headers) {}

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

    /**
     * UC-84 — response of {@code GET /v1/server/update/check}. Reports the
     * server's running version, the newest {@code server-v*} release, whether an
     * update is available (semantic-version ordering), and the release's HTML
     * page + matching {@code _amd64.deb} download URL.
     *
     * <p>{@code debAssetUrl} is INFORMATIONAL ONLY (the privileged updater
     * self-determines and downloads its own target — AC8/AC11); the client uses
     * {@code releaseHtmlUrl} for the "Changelog" external-browser link (AC6).
     * Null-valued fields are omitted from the JSON (class-level
     * {@code @JsonInclude(NON_NULL)}).
     */
    @Schema(description = "Response of GET /v1/server/update/check (UC-84).")
    public record UpdateCheckResponse(
            @Schema(description = "The server's running version (jar manifest), or 'dev' outside a packaged jar.")
                    String currentVersion,
            @Schema(description = "Newest server-v* release version, or omitted when none is found.")
                    String latestVersion,
            @Schema(description = "True iff latestVersion is strictly newer than currentVersion.")
                    boolean updateAvailable,
            @Schema(description = "The latest release's GitHub HTML page — the external-browser Changelog link.")
                    String releaseHtmlUrl,
            @Schema(description = "Download URL of the matching *_amd64.deb asset. Informational only.")
                    String debAssetUrl) {}

    /**
     * UC-84 — response of {@code POST /v1/server/update/apply}. Acknowledges
     * that the parameter-free update trigger was emitted; the actual install +
     * restart is performed asynchronously by the independent root
     * {@code ai-sandbox-updater} unit, so this returns promptly without blocking
     * on the restart (AC9). {@code targetVersion} is a best-effort hint for the
     * client's "updating…" copy (the updater self-determines the real target).
     */
    @Schema(description = "Response of POST /v1/server/update/apply (UC-84).")
    public record UpdateApplyResponse(
            @Schema(description = "Always true on success (failures return a problem+json instead).") boolean accepted,
            @Schema(description = "Best-effort latest server-v* version the update targets, or omitted if unresolved.")
                    String targetVersion) {}
}
