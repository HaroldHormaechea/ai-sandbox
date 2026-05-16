package com.aisandbox.server.health.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.health.service.HealthService;
import org.springframework.stereotype.Component;

/**
 * Use-case-level entry point for {@code GET /v1/healthz}. Emits a
 * {@code HEALTHZ_FAIL} audit line whenever the probe is unhealthy (AC42).
 */
@Component
public class HealthFacade {

    private final HealthService service;
    private final AuditLogger audit;

    public HealthFacade(HealthService service, AuditLogger audit) {
        this.service = service;
        this.audit = audit;
    }

    public HealthService.HealthSnapshot health() {
        HealthService.HealthSnapshot snap = service.probe();
        if (!snap.healthy()) {
            audit.logEvent(
                    AuditAction.HEALTHZ_FAIL,
                    "fail",
                    "docker",
                    snap.dockerOk(),
                    "scripts",
                    snap.scriptsOk(),
                    "tls",
                    snap.tlsOk());
        }
        return snap;
    }
}
