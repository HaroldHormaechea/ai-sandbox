package com.aisandbox.server.api.error;

import com.aisandbox.server.sessions.facade.SessionFacade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
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

    /**
     * Static mapper used by {@link #renderJson(HttpStatus, ErrorCode,
     * String)}.
     *
     * <p>UC11 round-1 QA fix — registers {@link
     * ProblemDetailJacksonMixin}, the same mix-in Spring's
     * {@code Jackson2ObjectMapperBuilder} wires onto the auto-configured
     * application mapper. Without the mix-in, Jackson serialises
     * {@link ProblemDetail}'s {@code getProperties()} map under a
     * nested {@code "properties": {...}} object, breaking RFC 9457
     * consumers that read the flat-object form (every Android client
     * + the controller-test {@code $.code} JsonPath assertions).
     *
     * <p>{@link RequestSizeLimitFilter} and {@link
     * com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler}
     * both call {@code renderJson} directly (the encoder path that
     * normally registers this mix-in only applies when the framework
     * serialises the response itself — neither call site goes through
     * the encoder). Configuring the static mapper here closes the gap
     * for both call sites in a single edit.
     */
    private static final ObjectMapper RENDER_MAPPER =
            new ObjectMapper().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

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

    // UC04 / UC11 — the five enrollment failure modes
    // (RateLimitedException, TokenInvalid / TokenExpired / TokenRedeemed,
    // and CertAlreadyExistsException added in UC11 § AC4 / S3.4) are
    // mapped by com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler,
    // a reactive-aware WebExceptionHandler bean. UC11 replaced the
    // previous @RestControllerAdvice sibling because the @ExceptionHandler
    // mechanism did not fire under WebFlux — every documented error path
    // landed in handleAny(Throwable) below with a wrapped
    // UnsupportedOperationException ("ServerHttpResponse already committed").
    // The handler still lives in enrollment.api (not here in api.error)
    // so the api package never reaches back into the enrollment package
    // (LayeringTest § no_cycles_between_top_level_feature_packages).

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

    /**
     * Static factory for use by interceptors / filters / WebExceptionHandlers
     * that need to inline a Problem+JSON body without going through the
     * framework's response encoder.
     *
     * <p>Uses {@link #RENDER_MAPPER} so the output flattens
     * {@link ProblemDetail#getProperties()} (notably the {@code code}
     * key set by {@link #build(HttpStatus, ErrorCode, String)}) onto
     * the top-level JSON object — matching the byte shape Spring's
     * auto-configured Jackson encoder produces for the same
     * {@code ProblemDetail} instance going out through a controller
     * return value (UC11 round-1 QA fix).
     */
    public static String renderJson(HttpStatus status, ErrorCode code, String detail) {
        try {
            return RENDER_MAPPER.writeValueAsString(build(status, code, detail));
        } catch (JsonProcessingException e) {
            return "{\"status\":" + status.value() + ",\"code\":\"" + code.wire() + "\"}";
        }
    }

    /** Convenience for content-type. */
    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
}
