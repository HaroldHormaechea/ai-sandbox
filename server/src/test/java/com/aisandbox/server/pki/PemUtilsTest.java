package com.aisandbox.server.pki;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.test.CertFixtures;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;

/**
 * UC-09 § AC1 — pin {@link PemUtils#spkiFingerprintHex(X509Certificate)} to
 * the canonical openssl SPKI invocation:
 *
 * <pre>{@code
 * openssl x509 -in <pem> -noout -pubkey \
 *     | openssl pkey -pubin -outform DER \
 *     | sha256sum
 * }</pre>
 *
 * <p>Three orthogonal assertions cover the contract:
 *
 * <ol>
 *   <li><b>Shape</b> — output is exactly 64 lowercase hex chars. Catches
 *       accidental encoding regressions (uppercase, base64, missing padding,
 *       inclusion of the {@code "sha256:"} prefix, …) without re-deriving
 *       the value from the same code path (tautology-free).</li>
 *   <li><b>Canonical openssl match</b> — load the committed
 *       {@code spki-fixture.crt} and assert the helper returns
 *       {@link #EXPECTED_SPKI_HEX}, a constant captured ONCE at
 *       fixture-generation time via the OpenSSL stack (see the
 *       {@code Regenerating the fixture} section below). This is the JDK
 *       drift detector: if a future JDK / BouncyCastle release changes the
 *       bytes returned by {@link X509Certificate#getPublicKey()}{@code
 *       .getEncoded()} for an RSA-2048 key, this assertion fails loudly
 *       instead of silently shipping incompatible pins to operators.</li>
 *   <li><b>Distinct from {@link PemUtils#fingerprintHex}</b> — the two
 *       methods must produce different output on the same certificate.
 *       Guards against a future accidental code collapse (someone
 *       "refactoring" {@code spkiFingerprintHex} back into a delegate of
 *       {@code fingerprintHex} would re-introduce the v0.0.9 bug, and the
 *       integration test in {@code EnrollmentPinAlgorithmTest} only catches
 *       it at the cost of a full Spring Boot context bring-up).</li>
 * </ol>
 *
 * <h2>Regenerating the fixture</h2>
 *
 * The committed fixture lives at
 * {@code server/src/test/resources/pki/spki-fixture.crt}. To rotate it
 * (or to add a new fixture for a different key algorithm), run the
 * following on a host with OpenSSL — the EXACT commands UC-09 § AC1
 * pins as the canonical SPKI computation, with no flags omitted or
 * reordered:
 *
 * <pre>{@code
 * # 1. Generate a fresh self-signed RSA-2048 cert. No SAN, no real CN,
 * #    100-year expiry so this fixture survives any reasonable CI clock.
 * openssl req -x509 -newkey rsa:2048 \
 *     -keyout /tmp/throwaway.key \
 *     -out server/src/test/resources/pki/spki-fixture.crt \
 *     -days 36500 -nodes \
 *     -subj '/CN=ai-sandbox-uc09-spki-fixture'
 *
 * # 2. Discard the private key — it must NEVER be committed.
 * rm /tmp/throwaway.key
 *
 * # 3. Capture the expected SPKI hash and copy it into EXPECTED_SPKI_HEX
 * #    below. THIS is the canonical openssl invocation AC1 pins; the
 * #    leading "openssl x509 -noout -pubkey" extracts the
 * #    SubjectPublicKeyInfo, "openssl pkey -pubin -outform DER" emits the
 * #    raw SPKI DER bytes, and "sha256sum" hashes them.
 * openssl x509 -in server/src/test/resources/pki/spki-fixture.crt \
 *     -noout -pubkey \
 *   | openssl pkey -pubin -outform DER \
 *   | sha256sum
 * }</pre>
 *
 * <p><b>Fixture generation note (this specific commit).</b> The QA agent
 * lacked the {@code openssl} CLI in its sandbox, so the committed fixture
 * was minted via {@code keytool -genkeypair -alias spkifixture -keyalg
 * RSA -keysize 2048 -dname 'CN=ai-sandbox-uc09-spki-fixture' -validity
 * 36500 -keystore <tmp>/ks.p12 -storetype PKCS12 -storepass changeit
 * -keypass changeit} followed by {@code keytool -exportcert -alias
 * spkifixture -keystore <tmp>/ks.p12 -storetype PKCS12 -storepass
 * changeit -rfc > spki-fixture.crt}, and the expected hash was captured
 * via Node 24's OpenSSL-backed {@code crypto.X509Certificate.publicKey
 * .export({type:'spki', format:'der'})} → SHA-256. Node's crypto module
 * links against the SAME OpenSSL the CLI invocation above would use, so
 * the captured hash matches what the documented {@code openssl …
 * sha256sum} pipeline would have produced. The next rotation SHOULD run
 * the documented openssl commands above; the keytool/Node path is a
 * one-time bootstrap artifact.
 */
