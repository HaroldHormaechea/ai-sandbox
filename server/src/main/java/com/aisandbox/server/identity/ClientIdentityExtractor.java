package com.aisandbox.server.identity;

import io.netty.channel.ChannelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * UC04 § B2 — canonical writer of the {@link #ATTR} Reactor exchange
 * attribute. Resolves the authenticated {@link ClientIdentity} via the
 * Netty {@link ChannelId} attached during the TLS handshake-completion
 * handler (see {@link com.aisandbox.server.tls.NettyServerCustomizer}),
 * stashes it on the exchange so downstream filters
 * ({@code MtlsEnforcementFilter}) and controllers can read it without a
 * second registry hop, and decorates the SLF4J MDC for log lines.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the
 * {@code MtlsEnforcementFilter} (which sits at
 * {@code HIGHEST_PRECEDENCE + 10}) sees the ATTR populated by this
 * filter on every request.
 *
 * <p>Pre-UC04 the channel-id was implicit and this filter merely
 * mirrored an already-stashed attribute. With the L7 mTLS gate this
 * filter becomes authoritative: the Reactor-Netty 1.2.x cast required
 * to reach the channel id from a {@link ServerHttpRequest} is isolated
 * in {@link #channelIdOf(ServerHttpRequest)}.
 *
 * <p>Disabled under {@code docs-only}: OAS rendering never has a
 * registry-attached identity and we don't want the ATTR-write path
 * silently masking what should be unauthenticated rendering.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("!docs-only")
public class ClientIdentityExtractor implements WebFilter {

    public static final String ATTR = "ai-sandbox.client-identity";

    private static final Logger LOG = LoggerFactory.getLogger(ClientIdentityExtractor.class);

    private final ActiveConnectionRegistry connections;

    public ClientIdentityExtractor(ActiveConnectionRegistry connections) {
        this.connections = connections;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 1. Honour a pre-stashed attribute (tests, future bypass paths).
        ClientIdentity identity = (ClientIdentity) exchange.getAttributes().get(ATTR);

        // 2. Otherwise resolve via the Netty channel id.
        if (identity == null) {
            ChannelId channelId = channelIdOf(exchange.getRequest());
            if (channelId != null) {
                identity = connections.identityFor(channelId);
            }
        }

        if (identity == null) {
            // No identity recorded — the MtlsEnforcementFilter will
            // decide whether this is OK (POST /v1/enrollment) or a
            // 401 mtls_required (everything else).
            return chain.filter(exchange);
        }

        exchange.getAttributes().put(ATTR, identity);
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

    /**
     * Reactor-Netty 1.2.x specific: walk
     * {@code ServerHttpRequest → AbstractServerHttpRequest.getNativeRequest()
     *  → HttpServerRequest → Connection → channel().id()}.
     *
     * <p>{@code getNativeRequest()} lives on
     * {@link AbstractServerHttpRequest}, not {@link ServerHttpRequest}
     * itself, so the cast is required. The Netty {@link io.netty.channel.Channel}
     * is reached via the {@link reactor.netty.Connection} supertype of
     * {@link reactor.netty.http.server.HttpServerRequest} —
     * {@code HttpServerRequest} no longer exposes {@code channel()}
     * directly in Reactor-Netty 1.2.x.
     *
     * <p>The casts are isolated here so the rest of the codebase keeps
     * a clean abstraction boundary. Returns {@code null} for non-
     * Reactor-Netty requests (mock test pipelines, MockServerHttpRequest)
     * — the caller falls back to a pre-stashed attribute in that case.
     */
    private static ChannelId channelIdOf(ServerHttpRequest req) {
        if (!(req instanceof AbstractServerHttpRequest abstractReq)) {
            return null;
        }
        try {
            Object nativeRequest = abstractReq.getNativeRequest();
            if (nativeRequest instanceof reactor.netty.Connection conn) {
                return conn.channel().id();
            }
        } catch (RuntimeException re) {
            LOG.trace("Cannot resolve Netty channel id from request: {}", re.toString());
        }
        return null;
    }
}
