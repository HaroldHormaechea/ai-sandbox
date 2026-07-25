package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * UC-100 (AC8) — the hard-cut legacy signal. The three removed realtime
 * WebSocket paths are re-claimed as plain HTTP routes that answer
 * {@code 426 Upgrade Required} + {@code application/problem+json}
 * ({@code client_upgrade_required}) — a fast, explicit failure for an
 * old-client↔new-server mismatch, never a silent hang or a WS upgrade.
 */
class LegacyWebSocketGoneControllerTest {

    private final LegacyWebSocketGoneController controller = new LegacyWebSocketGoneController();

    @Test
    void legacy_stream_path_is_gone_with_426_and_upgrade_code() {
        assertUpgradeRequired(controller.legacyStream());
    }

    @Test
    void legacy_conversation_path_is_gone_with_426_and_upgrade_code() {
        assertUpgradeRequired(controller.legacyConversation());
    }

    @Test
    void legacy_events_path_is_gone_with_426_and_upgrade_code() {
        assertUpgradeRequired(controller.legacyEvents());
    }

    private static void assertUpgradeRequired(ResponseEntity<ProblemDetail> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UPGRADE_REQUIRED);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(426);
        assertThat(body.getProperties()).containsEntry("code", "client_upgrade_required");
        assertThat(body.getDetail()).contains("/v1/mux");
    }
}
