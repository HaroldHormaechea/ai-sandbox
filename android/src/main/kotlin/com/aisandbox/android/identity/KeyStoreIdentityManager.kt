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
        /** PKCS#12 transport passphrase from POST /v1/enrollment is always empty. */
        private val EMPTY_PASSWORD = CharArray(0)
    }

    /**
     * Import the PKCS#12 blob into the Android KeyStore under the
     * canonical alias. Returns the parsed leaf cert so the UI can show
     * CN + expiry on the "Identity imported" confirmation panel.
     */
    fun importPkcs12(p12Bytes: ByteArray): ImportResult {
        // 1. Parse the P12 with empty passphrase.
        val source = KeyStore.getInstance("PKCS12")
        source.load(ByteArrayInputStream(p12Bytes), EMPTY_PASSWORD)
        val aliases = source.aliases().toList()
        require(aliases.isNotEmpty()) { "PKCS#12 contains no aliases" }
        val sourceAlias = aliases.first()

        val privateKey = source.getKey(sourceAlias, EMPTY_PASSWORD) as PrivateKey
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
