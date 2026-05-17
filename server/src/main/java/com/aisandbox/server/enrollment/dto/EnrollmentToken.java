package com.aisandbox.server.enrollment.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * One enrollment token persisted to disk by {@code aisandboxctl client invite}
 * and consumed atomically by {@code POST /v1/enrollment} (UC04).
 *
 * <p>Wire shape on disk is a tiny JSON file at
 * {@code <enrollment.dir>/<prefix>.json}, mode 0600, owned by
 * {@code ai-sandbox-server}, with this exact field set:
 *
 * <pre>{@code
 * { "token": "...", "name": "...", "exp": "2026-05-17T12:34:56Z" }
 * }</pre>
 *
 * <p>The filename prefix is the first
 * {@value com.aisandbox.server.enrollment.service.EnrollmentTokenStore#FILENAME_PREFIX_LEN}
 * characters of the token; the full token in the file is the
 * collision-anti-fraud check at redemption time. Single-use semantics
 * come from atomically deleting the file on a successful redeem (see
 * {@link com.aisandbox.server.enrollment.service.EnrollmentTokenStore}).
 *
 * @param token     opaque single-use token (≥256 bits of entropy, hex-encoded).
 * @param name      client name (used as CN + allowlist filename stem on mint).
 * @param expiresAt RFC 3339 instant after which the token MUST be refused.
 */
public record EnrollmentToken(String token, String name, Instant expiresAt) {

    public EnrollmentToken {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** {@code true} if {@link #expiresAt} is at or before {@code now}. */
    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
