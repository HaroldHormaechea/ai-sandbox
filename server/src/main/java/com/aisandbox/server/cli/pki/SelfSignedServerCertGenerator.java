package com.aisandbox.server.cli.pki;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates the server's RSA-2048 self-signed certificate + private key
 * for the {@code aisandboxctl pki init} command.
 *
 * <p>The cert has:
 * <ul>
 *   <li>Subject CN = the caller-supplied CN (default {@code ai-sandbox-server}).</li>
 *   <li>Validity = 5 years from generation.</li>
 *   <li>BasicConstraints = end-entity (not CA).</li>
 *   <li>KeyUsage = digitalSignature + keyEncipherment.</li>
 * </ul>
 */
public final class SelfSignedServerCertGenerator {

    public record Material(KeyPair keyPair, X509Certificate certificate) {}

    public Material generate(String commonName)
            throws NoSuchAlgorithmException, OperatorCreationException, CertificateException, CertIOException,
                    IOException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        Instant now = Instant.now();
        BigInteger serial = new BigInteger(64, new SecureRandom());
        X500Name subject =
                new X500Name("CN=" + (commonName == null || commonName.isBlank() ? "ai-sandbox-server" : commonName));

        X509v3CertificateBuilder cb = new JcaX509v3CertificateBuilder(
                subject,
                serial,
                Date.from(now.minus(60, ChronoUnit.SECONDS)),
                Date.from(now.plus(365L * 5, ChronoUnit.DAYS)),
                subject,
                kp.getPublic());
        cb.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        cb.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(cb.build(signer));
        return new Material(kp, cert);
    }
}
