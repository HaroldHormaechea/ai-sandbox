package com.aisandbox.server.api;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.api.mapper.ApiMappers;
import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.clients.facade.ClientAllowlistFacade;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the client allowlist (AC19 → /v1/clients).
 * Calls the {@link ClientAllowlistFacade} only; never reaches into
 * services or the directory wrapper.
 */
@RestController
@RequestMapping("/v1/clients")
public class ClientController {

    private final ClientAllowlistFacade facade;

    public ClientController(ClientAllowlistFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public List<ApiDtos.ClientSummary> list() {
        return facade.listClients().stream().map(ApiMappers::toClientSummary).toList();
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody ApiDtos.AddClientRequest req) {
        if (req == null || req.name() == null || req.certPem() == null) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "name and certPem required"));
        }
        try {
            AllowedClient added = facade.addClient(req.name(), req.certPem());
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiMappers.toClientSummary(added));
        } catch (CertificateException ce) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CERT_PEM, ce.getMessage()));
        } catch (IOException io) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.CONFLICT, ErrorCode.CLIENT_NAME_CONFLICT, io.getMessage()));
        }
    }

    @DeleteMapping("/{cnOrFingerprint}")
    public ResponseEntity<?> delete(@PathVariable String cnOrFingerprint) {
        try {
            boolean ok = facade.removeClient(cnOrFingerprint);
            if (ok) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.NOT_FOUND, ErrorCode.CLIENT_NOT_FOUND, cnOrFingerprint));
        } catch (IOException io) {
            return ResponseEntity.internalServerError()
                    .body(ProblemDetailsAdvice.build(
                            HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, io.getMessage()));
        }
    }
}
