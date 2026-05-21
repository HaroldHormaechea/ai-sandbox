package com.aisandbox.server.enrollment.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.enrollment.facade.EnrollmentFacade;
import java.nio.charset.StandardCharsets;
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
 * UC04 / UC11 — reactive-aware exception → {@code application/problem+json}
 * mapping for the five {@code POST /v1/enrollment} failure modes.
 *
 * <h2>Why a {@link WebExceptionHandler} and not a {@code @RestControllerAdvice}</h2>
 *
 * <p>Pre-UC11 this responsibility lived on a sibling
 * {@code @RestControllerAdvice} called {@code EnrollmentProblemDetailsAdvice}
 * with five {@code @ExceptionHandler} methods. Empirically (potato-server
 * production logs, 2026-05-20) those handlers did NOT fire under
 * Spring WebFlux: every documented-error path returned HTTP 500 with a
 * wrapped {@code UnsupportedOperationException: ServerHttpResponse already
 * committed}. The framework's catch-all
 * {@link ProblemDetailsAdvice#handleAny(Throwable)} did fire (the
 * "Unmapped exception in REST flow" log line proves it), but by that
 * point the response was already committed by some upstream handler and
 * the late attempt to rewrite the body threw.
 *
 * <p>The reactive-correct fix is a {@link WebExceptionHandler} bean
 * registered with sufficient {@link Order} precedence that it fires
 * BEFORE any default handler commits the response. UC11 § AC4 — the
 * five typed exceptions get mapped to their documented HTTP codes:
 * <ul>
 *   <li>{@link EnrollmentFacade.RateLimitedException} → 429
 *       {@code enrollment_rate_limited}</li>
 *   <li>{@link EnrollmentFacade.TokenInvalidException} → 401
 *       {@code enrollment_token_invalid}</li>
 *   <li>{@link EnrollmentFacade.TokenExpiredException} → 401
 *       {@code enrollment_token_expired}</li>
 *   <li>{@link EnrollmentFacade.TokenRedeemedException} → 401
 *       {@code enrollment_token_redeemed}</li>
 *   <li>{@link EnrollmentFacade.CertAlreadyExistsException} → 409
 *       {@code client_name_conflict}</li>
 * </ul>
 *
 * <p>Bodies are produced via {@link ProblemDetailsAdvice#renderJson(
 * HttpStatus, ErrorCode, String)} so they remain byte-identical to the
 * rest of the server's RFC 9457 envelopes — same {@code type} URI base
 * ({@code https://ai-sandbox.dev/problems/}), same {@code code}
 * property casing, same {@code title}/{@code detail}/{@code status}
 * fields.
 *
 * <p>The 413 {@code payload_too_large} path is NOT this handler's
 * responsibility: requests over the 256-byte cap are short-circuited
 * by {@code RequestSizeLimitFilter} long before any controller
 * invocation, so they never reach the facade and never raise an
 * enrollment exception.
 *
 * <h2>Fall-through behaviour</h2>
 *
 * <p>For any throwable that is NOT one of the five mapped types this
 * handler returns {@link Mono#error(Throwable)} so the next handler in
 * Spring's chain processes it. That delegates back to the framework's
 * default handlers + {@link ProblemDetailsAdvice}'s catch-all
 * {@code @ExceptionHandler(Throwable.class)}, which produces the
 * existing {@code internal_error} envelope — no regression for
 * unrelated endpoints.
 *
 * <h2>Layering</h2>
 *
 * <p>Lives in {@code enrollment.api} (not {@code api.error}) for the
 * same reason as its predecessor: the top-level {@code api} package
 * must not gain a back-edge to {@code enrollment}, and the
 * {@code LayeringTest} ArchUnit rule
 * {@code no_cycles_between_top_level_feature_packages} would trip if
 * this handler cohabited with {@link ProblemDetailsAdvice}.
 *
 * <h2>Order precedence</h2>
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} + 200 is the starting point
 * picked by the analyst proposal. If integration tests later show 500
 * / {@code internal_error} for any of the five mapped codes (a sign
 * that a higher-precedence default handler is committing the response
 * first), bisect downward.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
public class EnrollmentWebExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        final HttpStatus status;
        final ErrorCode code;
        switch (ex) {
            case EnrollmentFacade.RateLimitedException ignored -> {
                status = HttpStatus.TOO_MANY_REQUESTS;
                code = ErrorCode.ENROLLMENT_RATE_LIMITED;
            }
            case EnrollmentFacade.TokenInvalidException ignored -> {
                status = HttpStatus.UNAUTHORIZED;
                code = ErrorCode.ENROLLMENT_TOKEN_INVALID;
            }
            case EnrollmentFacade.TokenExpiredException ignored -> {
                status = HttpStatus.UNAUTHORIZED;
                code = ErrorCode.ENROLLMENT_TOKEN_EXPIRED;
            }
            case EnrollmentFacade.TokenRedeemedException ignored -> {
                status = HttpStatus.UNAUTHORIZED;
                code = ErrorCode.ENROLLMENT_TOKEN_REDEEMED;
            }
            case EnrollmentFacade.CertAlreadyExistsException ignored -> {
                status = HttpStatus.CONFLICT;
                code = ErrorCode.CLIENT_NAME_CONFLICT;
            }
            default -> {
                // Not ours — pass through to the next handler so the
                // generic ProblemDetailsAdvice fallback catches it.
                return Mono.error(ex);
            }
        }

        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // Someone else already started writing — bail out and let
            // Spring's default logging handle the inconsistency. This
            // is the exact pathology UC11 set out to fix, so reaching
            // this branch in production is a regression worth knowing
            // about.
            return Mono.error(ex);
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body =
                ProblemDetailsAdvice.renderJson(status, code, ex.getMessage()).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
