package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.mux.service.MuxProtocol;
import org.junit.jupiter.api.Test;

/**
 * UC-100 (AC8) — {@code GET /v1/capabilities}. A new client probes this before
 * connecting so a new-client↔old-server mismatch fails fast (an old server has
 * no such endpoint → 404, or reports a different {@code ws_protocol}). The
 * response must advertise the matched realtime protocol {@code mux.v1}.
 */
class CapabilitiesControllerTest {

    private final CapabilitiesController controller = new CapabilitiesController();

    @Test
    void reports_the_mux_ws_protocol() {
        ApiDtos.CapabilitiesResponse body = controller.capabilities();
        assertThat(body.wsProtocol()).isEqualTo("mux.v1");
        assertThat(body.wsProtocol()).isEqualTo(MuxProtocol.VERSION);
    }
}
