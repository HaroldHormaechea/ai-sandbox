package com.aisandbox.server.tls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * AC10 — TLS 1.3 only and the cipher allowlist is explicit (not JVM-default).
 *
 * <p>This test fixates the policy: it locks the protocol set to exactly
 * {@code TLSv1.3} and the cipher list to the three approved suites. Anyone
 * loosening the policy (or relying on the JVM default) MUST also update
 * this test, which makes the change auditable.
 */
class TlsCipherPolicyTest {

    @Test
    void protocols_isOnly_tls13() {
        assertThat(TlsCipherPolicy.PROTOCOLS).containsExactly("TLSv1.3");
    }

    @Test
    void ciphers_match_AC10_allowlist_exactly_and_in_order() {
        assertThat(TlsCipherPolicy.CIPHERS)
                .containsExactly("TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256", "TLS_AES_128_GCM_SHA256");
    }

    @Test
    void ciphers_does_not_include_legacy_or_tls12_suites() {
        // Sanity guard against accidentally re-adding a CBC / SHA1 / RC4 suite.
        assertThat(TlsCipherPolicy.CIPHERS).allSatisfy(s -> {
            assertThat(s).startsWith("TLS_");
            assertThat(s).doesNotContain("_CBC_");
            assertThat(s).doesNotContain("_SHA1");
            assertThat(s).doesNotContain("_RC4");
            assertThat(s).doesNotContain("_RSA_");
        });
    }
}
