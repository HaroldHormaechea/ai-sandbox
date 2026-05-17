package com.aisandbox.server.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * UC04 § B2 — canonical writer of the {@link ClientIdentityExtractor#ATTR}
 * exchange attribute.
 *
 * <p>Two paths are unit-friendly under {@link MockServerHttpRequest}:
 *
 * <ul>
 *   <li>Pre-stashed attribute — the filter honours a value the test (or
 *       a future bypass filter) put on the exchange and propagates it.</li>
 *   <li>No identity available — the filter passes the chain through
 *       without setting the attribute, leaving the {@code MtlsEnforcementFilter}
 *       to decide whether the path is mTLS-exempt.</li>
 * </ul>
 *
 * <p>The Reactor-Netty {@code channelIdOf(...)} resolution path requires
 * a real {@code AbstractServerHttpRequest} wrapping a {@code reactor.netty.Connection}
 * and is therefore exercised at the integration tier (StreamHandshakeIT)
 * — not here. Coverage gap is explicit in the TEST SUMMARY.
 */
class ClientIdentityExtractorTest {

    private static final ClientIdentity REAL = new ClientIdentity("alice-phone", "fa".repeat(32), BigInteger.ONE);

    private static WebFilterChain capturingChain(java.util.concurrent.atomic.AtomicReference<ServerWebExchange> seen) {
        return ex -> {
            seen.set(ex);
            return Mono.empty();
        };
    }

    @Test
    void pre_stashed_attribute_is_preserved_and_passed_through() {
        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);
        ClientIdentityExtractor filter = new ClientIdentityExtractor(connections);

        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, REAL);

        java.util.concurrent.atomic.AtomicReference<ServerWebExchange> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        filter.filter(ex, capturingChain(seen)).block();

        // The chain saw the identity on the attribute key.
        Object readBack = seen.get().getAttributes().get(ClientIdentityExtractor.ATTR);
        assertThat(readBack).isSameAs(REAL);
    }

    @Test
    void no_identity_and_no_native_channel_passes_through_without_writing_attr() {
        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);
        ClientIdentityExtractor filter = new ClientIdentityExtractor(connections);

        // MockServerHttpRequest is not an AbstractServerHttpRequest holding
        // a reactor.netty.Connection, so the channel-id resolver returns
        // null. With no pre-stashed ATTR either, the filter must pass
        // the chain through cleanly — MtlsEnforcementFilter (next in
        // ordering) is responsible for deciding what to do.
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/healthz"));

        java.util.concurrent.atomic.AtomicReference<ServerWebExchange> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        filter.filter(ex, capturingChain(seen)).block();

        assertThat(seen.get()).isNotNull();
        // ATTR must not be set silently — that would leak a phantom anonymous
        // identity past the mTLS gate.
        assertThat(seen.get().getAttributes().get(ClientIdentityExtractor.ATTR)).isNull();
    }

    @Test
    void mdc_is_populated_while_chain_runs_and_cleaned_up_after() {
        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);
        ClientIdentityExtractor filter = new ClientIdentityExtractor(connections);

        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/sessions"));
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, REAL);

        // Capture MDC values from inside the chain.
        java.util.concurrent.atomic.AtomicReference<String> identityDuring =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> fingerprintDuring =
                new java.util.concurrent.atomic.AtomicReference<>();
        WebFilterChain chain = e -> Mono.fromRunnable(() -> {
            identityDuring.set(MDC.get("identity"));
            fingerprintDuring.set(MDC.get("fingerprint"));
        });

        filter.filter(ex, chain).block();

        assertThat(identityDuring.get()).isEqualTo("alice-phone");
        assertThat(fingerprintDuring.get()).isEqualTo("fa".repeat(32));
        // After the chain settles, MDC is wiped.
        assertThat(MDC.get("identity")).isNull();
        assertThat(MDC.get("fingerprint")).isNull();
    }

    @Test
    void anonymous_pre_stashed_is_also_propagated() {
        // The extractor doesn't make policy decisions; it just stashes /
        // propagates whatever identity is recorded. MtlsEnforcementFilter
        // is the policy seat (rejects anonymous on every non-/v1/enrollment
        // path). Pinning the propagation here keeps the contract crisp.
        ActiveConnectionRegistry connections = mock(ActiveConnectionRegistry.class);
        ClientIdentityExtractor filter = new ClientIdentityExtractor(connections);

        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/enrollment"));
        ex.getAttributes().put(ClientIdentityExtractor.ATTR, ClientIdentity.ANONYMOUS);

        java.util.concurrent.atomic.AtomicReference<ServerWebExchange> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        filter.filter(ex, capturingChain(seen)).block();

        Object readBack = seen.get().getAttributes().get(ClientIdentityExtractor.ATTR);
        assertThat(readBack).isInstanceOf(ClientIdentity.class);
        assertThat(((ClientIdentity) readBack).isAnonymous()).isTrue();
    }
}
