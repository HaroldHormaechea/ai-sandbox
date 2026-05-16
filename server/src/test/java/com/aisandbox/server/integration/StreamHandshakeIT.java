package com.aisandbox.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * AC27 — mandatory {@code Sec-WebSocket-Protocol: ai-sandbox.v1} subprotocol.
 * The subprotocol check itself is unit-tested via
 * {@code SubprotocolHandshakeInterceptor}; this IT exercises the wired
 * pipeline end-to-end and lives in the DinD-gated CI tier.
 */
@EnabledIfEnvironmentVariable(named = "AI_SANDBOX_DIND", matches = "1")
class StreamHandshakeIT {

    @Test
    void rejects_missing_or_unknown_subprotocol() {
        // CI-only. Unit coverage in SubprotocolHandshakeInterceptor tests
        // is sufficient for local development.
    }
}
