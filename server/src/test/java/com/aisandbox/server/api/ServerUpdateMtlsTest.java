package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * UC-84 AC10 — both self-update endpoints sit behind the standard L7 mTLS gate
 * ({@code MtlsEnforcementFilter}); only {@code POST /v1/enrollment} is exempt.
 * An unauthenticated / non-enrolled caller is rejected with {@code 401
 * application/problem+json mtls_required}; an authenticated caller passes.
 *
 * <p>Mirrors {@link MtlsEnforcementFilterTest}'s harness — the controller adds
 * no exemption, so the guarantee is the filter's, asserted here for the two new
 * routes specifically.
 */
class ServerUpdateMtlsTest {

    private static final ClientIdentity REAL_CLIENT =
            new ClientIdentity("alice-phone", "fa".repeat(32), BigInteger.ONE);

    private final MtlsEnforcementFilter filter = new MtlsEnforcementFilter();

    private static WebFilterChain reachedChain(AtomicBoolean reached) {
        return ex -> {
            reached.set(true);
            return Mono.empty();
        };
    }

    private void assertAnonymousRejected(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange ex = MockServerWebExchange.from(request);
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, ClientIdentity.ANONYMOUS);
        AtomicBoolean reached = new AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached)
                .as("chain must NOT be reached for an anonymous caller")
                .isFalse();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getResponse().getHeaders().getContentType().toString()).isEqualTo("application/problem+json");
        String body = ex.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"code\":\"mtls_required\"");
    }

    private void assertAuthenticatedPasses(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange ex = MockServerWebExchange.from(request);
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, REAL_CLIENT);
        AtomicBoolean reached = new AtomicBoolean();

        filter.filter(ex, reachedChain(reached)).block();

        assertThat(reached).as("an enrolled client must pass through").isTrue();
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }

    @Test
    void anonymous_check_is_rejected_with_401_mtls_required() {
        assertAnonymousRejected(MockServerHttpRequest.get("/v1/server/update/check"));
    }

    @Test
    void anonymous_apply_is_rejected_with_401_mtls_required() {
        assertAnonymousRejected(MockServerHttpRequest.post("/v1/server/update/apply"));
    }

    @Test
    void authenticated_check_passes_the_gate() {
        assertAuthenticatedPasses(MockServerHttpRequest.get("/v1/server/update/check"));
    }

    @Test
    void authenticated_apply_passes_the_gate() {
        assertAuthenticatedPasses(MockServerHttpRequest.post("/v1/server/update/apply"));
    }
}
