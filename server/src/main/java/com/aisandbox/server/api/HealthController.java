package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.health.facade.HealthFacade;
import com.aisandbox.server.health.service.HealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * mTLS-gated health endpoint (AC19). 200 only when Docker, scripts and
 * TLS are all healthy; 503 + Problem-Details otherwise.
 */
@RestController
@RequestMapping("/v1")
public class HealthController {

    private final HealthFacade facade;

    public HealthController(HealthFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/healthz")
    public ResponseEntity<?> healthz() {
        HealthService.HealthSnapshot snap = facade.health();
        if (snap.healthy()) {
            return ResponseEntity.ok(new ApiDtos.HealthResponse(snap.dockerOk(), snap.scriptsOk(), snap.tlsOk()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetailsAdvice.build(
                        HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.HEALTHZ_FAIL, snap.detail()));
    }
}
