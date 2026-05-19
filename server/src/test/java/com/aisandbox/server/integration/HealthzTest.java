package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.api.HealthController;
import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.health.facade.HealthFacade;
import com.aisandbox.server.health.service.HealthService;
import com.aisandbox.server.health.service.HealthService.HealthSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * AC19 — {@code GET /v1/healthz} returns 200 when docker / scripts / TLS
 * are all healthy; 503 + Problem-Details body otherwise. Driven directly
 * through the controller (a full Boot context for healthz alone would add
 * more than this test buys; the wider boot-startup checks live in the
 * DinD-gated integration tier).
 */
class HealthzTest {

    @Test
    void healthy_returns_200_with_dto() {
        HealthFacade facade = mock(HealthFacade.class);
        when(facade.health()).thenReturn(new HealthSnapshot(true, true, true, "ok"));

        ResponseEntity<?> resp = new HealthController(facade).healthz();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isInstanceOf(ApiDtos.HealthResponse.class);
        ApiDtos.HealthResponse body = (ApiDtos.HealthResponse) resp.getBody();
        assertThat(body.dockerOk()).isTrue();
        assertThat(body.scriptsOk()).isTrue();
        assertThat(body.tlsOk()).isTrue();
    }

    @Test
    void unhealthy_returns_503_with_problem_details() {
        HealthFacade facade = mock(HealthFacade.class);
        when(facade.health()).thenReturn(new HealthService.HealthSnapshot(false, true, true, "docker down"));

        ResponseEntity<?> resp = new HealthController(facade).healthz();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).isInstanceOf(org.springframework.http.ProblemDetail.class);
        var pd = (org.springframework.http.ProblemDetail) resp.getBody();
        assertThat(pd.getProperties()).containsEntry("code", "healthz_fail");
    }
}
