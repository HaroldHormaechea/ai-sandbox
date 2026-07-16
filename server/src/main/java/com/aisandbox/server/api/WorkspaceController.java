package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.workspace.facade.WorkspaceProjectFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-98 — mTLS-gated catalogue of selectable workspace projects (the top-level
 * folders under the server's shared workspace root) the client offers in the
 * "New session" drop-down. Global (not per-session): the same shared workspace
 * root applies regardless of the spawning session's workspace mode (AC7). Calls
 * the {@link WorkspaceProjectFacade} only — never a service or the properties
 * directly (per {@code profile-java-server-architecture} rule 6). Mirrors
 * {@link ModelController}; mTLS is enforced automatically like every other
 * {@code /v1/*} endpoint.
 */
@RestController
@RequestMapping("/v1/workspace")
public class WorkspaceController {

    private final WorkspaceProjectFacade facade;

    public WorkspaceController(WorkspaceProjectFacade facade) {
        this.facade = facade;
    }

    @Operation(summary = "List the selectable workspace projects (top-level folders under the shared workspace root).")
    @ApiResponse(
            responseCode = "200",
            description = "All selectable projects (may be empty when the workspace root is absent or has no folders).",
            content =
                    @Content(
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = ApiDtos.WorkspaceProjectSummary.class))))
    @GetMapping("/projects")
    public List<ApiDtos.WorkspaceProjectSummary> listProjects() {
        return facade.listProjects().stream()
                .map(ApiMappers::toWorkspaceProjectSummary)
                .toList();
    }
}
