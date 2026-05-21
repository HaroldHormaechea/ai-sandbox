package com.aisandbox.server.enrollment.service;

import com.aisandbox.server.cli.pki.ClientCertGenerator;
import com.aisandbox.server.cli.pki.PemWriter;
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
 * <p>The PKCS#12 bundle returned by {@link #mint(String)} is wrapped
 * with the sentinel transport passphrase
 * {@link #ENROLLMENT_PKCS12_PASSPHRASE} (UC14 — see that constant's
 * KDoc for the BouncyCastle empty-password rationale). The client
 * consumes the bundle in-memory and never writes it to durable
 * storage (AC5).
 *
 * <p>Loaded in every profile (so {@link EnrollmentFacade} can autowire);
 * never invoked under {@code docs-only} since the TLS listener is also
 * down there.
 */
@Service
public class EnrollmentCertMintService {

    private final ClientCertGenerator generator = new ClientCertGenerator();

    /**
     * Generate a fresh RSA-2048 key + self-signed cert under {@code CN=name},
     * package it into an in-memory PKCS#12 with the
     * {@link #ENROLLMENT_PKCS12_PASSPHRASE} sentinel passphrase, and
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
     * Sentinel passphrase for the in-memory enrollment PKCS#12 bundle.
     *
     * <p>UC14 — the JDK 21 default PKCS#12 emitter wraps the private-key
     * bag with PBES2 (PBKDF2-HMAC-SHA256 + AES-256) regardless of whether
     * the passphrase is empty. BouncyCastle 1.79 (the Android-side parser
     * bundled by UC13) hard-rejects an empty {@code char[]} during PBKDF2
     * key derivation — {@code IllegalArgumentException("password empty")}
     * from {@code PBEPBKDF2$BasePBKDF2.engineGenerateSecret}. There is no
     * system property to disable that check. Both sides therefore agree
     * on a fixed non-empty sentinel: the value below is not a secret (it
     * lives in plaintext source on both sides; the enrollment bundle's
     * confidentiality comes from the mTLS tunnel, not from PBES2).
     *
     * <p>The matching Android constant lives at
     * {@code KeyStoreIdentityManager.kt} → {@code ENROLLMENT_PKCS12_PASSPHRASE}.
     */
    public static final String ENROLLMENT_PKCS12_PASSPHRASE = "ai-sandbox-enrollment";

    /**
     * PKCS#12 encoder that writes to a byte array rather than a file. The
     * sentinel passphrase mirrors the Android-side constant (see KDoc on
     * {@link #ENROLLMENT_PKCS12_PASSPHRASE} for why the bundle is no
     * longer emitted with an empty passphrase) — we never touch disk; the
     * bundle lives in memory only.
     */
    private static byte[] packageInMemoryPkcs12(String alias, java.security.PrivateKey key, X509Certificate cert)
            throws IOException, CertificateException {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            char[] passphrase = ENROLLMENT_PKCS12_PASSPHRASE.toCharArray();
            ks.setKeyEntry(alias, key, passphrase, new Certificate[] {cert});
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            ks.store(out, passphrase);
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
