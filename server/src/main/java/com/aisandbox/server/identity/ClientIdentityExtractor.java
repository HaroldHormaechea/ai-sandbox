package com.aisandbox.server.identity;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reads the {@link ClientIdentity} previously stashed on the request by
 * the Netty-side handshake handler and republishes it as a Reactor
 * exchange attribute plus an SLF4J MDC entry — so log lines carry
 * {@code identity=}/{@code fingerprint=} even for REST work that never
 * touches the registry directly.
 *
 * <p>Runs as the outermost {@link WebFilter}. If the channel attribute is
 * missing (the TLS plumbing never recorded an identity, e.g. during
 * docs-only render), the filter passes through untouched — security is
 * still enforced by {@code AllowlistTrustManager} at the TLS layer.
 */
@Component
public class ClientIdentityExtractor implements WebFilter {

    public static final String ATTR = "ai-sandbox.client-identity";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ClientIdentity identity = (ClientIdentity) exchange.getAttributes().get(ATTR);
        if (identity == null) {
            return chain.filter(exchange);
        }
        final ClientIdentity captured = identity;
        return chain.filter(exchange)
                .doOnSubscribe(s -> {
                    MDC.put("identity", captured.cn());
                    MDC.put("fingerprint", captured.fingerprintHex());
                })
                .doFinally(sig -> {
                    MDC.remove("identity");
                    MDC.remove("fingerprint");
                });
    }
}
