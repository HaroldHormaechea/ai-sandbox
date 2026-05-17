package com.aisandbox.server.enrollment.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.enrollment.facade.EnrollmentFacade;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * UC04-specific exception → {@code application/problem+json} mappings
 * for the {@code POST /v1/enrollment} flow.
 *
 * <p>Lives in the {@code enrollment.api} package — NOT alongside
 * {@code com.aisandbox.server.api.error.ProblemDetailsAdvice} — so the
 * top-level {@code api} slice does not gain a back-edge to
 * {@code enrollment}. The {@code LayeringTest}
 * {@code no_cycles_between_top_level_feature_packages} ArchUnit rule
 * enforces this; cohabitation in {@code api.error} would form a cycle
 * because {@code enrollment.api.EnrollmentController} already imports
 * {@code api.dto.ApiDtos.EnrollmentRequest}.
 *
 * <p>The four handlers reuse {@link ProblemDetailsAdvice#build(HttpStatus,
 * ErrorCode, String)} so the body shape stays identical to the rest of
 * the server's error responses (RFC 9457 envelope, stable
 * {@code https://ai-sandbox.dev/problems/&lt;code&gt;} type URI, lowercase
 * {@code code} property).
 */
@RestControllerAdvice
public class EnrollmentProblemDetailsAdvice {

    @ExceptionHandler(EnrollmentFacade.RateLimitedException.class)
    @ResponseBody
    public ProblemDetail handleRateLimited(EnrollmentFacade.RateLimitedException ex) {
        return ProblemDetailsAdvice.build(
                HttpStatus.TOO_MANY_REQUESTS, ErrorCode.ENROLLMENT_RATE_LIMITED, ex.getMessage());
    }

    @ExceptionHandler(EnrollmentFacade.TokenInvalidException.class)
    @ResponseBody
    public ProblemDetail handleTokenInvalid(EnrollmentFacade.TokenInvalidException ex) {
        return ProblemDetailsAdvice.build(HttpStatus.UNAUTHORIZED, ErrorCode.ENROLLMENT_TOKEN_INVALID, ex.getMessage());
    }

    @ExceptionHandler(EnrollmentFacade.TokenExpiredException.class)
    @ResponseBody
    public ProblemDetail handleTokenExpired(EnrollmentFacade.TokenExpiredException ex) {
        return ProblemDetailsAdvice.build(HttpStatus.UNAUTHORIZED, ErrorCode.ENROLLMENT_TOKEN_EXPIRED, ex.getMessage());
    }

    @ExceptionHandler(EnrollmentFacade.TokenRedeemedException.class)
    @ResponseBody
    public ProblemDetail handleTokenRedeemed(EnrollmentFacade.TokenRedeemedException ex) {
        return ProblemDetailsAdvice.build(
                HttpStatus.UNAUTHORIZED, ErrorCode.ENROLLMENT_TOKEN_REDEEMED, ex.getMessage());
    }
}
