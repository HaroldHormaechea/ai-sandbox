package com.aisandbox.server.clients.service;

import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.pki.PemUtils;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import org.springframework.stereotype.Component;

/**
 * Parses a client-cert PEM payload into the internal {@link AllowedClient}
 * shape, computing the SHA-256 fingerprint and extracting Common Name +
 * serial via BouncyCastle.
 *
 * <p>Lives at the service tier — repository-equivalent here, since the
 * allowlist is filesystem-backed rather than DB-backed.
 */
@Component
public class ClientCertParser {

    public AllowedClient parse(String name, String pem) throws CertificateException, IOException {
        X509Certificate cert = PemUtils.parseCertificate(pem);
        String fingerprint = PemUtils.fingerprintHex(cert);
        String cn = PemUtils.extractCommonName(cert);
        return new AllowedClient(
                name,
                cn,
                fingerprint,
                cert.getSerialNumber(),
                cert.getNotBefore().toInstant());
    }

    public X509Certificate parseRaw(String pem) throws CertificateException, IOException {
        return PemUtils.parseCertificate(pem);
    }
}
