package com.aisandbox.server.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-100 (AC8) — the hard-cut legacy signal. The three removed realtime
 * WebSocket paths are re-claimed here as <b>plain HTTP routes</b> (NOT reactive
 * {@code WebSocketHandler}s — a reactive WS handler runs post-101 and can't emit
 * a pre-upgrade status). Any request — including an old client's
 * {@code Upgrade: websocket} handshake — is answered with
 * {@code 426 Upgrade Required} + {@code application/problem+json}
 * ({@code client_upgrade_required}), never an upgrade attempt. That makes an
 * old-client↔new-server mismatch a fast, explicit failure (okhttp {@code onFailure}
 * on the non-101 response) instead of a silent hang.
 *
 * <p>Since no {@code HandlerMapping} claims these paths for a WebSocket upgrade
 * anymore, the upgrade request falls through to this controller.
 */
@RestController
public class LegacyWebSocketGoneController {

    @RequestMapping("/v1/sessions/{n}/stream")
    public ResponseEntity<ProblemDetail> legacyStream() {
        return gone("the per-session terminal stream is now the 'stream' channel of the /v1/mux multiplex");
    }

    @RequestMapping("/v1/sessions/{n}/conversation")
    public ResponseEntity<ProblemDetail> legacyConversation() {
        return gone("the structured conversation is now the 'conversation' channel of the /v1/mux multiplex");
    }

    @RequestMapping("/v1/sessions/events")
    public ResponseEntity<ProblemDetail> legacyEvents() {
        return gone("the sessions-events feed is now the 'events' channel of the /v1/mux multiplex");
    }

    private ResponseEntity<ProblemDetail> gone(String detail) {
        return ResponseEntity.status(HttpStatus.UPGRADE_REQUIRED)
                .body(ProblemDetailsAdvice.build(
                        HttpStatus.UPGRADE_REQUIRED,
                        ErrorCode.CLIENT_UPGRADE_REQUIRED,
                        "This endpoint was removed; upgrade the client and server together. " + detail + "."));
    }
}
