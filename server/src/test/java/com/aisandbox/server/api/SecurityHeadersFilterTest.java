package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * AC22 — every REST response carries the required security headers. The
 * Swagger UI namespace gets the strict CSP on top.
 */
class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void sets_baseline_security_headers_on_every_response() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));
        WebFilterChain chain = e -> Mono.empty();

        filter.filter(ex, chain).block();

        var h = ex.getResponse().getHeaders();
        assertThat(h.getFirst("Strict-Transport-Security")).isEqualTo("max-age=63072000");
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        // Non-swagger path gets no CSP from this filter.
        assertThat(h.getFirst("Content-Security-Policy")).isNull();
    }

    @Test
    void adds_strict_csp_on_swagger_ui_routes() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/swagger-ui"));
        WebFilterChain chain = e -> Mono.empty();

        filter.filter(ex, chain).block();

        String csp = ex.getResponse().getHeaders().getFirst("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp).contains("default-src 'none'");
        assertThat(csp).contains("frame-ancestors 'none'");
    }
}
