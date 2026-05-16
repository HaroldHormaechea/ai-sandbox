package com.aisandbox.server.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds the AC22 security headers on every response. The Swagger UI
 * paths get a stricter Content-Security-Policy that only allows the
 * webjar-served swagger-ui assets.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class SecurityHeadersFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> apply(exchange)));
    }

    private void apply(ServerWebExchange exchange) {
        HttpHeaders h = exchange.getResponse().getHeaders();
        h.set("Strict-Transport-Security", "max-age=63072000");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        String path = exchange.getRequest().getURI().getPath();
        if (path != null && path.startsWith("/v1/swagger-ui")) {
            h.set(
                    "Content-Security-Policy",
                    "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'");
        }
    }
}
