package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.facade.SessionFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for session lifecycle (AC19 → /v1/sessions/*).
 *
 * <p>All real work lives in {@link SessionFacade}; this layer is HTTP
 * mapping, request validation, and DTO conversion.
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private final SessionFacade facade;

    public SessionController(SessionFacade facade) {
        this.facade = facade;
    }

    @Operation(summary = "List all ai-sandbox sessions.")
    @ApiResponse(
            responseCode = "200",
            description = "All enumerated sessions (running, starting, provisioning, terminating, stopped).",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiDtos.SessionSummary.class))))
    @GetMapping
    public ResponseEntity<?> list() throws IOException {
        return ResponseEntity.ok(ApiMappers.toSummaries(facade.listSessions()));
    }

    /**
     * UC-62 — create (or focus) the singleton server host-shell session.
     *
     * <p>Idempotent create-or-focus: the singleton is enforced server-side
     * ({@link SessionFacade#createServerSsh()}), so a second tap of the Android
     * shell icon returns the same row (200) rather than creating a second
     * SERVER SSH SESSION (AC2, AC13). Returns the row as a normal
     * {@link ApiDtos.SessionSummary} (it carries {@code type=server-ssh} and the
     * reserved id), so it then flows through the existing list / stream / delete
     * plumbing.
     */
    @Operation(
            summary = "Create (or focus) the singleton server host-shell session.",
            description = "Opens a tmux login shell on the management-server host (NOT a sandbox container) and "
                    + "returns its pinned row. Idempotent: a second call focuses the existing row rather than "
                    + "creating a second one (server-enforced singleton).")
    @ApiResponse(
            responseCode = "200",
            description = "The server host-shell row (type=server-ssh, reserved id).",
            content = @Content(schema = @Schema(implementation = ApiDtos.SessionSummary.class)))
    @PostMapping("/server-ssh")
    public ResponseEntity<?> createServerSsh() throws IOException {
        return ResponseEntity.ok(ApiMappers.toSummary(facade.createServerSsh()));
    }

    @PostMapping
    public ResponseEntity<?> spawn(@RequestBody(required = false) ApiDtos.SpawnRequest req)
            throws IOException, InterruptedException {
        SpawnCommand cmd;
        try {
            cmd = ApiMappers.toSpawnCommand(req);
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetailsAdvice.build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, iae.getMessage()));
        }
        int n = facade.spawnSession(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SpawnedDto(n));
    }

    @Operation(summary = "Get detail for a single session by N.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Session detail.",
                content = @Content(schema = @Schema(implementation = ApiDtos.SessionDetailDto.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No session with that N.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    })
    @GetMapping("/{n}")
    public ResponseEntity<?> detail(@PathVariable int n) throws IOException {
        return facade.getSession(n)
                .map(ApiMappers::toDetail)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseThrow(() -> new NoSuchElementException("session " + n + " not found"));
    }

    /**
     * Delete (tear down) a session by N.
     *
     * <p>Contract (BUG 2 fix): with {@code force=false} (the default), an
     * absent {@code N} returns 404 {@code session_not_found} — the facade's
     * existence gate throws {@link NoSuchElementException}, which {@code
     * ProblemDetailsAdvice.handleNotFound} maps — and {@code clean.sh} is
     * NOT run. 500 {@code internal_error} is now reserved for a genuine
     * {@code clean.sh} failure (it actually ran and exited non-zero). With
     * {@code force=true} the existence check is skipped and {@code clean.sh}
     * runs unconditionally (operator escape hatch). An enumeration outage
     * surfaces as a 5xx via the generic fallback, never a 404.
     */
    @Operation(
            summary = "Delete (tear down) a session by N.",
            description = "Runs clean.sh for the target session. With force=false (default), a non-existent "
                    + "N returns 404 session_not_found and clean.sh is NOT run; with force=true the existence "
                    + "check is skipped and clean.sh runs unconditionally (operator escape hatch). 204 on "
                    + "success; 500 internal_error only when clean.sh actually ran and exited non-zero.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Session deleted (clean.sh exited 0)."),
        @ApiResponse(
                responseCode = "404",
                description = "No session with that N (force=false only).",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "500",
                description = "clean.sh exited non-zero.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    })
    @DeleteMapping("/{n}")
    public ResponseEntity<?> delete(
            @PathVariable int n, @RequestParam(name = "force", required = false, defaultValue = "false") boolean force)
            throws IOException, InterruptedException {
        boolean ok = facade.deleteSession(n, force);
        if (ok) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.internalServerError()
                .body(ProblemDetailsAdvice.build(
                        HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "clean.sh exited non-zero"));
    }

    /**
     * UC-46 — drive a Docker-lifecycle action on a session.
     *
     * <p>{@code POST /v1/sessions/{n}/{action}} where {@code action} is one of
     * {@code stop|start|pause|unpause} (the path is regex-constrained, so any
     * other token 404s as an unmapped path). No request body.
     *
     * <p>Contract:
     * <ul>
     *   <li><b>204</b> — {@code lifecycle.sh} ran and exited 0.</li>
     *   <li><b>404</b> {@code session_not_found} — no session with that N
     *       (the facade's existence gate throws {@link
     *       java.util.NoSuchElementException}).</li>
     *   <li><b>409</b> {@code session_state_conflict} — the action is invalid
     *       for the session's current state (e.g. START on a running
     *       session); {@link SessionFacade.InvalidLifecycleTransitionException}.</li>
     *   <li><b>500</b> {@code internal_error} — {@code lifecycle.sh} ran and
     *       exited non-zero.</li>
     * </ul>
     */
    @Operation(
            summary = "Drive a Docker-lifecycle action (stop/start/pause/unpause) on a session.",
            description = "Runs lifecycle.sh for the target session. 204 on success; 404 session_not_found for an "
                    + "unknown N; 409 session_state_conflict when the action is invalid for the current state; "
                    + "500 internal_error when lifecycle.sh exits non-zero.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Action applied (lifecycle.sh exited 0)."),
        @ApiResponse(
                responseCode = "404",
                description = "No session with that N.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "409",
                description = "Action invalid for the session's current state.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
                responseCode = "500",
                description = "lifecycle.sh exited non-zero.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    })
    @PostMapping("/{n}/{action:stop|start|pause|unpause}")
    public ResponseEntity<?> lifecycle(@PathVariable int n, @PathVariable String action)
            throws IOException, InterruptedException {
        // The raw path token is passed straight through; the facade owns the
        // token-to-action parse so this ..api layer never depends on the
        // internal ..sessions.dto enum (profile-java-server-architecture rule 5;
        // enforced by LayeringTest). The path is regex-pinned above, so the
        // facade's parse always succeeds here.
        boolean ok = facade.lifecycle(n, action);
        if (ok) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.internalServerError()
                .body(ProblemDetailsAdvice.build(
                        HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "lifecycle.sh exited non-zero"));
    }

    public record SpawnedDto(int n) {}
}
