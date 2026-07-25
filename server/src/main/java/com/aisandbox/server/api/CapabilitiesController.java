package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.mux.service.MuxProtocol;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-100 — {@code GET /v1/capabilities}. A new client hits this before opening
 * the {@code /v1/mux} WebSocket so a new-client↔old-server mismatch fails fast:
 * an old server has no such endpoint (404) or reports a different
 * {@code ws_protocol}. mTLS-gated like every other {@code /v1} route.
 */
@RestController
@RequestMapping("/v1")
public class CapabilitiesController {

    @GetMapping("/capabilities")
    public ApiDtos.CapabilitiesResponse capabilities() {
        return new ApiDtos.CapabilitiesResponse(MuxProtocol.VERSION);
    }
}
