package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * UC04 § B2 — L7 mTLS gate. Replaces the L5
 * {@link io.netty.handler.ssl.ClientAuth#REQUIRE} we used before UC04
 * flipped the TLS layer to {@link io.netty.handler.ssl.ClientAuth#OPTIONAL}.
 *
 * <p>This test pins:
 *
 * <ul>
 *   <li>anonymous → {@code /v1/enrollment}: pass-through (the only
 *       mTLS-exempt path).</li>
 *   <li>anonymous → any other path: 401
 *       {@code application/problem+json} {@code mtls_required}.</li>
 *   <li>authenticated identity: pass-through everywhere.</li>
 *   <li>trailing-slash normalisation on {@code /v1/enrollment/}.</li>
 *   <li>no-stashed-identity (null ATTR): treated as anonymous → 401.</li>
 * </ul>
 */
class MtlsEnforcementFilterTest {

    private static final ClientIdentity REAL_CLIENT =
            new ClientIdentity("alice-phone", "fa".repeat(32), BigInteger.ONE);

    private static WebFilterChain reachedChain(java.util.concurrent.atomic.AtomicBoolean reached) {
        return ex -> {
            reached.set(true);
            return Mono.empty();
        };
    }

    private final MtlsEnforcementFilter filter = new MtlsEnforcementFilter();

    @Test
    void anonymous_to_enrollment_is_allowed() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/enrollment"));
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, ClientIdentity.ANONYMOUS);
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached).isTrue();
        assertThat(ex.getResponse().getStatusCode()).isNull(); // no rejection set
    }

    @Test
    void anonymous_to_enrollment_trailing_slash_is_allowed() {
        // Trailing-slash normalisation so curl users typing /v1/enrollment/
        // don't accidentally hit the gate.
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/enrollment/"));
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, ClientIdentity.ANONYMOUS);
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached).isTrue();
    }

    @Test
    void anonymous_to_sessions_is_rejected_with_401_mtls_required() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, ClientIdentity.ANONYMOUS);
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached).as("chain should NOT have been called").isFalse();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getHeaders().getContentType().toString()).isEqualTo("application/problem+json");

        // Body carries the mtls_required code.
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"code\":\"mtls_required\"");
    }

    @Test
    void anonymous_to_subpath_under_enrollment_is_NOT_exempt() {
        // Defensive — sub-paths under /v1/enrollment/ are NOT bypassed.
        // There are none today and adding one would need a threat-model review.
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/enrollment/foo"));
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, ClientIdentity.ANONYMOUS);
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached).isFalse();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authenticated_identity_passes_on_any_path() {
        for (String path : new String[] {"/v1/sessions", "/v1/clients", "/v1/healthz", "/v1/sessions/3/stream"}) {
            MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            ex.getAttributes().put(ClientIdentityExtractor.ATTR, REAL_CLIENT);
            java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

            filter.filter(ex, reachedChain(reached)).block();

            assertThat(reached).as("authenticated → %s should pass", path).isTrue();
            assertThat(ex.getResponse().getStatusCode()).as("path=%s", path).isNull();
        }
    }

    @Test
    void no_stashed_identity_treated_as_anonymous_on_non_exempt_paths() {
        // ClientIdentityExtractor may not yet have written the ATTR if the
        // request comes through a test pipeline that doesn't carry a real
        // Netty channel. Defensive: treat null identity as anonymous and
        // reject — never silently let an unidentified request through.
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));
        // No ATTR put.
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached).isFalse();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void no_stashed_identity_is_still_admitted_for_enrollment() {
        // Enrollment is the only mTLS-exempt path — even without a
        // recorded identity (test pipeline / pre-extractor races) it
        // must reach the controller.
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/enrollment"));
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached).isTrue();
    }
}
