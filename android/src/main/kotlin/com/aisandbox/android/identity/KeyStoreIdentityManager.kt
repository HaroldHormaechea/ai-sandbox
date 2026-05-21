package com.aisandbox.android.identity

import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509KeyManager

/**
 * Owns the client cert + private key in the Android KeyStore (UC04 AC5).
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Onboarding receives a PKCS#12 byte-array from
 *       `POST /v1/enrollment` (transport passphrase is empty per UC04
 *       § "PKCS#12 transport passphrase is empty").</li>
 *   <li>{@link #importPkcs12} parses the P12 with an empty password,
 *       extracts the key + leaf cert, and stores both under
 *       {@link #KEYSTORE_ALIAS} in the `AndroidKeyStore`. Storing the
 *       key directly hands it to the system keystore (typically
 *       hardware-backed on devices with a TEE or StrongBox).</li>
 *   <li>Network code asks for a {@link KeyManagerFactory} via
 *       {@link #keyManagerFactory()} and hands it to OkHttp's
 *       {@code sslSocketFactory}. The actual key bytes never leave the
 *       KeyStore.</li>
 *   <li>Replace flow: re-scanning a QR calls {@link #wipe} first so the
 *       new import is the only key under this alias.</li>
 * </ol>
 *
 * <p>Hardware-backed isn't strictly guaranteed by the platform — on
 * pre-Pixel devices the KeyStore may use software-backed entries. The
 * key remains non-exportable either way; the Settings cert card shows
 * a "KeyStore · non-exportable" badge regardless.
 */
class KeyStoreIdentityManager {

    companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "ai-sandbox-client-cert"
        private const val TAG = "KeyStoreIdentity"

        /**
         * UC14 — Sentinel passphrase for the enrollment PKCS#12 bundle
         * returned by `POST /v1/enrollment`. The matching constant on
         * the server side is `EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE`.
         *
         * This is NOT a secret. JDK 21's default PKCS#12 emitter wraps
         * the key bag with PBES2 (PBKDF2-HMAC-SHA256 + AES-256) even
         * when given an empty passphrase, and BouncyCastle 1.79 (the
         * Android-side parser bundled by UC13) hard-rejects an empty
         * `char[]` during PBKDF2 key derivation with
         * `IllegalArgumentException("password empty")`. No system
         * property disables that check. Both sides therefore agree on
         * a fixed non-empty sentinel; the bundle's confidentiality
         * comes from the mTLS tunnel, not from PBES2.
         */
        private val ENROLLMENT_PKCS12_PASSPHRASE = "ai-sandbox-enrollment".toCharArray()

        /**
         * AndroidKeyStore has no separate passphrase (key material is
         * managed by the platform). The `KeyManagerFactory.init` call
         * site below passes this empty char[] purely to satisfy the
         * JCA API signature.
         */
        private val EMPTY_PASSWORD = CharArray(0)
    }

    /**
     * Import the PKCS#12 blob into the Android KeyStore under the
     * canonical alias. Returns the parsed leaf cert so the UI can show
     * CN + expiry on the "Identity imported" confirmation panel.
     */
    fun importPkcs12(p12Bytes: ByteArray): ImportResult {
        // 1. Parse the P12 with the UC14 sentinel passphrase.
        //
        // UC13 — Route this specific PKCS#12 unwrap through the real
        // BouncyCastle provider (registered under the project-specific
        // name `BC-ai-sandbox-client`; see
        // [BouncyCastleClientProvider]). Android's stock PKCS12 stack
        // (Conscrypt + the stripped-down `"BC"` provider) does NOT
        // register a SecretKeyFactory under the bare PBKDF2 OID
        // `1.2.840.113549.1.5.12` that the server-emitted PBES2
        // PKCS#12 bundles require during private-key unwrap, so this
        // call without the explicit provider name fails with
        // `NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available`.
        // Naming the provider explicitly here keeps TLS (Conscrypt) and
        // every other JCA consumer on Android's defaults.
        // NOTE: `KEYSTORE_PROVIDER` in the companion below is
        // `"AndroidKeyStore"` and is *unrelated* to this provider name —
        // it refers to the destination KeyStore type, not a JCE Provider.
        //
        // UC14 — The passphrase is the agreed sentinel
        // `ENROLLMENT_PKCS12_PASSPHRASE`, NOT empty. BouncyCastle 1.79
        // rejects empty char[] during PBKDF2 key derivation; see the
        // KDoc on the constant for the full rationale.
        val source = KeyStore.getInstance("PKCS12", BouncyCastleClientProvider.NAME)
        source.load(ByteArrayInputStream(p12Bytes), ENROLLMENT_PKCS12_PASSPHRASE)
        val aliases = source.aliases().toList()
        require(aliases.isNotEmpty()) { "PKCS#12 contains no aliases" }
        val sourceAlias = aliases.first()

        val privateKey = source.getKey(sourceAlias, ENROLLMENT_PKCS12_PASSPHRASE) as PrivateKey
        val chain = source.getCertificateChain(sourceAlias)
            ?: arrayOf(source.getCertificate(sourceAlias))
        val leaf = chain.first() as X509Certificate

        // 2. Move into the AndroidKeyStore under the canonical alias.
        // We pass null protection because AndroidKeyStore manages key
        // material directly — there is no separate passphrase.
        val androidKeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        androidKeyStore.load(null)
        androidKeyStore.setKeyEntry(KEYSTORE_ALIAS, privateKey, null, chain)

        Log.i(TAG, "Imported client cert: CN=${leaf.subjectX500Principal.name}, not-after=${leaf.notAfter}")
        return ImportResult(leaf)
    }

    /** Remove the existing cert + key — used by the "replace identity" flow. */
    fun wipe() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            ks.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    /** {@code true} when there is an imported identity ready for TLS. */
    fun hasIdentity(): Boolean {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        return ks.containsAlias(KEYSTORE_ALIAS)
    }

    /**
     * Build a [KeyManagerFactory] wrapping the AndroidKeyStore so OkHttp
     * can authenticate every mTLS request without ever touching the raw
     * private key bytes. Caller initializes its SSLContext with the
     * returned KeyManagers + a per-server trust manager (UC10 wires
     * `com.aisandbox.android.net.SpkiPinningTrustManager`, which performs
     * the SPKI pin check inside `checkServerTrusted`).
     */
    fun keyManagerFactory(): KeyManagerFactory {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, EMPTY_PASSWORD)
        return kmf
    }

    /**
     * Surface the leaf cert metadata to the UI for the Settings cert card
     * and the onboarding "imported" confirmation panel.
     */
    fun leafCertificate(): X509Certificate? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        return ks.getCertificate(KEYSTORE_ALIAS) as? X509Certificate
    }

    /**
     * Quick KeyManager sanity check — used by tests and by the Diagnostics
     * "Simulate cert revoke" path that wants to confirm a real
     * X509KeyManager is in the chain.
     */
    fun x509KeyManagerOrNull(): X509KeyManager? =
        keyManagerFactory().keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()

    /** Result envelope so the onboarding screen has structured metadata. */
    data class ImportResult(val leaf: X509Certificate)
}
