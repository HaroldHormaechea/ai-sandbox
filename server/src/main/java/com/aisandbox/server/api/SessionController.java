package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.facade.SessionFacade;
import java.io.IOException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
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
