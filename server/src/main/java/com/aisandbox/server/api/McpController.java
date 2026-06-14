package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.mcp.facade.McpFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-67 — mTLS-gated, per-session MCP management surface. Lists the MCP servers
 * the session's embedded Claude Code knows ({@code claude mcp list}) and drives
 * the controls each needs to operate (login / reconnect / refresh). All real
 * work lives in {@link McpFacade}; this layer is HTTP mapping and DTO
 * conversion only (per {@code profile-java-server-architecture} — controllers
 * depend on facades, never services).
 *
 * <p>The inventory degrades gracefully: a non-running / SERVER_SSH session, or an
 * unparseable {@code claude mcp} surface, returns an empty list (200), which the
 * client renders as the "no MCP servers" empty state (AC7) rather than an error.
 */
@RestController
@RequestMapping("/v1/sessions/{n}/mcp")
public class McpController {

    private final McpFacade facade;

    public McpController(McpFacade facade) {
        this.facade = facade;
    }

    @Operation(
            summary = "List the MCP servers configured for a session.",
            description = "Returns the session's MCP servers and their current state, as known to the embedded "
                    + "Claude Code (claude mcp list). Degrades to an empty list for a non-running session or an "
                    + "unrecognised MCP surface — never a 4xx for those cases.")
    @ApiResponse(
            responseCode = "200",
            description = "The session's MCP servers (possibly empty).",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiDtos.McpServerSummary.class))))
    @GetMapping
    public List<ApiDtos.McpServerSummary> list(@PathVariable int n) {
        return facade.list(n).stream().map(ApiMappers::toMcpServerSummary).toList();
    }

    /**
     * UC-67 — drive a control action against one MCP server.
     *
     * <p>{@code POST /v1/sessions/{n}/mcp/{name}/{action}} where {@code action} is
     * one of {@code login|reconnect|refresh} (the path is regex-constrained, so
     * any other token 404s as an unmapped path — mirroring
     * {@code SessionController.lifecycle}). No request body.
     *
     * <p><b>login only INITIATES</b> the flow: it surfaces Claude Code's
     * interactive {@code /mcp} menu in the session's live main pane for the human
     * to complete; the server never completes OAuth headlessly. The response
     * carries the post-action state from a fresh inventory plus an honest message.
     */
    @Operation(
            summary = "Drive an MCP control action (login/reconnect/refresh) on one server.",
            description = "200 with the server's post-action state. login initiates the auth flow in the live "
                    + "session (it cannot complete OAuth headlessly); reconnect/refresh re-run the health check. "
                    + "An unknown server name yields state=unknown rather than a 404.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Action applied; carries the server's post-action state.",
                content = @Content(schema = @Schema(implementation = ApiDtos.McpActionResult.class))),
        @ApiResponse(
                responseCode = "500",
                description = "The underlying claude mcp / pane-injection call failed.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    })
    @PostMapping("/{name}/{action:login|reconnect|refresh}")
    public ResponseEntity<ApiDtos.McpActionResult> operate(
            @PathVariable int n,
            @PathVariable String name,
            @PathVariable String action,
            @RequestAttribute(name = ClientIdentityExtractor.ATTR, required = false) ClientIdentity identity)
            throws IOException {
        ClientIdentity id = identity == null ? ClientIdentity.ANONYMOUS : identity;
        return ResponseEntity.ok(ApiMappers.toMcpActionResult(facade.operate(n, name, action, id)));
    }
}
