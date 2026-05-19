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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
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
 *   <li>SubjectAlternativeName (non-critical, RFC 5280 § 4.2.1.6) — added when
 *       the caller supplies SAN entries via {@link #generate(String, List)}.
 *       Modern TLS clients (curl ≥ 7.66, browsers, OkHttp) ignore CN for
 *       hostname verification and rely on SAN exclusively, so a SAN-less cert
 *       is effectively un-verifiable on the wire even though it remains valid
 *       per X.509.</li>
 * </ul>
 *
 * <p><b>SAN entry syntax.</b> Each entry is {@code DNS:<value>} (e.g.
 * {@code DNS:host.example}) or {@code IP:<value>} (e.g. {@code IP:127.0.0.1},
 * {@code IP:::1}). DNS values are case-folded to lowercase in the caller
 * (see {@code PkiInitCommand}) before deduplication — X.509 dNSName is
 * technically case-sensitive but the IETF and every modern verifier treat it
 * case-insensitively, so folding keeps the cert tidy and prevents
 * {@code DNS:Foo} + {@code DNS:foo} from co-existing. IP values are passed
 * verbatim — they are case-insensitive anyway. Malformed entries throw
 * {@link IllegalArgumentException}.
 */
public final class SelfSignedServerCertGenerator {

    public record Material(KeyPair keyPair, X509Certificate certificate) {}

    /**
     * Backwards-compatible overload — mints a cert with no SAN extension.
     * New callers should prefer {@link #generate(String, List)} so the
     * resulting cert is usable over the wire (see class Javadoc).
     */
    public Material generate(String commonName)
            throws NoSuchAlgorithmException, OperatorCreationException, CertificateException, CertIOException,
                    IOException {
        return generate(commonName, List.of());
    }

    /**
     * Mint a self-signed server cert with the supplied SAN entries.
     *
     * @param commonName subject CN (defaults to {@code ai-sandbox-server} when null/blank).
     * @param sanEntries ordered list of {@code DNS:<value>} / {@code IP:<value>} entries.
     *     Order is preserved in the resulting extension (matches the order of the
     *     caller-supplied composition: explicit {@code --san} first, auto-hostname
     *     next, loopback last). May be empty (no SAN extension is added).
     * @throws IllegalArgumentException when any entry is malformed.
     */
    public Material generate(String commonName, List<String> sanEntries)
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

        if (sanEntries != null && !sanEntries.isEmpty()) {
            GeneralName[] gns = parseSanEntries(sanEntries);
            // Non-critical per RFC 5280 § 4.2.1.6 — clients that don't
            // understand SAN MUST still accept the cert; clients that
            // do (everything modern) MUST use it for hostname matching.
            cb.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(gns));
        }

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(cb.build(signer));
        return new Material(kp, cert);
    }

    private static GeneralName[] parseSanEntries(List<String> sanEntries) {
        List<GeneralName> out = new ArrayList<>(sanEntries.size());
        for (String raw : sanEntries) {
            if (raw == null) {
                throw new IllegalArgumentException("SAN entry must not be null");
            }
            String entry = raw.trim();
            if (entry.isEmpty()) {
                throw new IllegalArgumentException("SAN entry must not be blank");
            }
            int colon = entry.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "Malformed SAN entry '" + raw + "' — expected 'DNS:<value>' or 'IP:<value>'");
            }
            String tag = entry.substring(0, colon);
            String value = entry.substring(colon + 1);
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Empty value in SAN entry '" + raw + "'");
            }
            switch (tag) {
                case "DNS" -> out.add(new GeneralName(GeneralName.dNSName, value));
                case "IP" -> out.add(new GeneralName(GeneralName.iPAddress, value));
                default -> throw new IllegalArgumentException(
                        "Unsupported SAN tag '" + tag + "' in '" + raw + "' (only DNS: and IP: are supported)");
            }
        }
        return out.toArray(new GeneralName[0]);
    }
}
