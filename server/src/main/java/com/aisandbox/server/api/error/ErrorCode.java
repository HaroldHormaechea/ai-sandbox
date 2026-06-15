package com.aisandbox.server.api.error;

/**
 * Stable, machine-readable error codes returned in
 * {@code application/problem+json} bodies per AC21 (RFC 9457).
 *
 * <p>The wire shape is the lowercase enum name; never the localised
 * detail. Clients pattern-match on the code, not the message.
 */
public enum ErrorCode {
    SESSION_NOT_FOUND,
    SPAWN_TIMEOUT,
    SPAWN_FAILED,
    SPAWN_CAP_EXCEEDED,
    STREAM_CAP_EXCEEDED,
    INVALID_CERT_PEM,
    CLIENT_NOT_FOUND,
    CLIENT_NAME_CONFLICT,
    REQUEST_TOO_LARGE,
    HEALTHZ_FAIL,
    STREAM_OVERFLOW,
    SUBPROTOCOL_REQUIRED,
    DRAINING,
    BAD_REQUEST,
    INTERNAL_ERROR,
    // UC04 — enrollment endpoint (AC32-AC35).
    ENROLLMENT_TOKEN_INVALID,
    ENROLLMENT_TOKEN_EXPIRED,
    ENROLLMENT_TOKEN_REDEEMED,
    ENROLLMENT_RATE_LIMITED,
    PAYLOAD_TOO_LARGE,
    // UC04 — TLS layer flip (AC7, proposal § B2): mTLS is required on
    // every path EXCEPT POST /v1/enrollment. MtlsEnforcementFilter emits
    // this when a request arrives without an authenticated client cert.
    MTLS_REQUIRED,
    // UC04 — AC37: GET /v1/sessions now also surfaces stopped containers.
    // The stream-attach guard returns this code when the client targets
    // a session whose container is not running.
    SESSION_NOT_RUNNING,
    // UC-46 — a lifecycle action (stop/start/pause/unpause) was requested
    // for a session whose current state does not permit it (e.g. START on a
    // running session, PAUSE on a stopped one). Mapped to 409 Conflict.
    SESSION_STATE_CONFLICT,
    // UC-77 — a spawn arrived while the ai-context:latest sandbox image is
    // still being prepared (warm build in progress, or just (re)kicked).
    // Mapped to 503; the request never runs the heavy build itself.
    SANDBOX_IMAGE_WARMING,
    // UC-82 — POST /v1/sessions/{n}/mcp targeted a server name that is already
    // configured for the session. Mapped to 409 Conflict (no silent overwrite).
    MCP_SERVER_EXISTS,
    // UC-82 — DELETE /v1/sessions/{n}/mcp/{name} targeted a server the session
    // does not have. Mapped to 404 Not Found.
    MCP_SERVER_NOT_FOUND,
    // UC-82 — the underlying `claude mcp add` invocation failed (non-zero exit
    // or I/O error). Mapped to 500.
    MCP_ADD_FAILED,
    // UC-82 — the underlying `claude mcp remove` invocation failed. Mapped to 500.
    MCP_REMOVE_FAILED;

    public String wire() {
        return name().toLowerCase();
    }
}
