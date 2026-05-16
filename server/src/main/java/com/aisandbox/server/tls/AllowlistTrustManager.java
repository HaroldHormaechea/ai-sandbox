package com.aisandbox.server.tls;

import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.pki.PemUtils;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom trust manager that ignores CA-chain validation entirely and gates
 * solely on a SHA-256 fingerprint match against {@link ClientAllowlistService}.
 *
 * <p>Rationale: the trust model for UC03 is folder-of-allowed-certs with no
 * CA; every client cert is self-signed (minted by {@code aisandboxctl}).
 * Standard chain validation would reject every connection.
 *
 * <p>Server-side variants ({@code checkClientTrusted}) implement the
 * gate; client-side variants are not used (we never act as TLS client).
 */
public class AllowlistTrustManager extends X509ExtendedTrustManager {

    private static final Logger LOG = LoggerFactory.getLogger(AllowlistTrustManager.class);

    private final ClientAllowlistService service;

    public AllowlistTrustManager(ClientAllowlistService service) {
        this.service = service;
    }

    private void check(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty client cert chain");
        }
        X509Certificate leaf = chain[0];
        // Sanity: cert validity dates. Allowlist match alone is not enough
        // if the cert is outside its NotBefore/NotAfter window.
        leaf.checkValidity();
        String fingerprint = PemUtils.fingerprintHex(leaf);
        if (!service.isAllowed(fingerprint)) {
            LOG.info("Rejecting unknown client cert fp={} cn={}", fingerprint, PemUtils.extractCommonName(leaf));
            throw new CertificateException("Client cert not in allowlist (fp=" + fingerprint + ")");
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Not a TLS client");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException("Not a TLS client");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("Not a TLS client");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
