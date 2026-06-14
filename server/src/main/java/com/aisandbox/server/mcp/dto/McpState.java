package com.aisandbox.server.mcp.dto;

/**
 * UC-67 — coarse connection state of one of a session's MCP servers, derived
 * defensively from the {@code claude mcp list} health line. Internal DTO enum:
 * lives strictly inside the {@code mcp} service / facade layers; the API layer
 * carries the state as a plain string on {@code ApiDtos.McpServerSummary} (per
 * {@code profile-java-server-architecture} rule 5 — no API-shaping types here).
 *
 * <p>The {@code claude mcp} surface is upstream-owned and version-volatile, so
 * any unrecognised health text maps to {@link #UNKNOWN} rather than failing.
 */
public enum McpState {
    /** The server is reachable and its health check passed (e.g. {@code ✓ Connected}). */
    CONNECTED,
    /**
     * The server requires an interactive authentication/login the client must
     * initiate (e.g. an OAuth server reporting {@code Needs authentication}).
     */
    NEEDS_AUTH,
    /** The server is configured but its health check failed (e.g. {@code ✗ Failed to connect}). */
    FAILED,
    /** A health check is still in progress / the server is starting up. */
    PENDING,
    /** The health line was present but its status text was not recognised. */
    UNKNOWN;

    /**
     * Map a free-form {@code claude mcp list} status fragment (the text after the
     * trailing {@code -}) to a state. Case-insensitive, defensive: an empty or
     * unrecognised fragment yields {@link #UNKNOWN}.
     */
    public static McpState fromStatusText(String statusText) {
        if (statusText == null) {
            return UNKNOWN;
        }
        String s = statusText.toLowerCase(java.util.Locale.ROOT);
        // Order matters: check auth before the generic "fail"/error families,
        // since an auth-required line can also read "failed to authenticate".
        if (s.contains("auth") || s.contains("login")) {
            return NEEDS_AUTH;
        }
        if (s.contains("connected") || s.contains("✓") || s.contains("ready") || s.contains("ok")) {
            return CONNECTED;
        }
        if (s.contains("connecting") || s.contains("pending") || s.contains("starting")) {
            return PENDING;
        }
        if (s.contains("fail") || s.contains("✗") || s.contains("error") || s.contains("disconnect")) {
            return FAILED;
        }
        return UNKNOWN;
    }
}
