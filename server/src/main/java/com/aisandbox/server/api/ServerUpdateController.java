package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.serverupdate.facade.ServerUpdateFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-84 — server self-update endpoints under {@code /v1/server/update}.
 *
 * <p>mTLS is enforced for the whole path automatically: {@code
 * MtlsEnforcementFilter} exempts ONLY {@code POST /v1/enrollment}, so both
 * routes here reject unauthenticated/non-enrolled callers with {@code 401
 * mtls_required} (AC10) — this controller adds no exemption.
 *
 * <p>Thin HTTP-mapping layer (profile-java-server-architecture rule 6):
 * facade-only, mapping internal results to {@code api.dto} types via
 * {@link ApiMappers}. All failure mapping to {@code application/problem+json}
 * lives in {@code ProblemDetailsAdvice}.
 */
@RestController
@RequestMapping("/v1/server/update")
public class ServerUpdateController {

    private final ServerUpdateFacade facade;

    public ServerUpdateController(ServerUpdateFacade facade) {
        this.facade = facade;
    }

    @Operation(
            summary = "Check GitHub for a newer server-v* release.",
            description = "Unauthenticated (no GitHub credentials) lookup of the newest server-v* release, compared "
                    + "with the running version by semantic-version ordering. Degrades to updateAvailable=false "
                    + "outside a packaged jar.")
    @ApiResponse(
            responseCode = "200",
            description = "Current + latest version, updateAvailable flag, changelog + .deb URLs.",
            content = @Content(schema = @Schema(implementation = ApiDtos.UpdateCheckResponse.class)))
    @GetMapping("/check")
    public ResponseEntity<?> check() {
        return ResponseEntity.ok(ApiMappers.toUpdateCheckResponse(facade.check()));
    }

    @Operation(
            summary = "Trigger a self-update to the latest server-v* release.",
            description = "Emits a parameter-free trigger consumed by the independent root ai-sandbox-updater unit, "
                    + "which self-determines the latest server-v* target, installs the .deb, and restarts the "
                    + "service. Returns promptly without blocking on the restart. The server passes the updater no "
                    + "version, path, or arguments.")
    @ApiResponse(
            responseCode = "200",
            description = "The update trigger was emitted (accepted=true).",
            content = @Content(schema = @Schema(implementation = ApiDtos.UpdateApplyResponse.class)))
    @PostMapping("/apply")
    public ResponseEntity<?> apply() {
        return ResponseEntity.ok(ApiMappers.toUpdateApplyResponse(facade.apply()));
    }
}
