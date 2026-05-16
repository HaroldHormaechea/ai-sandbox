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
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates a per-client RSA-2048 self-signed cert + private key for
 * {@code aisandboxctl client mint <name>}. The cert is self-signed —
 * there is no CA in this trust model; the public cert is added to the
 * server allowlist folder by the surrounding command.
 */
public final class ClientCertGenerator {

    public record Material(java.security.KeyPair keyPair, X509Certificate certificate) {}

    public Material generate(String name)
            throws NoSuchAlgorithmException, OperatorCreationException, CertificateException, CertIOException,
                    IOException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        Instant now = Instant.now();
        BigInteger serial = new BigInteger(64, new SecureRandom());
        X500Name subject = new X500Name("CN=" + name);

        var cb = new JcaX509v3CertificateBuilder(
                subject,
                serial,
                Date.from(now.minus(60, ChronoUnit.SECONDS)),
                Date.from(now.plus(365L * 2, ChronoUnit.DAYS)),
                subject,
                kp.getPublic());
        cb.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        cb.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(cb.build(signer));
        return new Material(kp, cert);
    }
}
