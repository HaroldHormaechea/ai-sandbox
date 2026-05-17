package com.aisandbox.server.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * UC04 § B2 — L7 mTLS gate. Replaces the L5 enforcement that
 * {@link io.netty.handler.ssl.ClientAuth#REQUIRE} provided before the
 * UC04 TLS-layer flip; with the holder now set to
 * {@link io.netty.handler.ssl.ClientAuth#OPTIONAL} the mTLS-exempt
 * {@code POST /v1/enrollment} path is the only place anonymous
 * requests are admitted, and this filter rejects every other path that
 * arrives without a real client cert with {@code 401 mtls_required}.
 *
 * <p>Ordering rationale:
 *
 * <ul>
 *   <li>{@link ClientIdentityExtractor} writes the
 *       {@link ClientIdentityExtractor#ATTR} attribute at
 *       {@link Ordered#HIGHEST_PRECEDENCE} so the ATTR is always
 *       populated when this filter runs.</li>
 *   <li>This filter sits at {@link Ordered#HIGHEST_PRECEDENCE} + 10 —
 *       after the extractor, before {@link RequestSizeLimitFilter}
 *       (HIGHEST_PRECEDENCE + 100), so identity and size checks both
 *       happen before any routing.</li>
 * </ul>
 *
 * <p>The bypass list contains exactly the path UC04 § B2 specifies:
 * {@code /v1/enrollment} with trailing-slash normalisation. Adding new
 * mTLS-exempt paths in the future means editing {@link #isExempt} —
 * never widen this without explicit threat-model approval (see
 * {@code docs/THREAT_MODEL.md} § "Enrollment trust boundary").
 *
 * <p>Lives in the {@code api} package — NOT the {@code identity}
 * package where the related {@link ClientIdentityExtractor} sits —
 * because importing {@link ProblemDetailsAdvice} from
 * {@code identity.*} would form an {@code api ↔ identity} cycle via
 * the existing {@code clients → identity} edge. The
 * {@code LayeringTest.no_cycles_between_top_level_feature_packages()}
 * ArchUnit rule enforces this.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Profile("!docs-only")
public class MtlsEnforcementFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isExempt(path)) {
            return chain.filter(exchange);
        }
        Object stashed = exchange.getAttributes().get(ClientIdentityExtractor.ATTR);
        if (stashed instanceof ClientIdentity ci && !ci.isAnonymous()) {
            return chain.filter(exchange);
        }
        return reject(exchange);
    }

    /**
     * Exact-match {@code /v1/enrollment} with optional trailing slash so
     * curl users typing {@code /v1/enrollment/} don't accidentally hit
     * the gate. Sub-paths under {@code /v1/enrollment/} are NOT exempt
     * — there are none today, and a future {@code /v1/enrollment/foo}
     * would need its own threat-model review.
     */
    private static boolean isExempt(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/v1/enrollment") || path.equals("/v1/enrollment/");
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        var resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().setContentType(ProblemDetailsAdvice.PROBLEM_JSON);
        byte[] body = ProblemDetailsAdvice.renderJson(
                        HttpStatus.UNAUTHORIZED,
                        ErrorCode.MTLS_REQUIRED,
                        "Client certificate required. Only POST /v1/enrollment is mTLS-exempt.")
                .getBytes(StandardCharsets.UTF_8);
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body)));
    }
}
