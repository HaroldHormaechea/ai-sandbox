package com.aisandbox.server.api.error;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * UC12 — lowest-precedence {@link WebExceptionHandler} that catches every
 * {@link Throwable} not claimed by a more specific handler upstream and
 * renders it as an HTTP 500 {@code application/problem+json} body with
 * {@link ErrorCode#INTERNAL_ERROR}.
 *
 * <h2>Why a {@code WebExceptionHandler} replaces
 * {@code ProblemDetailsAdvice.handleAny(Throwable)}</h2>
 *
 * <p>Pre-UC12 the catch-all lived on
 * {@link ProblemDetailsAdvice#handleAny(Throwable) handleAny} — a
 * {@code @ExceptionHandler(Throwable.class)} on the
 * {@code @RestControllerAdvice}. In Spring WebFlux that mechanism runs
 * at the {@code RequestMappingHandlerAdapter} → {@code
 * InvocableHandlerMethod} layer, BEFORE exceptions reach the {@code
 * ExceptionHandlingWebHandler} layer where {@code WebExceptionHandler}
 * beans run. The advice's {@code Throwable} catch-all therefore won
 * the race against
 * {@link com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler}
 * for the five documented enrollment exceptions, attempted to render
 * onto an already-committed {@code ServerHttpResponse}, and produced
 * HTTP 500 + a wrapped {@code UnsupportedOperationException} — exactly
 * the symptom UC11 was supposed to fix (production logs on
 * potato-server, 2026-05-21).
 *
 * <p>UC12 chose fix-shape (a): remove the {@code @ExceptionHandler(
 * Throwable.class)} entirely so the per-domain {@code
 * WebExceptionHandler} beans (enrollment + stream) get first crack at
 * the exception, and reintroduce the catch-all as this bean at
 * {@link Ordered#LOWEST_PRECEDENCE} so truly-unmapped exceptions still
 * produce a documented {@code internal_error} envelope instead of
 * falling through to Spring's default error response.
 *
 * <h2>Log contract</h2>
 *
 * <p>Emits a single WARN log line on the
 * {@link ProblemDetailsAdvice} logger category (via {@code
 * LoggerFactory.getLogger(ProblemDetailsAdvice.class)}) with the
 * literal prefix {@code "Unmapped exception in REST flow: "} followed
 * by {@code ex.toString()} (i.e. {@code <exception-class>: <message>}).
 * Log-monitoring tools key on this category + literal text — UC12
 * preserves both byte-for-byte so existing alerts continue to fire on
 * genuinely-unmapped exceptions.
 *
 * <h2>Response-committed defensive guard</h2>
 *
 * <p>Mirrors the early-return guard in
 * {@link com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler#handle(
 * ServerWebExchange, Throwable)}: if some upstream handler has already
 * committed the response we cannot rewrite it, so we delegate back to
 * the chain. Reaching that branch is itself a regression signal worth
 * surfacing.
 *
 * <h2>Layering</h2>
 *
 * <p>Sits in {@code api.error} alongside {@link ProblemDetailsAdvice}.
 * As a framework adapter at the API boundary it consumes the raw
 * {@link Throwable} and translates to a {@code ProblemDetail} envelope
 * — it does NOT call facades, services, or repositories, so the
 * Controller/Job → Facade → Service → Repository call chain is
 * unaffected (see {@code profile-java-server-architecture}).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class GenericProblemFallbackHandler implements WebExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        LOG.warn("Unmapped exception in REST flow: {}", ex.toString(), ex);

        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // Someone else already started writing — bail out and let
            // Spring's default logging handle the inconsistency. This
            // is the exact pathology UC11/UC12 set out to fix, so
            // reaching this branch in production is a regression worth
            // knowing about.
            return Mono.error(ex);
        }
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body = ProblemDetailsAdvice.renderJson(
                        HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, ex.getMessage())
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
