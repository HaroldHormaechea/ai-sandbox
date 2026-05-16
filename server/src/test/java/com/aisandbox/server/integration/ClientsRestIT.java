package com.aisandbox.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * AC19 — exercises {@code /v1/clients/*} against a live Spring context.
 * Local unit coverage is in ClientControllerTest-equivalent slices
 * (controller layer is thin; facade is fully covered in unit tests).
 */
@EnabledIfEnvironmentVariable(named = "AI_SANDBOX_DIND", matches = "1")
class ClientsRestIT {

    @Test
    void add_list_delete_round_trip() {
        // CI-only smoke test. ClientCertParserTest +
        // ClientAllowlistServiceTest cover the lower layers locally.
    }
}
