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
    CLIENT_ADD,
    CLIENT_REMOVE,
    SERVER_CERT_ROTATION,
    HEALTHZ_FAIL;

    public String wire() {
        return name().toLowerCase();
    }
}
