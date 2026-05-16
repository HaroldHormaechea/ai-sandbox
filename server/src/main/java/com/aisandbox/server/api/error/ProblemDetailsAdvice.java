package com.aisandbox.server.api.error;

import com.aisandbox.server.sessions.facade.SessionFacade;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/**
 * Maps domain and framework exceptions to {@code application/problem+json}
 * payloads per RFC 9457 (AC21). All bodies carry a stable {@code code}
 * (lowercase {@link ErrorCode}) and a {@code type} URI under
 * {@code https://ai-sandbox.dev/problems/} so docs are easy to find.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);
    private static final String TYPE_BASE = "https://ai-sandbox.dev/problems/";

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseBody
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.SESSION_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(SessionFacade.SpawnFailedException.class)
    @ResponseBody
    public ProblemDetail handleSpawnFailed(SessionFacade.SpawnFailedException ex) {
        ProblemDetail pd = build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SPAWN_FAILED, ex.stderr);
        pd.setProperty("exitCode", ex.exitCode);
        pd.setProperty("consumedN", ex.consumedN);
        return pd;
    }

    @ExceptionHandler(CertificateException.class)
    @ResponseBody
    public ProblemDetail handleBadCert(CertificateException ex) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CERT_PEM, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ProblemDetail handleBadArg(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({DecodingException.class, ServerWebInputException.class})
    @ResponseBody
    public ProblemDetail handleDecoding(Throwable t) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, t.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ErrorCode code =
                switch (status.series()) {
                    case CLIENT_ERROR -> ErrorCode.BAD_REQUEST;
                    default -> ErrorCode.INTERNAL_ERROR;
                };
        return build(status, code, ex.getReason() == null ? status.getReasonPhrase() : ex.getReason());
    }

    @ExceptionHandler(Throwable.class)
    @ResponseBody
    public ProblemDetail handleAny(Throwable t) {
        LOG.warn("Unmapped exception in REST flow: {}", t.toString(), t);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, t.getMessage());
    }

    public static ProblemDetail build(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create(TYPE_BASE + code.wire()));
        pd.setTitle(status.getReasonPhrase());
        pd.setDetail(detail == null ? "" : detail);
        pd.setProperty("code", code.wire());
        return pd;
    }

    /** Static factory for use by interceptors that need to inline a PD body. */
    public static String renderJson(HttpStatus status, ErrorCode code, String detail) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(build(status, code, detail));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"status\":" + status.value() + ",\"code\":\"" + code.wire() + "\"}";
        }
    }

    /** Convenience for content-type. */
    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
}
