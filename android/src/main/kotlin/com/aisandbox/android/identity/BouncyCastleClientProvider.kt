package com.aisandbox.android.identity

import android.util.Log
import java.security.Provider
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * UC13 — Registers the **real** BouncyCastle JCE provider under a
 * project-specific name so the Android client can unwrap modern
 * PBES2 PKCS#12 bundles emitted by `server-v0.0.12+`.
 *
 * ### Why this exists at all
 *
 * The server uses the JDK 21 default behaviour for the **single-argument**
 * form of `KeyStore.getInstance(...)` with type `PKCS12`, which since
 * Java 14+ emits **PKCS#12 v3 / PBES2** — i.e. the
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

    // Version reported by the wrapped Provider — kept in sync with the
    // `bouncycastle` pin in `gradle/libs.versions.toml`. Double rather
    // than String because the Provider(String, double, String) ctor is
    // the one resolvable under current compileSdk; see
    // `NamedBouncyCastleProvider` below.
    private const val BC_VERSION: Double = 1.79

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
        // Wrap upstream BC in a `java.security.Provider` subclass so we
        // can publish it under our distinct name. We can NOT subclass
        // `BouncyCastleProvider` directly: as of `bcprov-jdk18on:1.79`
        // the class is `final`, so the anonymous-subclass-overrides-
        // `getName()` trick stopped compiling. `java.security.Provider`
        // itself is NOT final, so we extend it instead and copy
        // upstream BC's algorithm registrations — supported algorithms,
        // OID mappings, the SecretKeyFactory for PBKDF2 OID
        // 1.2.840.113549.1.5.12 — into our instance via the entries
        // map. Functionally equivalent to using BC under a renamed
        // identifier; the JCA indexes our instance under
        // `BC-ai-sandbox-client` and the stock Android `"BC"` slot is
        // left untouched.
        val provider: Provider = NamedBouncyCastleProvider()
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

    /**
     * Private `java.security.Provider` subclass that republishes the
     * algorithm registrations of a fresh [BouncyCastleProvider]
     * instance under our distinct [NAME].
     *
     * We can NOT subclass [BouncyCastleProvider] directly because it is
     * `final` in `bcprov-jdk18on:1.79`. [Provider] itself is not final,
     * so we extend it, then copy every entry of a transient
     * [BouncyCastleProvider] into this instance via `put`. The
     * `Provider.id *` entries (`Provider.id name`, `Provider.id
     * version`, `Provider.id info`) are skipped — the base-class
     * constructor has already set the right ones for our distinct
     * name + version + info combination, and re-putting BC's would
     * stomp them.
     */
    // Provider(String name, double version, String info) — the legacy
    // protected ctor. The newer (String, String, String) overload added in
    // JDK 9 / Android API 28 is not resolved by the Kotlin compiler under
    // our current compileSdk / Android stubs combo (the stub apparently
    // only exposes the double-version form), so we use the double form
    // explicitly. The version literal is the BC pin (`bouncycastle =
    // "1.79"` in `gradle/libs.versions.toml`) carried verbatim into the
    // provider's reported version property.
    private class NamedBouncyCastleProvider : Provider(
        NAME,
        BC_VERSION,
        "ai-sandbox repackaging of BouncyCastle (bcprov-jdk18on) for PKCS#12 enrollment-cert import — see BouncyCastleClientProvider KDoc",
    ) {
        init {
            val src = BouncyCastleProvider()
            for ((k, v) in src.entries) {
                val ks = k.toString()
                if (!ks.startsWith("Provider.id ")) {
                    put(k, v)
                }
            }
        }
    }
}
