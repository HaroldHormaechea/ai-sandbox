package com.aisandbox.android.identity

import android.util.Log
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * UC13 — Registers the **real** BouncyCastle JCE provider under a
 * project-specific name so the Android client can unwrap modern
 * PBES2 PKCS#12 bundles emitted by `server-v0.0.12+`.
 *
 * ### Why this exists at all
 *
 * The server uses the JDK 21 default for `KeyStore.getInstance("PKCS12")`
 * which, since Java 14+, emits **PKCS#12 v3 / PBES2** — i.e. the
 * private key is wrapped using PBKDF2-HMAC-SHA256 + AES-256. During
 * unwrap, the JCA looks up a `SecretKeyFactory` for the **bare PBKDF2
 * OID `1.2.840.113549.1.5.12`** (not the named string
 * `PBKDF2WithHmacSHA256`). Android's stock PKCS12 stack (Conscrypt +
 * the stripped-down `"BC"` provider) does not register a factory under
 * that OID, so the import fails with:
 *
 * ```
 * java.security.NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available
 * ```
 *
 * Adding the upstream BouncyCastle provider (`bcprov-jdk18on`) fixes
 * this because its `SecretKeyFactory` table indexes PBKDF2 by both
 * named string and OID.
 *
 * ### Why a **distinct** provider name (`BC-ai-sandbox-client`)
 *
 * Android ships a provider already named `"BC"` (a stripped-down
 * subset). Naming our provider the same would either (a) collide with
 * the stock one (`addProvider` silently no-ops when the name is
 * already taken), or (b) require us to `removeProvider("BC")` first,
 * which then changes behaviour for any other library on the device
 * that explicitly asks for the stock `"BC"`. The distinct name
 * `BC-ai-sandbox-client` decouples us from both pitfalls: the stock
 * `"BC"` is left untouched, and every PKCS#12 call site explicitly
 * targets `KeyStore.getInstance("PKCS12", "BC-ai-sandbox-client")`.
 *
 * ### Why `Security.addProvider`, **not** `insertProviderAt`
 *
 * `Security.addProvider` appends at the lowest priority. Conscrypt is
 * Android's TLS provider (hardware-accelerated AES/AEAD on every
 * supported device) and **must remain the default** for
 * `SSLContext.getInstance("TLS")`. Using `insertProviderAt(provider, 1)`
 * would push BouncyCastle ahead of Conscrypt and could silently
 * regress the TLS handshake performance / behaviour. Lowest-priority
 * registration plus explicit-by-name lookup at the PKCS#12 site is
 * the deliberate combination that gives us PBES2 support **without**
 * touching the TLS path. See UC13 AC4 (Conscrypt-unaffected
 * assertion) and AC5 (UC09 / UC10 SPKI pinning regression).
 *
 * ### Scope: PKCS#12 unwrap only
 *
 * The only call site that names `BC-ai-sandbox-client` is the
 * `KeyStore.getInstance("PKCS12", "BC-ai-sandbox-client")` line in
 * [KeyStoreIdentityManager.importPkcs12]. Everything else (TLS, the
 * AndroidKeyStore-backed `X509KeyManager` for mTLS, SPKI pinning) is
 * unaffected and routes through Conscrypt / the AndroidKeyStore
 * provider as before.
 */
object BouncyCastleClientProvider {

    /** The provider name the rest of the codebase uses for PKCS#12 lookup. */
    const val NAME: String = "BC-ai-sandbox-client"

    private const val TAG = "BCClientProvider"

    /**
     * Idempotently register the real BouncyCastle under
     * [NAME]. Safe to call from [com.aisandbox.android.AiSandboxApplication.onCreate]
     * — repeat calls (e.g. from instrumented tests) short-circuit on
     * the second invocation.
     *
     * Returns `true` if registration happened on this call, `false` if
     * the provider was already present (which is the normal case for
     * every call after the first one in the process lifetime).
     */
    fun register(): Boolean {
        if (Security.getProvider(NAME) != null) {
            return false
        }
        // Anonymous subclass so we can override the provider's reported
        // name. The upstream `BouncyCastleProvider` hard-codes its
        // identifier as `"BC"`; we need it to report `BC-ai-sandbox-client`
        // so `Security.addProvider` indexes it under the distinct name
        // (and so the stock `"BC"` slot remains untouched). The provider
        // contents — supported algorithms, OID mappings, the
        // SecretKeyFactory for PBKDF2 OID 1.2.840.113549.1.5.12 — are
        // inherited unchanged from upstream BC.
        val provider = object : BouncyCastleProvider() {
            override fun getName(): String = NAME
        }
        // addProvider appends at the lowest priority — Conscrypt stays
        // the default for everything (especially TLS).
        val position = Security.addProvider(provider)
        if (position == -1) {
            // Should be impossible given the getProvider check above;
            // log defensively rather than throw, so a transient race
            // (e.g. concurrent test setup) doesn't crash the process.
            Log.w(TAG, "Provider $NAME already registered after race; continuing")
            return false
        }
        Log.i(TAG, "Registered $NAME at position $position (lowest priority)")
        return true
    }
}
