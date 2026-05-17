package com.aisandbox.server.tls;

import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.pki.PemUtils;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the live Netty {@link SslContext} for the listener. Rebuilds the
 * context atomically when {@code ServerCertWatcher} signals a server-cert
 * rotation; in-flight TLS sessions continue under their original cert
 * (Netty's per-connection {@code SslHandler} keeps a reference to the
 * context it was created with).
 *
 * <p>Cipher policy is {@link TlsCipherPolicy}; trust delegates entirely to
 * {@link AllowlistTrustManager}.
 */
public class ReloadableSslContextHolder {

    private static final Logger LOG = LoggerFactory.getLogger(ReloadableSslContextHolder.class);

    private final AtomicReference<SslContext> ref = new AtomicReference<>();
    private final ClientAllowlistService allowlist;

    public ReloadableSslContextHolder(ClientAllowlistService allowlist) {
        this.allowlist = allowlist;
    }

    public SslContext current() {
        SslContext ctx = ref.get();
        if (ctx == null) {
            throw new IllegalStateException("SSL context not yet initialised");
        }
        return ctx;
    }

    public synchronized void rebuild(Path certPath, Path keyPath)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyManagementException,
                    KeyStoreException {
        String certPem = Files.readString(certPath);
        String keyPem = Files.readString(keyPath);
        X509Certificate cert = PemUtils.parseCertificate(certPem);
        PrivateKey key = PemUtils.parsePrivateKey(keyPem);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        // We don't actually delegate to the JCE default trust managers; the
        // factory call exists only so SslContextBuilder accepts our custom
        // X509ExtendedTrustManager via the .trustManager() override below.
        tmf.init((java.security.KeyStore) null);

        SslContext ctx = SslContextBuilder.forServer(key, cert)
                .protocols(TlsCipherPolicy.PROTOCOLS)
                .ciphers(TlsCipherPolicy.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .sslProvider(SslProvider.JDK)
                // UC04 § B2 — flipped from REQUIRE → OPTIONAL so the
                // mTLS-exempt POST /v1/enrollment path can reach the
                // application layer. The MtlsEnforcementFilter rejects
                // every OTHER path that arrives without a client cert
                // with 401 mtls_required, so the security envelope is
                // preserved at L7 instead of L5.
                .clientAuth(ClientAuth.OPTIONAL)
                .trustManager(new AllowlistTrustManager(allowlist))
                .applicationProtocolConfig(new io.netty.handler.ssl.ApplicationProtocolConfig(
                        io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN,
                        io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        "h2",
                        "http/1.1"))
                .build();
        ref.set(ctx);
        LOG.info("SSL context (re)built from cert={} key={}", certPath, keyPath);
    }
}
