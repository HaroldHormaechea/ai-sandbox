package com.aisandbox.server.identity;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Represents an mTLS-authenticated client. Built once at TLS handshake
 * completion by {@code NettyServerCustomizer}'s handler and propagated
 * through Reactor exchange attributes and the SLF4J MDC.
 *
 * @param cn             Common Name from the certificate's Subject (display only).
 * @param fingerprintHex SHA-256 of the cert's DER encoding, lowercase hex, the
 *                       primary key for audit + allowlist lookups.
 * @param serial         Cert serial number (informational only).
 */
public record ClientIdentity(String cn, String fingerprintHex, BigInteger serial) {

    public ClientIdentity {
        Objects.requireNonNull(cn, "cn");
        Objects.requireNonNull(fingerprintHex, "fingerprintHex");
        Objects.requireNonNull(serial, "serial");
    }

    /** Convenience accessor for log lines / MDC values. */
    public String displayName() {
        return cn + " [" + fingerprintHex.substring(0, Math.min(12, fingerprintHex.length())) + "…]";
    }
}
