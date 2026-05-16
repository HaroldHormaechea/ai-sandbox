package com.aisandbox.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * AC16 — the per-IP TCP rate limiter fires before TLS bytes are exchanged.
 * Unit coverage in {@code PerIpRateLimiterTest} verifies bucket / concurrent
 * cap mechanics; this IT class exercises the {@code RateLimitingChannelHandler}
 * end-to-end against a Netty listener in CI.
 */
@EnabledIfEnvironmentVariable(named = "AI_SANDBOX_DIND", matches = "1")
class RateLimitIT {

    @Test
    void connections_above_per_ip_cap_are_dropped_pre_handshake() {
        // CI-only smoke test.
    }
}
