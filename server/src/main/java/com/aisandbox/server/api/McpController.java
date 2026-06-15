package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.mcp.McpRegistrationException;
import com.aisandbox.server.mcp.McpServerExistsException;
import com.aisandbox.server.mcp.McpServerNotFoundException;
import com.aisandbox.server.mcp.facade.McpFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
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
     * UC-82 — register a new MCP server for the session (AC1).
     *
     * <p>{@code POST /v1/sessions/{n}/mcp} with an {@link ApiDtos.McpAddRequest} body.
     * 201 on success (carrying the new server's post-add state); 400 for malformed /
     * missing fields (AC6); 409 when the name already exists (AC6 — no silent
     * overwrite); 500 if the underlying {@code claude mcp add} fails. The body's values
     * (command/url/args/env/headers) reach process execution as discrete argv elements
     * only — never a shell string (AC4) — and only a {@code claude mcp} config
     * subcommand can ever run (AC5).
     */
    @Operation(
            summary = "Register a new MCP server for a session.",
            description = "Adds an MCP server (stdio command or http/sse URL) to the session's embedded Claude Code "
                    + "via an argv-only `claude mcp add`. 201 with the server's post-add state; 400 malformed; "
                    + "409 duplicate name; 500 on add failure.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "The server was registered; carries its post-add state.",
                content = @Content(schema = @Schema(implementation = ApiDtos.McpActionResult.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Malformed or missing required fields.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "409",
                description = "A server with that name is already configured.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "500",
                description = "The underlying claude mcp add call failed.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    })
    @PostMapping
    public ResponseEntity<?> add(@PathVariable int n, @RequestBody(required = false) ApiDtos.McpAddRequest req) {
        try {
            // Inline the internal McpActionOutcome through the mapper so the controller
            // never names an mcp.dto type (LayeringTest pins api → mcp.dto out).
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiMappers.toMcpActionResult(facade.add(n, ApiMappers.toMcpAddSpec(req))));
        } catch (McpServerExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ProblemDetailsAdvice.build(HttpStatus.CONFLICT, ErrorCode.MCP_SERVER_EXISTS, e.getMessage()));
        } catch (McpRegistrationException e) {
            return ResponseEntity.internalServerError()
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.MCP_ADD_FAILED, e.getMessage()));
        }
        // IllegalArgumentException (validation) propagates to ProblemDetailsAdvice → 400.
    }

    /**
     * UC-82 — deregister an MCP server from the session (AC2).
     *
     * <p>{@code DELETE /v1/sessions/{n}/mcp/{name}} — the server name is a single path
     * segment (URL-decoded by Spring). 200 with an honest message (AC2: deregister +
     * reconcile-on-next-reload; an already-running child is not force-killed); 404 when
     * the session has no such server; 500 if {@code claude mcp remove} fails.
     */
    @Operation(
            summary = "Deregister an MCP server from a session.",
            description = "Removes the named MCP server via an argv-only `claude mcp remove`. 200 with an honest note "
                    + "(deregister + reconcile on next reload; a running child is not force-killed); 404 unknown "
                    + "server; 500 on remove failure.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The server was deregistered.",
                content = @Content(schema = @Schema(implementation = ApiDtos.McpActionResult.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No MCP server with that name in the session.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "500",
                description = "The underlying claude mcp remove call failed.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    })
    @DeleteMapping("/{name}")
    public ResponseEntity<?> remove(@PathVariable int n, @PathVariable String name) {
        try {
            return ResponseEntity.ok(ApiMappers.toMcpActionResult(facade.remove(n, name)));
        } catch (McpServerNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.NOT_FOUND, ErrorCode.MCP_SERVER_NOT_FOUND, e.getMessage()));
        } catch (McpRegistrationException e) {
            return ResponseEntity.internalServerError()
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.MCP_REMOVE_FAILED, e.getMessage()));
        }
        // IllegalArgumentException (validation) propagates to ProblemDetailsAdvice → 400.
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
