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
    HEALTHZ_FAIL;

    public String wire() {
        return name().toLowerCase();
    }
}
