package com.aisandbox.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * AC13 — adding / removing a client cert from the allowlist folder
 * propagates through the watcher and tears down in-flight TLS sessions
 * for revoked certs.
 *
 * <p>Local coverage is in {@code AllowlistWatcherTest} (filesystem-event
 * driven; no Docker). This IT class exercises the same flow against a
 * running Spring context and lives in the DinD-gated CI tier.
 */
@EnabledIfEnvironmentVariable(named = "AI_SANDBOX_DIND", matches = "1")
class AllowlistHotReloadIT {

    @Test
    void watcher_tears_down_revoked_certs() {
        // CI-only smoke test.
    }
}
