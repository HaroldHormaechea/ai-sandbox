package com.aisandbox.server.pki;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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
