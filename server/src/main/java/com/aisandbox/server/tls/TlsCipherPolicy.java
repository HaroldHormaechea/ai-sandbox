package com.aisandbox.server.tls;

import java.util.List;

/**
 * Hard-coded TLS 1.3 cipher allowlist (AC10).
 *
 * <p>The set is intentionally explicit rather than JVM-default — we never
 * want a future JDK update to silently re-enable a deprecated suite. The
 * names below are JVM (JSSE) standard names, not OpenSSL aliases.
 */
public final class TlsCipherPolicy {

    private TlsCipherPolicy() {}

    public static final List<String> PROTOCOLS = List.of("TLSv1.3");

    public static final List<String> CIPHERS =
            List.of("TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256", "TLS_AES_128_GCM_SHA256");
}
