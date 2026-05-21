package com.aisandbox.android.identity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC13 + UC14 — regression guard for the Android-side PKCS#12 import
 * code path.
 *
 * <h2>What this test asserts</h2>
 *
 * <p>A PKCS#12 envelope emitted in the same shape the server produces
 * (JDK-21-default PBES2 / PBKDF2-HMAC-SHA256 + AES-256, wrapped with the
 * agreed sentinel transport passphrase) is loadable by the production
 * code path: {@code KeyStore.getInstance("PKCS12", BouncyCastleClientProvider.NAME)}
 * + {@code load(stream, ENROLLMENT_PKCS12_PASSPHRASE)}, with private-key
 * and X.509 cert bytes round-tripping exactly.
 *
 * <h2>Pre-fix regression signatures captured</h2>
 *
 * <ul>
 *   <li><b>UC13 (Android stock):</b>
 *       {@code NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available}
 *       — Android stock providers don't register a SecretKeyFactory under
 *       the bare PBKDF2 OID. Fixed by routing the lookup through the
 *       upstream BouncyCastle bundled as {@code BC-ai-sandbox-client}.</li>
 *   <li><b>UC14 (BouncyCastle 1.79 empty-password rejection):</b>
 *       {@code IllegalArgumentException: password empty} thrown from
 *       {@code PBEPBKDF2$BasePBKDF2.engineGenerateSecret}. Fixed by
 *       wrapping the bundle with a non-empty sentinel passphrase
 *       (matching the server-side
 *       {@code EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE}).</li>
 * </ul>
 *
 * <h2>Why this is pure JUnit 5 (no Robolectric)</h2>
 *
 * <p>The code under test only talks to {@code java.security.*} — no
 * Android-specific APIs. Robolectric is heavy startup, and the
 * project's current Robolectric environment surfaces unrelated runtime
 * problems (Resources$NotFoundException, RoboMonitoringInstrumentation
 * errors) when the test classpath actually loads it via JUnit 4. Pure
 * JUnit 5 sidesteps all of that and proves the production unwrap path
 * works against a server-shape envelope. The Android-lifecycle
 * "register-on-startup" wiring is exercised at runtime by the actual
 * app (and was the symptom UC14 was filed to fix); a Robolectric unit
 * test of that wiring would add no marginal value over the runtime
 * gate.
 *
 * <h2>Fixture-emission assumption</h2>
 *
 * <p>The test mints its PKCS#12 fixture via
 * {@code KeyStore.getInstance("PKCS12")} with no explicit provider —
 * the same call shape the server's
 * {@code EnrollmentCertMintService.packageInMemoryPkcs12} uses. Both
 * code paths run on JDK 21, which since Java 14+ emits a v3 envelope
 * with PBES2 (PBKDF2-HMAC-SHA256 + AES-256) by default. The envelope
 * shape this fixture produces is therefore identical to what the
 * phone receives in onboarding. If a future JDK changes that default,
 * this test's diagnostic value drops; re-pin the fixture-emission
 * provider or move fixture generation under the server module itself.
 */
class KeyStoreIdentityManagerPkcs12ImportTest {

    // UC14 — Sentinel passphrase the server emits the PKCS#12 with and
    // the Android side consumes it with. BouncyCastle 1.79 hard-rejects
    // empty char[] during PBKDF2 key derivation; the transport-side
    // fixture has to mirror the production constant or the load would
    // fail with `password empty`. Kept in sync with
    // `EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE` (server)
    // and the private constant in `KeyStoreIdentityManager.kt` (production).
    private val transportPassphrase: CharArray = "ai-sandbox-enrollment".toCharArray()

    @BeforeEach
    fun ensureBouncyCastleClientProviderRegistered() {
        BouncyCastleClientProvider.register()
    }

    @Test
    fun loadPbes2EnvelopeViaBcSucceedsAndRoundTripsKeyAndCert() {
        // 1. Mint a real RSA-2048 + self-signed X.509 fixture. Inject the
        //    KeyPair explicitly so the envelope matches the server-side
        //    enrollment cert (RSA-2048 per UC04 AC5) regardless of
        //    HeldCertificate's default algorithm choice.
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val held = HeldCertificate.Builder()
            .keyPair(keyPair)
            .commonName("uc14-pkcs12-import-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val sourceKey: PrivateKey = held.keyPair.private
        val sourceCert: X509Certificate = held.certificate

        // 2. Emit a PKCS#12 envelope via the JDK default provider — same
        //    call shape the server uses in
        //    EnrollmentCertMintService.packageInMemoryPkcs12.
        val p12Bytes = emitPbes2Pkcs12(sourceKey, sourceCert)

        // 3. Load via the production code path's provider name.
        //
        //    Pre-UC13: would throw `NoSuchAlgorithmException: 1.2.840.113549.1.5.12
        //    SecretKeyFactory not available` (Android stock).
        //    Pre-UC14 (BC bundled but empty passphrase): would throw
        //    `IllegalArgumentException: password empty` from BC's PBKDF2.
        //    Post-UC14: loads cleanly.
        val ks: KeyStore = KeyStore.getInstance("PKCS12", BouncyCastleClientProvider.NAME)
        ks.load(ByteArrayInputStream(p12Bytes), transportPassphrase)

        val aliases = ks.aliases().toList()
        assertThat(aliases)
            .describedAs("PBES2 PKCS#12 envelope must expose at least one alias to the BC unwrap path")
            .isNotEmpty()

        val loadedKey = ks.getKey(aliases.first(), transportPassphrase) as PrivateKey
        val loadedChain = ks.getCertificateChain(aliases.first())
        val loadedLeaf = loadedChain.first() as X509Certificate

        // Extracted private key byte-equals the source.
        assertThat(loadedKey.encoded)
            .describedAs("Unwrapped private-key bytes must byte-equal the source key")
            .isEqualTo(sourceKey.encoded)

        // Extracted X.509 cert byte-equals the source.
        assertThat(loadedLeaf.encoded)
            .describedAs("Unwrapped X.509 leaf bytes must byte-equal the source cert")
            .isEqualTo(sourceCert.encoded)
    }

    /**
     * Build a PKCS#12 envelope around a single key + cert entry using
     * the JDK default provider — same call shape the server uses in
     * {@code EnrollmentCertMintService.packageInMemoryPkcs12}. On
     * JDK 14+ this emits a v3 envelope with PBES2 (PBKDF2-HMAC-SHA256
     * + AES-256).
     */
    private fun emitPbes2Pkcs12(key: PrivateKey, cert: X509Certificate): ByteArray {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, transportPassphrase)
        ks.setKeyEntry(
            "ai-sandbox-client-cert",
            key,
            transportPassphrase,
            arrayOf<java.security.cert.Certificate>(cert),
        )
        val out = ByteArrayOutputStream()
        ks.store(out, transportPassphrase)
        return out.toByteArray()
    }
}
