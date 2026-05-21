package com.aisandbox.server.stream.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.stream.facade.StreamFacade;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * UC04 / UC12 — reactive-aware exception → {@code application/problem+json}
 * mapping for {@link StreamFacade.SessionNotRunningException}, parallel
 * to {@link com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler}.
 *
 * <h2>Why a {@link WebExceptionHandler} replaces the prior
 * {@code @RestControllerAdvice}</h2>
 *
 * <p>Pre-UC12 this responsibility lived on a sibling
 * {@code @RestControllerAdvice} called {@code StreamProblemDetailsAdvice}
 * with a single {@code @ExceptionHandler(SessionNotRunningException.class)}
 * method. Empirically (UC12 § AC5 pre-fix cascade — QA's
 * {@code StreamExceptionRoutingTest} run on the v0.0.13 baseline) that
 * handler did NOT fire under Spring WebFlux: the synthetic endpoint
 * that throws {@code SessionNotRunningException} returned HTTP 500 with
 * the same wrapped {@code UnsupportedOperationException: ServerHttpResponse
 * already committed} pathology that motivated the enrollment migration
 * in UC11/UC12. The bug is the same root cause — the controller-advice
 * {@code @ExceptionHandler} mechanism runs at the
 * {@code RequestMappingHandlerAdapter} layer BEFORE the framework's
 * {@code ExceptionHandlingWebHandler} where {@code WebExceptionHandler}
 * beans run, and
 * {@link ProblemDetailsAdvice}'s former {@code handleAny(Throwable)}
 * catch-all consumed the exception first and tried to render onto an
 * already-committed response.
 *
 * <p>UC12 applies the same fix shape (option a) to the stream side:
 * delete the {@code @RestControllerAdvice} entirely and reintroduce
 * the mapping as a {@link WebExceptionHandler} bean at high precedence,
 * with the generic catch-all reintroduced separately as
 * {@link com.aisandbox.server.api.error.GenericProblemFallbackHandler}
 * at lowest precedence so unmapped throwables still produce an
 * {@code internal_error} envelope.
 *
 * <h2>Mapping</h2>
 *
 * <ul>
 *   <li>{@link StreamFacade.SessionNotRunningException} → 409
 *       {@code session_not_running}, with two extra properties on the
 *       Problem+JSON body that the deleted advice also emitted:
 *       <ul>
 *         <li>{@code n} (Integer) — the session ordinal that was
 *             targeted</li>
 *         <li>{@code state} (String) — the session's actual state at
 *             rejection time</li>
 *       </ul>
 *       Both are rendered as flat top-level fields (via
 *       {@link ProblemDetailsAdvice#renderJson(ProblemDetail)}, which
 *       uses the same {@code ProblemDetailJacksonMixin} the encoder
 *       path uses for controller return values).</li>
 * </ul>
 *
 * <h2>Fall-through behaviour</h2>
 *
 * <p>For any throwable that is NOT
 * {@link StreamFacade.SessionNotRunningException} this handler returns
 * {@link Mono#error(Throwable)} so Spring's chain continues to the
 * next handler. That ultimately delegates to
 * {@link com.aisandbox.server.api.error.GenericProblemFallbackHandler}'s
 * {@code internal_error} envelope — no regression for unrelated
 * endpoints.
 *
 * <h2>Layering</h2>
 *
 * <p>Lives in {@code stream.api} (not {@code api.error}) — same reason
 * as the deleted advice and as
 * {@link com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler}:
 * the top-level {@code api} package must not gain a back-edge to
 * {@code stream}, and the {@code LayeringTest} ArchUnit rule
 * {@code no_cycles_between_top_level_feature_packages} would trip if
 * this handler cohabited with {@link ProblemDetailsAdvice}.
 *
 * <h2>Order precedence</h2>
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} + 200 mirrors
 * {@link com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler}.
 * The two domain handlers do not overlap on exception types — each
 * passes through anything it does not recognise — so their relative
 * ordering does not matter, but keeping both at the same precedence
 * makes future debugging simpler.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
public class StreamWebExceptionHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!(ex instanceof StreamFacade.SessionNotRunningException notRunning)) {
            // Not ours — pass through to the next handler so the
            // generic fallback catches it.
            return Mono.error(ex);
        }

        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // Someone else already started writing — bail out and let
            // Spring's default logging surface the inconsistency. This
            // is the exact pathology UC12 set out to fix, so reaching
            // this branch in production is a regression worth knowing
            // about.
            return Mono.error(ex);
        }

        ProblemDetail pd =
                ProblemDetailsAdvice.build(HttpStatus.CONFLICT, ErrorCode.SESSION_NOT_RUNNING, notRunning.getMessage());
        pd.setProperty("n", notRunning.n());
        pd.setProperty("state", notRunning.state());

        response.setStatusCode(HttpStatus.CONFLICT);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body = ProblemDetailsAdvice.renderJson(pd).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
