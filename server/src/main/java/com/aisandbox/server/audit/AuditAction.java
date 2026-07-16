package com.aisandbox.server.audit;

/**
 * Closed enumeration of audit-relevant events. Wire only enums (or their
 * lowercase string form) into log lines — free-form strings invite drift.
 */
public enum AuditAction {
    SESSION_SPAWN,
    SESSION_KILL,
    STREAM_OPEN,
    STREAM_CLOSE,
    // UC-21 — a stream switched its bridged target mid-stream (agent switcher).
    STREAM_REBRIDGE,
    // UC-37 — structured-conversation channel lifecycle + input events.
    CONVERSATION_OPEN,
    CONVERSATION_CLOSE,
    CONVERSATION_INPUT,
    CONVERSATION_ANSWER,
    CONVERSATION_INTERRUPT,
    // UC-41 — on-demand fetch of a tool call's full (untruncated) input + result.
    CONVERSATION_FETCH_DETAIL,
    // UC-79 — on-demand fetch of an OLDER page of transcript lines (infinite scroll).
    CONVERSATION_FETCH_PAGE,
    // UC-67 — the MCP screen initiated a server's login flow by surfacing the
    // interactive `/mcp` menu in the session's live main pane (the auth itself
    // is completed by the human in that session, never headlessly).
    MCP_LOGIN,
    // UC-82 — a new MCP server was registered for a session via the MCP screen
    // (`claude mcp add`). Payload carries {n, name, transport}; NEVER the
    // server's env / header / secret VALUES.
    MCP_ADD,
    // UC-82 — an MCP server was deregistered for a session (`claude mcp remove`).
    // Payload carries {n, name}.
    MCP_REMOVE,
    CLIENT_ADD,
    CLIENT_REMOVE,
    // UC04 — successful POST /v1/enrollment redemption.
    CLIENT_ENROLL,
    // UC04 — POST /v1/enrollment rejected (invalid/expired/redeemed token,
    // rate-limited IP, oversized payload). The reason is the lowercase
    // ErrorCode wire form so dashboards can group rejections by class.
    CLIENT_ENROLL_REJECT,
    SERVER_CERT_ROTATION,
    // UC-46 — a Docker-lifecycle action (stop/start/pause/unpause) ran for a
    // session. Payload carries {action, n, exitCode}; result is ok / fail.
    SESSION_LIFECYCLE,
    // UC-77 — a server-side warm build of the ai-context:latest sandbox image
    // ran. Payload carries {image, durationMs[, error]}; result is ok / fail.
    // Distinguishes a long "warming up" build from a hard session spawn_failed.
    SANDBOX_IMAGE_WARM,
    HEALTHZ_FAIL,
    // UC-84 — the Android client asked the server to check GitHub for a newer
    // server-v* release (GET /v1/server/update/check). Payload carries
    // {current, latest, updateAvailable}; result is ok / the failure code.
    SERVER_UPDATE_CHECK,
    // UC-84 — the Android client confirmed a self-update (POST /v1/server/update/apply).
    // The server emits the parameter-free trigger marker; payload carries
    // {target}; result is ok / update_trigger_failed. The privileged install +
    // restart is performed by the independent root ai-sandbox-updater unit, NOT
    // by the server — so this line records only that the trigger was emitted.
    SERVER_UPDATE_APPLY,
    // UC-98 — the server auto-injected the workspace-project setup prompt
    // ("We will work in the project <folder>.") into a freshly-spawned session
    // after its readiness marker came up. Payload carries {n, project}; result
    // is ok (injected once), skip (stale/absent project id — AC10, or readiness
    // never confirmed), or fail (the inject itself errored — non-fatal, the
    // session is already spawned). Never retried / re-injected.
    SESSION_WORKSPACE_INJECT;

    public String wire() {
        return name().toLowerCase();
    }
}