class PemUtilsTest {

    /**
     * SHA-256 of the SubjectPublicKeyInfo DER of
     * {@code spki-fixture.crt}, captured at fixture-generation time
     * via the OpenSSL stack (see class Javadoc).
     */
    private static final String EXPECTED_SPKI_HEX = "3c1362402d33dfafe3e3306d51faa7b5d776e98782305d629dd3d6defce9f3d9";

    @Test
    void spkiFingerprintHex_returns_64_lowercase_hex_chars() throws Exception {
        // Fresh keypair — every run sees a different cert, so the shape
        // assertion does not accidentally pin the value of one specific
        // fixture. If sha256Hex ever produces uppercase, a leading prefix,
        // or any non-hex character, the regex fails.
        CertFixtures.ServerMaterial material = CertFixtures.newServer("pem-utils-spki");

        String spkiHex = PemUtils.spkiFingerprintHex(material.certificate());

        assertThat(spkiHex)
                .as("UC-09 § AC1 — SPKI hex must be exactly 64 lowercase hex chars")
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void spkiFingerprintHex_matches_canonical_openssl_invocation_on_committed_fixture() throws Exception {
        // Load the committed fixture cert. The classpath path mirrors the
        // on-disk layout under server/src/test/resources/.
        X509Certificate fixture;
        try (InputStream in = PemUtilsTest.class.getResourceAsStream("/pki/spki-fixture.crt")) {
            assertThat(in)
                    .as("UC-09 § AC1 — committed fixture cert must be on the test classpath")
                    .isNotNull();
            String pem = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            fixture = PemUtils.parseCertificate(pem);
        }

        String spkiHex = PemUtils.spkiFingerprintHex(fixture);

        assertThat(spkiHex)
                .as("UC-09 § AC1 — PemUtils.spkiFingerprintHex must match the value captured at "
                        + "fixture-generation time via `openssl x509 -noout -pubkey | openssl pkey "
                        + "-pubin -outform DER | sha256sum`. A mismatch means either the fixture was "
                        + "rotated without updating EXPECTED_SPKI_HEX, or the JDK's "
                        + "X509Certificate.getPublicKey().getEncoded() changed the SPKI byte layout "
                        + "for an RSA-2048 key (drift detector).")
                .isEqualTo(EXPECTED_SPKI_HEX);
    }

    @Test
    void spkiFingerprintHex_differs_from_fingerprintHex_on_same_certificate() throws Exception {
        // The two methods MUST hash different byte streams — full DER cert
        // vs. SPKI DER. If a future "simplification" accidentally collapses
        // spkiFingerprintHex into a fingerprintHex delegate, this guard
        // catches it at the unit level (cheap), before the
        // EnrollmentPinAlgorithmTest catches it via a Spring Boot
        // SSLPeerUnverifiedException (expensive — boots the server, opens
        // a TLS socket).
        CertFixtures.ServerMaterial material = CertFixtures.newServer("pem-utils-collapse-guard");

        String fullDerHex = PemUtils.fingerprintHex(material.certificate());
        String spkiHex = PemUtils.spkiFingerprintHex(material.certificate());

        assertThat(spkiHex)
                .as("UC-09 § AC1 — fingerprintHex and spkiFingerprintHex must NEVER produce "
                        + "the same value on the same cert. Equal output here means someone has "
                        + "collapsed the SPKI helper back into the full-DER helper, re-introducing "
                        + "the v0.0.9 algorithm bug.")
                .isNotEqualTo(fullDerHex);
    }
}
