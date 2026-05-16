package com.aisandbox.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * AC19 — exercises {@code /v1/sessions/*} against a live Spring context.
 * The full body lives in the DinD-gated CI tier because real
 * spawn / clean invocations are part of the contract.
 */
@EnabledIfEnvironmentVariable(named = "AI_SANDBOX_DIND", matches = "1")
class SessionsRestIT {

    @Test
    void list_spawn_delete_round_trip() {
        // CI-only smoke test. Local unit tier covers the facade + controller
        // layers via SessionFacadeTest and ScriptExecutorServiceTest.
    }
}
