package com.aisandbox.server.identity;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Represents an mTLS-authenticated client. Built once at TLS handshake
 * completion by {@code NettyServerCustomizer}'s handler and propagated
 * through Reactor exchange attributes and the SLF4J MDC.
 *
 * <p>The {@link #ANONYMOUS} sentinel (UC04 § B2) represents a TLS
 * connection that completed the handshake without presenting a client
 * certificate. With {@link io.netty.handler.ssl.ClientAuth#OPTIONAL} on
 * the server context — required so the mTLS-exempt
 * {@code POST /v1/enrollment} path can reach the application layer —
 * {@code SSLPeerUnverifiedException} is no longer fatal; the
 * {@code IdentityCapturingHandler} catches it and attaches this sentinel
 * via {@link ActiveConnectionRegistry#attachAnonymous}. The
 * {@code MtlsEnforcementFilter} then rejects every request whose path is
 * NOT {@code /v1/enrollment} with {@code 401 mtls_required}.
 *
 * @param cn             Common Name from the certificate's Subject (display only).
 * @param fingerprintHex SHA-256 of the cert's DER encoding, lowercase hex,
 *                       the primary key for audit + allowlist lookups. Empty
 *                       string for the {@link #ANONYMOUS} sentinel.
 * @param serial         Cert serial number (informational only). {@link BigInteger#ZERO}
 *                       for the {@link #ANONYMOUS} sentinel.
 */
public record ClientIdentity(String cn, String fingerprintHex, BigInteger serial) {

    /**
     * Singleton sentinel for TLS connections that completed the handshake
     * without presenting a client certificate. Identity equality should
     * be checked via {@link #isAnonymous()} so that any code path that
     * reconstructs the value object still matches the contract.
     */
    public static final ClientIdentity ANONYMOUS = new ClientIdentity("anonymous", "", BigInteger.ZERO);

    public ClientIdentity {
        Objects.requireNonNull(cn, "cn");
        Objects.requireNonNull(fingerprintHex, "fingerprintHex");
        Objects.requireNonNull(serial, "serial");
    }

    /**
     * {@code true} when this identity is the {@link #ANONYMOUS} sentinel
     * (UC04 § B2) — no client cert was presented. The
     * {@code MtlsEnforcementFilter} treats anonymous identical to null:
     * every path EXCEPT {@code /v1/enrollment} requires a real cert.
     */
    public boolean isAnonymous() {
        return this == ANONYMOUS || fingerprintHex.isEmpty();
    }

    /** Convenience accessor for log lines / MDC values. */
    public String displayName() {
        if (isAnonymous()) {
            return "anonymous";
        }
        return cn + " [" + fingerprintHex.substring(0, Math.min(12, fingerprintHex.length())) + "…]";
    }
}
