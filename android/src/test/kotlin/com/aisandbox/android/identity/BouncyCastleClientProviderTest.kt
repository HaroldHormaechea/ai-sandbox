package com.aisandbox.android.identity

import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC13 — unit tests for [BouncyCastleClientProvider]. Covers AC3
 * (distinct provider-name registration), AC4 (Conscrypt / default TLS
 * provider invariance), AC5 (SPKI-pinning code path's
 * crypto-provider call site untouched), and the idempotency contract
 * the singleton documents.
 *
 * <h2>Why this is a pure-JVM JUnit-5 test (no Robolectric)</h2>
 *
 * <p>The class under test only talks to {@code java.security.*} — no
 * Android-specific APIs. Routing through Robolectric would add startup
 * cost and a custom ClassLoader for no behavioural gain. The
 * companion {@link AiSandboxApplicationBootstrapTest} covers the
 * Android-lifecycle assertion (provider is registered before any
 * KeyStore consumer can run).
 *
 * <h2>TLS-provider invariance — JVM vs. Android note</h2>
 *
 * <p>AC4's production claim is "Conscrypt stays the TLS provider". On
 * Android, the default {@code SSLContext.getInstance("TLS")} provider
 * is Conscrypt; on the JVM (where this test runs), it is SunJSSE. The
 * test asserts the actual portable invariant —
 * {@code BouncyCastleClientProvider.register()} MUST NOT displace
 * whatever provider was answering {@code TLS} before — by snapshotting
 * the provider reference before and after registration and comparing
 * them. That same machinery (lowest-priority {@code addProvider})
 * leaves Conscrypt in place on the device.
 *
 * <p>The not-BC sanity belt below pins the claim a step further: after
 * registration, the TLS provider's name must not be
 * {@link BouncyCastleClientProvider#NAME} — i.e. {@code register()}
 * has not silently won the TLS arbitration race.
 */
class BouncyCastleClientProviderTest {

    /**
     * Pre-test cleanup so each method exercises the
     * {@link BouncyCastleClientProvider#register} machinery from a
     * clean slate. Other tests in the suite (and the Robolectric
     * application bootstrap) may have already registered the provider
     * inside this process — JUnit's default static-VM lifecycle means
     * {@code java.security.Security} survives across test classes.
     */
    @BeforeEach
    fun removeBcClientProvider() {
        Security.removeProvider(BouncyCastleClientProvider.NAME)
    }

    /**
     * Belt-and-braces — leave the JCA state the way we found it so
     * test-class ordering inside the JVM doesn't create coupling.
     */
    @AfterEach
    fun cleanup() {
        Security.removeProvider(BouncyCastleClientProvider.NAME)
    }

    // ── AC3 — provider registered under the distinct project-specific name ──

    @Test
    fun `register installs provider under the BC-ai-sandbox-client name`() {
        // Pre-condition: clean slate after @BeforeEach.
        assertThat(Security.getProvider(BouncyCastleClientProvider.NAME)).isNull()

        val didRegister = BouncyCastleClientProvider.register()

        assertThat(didRegister).isTrue()
        val provider = Security.getProvider(BouncyCastleClientProvider.NAME)
        assertThat(provider)
            .describedAs(
                "Security.getProvider(\"BC-ai-sandbox-client\") should return the registered provider",
            )
            .isNotNull()
        // The JCA tables index this provider under the distinct
        // project-specific name (and the stock Android "BC" slot is
        // left untouched). We do NOT assert on the concrete class —
        // `BouncyCastleProvider` is final in bcprov-jdk18on:1.79 so the
        // production code wraps it in a `java.security.Provider`
        // subclass that copies BC's algorithm registrations. The
        // functional contract is what matters, asserted below.
        assertThat(provider!!.name).isEqualTo("BC-ai-sandbox-client")

        // AC3 functional contract — the registered provider MUST expose
        // a SecretKeyFactory for the bare PBKDF2 OID
        // 1.2.840.113549.1.5.12. That is the JCA lookup the stock
        // Android "BC" provider misses, and the entire reason this
        // provider exists. Both the OID form and the named-string form
        // ("PBKDF2WithHmacSHA256") are offered by upstream BC.
        assertThat(provider.getService("SecretKeyFactory", "1.2.840.113549.1.5.12"))
            .describedAs(
                "Registered provider must expose SecretKeyFactory for the PBKDF2 OID " +
                    "1.2.840.113549.1.5.12 — the lookup PKCS#12 v3 / PBES2 unwrap performs.",
            )
            .isNotNull()
        assertThat(provider.getService("SecretKeyFactory", "PBKDF2WithHmacSHA256"))
            .describedAs(
                "Registered provider must also expose SecretKeyFactory under the named-string " +
                    "form PBKDF2WithHmacSHA256 (BC indexes the algorithm under both keys).",
            )
            .isNotNull()
    }

    @Test
    fun `register is idempotent — repeat calls do not duplicate the provider`() {
        BouncyCastleClientProvider.register()
        val secondCall = BouncyCastleClientProvider.register()
        val thirdCall = BouncyCastleClientProvider.register()

        assertThat(secondCall)
            .describedAs("Second register() must return false (already-present short-circuit)")
            .isFalse()
        assertThat(thirdCall).isFalse()

        val matching = Security.getProviders().count { it.name == BouncyCastleClientProvider.NAME }
        assertThat(matching)
            .describedAs("Exactly one provider must be indexed under \"BC-ai-sandbox-client\"")
            .isEqualTo(1)
    }

    // ── AC4 — Conscrypt / default TLS provider invariance ────────────────

    @Test
    fun `register does not displace the default TLS provider`() {
        // Snapshot the TLS provider BEFORE BC is added. On Android this
        // is Conscrypt; on the JVM (where this test runs) it is SunJSSE.
        // The invariance claim is independent of which one it is.
        val tlsProviderBefore: Provider = SSLContext.getInstance("TLS").provider
        val tlsProviderBeforeName = tlsProviderBefore.name
        val tlsProviderBeforeVersion = tlsProviderBefore.versionStr

        BouncyCastleClientProvider.register()

        val tlsProviderAfter: Provider = SSLContext.getInstance("TLS").provider

        // Reference-identity is the strongest assertion (same JVM
        // singleton came back). If the runtime returns a different
        // Provider instance on each call we fall back to name+version
        // equality, which still proves nothing was displaced.
        assertThat(tlsProviderAfter === tlsProviderBefore || (
            tlsProviderAfter.name == tlsProviderBeforeName &&
                tlsProviderAfter.versionStr == tlsProviderBeforeVersion
        ))
            .describedAs(
                "TLS provider must be unchanged after register(); " +
                    "before=$tlsProviderBeforeName/$tlsProviderBeforeVersion, " +
                    "after=${tlsProviderAfter.name}/${tlsProviderAfter.versionStr}",
            )
            .isTrue()

        // Not-BC sanity belt — even if some future runtime change makes
        // the before/after comparison degenerate, the post-register TLS
        // provider must not be the one we just added.
        assertThat(tlsProviderAfter.name)
            .describedAs("BC-ai-sandbox-client must NEVER win the default TLS arbitration")
            .isNotEqualTo(BouncyCastleClientProvider.NAME)
    }

    @Test
    fun `register does not displace the default KeyManagerFactory provider`() {
        // The mTLS handshake path goes through KeyManagerFactory; AC4's
        // intent extends to this provider just as much as to SSLContext.
        val kmfAlg = KeyManagerFactory.getDefaultAlgorithm()
        val kmfProviderBefore = KeyManagerFactory.getInstance(kmfAlg).provider
        val beforeName = kmfProviderBefore.name

        BouncyCastleClientProvider.register()

        val kmfProviderAfter = KeyManagerFactory.getInstance(kmfAlg).provider
        assertThat(kmfProviderAfter.name)
            .describedAs("KeyManagerFactory($kmfAlg) provider must not be BC after register()")
            .isNotEqualTo(BouncyCastleClientProvider.NAME)
        assertThat(kmfProviderAfter.name)
            .describedAs("KeyManagerFactory($kmfAlg) provider name must be unchanged")
            .isEqualTo(beforeName)
    }

    // ── AC5 — SPKI-pinning code path's crypto-provider call site untouched ─

    @Test
    fun `register does not displace the default SHA-256 MessageDigest provider`() {
        // SpkiPinningTrustManager calls MessageDigest.getInstance("SHA-256")
        // to compute the SPKI digest of each presented leaf cert. AC5
        // requires the BC integration not to change that code path's
        // provider — both because Conscrypt's SHA-256 is hardware-
        // accelerated on Android and because UC09/UC10 already pinned
        // the trust-manager semantics.
        val digestProviderBefore = MessageDigest.getInstance("SHA-256").provider
        val beforeName = digestProviderBefore.name

        BouncyCastleClientProvider.register()

        val digestProviderAfter = MessageDigest.getInstance("SHA-256").provider
        assertThat(digestProviderAfter.name)
            .describedAs("MessageDigest(SHA-256) provider must not switch to BC after register()")
            .isNotEqualTo(BouncyCastleClientProvider.NAME)
        assertThat(digestProviderAfter.name)
            .describedAs("MessageDigest(SHA-256) provider name must be unchanged")
            .isEqualTo(beforeName)
    }
}
