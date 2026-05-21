package com.aisandbox.server.enrollment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.enrollment.dto.MintedBundle;
import java.io.ByteArrayInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Map;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * UC14 follow-up — closes the testing gap exposed by the on-device
 * "PKCS12 key store mac invalid" regression that hit `android-v0.3.1`.
 *
 * <p>Pre-existing tests (UC13's {@code KeyStoreIdentityManagerPkcs12ImportTest}
 * + this module's {@code EnrollmentCertMintServiceTest}) verify the
 * round-trip in halves: one side mints a PKCS#12 with the JDK default
 * emitter in the test JVM and parses it back; the other side asserts
 * the server's bundle has the right inner shape via JDK's KeyStore.
 * Neither test asserts that the bytes produced by the actual server
 * code path ({@link EnrollmentCertMintService#mint(String)}) can be
 * unwrapped by the actual Android-side code path
 * ({@code KeyStoreIdentityManager.importPkcs12} — which is
 * {@code KeyStore.getInstance("PKCS12", "BC-ai-sandbox-client")} +
 * {@code load(..., ENROLLMENT_PKCS12_PASSPHRASE)}).
 *
 * <p>This test fills that gap on the server module's classpath
 * (BouncyCastle is already a server-side production dependency for
 * cert generation, so registering the wrapped provider here is free)
 * by reproducing the Android production code path's provider
 * registration + load mechanics 1:1.
 *
 * <p>Failure modes this catches (any one would have prevented the
 * `android-v0.3.1` field regression):
 *
 * <ul>
 *   <li>Server emits with a passphrase that doesn't match what Android
 *       passes to {@code load}.</li>
 *   <li>Server emits with an algorithm or parameter that BC 1.79's
 *       PBKDF2 SecretKeyFactory rejects (e.g., empty char[] regression
 *       from UC13).</li>
 *   <li>BC's MAC verification disagrees with the server emit's MAC for
 *       the same passphrase (would indicate an encoding mismatch
 *       between the two providers' MAC code paths).</li>
 *   <li>Drift between the {@link EnrollmentCertMintService#ENROLLMENT_PKCS12_PASSPHRASE}
 *       constant and the value the Android client hard-codes — both
 *       sides agree on the literal {@code "ai-sandbox-enrollment"}
 *       today; this test pins both halves.</li>
 * </ul>
 */
class EnrollmentMintRoundTripsThroughAndroidProductionPathTest {

    /**
     * Name the Android client uses when it registers the wrapped BC
     * provider — see
     * {@code android/src/main/kotlin/com/aisandbox/android/identity/BouncyCastleClientProvider.kt}.
     */
    private static final String ANDROID_BC_PROVIDER_NAME = "BC-ai-sandbox-client";

    /**
     * String literal the Android client uses as the transport
     * passphrase — see
     * {@code android/src/main/kotlin/com/aisandbox/android/identity/KeyStoreIdentityManager.kt}.
     * Pinned here as a literal (not a reference to the server-side
     * constant) so a drift between the two sides would cause this
     * test to fail.
     */
    private static final char[] ANDROID_TRANSPORT_PASSPHRASE = "ai-sandbox-enrollment".toCharArray();

    @BeforeAll
    static void registerWrappedBouncyCastleClientProvider() {
        if (Security.getProvider(ANDROID_BC_PROVIDER_NAME) == null) {
            Security.addProvider(new NamedBouncyCastleProvider());
        }
    }

    @AfterAll
    static void cleanup() {
        Security.removeProvider(ANDROID_BC_PROVIDER_NAME);
    }

    @Test
    void server_minted_bundle_is_loadable_by_the_android_production_code_path() throws Exception {
        // 1. Mint via the REAL server code path. This is the
        //    EnrollmentCertMintService that Spring autowires into
        //    EnrollmentFacade in production — same code, same JCA
        //    calls, same JDK 21 default PKCS#12 emit shape.
        EnrollmentCertMintService svc = new EnrollmentCertMintService();
        MintedBundle bundle = svc.mint("test-uc14-roundtrip");

        // 2. Load via a code path that mirrors the Android production
        //    KeyStoreIdentityManager.importPkcs12 line-for-line:
        //      KeyStore.getInstance("PKCS12", "BC-ai-sandbox-client")
        //      ks.load(bytes, ENROLLMENT_PKCS12_PASSPHRASE)
        //      ks.getKey(alias, ENROLLMENT_PKCS12_PASSPHRASE)
        KeyStore ks = KeyStore.getInstance("PKCS12", ANDROID_BC_PROVIDER_NAME);
        ks.load(new ByteArrayInputStream(bundle.pkcs12()), ANDROID_TRANSPORT_PASSPHRASE);

        // 3. Sanity-check the alias and round-trip key + cert bytes.
        var aliases = java.util.Collections.list(ks.aliases());
        assertThat(aliases)
                .as("Server-minted PKCS#12 must expose its alias to the wrapped BC PKCS12 unwrap path")
                .hasSize(1)
                .containsExactly("test-uc14-roundtrip");

        String alias = aliases.get(0);
        Key key = ks.getKey(alias, ANDROID_TRANSPORT_PASSPHRASE);
        assertThat(key)
                .as("Wrapped BC must unwrap the private key under the agreed sentinel passphrase")
                .isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");

        java.security.cert.Certificate cert = ks.getCertificate(alias);
        assertThat(cert)
                .as("Wrapped BC must expose the leaf cert under the bundle alias")
                .isInstanceOf(X509Certificate.class);
        X509Certificate x509 = (X509Certificate) cert;
        assertThat(x509.getSubjectX500Principal().getName()).contains("CN=test-uc14-roundtrip");
    }

    @Test
    void server_constant_matches_android_hardcoded_literal() {
        // Pin the contract — both sides agree on the literal. If a
        // future edit changes one but not the other, this test fails
        // with a crisp signal pointing at the drift.
        assertThat(EnrollmentCertMintService.ENROLLMENT_PKCS12_PASSPHRASE)
                .as("Server-side ENROLLMENT_PKCS12_PASSPHRASE must equal the Android-side hard-coded "
                        + "value 'ai-sandbox-enrollment' (UC14 contract); drift between the two means the "
                        + "phone cannot import the server's enrollment bundle.")
                .isEqualTo("ai-sandbox-enrollment");
    }

    /**
     * Reproduces the Android client's
     * {@code BouncyCastleClientProvider.NamedBouncyCastleProvider} 1:1
     * — wraps an upstream {@link BouncyCastleProvider}'s algorithm
     * registrations under a distinct project-specific name. The
     * Android version exists because {@code BouncyCastleProvider} is
     * {@code final} in {@code bcprov-jdk18on:1.79}; the server-side
     * production code never wraps BC like this (it just uses the
     * stock BC for cert generation), so we re-do the wrapping in this
     * test class to exactly mirror what the Android client does at
     * runtime.
     */
    private static class NamedBouncyCastleProvider extends Provider {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        NamedBouncyCastleProvider() {
            super(
                    ANDROID_BC_PROVIDER_NAME,
                    1.79,
                    "ai-sandbox repackaging of BouncyCastle (bcprov-jdk18on) for "
                            + "PKCS#12 enrollment-cert import — UC14 test mirror");
            BouncyCastleProvider src = new BouncyCastleProvider();
            for (Map.Entry<Object, Object> e : src.entrySet()) {
                String k = e.getKey().toString();
                if (!k.startsWith("Provider.id ")) {
                    put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
