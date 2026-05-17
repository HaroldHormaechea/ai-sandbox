package com.aisandbox.server.enrollment.dto;

import java.util.Objects;

/**
 * Output of a successful enrollment redemption — a freshly-minted client
 * cert that has been written into the allowlist folder and packaged into
 * an in-memory PKCS#12 blob.
 *
 * <p>The {@link #certPem()} field is what {@code ClientAllowlistFacade.addClient}
 * received (kept here for audit + return-path symmetry); the
 * {@link #pkcs12()} blob is what {@code EnrollmentController} streams to
 * the Android client as the {@code application/octet-stream} response
 * body (UC04 AC33). The PKCS#12 transport passphrase is empty — the
 * bundle is consumed in-memory by the client and never written to durable
 * storage (UC04 § Potential Pitfalls).
 *
 * @param name    client name (matches the {@link EnrollmentToken#name()}).
 * @param certPem freshly-minted PEM-encoded X.509 cert (already written
 *                to {@code <clients.dir>/<name>.crt} by the facade).
 * @param pkcs12  in-memory PKCS#12 blob containing private key + cert,
 *                empty passphrase. Caller MUST treat as sensitive.
 */
public record MintedBundle(String name, String certPem, byte[] pkcs12) {

    public MintedBundle {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(certPem, "certPem");
        Objects.requireNonNull(pkcs12, "pkcs12");
    }

    /** Defensive accessor — callers must not mutate the underlying array. */
    @Override
    public byte[] pkcs12() {
        return pkcs12;
    }
}
