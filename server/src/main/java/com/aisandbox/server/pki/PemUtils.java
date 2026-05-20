package com.aisandbox.server.pki;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Shared PEM / SHA-256 helpers used by both the server (cert + key loading,
 * fingerprint computation) and the {@code aisandboxctl} CLI.
 *
 * <p>Stateless — every method is static and side-effect-free.
 */
public final class PemUtils {

    private PemUtils() {}

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing from JCE", e);
        }
    }

    /**
     * Parse a single X.509 certificate from a PEM string. Multi-cert chains
     * are rejected — UC03 deals strictly in leaf certs.
     */
    public static X509Certificate parseCertificate(String pem) throws IOException, CertificateException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj == null) {
                throw new CertificateException("Empty PEM content");
            }
            if (obj instanceof X509Certificate cert) {
                return cert;
            }
            byte[] encoded =
                    (obj instanceof org.bouncycastle.cert.X509CertificateHolder holder) ? holder.getEncoded() : null;
            if (encoded == null) {
                throw new CertificateException(
                        "Unexpected PEM object: " + obj.getClass().getName());
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(encoded));
        }
    }

    /**
     * SHA-256 fingerprint of a cert's DER body, lowercase hex.
     *
     * <p>Full DER-encoded certificate hash; used for client-cert allowlist
     * fingerprints (see {@code ClientAllowlistService}, {@code aisandboxctl
     * client list}, audit log {@code client_*} events, and the TLS hot-path
     * matchers in {@code AllowlistTrustManager} +
     * {@code NettyServerCustomizer.IdentityCapturingHandler}). For the
     * server pin advertised in the QR — and verified by the Android client
     * via OkHttp's {@code CertificatePinner} — see {@link #spkiFingerprintHex}.
     */
    public static String fingerprintHex(X509Certificate cert) throws CertificateException {
        try {
            return sha256Hex(cert.getEncoded());
        } catch (java.security.cert.CertificateEncodingException ce) {
            throw new CertificateException("Cannot DER-encode certificate", ce);
        }
    }

    /**
     * SHA-256 fingerprint of a cert's SubjectPublicKeyInfo (SPKI), lowercase hex.
     *
     * <p>SPKI hash; used for the server pin in the QR + Android OkHttp
     * {@code CertificatePinner}. Matches HPKP / RFC 7469 — the industry
     * convention OkHttp's {@code CertificatePinner} verifies against by
     * default (it computes {@code sha256(cert.publicKey.encoded)} on the
     * presented chain). Distinct from {@link #fingerprintHex}, which hashes
     * the full DER-encoded certificate body and is used for the client-cert
     * allowlist (a different contract with a different threat model — cert
     * identity vs. public-key continuity).
     *
     * <p>Implementation note: {@code X509Certificate.getPublicKey()
     * .getEncoded()} returns the X.509 {@code SubjectPublicKeyInfo} DER
     * structure per the {@link java.security.spec.X509EncodedKeySpec}
     * contract — the same bytes openssl emits from {@code openssl x509
     * -noout -pubkey | openssl pkey -pubin -outform DER}. UC09 § AC1 pins
     * this assumption with a unit test against the canonical openssl
     * invocation.
     */
    public static String spkiFingerprintHex(X509Certificate cert) {
        return sha256Hex(cert.getPublicKey().getEncoded());
    }

    public static String extractCommonName(X509Certificate cert) {
        X500Principal subj = cert.getSubjectX500Principal();
        X500Name name = new X500Name(subj.getName());
        RDN[] cnRdn = name.getRDNs(BCStyle.CN);
        if (cnRdn.length == 0) {
            return subj.getName();
        }
        return IETFUtils.valueToString(cnRdn[0].getFirst().getValue());
    }

    /**
     * Parse Subject Alternative Name entries from a cert into a normalized list.
     *
     * <p>UC10 § AC6 — {@code aisandboxctl client invite} uses this to refuse minting
     * a QR whose {@code --server-url} host is absent from the SAN list. Catching the
     * SAN-vs-URL mismatch at mint time prevents the class of silent enrollment
     * failures that masked the OkHttp 5.3.2 chain-cleaning bug through UC04–UC09.
     *
     * <p>Normalization rules:
     * <ul>
     *   <li><b>DNS</b> ({@code GeneralName} type 2) — lowercased ASCII per RFC 6125
     *       § 6.4 (DNS matching is case-insensitive).</li>
     *   <li><b>IP</b> ({@code GeneralName} type 7) — returned as
     *       {@link InetAddress#getHostAddress()} canonical form ({@code 127.0.0.1},
     *       never {@code 127.000.000.001}). UC10's caller refuses IPv6-literal
     *       {@code --server-url} up-front; the IPv6 branch here is exercised only
     *       for error-message formatting.</li>
     *   <li>Other {@code GeneralName} types (RFC 822, URI, directoryName, …) are
     *       dropped — not relevant to host-vs-SAN validation.</li>
     * </ul>
     *
     * <p>Returns an empty list when the cert has no SAN extension (legacy certs
     * with a CN-only identity). The caller treats an empty list as "no SAN to
     * check against" and refuses with the documented remediation message rather
     * than silently accepting.
     *
     * @return immutable list of normalized entries, in iteration order of the SAN
     *     extension; may be empty but never null
     * @throws CertificateParsingException if the SAN extension cannot be decoded
     */
    public static List<SanEntry> extractSanEntries(X509Certificate cert) throws CertificateParsingException {
        Collection<List<?>> rawSans = cert.getSubjectAlternativeNames();
        if (rawSans == null || rawSans.isEmpty()) {
            return List.of();
        }
        List<SanEntry> out = new ArrayList<>(rawSans.size());
        for (List<?> entry : rawSans) {
            if (entry == null || entry.size() < 2) {
                continue;
            }
            Object typeObj = entry.get(0);
            Object valueObj = entry.get(1);
            if (!(typeObj instanceof Integer typeInt) || !(valueObj instanceof String valueStr)) {
                continue;
            }
            switch (typeInt) {
                case 2 -> out.add(new SanEntry(SanType.DNS, valueStr.toLowerCase(Locale.ROOT)));
                case 7 -> out.add(new SanEntry(SanType.IP, canonicalIp(valueStr)));
                default -> {
                    /* Other GeneralName kinds (rfc822Name, URI, directoryName, …) — skipped. */
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Best-effort canonicalisation of an IP-address SAN value via
     * {@link InetAddress#getByName(String)}. Falls back to the raw value if the
     * JDK refuses to resolve it as a literal (shouldn't happen for X.509-issued
     * SANs, which are always literal IPs, but we degrade gracefully rather than
     * propagate the exception — the caller is doing error formatting).
     */
    private static String canonicalIp(String raw) {
        try {
            return InetAddress.getByName(raw).getHostAddress();
        } catch (UnknownHostException uhe) {
            return raw;
        }
    }

    /** SAN entry kind we care about for host-vs-SAN validation. */
    public enum SanType {
        DNS,
        IP
    }

    /**
     * Normalized SAN entry — pair of {@link SanType} kind and its string value
     * (lowercased DNS name or canonical IP literal). Returned by
     * {@link #extractSanEntries(X509Certificate)}.
     */
    public record SanEntry(SanType type, String value) {}

    public static PrivateKey parsePrivateKey(String pem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair kp) {
                return converter.getKeyPair(kp).getPrivate();
            }
            if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo pki) {
                return converter.getPrivateKey(pki);
            }
            throw new IOException("Unrecognised PEM private-key object: "
                    + (obj == null ? "null" : obj.getClass().getName()));
        }
    }
}
