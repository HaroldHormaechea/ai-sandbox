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
    SESSION_NOT_RUNNING;

    public String wire() {
        return name().toLowerCase();
    }
}
