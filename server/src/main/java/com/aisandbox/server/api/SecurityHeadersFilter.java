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
 *
 * <p><strong>Ordering contract — headers are set BEFORE
 * {@code chain.filter(exchange)} returns.</strong> Setting headers
 * <em>post</em>-{@code chain.filter} (e.g. via
 * {@code Mono.then(Mono.fromRunnable(() -> apply(exchange)))}) is
 * unsafe: any downstream handler that streams a body (e.g. the
 * docker-touching {@code /v1/sessions} endpoints returning a
 * {@code Flux} of session summaries) commits the response — flushing
 * status + headers and the first bytes onto the wire — before the
 * {@code Mono.then} fires. Once committed,
 * {@code ServerHttpResponse.getHeaders()} is sealed and any attempt
 * to mutate it throws {@link UnsupportedOperationException}, which
 * Reactor surfaces as
 * {@code "Error [java.lang.UnsupportedOperationException] for HTTP
 * GET ..., but ServerHttpResponse already committed (200 OK)"} in
 * the {@code HttpWebHandlerAdapter} logs. The client has already
 * received {@code 200 OK} with the partial body by the time this
 * throws, so the symptom is "endpoint works once, server log fills
 * with UOE stack traces on every call."
 *
 * <p><strong>Controller-override contract.</strong> Because headers
 * are set on the response <em>before</em> the controller /
 * downstream handler runs, any controller that explicitly sets one
 * of these headers (for example, an OpenAPI doc endpoint overriding
 * {@code Content-Security-Policy} for inline Swagger-UI scripts)
 * will see its value win — controllers run after this filter and
 * the last write to a still-uncommitted response replaces the
 * earlier value. That is the intended behaviour: the filter sets
 * safe defaults, and a controller that needs a different value
 * gets to override it without contention.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class SecurityHeadersFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Set headers SYNCHRONOUSLY before delegating downstream. See the
        // class-level Javadoc for why post-chain.filter mutation is
        // unsafe (sealed-response UnsupportedOperationException on any
        // streaming endpoint).
        apply(exchange);
        return chain.filter(exchange);
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
