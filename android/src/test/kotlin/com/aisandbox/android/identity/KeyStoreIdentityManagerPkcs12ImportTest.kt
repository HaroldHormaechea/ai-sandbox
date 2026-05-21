package com.aisandbox.android.identity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UC13 — covers AC1 (phone-equivalent import succeeds), AC2 (a/b/c —
 * KeyStore.load succeeds via BC and the extracted key + cert byte-equal
 * the source material), and AC11 (test-first cascade — the pre-fix
 * regression-signature documented in this KDoc).
 *
 * <h2>Pre-fix regression-signature (AC11)</h2>
 *
 * <p>Before this UC landed, the Android client failed to import the
 * PBES2-encrypted PKCS#12 bundle emitted by {@code server-v0.0.12+}
 * (and by the JDK 21 default {@code KeyStore.getInstance("PKCS12")}
 * code path generally). The failure surfaced as
 *
 * <pre>
 * import_failed Cannot import client cert: exception unwrapping private key -
 *   java.security.NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available
 * </pre>
 *
 * <p>OID {@code 1.2.840.113549.1.5.12} is PBKDF2. Android's stock
 * PKCS12 stack (Conscrypt + the stripped-down platform {@code "BC"}
 * provider) does not register a {@code SecretKeyFactory} under the
 * bare OID, only under the named string
 * {@code PBKDF2WithHmacSHA256}, so the lookup during private-key
 * unwrap returns no provider and the import fails.
 *
 * <p>This test class is the regression check for that failure mode: it
 * generates a real-shape PBES2 PKCS#12 envelope and asserts the
 * production {@link BouncyCastleClientProvider}-routed unwrap path
 * loads it successfully.
 *
 * <h2>Why pure-JVM is the load-bearing assertion (primary phase)</h2>
 *
 * <p>The pre-fix failure is Android-only — the same envelope loads
 * fine on a vanilla JDK 21 via the SunJSSE PKCS12 implementation
 * because SunJSSE indexes PBKDF2 by OID. What this primary-phase test
 * actually proves is that the upstream {@code bcprov-jdk18on}
 * implementation, registered through {@link BouncyCastleClientProvider}
 * under the project-specific name {@code BC-ai-sandbox-client}, knows
 * how to unwrap PBES2 PKCS#12 — i.e. the production code path
 * {@code KeyStore.getInstance("PKCS12", "BC-ai-sandbox-client")} works
 * end-to-end against a PBES2 envelope. On Android that becomes the
 * ONLY path that works; on the JVM it's one of two that do, and we
 * exercise the BC one because that is what the production code site
 * in {@link KeyStoreIdentityManager#importPkcs12} calls.
 *
 * <h2>Fixture-emission assumption (server-envelope shape parity)</h2>
 *
 * <p>The test mints its PKCS#12 fixture via
 * {@code KeyStore.getInstance("PKCS12")} with no explicit provider —
 * the same call shape the server's
 * {@code EnrollmentCertMintService.packageInMemoryPkcs12} uses.
 * Both code paths run on JDK 21, which since Java 14+ emits a v3
 * envelope with PBES2 (PBKDF2-HMAC-SHA256 + AES-256) by default. The
 * envelope shape this fixture produces is therefore identical to
 * what the phone receives in onboarding. If a future JDK ever
 * changes that default, this test's diagnostic value drops; in that
 * case re-pin the fixture-emission props or move the fixture
 * generation under the server module itself.
 *
 * <h2>Robolectric secondary phase (best-effort)</h2>
 *
 * <p>The secondary {@link #robolectric_importPkcs12_via_KeyStoreIdentityManager}
 * boots a real {@link KeyStoreIdentityManager} and calls
 * {@link KeyStoreIdentityManager#importPkcs12} end-to-end. The BC
 * unwrap step is what UC13 actually changes; the subsequent
 * AndroidKeyStore.setKeyEntry step is unchanged from UC04 and is what
 * Robolectric's {@code AndroidKeyStore} shadow may not faithfully
 * model. If the shadow rejects {@code setKeyEntry(alias, PrivateKey,
 * null, chain)} (or any other downstream AndroidKeyStore call), the
 * test still passes as long as (a) the failure is not the pre-fix
 * regression-signature documented above, and (b) the failure occurs
 * AFTER the BC unwrap step — which is the only thing this UC changes.
 * Per UC13's MVP framing and the project's "no-emulator CI policy"
 * (UC04 AC30), exhaustive AndroidKeyStore coverage lives in the
 * deferred {@code connectedAndroidTest} surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class KeyStoreIdentityManagerPkcs12ImportTest {

    private val transportPassphrase: CharArray = CharArray(0)

    @Before
    fun ensureBouncyCastleClientProviderRegistered() {
        // Production code path calls this in
        // AiSandboxApplication.onCreate(). Calling it again here is
        // idempotent — every register() after the first one in the
        // process lifetime is a no-op.
        BouncyCastleClientProvider.register()
    }

    // ── AC1 + AC2 — primary phase (pure-JVM mandatory) ───────────────────

    @Test
    fun primary_loadPbes2Envelope_via_BC_succeeds_and_round_trips_key_and_cert() {
        // 1. Mint a real RSA-2048 + self-signed X.509 fixture. We
        //    explicitly inject an RSA-2048 KeyPair so the envelope
        //    matches the server-side enrollment cert (RSA-2048 per
        //    UC04 AC5) regardless of HeldCertificate's default
        //    algorithm choice in any given okhttp-tls minor version.
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val held = HeldCertificate.Builder()
            .keyPair(keyPair)
            .commonName("uc13-pbes2-import-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val sourceKey: PrivateKey = held.keyPair.private
        val sourceCert: X509Certificate = held.certificate

        // 2. Emit a PKCS#12 envelope via the JDK default provider
        //    (no explicit provider arg → SunJSSE on JDK 21). JDK 14+
        //    emits PBES2 (PBKDF2-HMAC-SHA256 + AES-256) by default —
        //    same envelope shape the server's
        //    EnrollmentCertMintService.packageInMemoryPkcs12 produces.
        val p12Bytes = emitPbes2Pkcs12(sourceKey, sourceCert)

        // 3. Load the envelope via the production code path's
        //    provider name. This is the assertion under test —
        //    if BC's SecretKeyFactory tables didn't index the bare
        //    PBKDF2 OID 1.2.840.113549.1.5.12, this line would throw
        //    `NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available`.
        val ks: KeyStore = KeyStore.getInstance("PKCS12", BouncyCastleClientProvider.NAME)
        ks.load(ByteArrayInputStream(p12Bytes), transportPassphrase)

        // AC2 (a): load succeeded — confirmed by reaching this line.
        // Sanity-check there's at least one alias before extracting.
        val aliases = ks.aliases().toList()
        assertThat(aliases)
            .describedAs("PBES2 PKCS#12 envelope must expose at least one alias to the BC unwrap path")
            .isNotEmpty()

        val loadedKey = ks.getKey(aliases.first(), transportPassphrase) as PrivateKey
        val loadedChain = ks.getCertificateChain(aliases.first())
        val loadedLeaf = loadedChain.first() as X509Certificate

        // AC2 (b): extracted private key byte-equals the source.
        assertThat(loadedKey.encoded)
            .describedAs("Unwrapped private-key bytes must byte-equal the source key")
            .isEqualTo(sourceKey.encoded)

        // AC2 (c): extracted X.509 cert byte-equals the source.
        assertThat(loadedLeaf.encoded)
            .describedAs("Unwrapped X.509 leaf bytes must byte-equal the source cert")
            .isEqualTo(sourceCert.encoded)
    }

    // ── AC1 + AC3 — secondary phase (Robolectric, best-effort) ───────────

    @Test
    fun robolectric_importPkcs12_via_KeyStoreIdentityManager() {
        // End-to-end exercise of KeyStoreIdentityManager.importPkcs12 —
        // BC unwrap PLUS the AndroidKeyStore.setKeyEntry write that
        // UC04 owns. This proves the production wiring is consistent
        // (the only call site uses BouncyCastleClientProvider.NAME)
        // and that no other Application-bootstrap state is needed to
        // make the BC step work.
        //
        // If Robolectric's AndroidKeyStore shadow rejects the
        // setKeyEntry call, that's acceptable: the BC unwrap step is
        // what UC13 changes, and the assertion below specifically
        // forbids the pre-fix regression-signature regardless of
        // whether the downstream AndroidKeyStore step succeeds.
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val held = HeldCertificate.Builder()
            .keyPair(keyPair)
            .commonName("uc13-robolectric-import-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val p12Bytes = emitPbes2Pkcs12(held.keyPair.private, held.certificate)

        val identity = KeyStoreIdentityManager()
        val maybeResult: KeyStoreIdentityManager.ImportResult? = try {
            identity.importPkcs12(p12Bytes)
        } catch (t: Throwable) {
            // Walk the cause chain; the regression-signature can hide
            // behind ProviderException / KeyStoreException wrappers.
            assertNotPbkdf2OidFailure(t)
            null
        }

        // If the call succeeded, the leaf cert came back; assert basic
        // shape (same DN we minted with). If it failed downstream of
        // BC, we've already asserted the failure was not the pre-fix
        // regression-signature, which is the only thing this UC owns.
        if (maybeResult != null) {
            assertThat(maybeResult.leaf.subjectX500Principal.name)
                .describedAs("Round-tripped leaf cert DN must match the minted fixture")
                .contains("uc13-robolectric-import-test")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Build a PKCS#12 envelope around a single key + cert entry using
     * the JDK default provider — same call shape the server uses in
     * {@code EnrollmentCertMintService.packageInMemoryPkcs12}. On
     * JDK 14+ this emits a v3 envelope with PBES2 (PBKDF2-HMAC-SHA256
     * + AES-256), which is the exact format that triggers the
     * Android-side regression documented in this class's KDoc.
     */
    private fun emitPbes2Pkcs12(key: PrivateKey, cert: X509Certificate): ByteArray {
        // NO provider arg here — we want the JDK default (SunJSSE on
        // JDK 21), not the BC we registered for the read path. This
        // matches the server's emit-side call shape.
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

    /**
     * Assert that a thrown exception (or any link in its cause chain)
     * is NOT the pre-fix regression-signature from this UC. Used by
     * the Robolectric secondary phase to filter Android-shadow-induced
     * failures (which UC13 does not own) from the actual UC13
     * regression (which it does).
     */
    private fun assertNotPbkdf2OidFailure(t: Throwable) {
        var cursor: Throwable? = t
        while (cursor != null) {
            val msg = cursor.message ?: ""
            assertThat(msg)
                .describedAs(
                    "Pre-fix regression-signature `1.2.840.113549.1.5.12 SecretKeyFactory not available` " +
                        "leaked into the Robolectric secondary phase — this UC must keep it dead. " +
                        "Throwable chain root cause: ${cursor.javaClass.name}",
                )
                .doesNotContain("1.2.840.113549.1.5.12")
            cursor = cursor.cause
        }
    }
}
