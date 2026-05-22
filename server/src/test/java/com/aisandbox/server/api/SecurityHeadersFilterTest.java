package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * AC22 — every REST response carries the required security headers. The
 * Swagger UI namespace gets the strict CSP on top.
 *
 * <p>UC-17 additions: the filter now sets the headers
 * <em>before</em> {@code chain.filter(exchange)} returns (so that any
 * downstream handler that streams a body cannot commit the response
 * before the filter writes the headers). Two new unit assertions pin
 * this behaviour at the filter boundary:
 *
 * <ol>
 *   <li>{@link #headers_are_set_before_chain_filter_runs} — proves the
 *       ordering: by the time the {@link WebFilterChain} is invoked,
 *       the four baseline headers are already present on the response.
 *       Pre-fix shape ({@code chain.filter(...).then(apply)}) would
 *       see absent headers at chain-entry; post-fix shape
 *       ({@code apply(); return chain.filter(...)}) sees them present.</li>
 *   <li>{@link #controller_override_wins_over_filter_default} — a
 *       downstream handler that sets {@code Content-Security-Policy}
 *       to a sentinel value wins over the filter's swagger-UI
 *       default. Proves the controller-override contract from the
 *       UC-17 Javadoc — headers are defaults that controllers may
 *       replace before the response commits.</li>
 * </ol>
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

    /**
     * UC-17 ordering pin — the filter MUST set the four baseline
     * headers BEFORE delegating to {@link WebFilterChain#filter}.
     * Captured via a chain that snapshots the response headers at
     * the moment it is invoked. Pre-fix shape (apply runs after
     * chain.filter via {@code Mono.then}) would observe absent
     * headers at chain-entry — that's the bug. Post-fix shape
     * observes them present.
     */
    @Test
    void headers_are_set_before_chain_filter_runs() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));
        AtomicReference<String> hstsAtChainEntry = new AtomicReference<>();
        AtomicReference<String> nosniffAtChainEntry = new AtomicReference<>();
        AtomicReference<String> xFrameAtChainEntry = new AtomicReference<>();
        AtomicReference<String> referrerAtChainEntry = new AtomicReference<>();

        WebFilterChain chain = e -> {
            HttpHeaders h = e.getResponse().getHeaders();
            hstsAtChainEntry.set(h.getFirst("Strict-Transport-Security"));
            nosniffAtChainEntry.set(h.getFirst("X-Content-Type-Options"));
            xFrameAtChainEntry.set(h.getFirst("X-Frame-Options"));
            referrerAtChainEntry.set(h.getFirst("Referrer-Policy"));
            return Mono.empty();
        };

        filter.filter(ex, chain).block();

        assertThat(hstsAtChainEntry.get())
                .as("UC-17 — HSTS MUST be present on the response BEFORE chain.filter runs")
                .isEqualTo("max-age=63072000");
        assertThat(nosniffAtChainEntry.get())
                .as("UC-17 — X-Content-Type-Options MUST be present on the response BEFORE chain.filter runs")
                .isEqualTo("nosniff");
        assertThat(xFrameAtChainEntry.get())
                .as("UC-17 — X-Frame-Options MUST be present on the response BEFORE chain.filter runs")
                .isEqualTo("DENY");
        assertThat(referrerAtChainEntry.get())
                .as("UC-17 — Referrer-Policy MUST be present on the response BEFORE chain.filter runs")
                .isEqualTo("no-referrer");
    }

    /**
     * UC-17 controller-override contract — a downstream handler that
     * sets {@code Content-Security-Policy} to a sentinel value wins
     * over the filter's swagger-UI default. The filter writes its
     * default first (because apply() runs before chain.filter); the
     * downstream chain replaces it on the still-uncommitted response
     * via {@code h.set("Content-Security-Policy", sentinel)}, and the
     * final wire value is the sentinel. Mirrors the Javadoc contract
     * on {@link SecurityHeadersFilter} ("a controller that needs a
     * different value gets to override it without contention").
     */
    @Test
    void controller_override_wins_over_filter_default() {
        // Use the swagger-UI path so the filter writes a non-null
        // default CSP that we can demonstrate being overridden.
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/swagger-ui"));
        String sentinel = "default-src 'self'; script-src 'self' 'sha256-sentinel'";

        WebFilterChain chain = e -> {
            // Pre-condition: the filter already wrote its default
            // before delegating to us. Without that, the test is
            // testing the wrong thing.
            String filterDefault = e.getResponse().getHeaders().getFirst("Content-Security-Policy");
            assertThat(filterDefault)
                    .as("UC-17 — filter MUST set its swagger CSP default before invoking chain.filter")
                    .isNotNull()
                    .contains("default-src 'none'");
            // Now the controller takes over and overrides on the
            // still-uncommitted response.
            e.getResponse().getHeaders().set("Content-Security-Policy", sentinel);
            return Mono.empty();
        };

        filter.filter(ex, chain).block();

        String finalCsp = ex.getResponse().getHeaders().getFirst("Content-Security-Policy");
        assertThat(finalCsp)
                .as("UC-17 controller-override contract — a controller's explicit "
                        + "Content-Security-Policy value MUST win over the filter's default. "
                        + "If this assertion sees the filter default, the filter is "
                        + "post-processing headers after the chain runs (regression of the "
                        + "UC-17 ordering fix).")
                .isEqualTo(sentinel);
    }
}
