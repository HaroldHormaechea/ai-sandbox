package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.facade.SessionFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
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

    @GetMapping
    public ResponseEntity<?> list() throws IOException {
        return ResponseEntity.ok(ApiMappers.toSummaries(facade.listSessions()));
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

    public record SpawnedDto(int n) {}
}
