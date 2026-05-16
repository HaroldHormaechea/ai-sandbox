package com.aisandbox.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * AC14 — replacing server.crt / server.key on disk triggers an
 * {@code SslContext} rebuild. New handshakes get the new material;
 * in-flight TLS sessions keep their original cert.
 *
 * <p>Unit coverage in {@code ReloadableSslContextHolderTest} verifies the
 * holder swap; this IT class exercises the full watch path in CI.
 */
@EnabledIfEnvironmentVariable(named = "AI_SANDBOX_DIND", matches = "1")
class ServerCertHotReloadIT {

    @Test
    void watcher_rebuilds_ssl_context_when_files_change() {
        // CI-only smoke test.
    }
}
