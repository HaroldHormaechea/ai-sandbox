package com.aisandbox.server.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.config.ServerProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects POST / PUT / PATCH requests whose {@code Content-Length} exceeds
 * the configured per-path cap with a 413 body.
 *
 * <p>Two caps coexist:
 *
 * <ul>
 *   <li><b>Global</b> — {@code limits.max-request-bytes} (AC20, default
 *       64 KiB). Tripping returns {@link ErrorCode#REQUEST_TOO_LARGE}.</li>
 *   <li><b>Enrollment</b> — UC04 AC33 caps {@code POST /v1/enrollment}
 *       at {@link #ENROLLMENT_MAX_BODY_BYTES} (256 B) since the body is
 *       just {@code {"token":"..."}}. Tripping returns the UC04-specific
 *       {@link ErrorCode#PAYLOAD_TOO_LARGE}.</li>
 * </ul>
 *
 * The webflux codec also enforces an in-memory cap via
 * {@code spring.codec.max-in-memory-size}, but a Content-Length pre-check
 * lets us fail fast without buffering.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RequestSizeLimitFilter implements WebFilter {

    /** UC04 AC33 per-route cap for {@code POST /v1/enrollment}. */
    static final long ENROLLMENT_MAX_BODY_BYTES = 256L;

    static final String ENROLLMENT_PATH = "/v1/enrollment";

    private final long maxBytes;

    public RequestSizeLimitFilter(ServerProperties props) {
        this.maxBytes = props.limits().maxRequestBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (method != null && (method.matches("POST") || method.matches("PUT") || method.matches("PATCH"))) {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            long cl = headers.getContentLength();
            String path = exchange.getRequest().getURI().getPath();
            if (isEnrollmentPath(path)) {
                if (cl > ENROLLMENT_MAX_BODY_BYTES) {
                    return reject(
                            exchange,
                            ErrorCode.PAYLOAD_TOO_LARGE,
                            "Enrollment request body exceeds " + ENROLLMENT_MAX_BODY_BYTES + " bytes");
                }
            } else if (cl > maxBytes) {
                return reject(exchange, ErrorCode.REQUEST_TOO_LARGE, "Request body exceeds " + maxBytes + " bytes");
            }
        }
        return chain.filter(exchange);
    }

    /**
     * Match {@code /v1/enrollment} with optional trailing slash so a
     * stray {@code /v1/enrollment/} from a curious client maps to the
     * same cap. The {@code MtlsEnforcementFilter} (UC04 B2) applies the
     * same trailing-slash normalisation when deciding which paths are
     * mTLS-exempt — keep the rules in sync.
     */
    private static boolean isEnrollmentPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals(ENROLLMENT_PATH) || path.equals(ENROLLMENT_PATH + "/");
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode code, String detail) {
        var resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        resp.getHeaders().setContentType(ProblemDetailsAdvice.PROBLEM_JSON);
        byte[] body = ProblemDetailsAdvice.renderJson(HttpStatus.PAYLOAD_TOO_LARGE, code, detail)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body)));
    }
}
