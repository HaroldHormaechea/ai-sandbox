package com.aisandbox.server.enrollment.service;

import com.aisandbox.server.cli.pki.ClientCertGenerator;
import com.aisandbox.server.cli.pki.PemWriter;
import com.aisandbox.server.cli.pki.Pkcs12Writer;
import com.aisandbox.server.enrollment.dto.MintedBundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Mints a fresh client cert + key in response to a redeemed enrollment
 * token (UC04 § B1). Reuses the same {@link ClientCertGenerator} +
 * RSA-2048 policy as {@code aisandboxctl client mint} so the on-disk
 * audit trail looks identical regardless of how a cert was created.
 *
 * <p>The minted cert is written PEM-encoded next to the existing
 * allowlist directory entries (the caller — {@code EnrollmentFacade} —
 * passes it to {@code ClientAllowlistFacade.addClient}, which performs
 * the atomic write through {@code AllowlistDirectory}).
 *
 * <p>The PKCS#12 bundle returned by {@link #mint(String)} has an empty
 * transport passphrase. The client consumes it in-memory and never
 * writes it to durable storage (AC5 + UC04 § "PKCS#12 transport
 * passphrase is empty").
 *
 * <p>Disabled under {@code docs-only} — OAS rendering does not need to
 * mint anything.
 */
@Service
@Profile("!docs-only")
public class EnrollmentCertMintService {

    private final ClientCertGenerator generator = new ClientCertGenerator();

    /**
     * Generate a fresh RSA-2048 key + self-signed cert under {@code CN=name},
     * package it into an in-memory PKCS#12 with an empty passphrase, and
     * return both the PEM-encoded cert (for the allowlist write) and the
     * P12 blob (for the HTTP response body).
     *
     * @throws IllegalArgumentException if {@code name} is not allowlist-safe
     *         (the same {@code [A-Za-z0-9._-]+} pattern enforced by the
     *         existing mint subcommand).
     */
    public MintedBundle mint(String name) throws IOException, CertificateException {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Client name must match [A-Za-z0-9._-]+");
        }
        ClientCertGenerator.Material material;
        try {
            material = generator.generate(name);
        } catch (RuntimeException re) {
            throw re;
        } catch (CertificateException | IOException pass) {
            throw pass;
        } catch (Exception other) {
            // ClientCertGenerator declares NoSuchAlgorithmException + OperatorCreationException.
            throw new IOException("Cannot mint client cert: " + other.getMessage(), other);
        }
        X509Certificate cert = material.certificate();
        String certPem = PemWriter.certPem(cert);

        byte[] p12 = packageInMemoryPkcs12(name, material.keyPair().getPrivate(), cert);
        return new MintedBundle(name, certPem, p12);
    }

    /**
     * PKCS#12 encoder that writes to a byte array rather than a file. The
     * empty passphrase mirrors {@link Pkcs12Writer} except that we never
     * touch disk — the bundle lives in memory only.
     */
    private static byte[] packageInMemoryPkcs12(String alias, java.security.PrivateKey key, X509Certificate cert)
            throws IOException, CertificateException {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            char[] empty = new char[0];
            ks.setKeyEntry(alias, key, empty, new Certificate[] {cert});
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            ks.store(out, empty);
            return out.toByteArray();
        } catch (KeyStoreException | NoSuchAlgorithmException e) {
            throw new IOException("Cannot package PKCS#12 bundle: " + e.getMessage(), e);
        }
    }

    /**
     * For symmetry with {@code aisandboxctl client mint --pem}: write the
     * PEM-encoded cert into a file under {@code clients.dir} using the
     * same {@link PemWriter} helper. Used by tests today; the production
     * write path goes through {@code ClientAllowlistFacade.addClient}
     * which already calls {@link com.aisandbox.server.clients.service.AllowlistDirectory#write}.
     */
    @SuppressWarnings("unused")
    static void writePemForTests(Path file, X509Certificate cert) {
        try {
            Files.createDirectories(file.getParent());
            PemWriter.writeCert(file, cert);
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }
}
