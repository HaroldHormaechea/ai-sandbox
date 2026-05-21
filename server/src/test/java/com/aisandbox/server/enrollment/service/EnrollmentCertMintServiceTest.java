package com.aisandbox.server.enrollment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.enrollment.dto.MintedBundle;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Enumeration;
import org.junit.jupiter.api.Test;

/**
 * UC04 AC33 + § B1 — mint a fresh client cert + key, package into an
 * in-memory PKCS#12 with the empty transport passphrase. Reuses the
 * existing {@code ClientCertGenerator} so the cert shape matches the
 * legacy {@code aisandboxctl client mint} path exactly.
 */
class EnrollmentCertMintServiceTest {

    @Test
    void mint_produces_rsa_2048_self_signed_cert_with_matching_cn() throws Exception {
        EnrollmentCertMintService svc = new EnrollmentCertMintService();
        MintedBundle bundle = svc.mint("alice-phone");

        // PKCS#12 round-trip — re-parse the bundle to assert the inner
        // shape the Android client will see.
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] passphrase = EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE.toCharArray();
        ks.load(new ByteArrayInputStream(bundle.pkcs12()), passphrase);

        // Exactly one alias, matching the requested name.
        Enumeration<String> aliases = ks.aliases();
        assertThat(aliases.hasMoreElements()).isTrue();
        String alias = aliases.nextElement();
        assertThat(alias).isEqualTo("alice-phone");
        assertThat(aliases.hasMoreElements()).isFalse();

        // Cert + key entry under that alias.
        assertThat(ks.isKeyEntry(alias)).isTrue();
        PrivateKey key = (PrivateKey) ks.getKey(alias, passphrase);
        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");

        Certificate cert = ks.getCertificate(alias);
        assertThat(cert).isInstanceOf(X509Certificate.class);
        X509Certificate x509 = (X509Certificate) cert;
        // CN matches the requested name (subject is CN=<name>).
        assertThat(x509.getSubjectX500Principal().getName()).contains("CN=alice-phone");
        // Self-signed — issuer == subject.
        assertThat(x509.getIssuerX500Principal()).isEqualTo(x509.getSubjectX500Principal());
        // RSA 2048-bit per UC04 § B1.
        assertThat(x509.getPublicKey()).isInstanceOf(RSAPublicKey.class);
        RSAPublicKey rsa = (RSAPublicKey) x509.getPublicKey();
        assertThat(rsa.getModulus().bitLength()).isEqualTo(2048);

        // PEM string in the bundle round-trips back to the same cert.
        assertThat(bundle.certPem()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(bundle.certPem()).contains("-----END CERTIFICATE-----");
    }

    @Test
    void sentinel_passphrase_is_required_to_open_the_bundle() throws Exception {
        // UC14 — the bundle is wrapped with the agreed sentinel
        // passphrase (see KDoc on
        // EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE for the
        // BouncyCastle empty-password rationale). A wrong passphrase
        // attempt must fail with an IOException (PKCS#12 MAC mismatch
        // surfaces this way under SunJSSE).
        EnrollmentCertMintService svc = new EnrollmentCertMintService();
        MintedBundle bundle = svc.mint("alice-phone");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        assertThatThrownBy(() -> ks.load(new ByteArrayInputStream(bundle.pkcs12()), "not-the-sentinel".toCharArray()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void cert_pem_matches_p12_inner_cert() throws Exception {
        // The certPem field is what's written to the allowlist directory;
        // the p12 blob is what's streamed back to the Android client.
        // Both must be the same certificate.
        EnrollmentCertMintService svc = new EnrollmentCertMintService();
        MintedBundle bundle = svc.mint("alice-phone");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(
                new ByteArrayInputStream(bundle.pkcs12()),
                EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE.toCharArray());
        X509Certificate p12Cert = (X509Certificate) ks.getCertificate("alice-phone");

        // Re-parse the PEM to a cert and compare.
        var pemBytes = bundle.certPem().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        X509Certificate pemCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pemBytes));

        assertThat(pemCert).isEqualTo(p12Cert);
    }

    @Test
    void name_validation_rejects_path_traversal_and_bad_chars() {
        EnrollmentCertMintService svc = new EnrollmentCertMintService();
        // Same name-shape contract as `aisandboxctl client mint` —
        // [A-Za-z0-9._-]+ only. Reject anything that could escape the
        // allowlist directory or break the on-disk schema.
        for (String bad : new String[] {"../escape", "with space", "with/slash", "with\\backslash", ":", "", null}) {
            assertThatThrownBy(() -> svc.mint(bad))
                    .as("name=%s should be rejected", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void two_consecutive_mints_produce_distinct_key_material() throws Exception {
        // Defensive — never re-use key material across enrollments. Each
        // mint creates a fresh keypair via SecureRandom.
        EnrollmentCertMintService svc = new EnrollmentCertMintService();
        MintedBundle a = svc.mint("alice-phone");
        MintedBundle b = svc.mint("alice-phone");

        // Different bytes — the PKCS#12 envelopes are not deterministic
        // even with the same name.
        assertThat(a.pkcs12()).isNotEqualTo(b.pkcs12());

        KeyStore ksA = KeyStore.getInstance("PKCS12");
        KeyStore ksB = KeyStore.getInstance("PKCS12");
        char[] passphrase = EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE.toCharArray();
        ksA.load(new ByteArrayInputStream(a.pkcs12()), passphrase);
        ksB.load(new ByteArrayInputStream(b.pkcs12()), passphrase);

        X509Certificate cA = (X509Certificate) ksA.getCertificate("alice-phone");
        X509Certificate cB = (X509Certificate) ksB.getCertificate("alice-phone");
        // Different serials, different public keys.
        assertThat(cA.getSerialNumber()).isNotEqualTo(cB.getSerialNumber());
        assertThat(cA.getPublicKey()).isNotEqualTo(cB.getPublicKey());
    }
}
