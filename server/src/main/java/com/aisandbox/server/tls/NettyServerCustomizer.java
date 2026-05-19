package com.aisandbox.server.tls;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.pki.PemUtils;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.netty.NettyPipeline;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * Wires the Reactor-Netty server with our custom TLS plumbing: pre-TLS
 * rate-limiter, server SslContext from {@link ReloadableSslContextHolder},
 * post-handshake identity capture.
 *
 * <p>Disabled under {@code docs-only} — the OAS render does not need a TLS
 * port.
 */
@Component
@Profile("!docs-only")
public class NettyServerCustomizer implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {

    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCustomizer.class);

    private final ServerProperties props;
    private final ReloadableSslContextHolder sslHolder;
    private final PerIpRateLimiter rateLimiter;
    private final ActiveConnectionRegistry registry;

    public NettyServerCustomizer(
            ServerProperties props,
            ReloadableSslContextHolder sslHolder,
            PerIpRateLimiter rateLimiter,
            ActiveConnectionRegistry registry) {
        this.props = props;
        this.sslHolder = sslHolder;
        this.rateLimiter = rateLimiter;
        this.registry = registry;
    }

    @Override
    public void customize(NettyReactiveWebServerFactory factory) {
        factory.setPort(props.tls().port());
        factory.addServerCustomizers(this::applyTls);
    }

    private HttpServer applyTls(HttpServer server) {
        return server.host(props.tls().bindAddress())
                // UC07 § AC2/AC3 — v0.0.8 re-enables HTTP/2 over TLS.
                //
                // Single source of truth for the listener's protocol set. ALPN
                // advertisements ("h2", "http/1.1") are owned by
                // ReloadableSslContextHolder.rebuild(); keep the two in sync.
                //
                // The mTLS-over-H2 fix.
                // ---------------------
                // Under HTTP/2, Reactor Netty splits a TLS connection into a
                // parent channel (carrying the SslHandler + handshake) and one
                // Http2StreamChannel per request stream. The parent channel is
                // where IdentityCapturingHandler attaches the authenticated
                // ClientIdentity to ActiveConnectionRegistry on
                // SslHandshakeCompletionEvent. Per-stream channels do NOT
                // inherit the parent's IDENTITY_ATTR — they have a fresh,
                // distinct ChannelId — so the v0.0.6 ClientIdentityExtractor
                // path (channel-id lookup against the registry) found nothing
                // and MtlsEnforcementFilter 401-ed every H2 request.
                //
                // The fix lives in ClientIdentityExtractor#channelIdOf: when
                // the resolved Reactor Netty Channel is an Http2StreamChannel,
                // walk to its parent() and use that channel's id. The parent
                // is the TLS-bearing connection channel and the value matches
                // what IdentityCapturingHandler already keyed the registry by.
                // Bytecode precedent for the parent-walk pattern:
                // HttpServerConfig.configureH2Pipeline and
                // ReactorServerHttpRequest.initSslInfo both walk to the parent
                // channel for SSL-state recovery on stream channels in
                // Reactor Netty / Spring WebFlux.
                //
                // WS-over-H2 (the upgraded WebSocket transport for the stream
                // endpoint) is deferred. SessionStreamHandler#channelIdOf
                // currently keys by the WebSocket session's channel id and
                // works for the H1.1-upgrade path that production still uses;
                // an H2-native WebSocket (RFC 8441 :protocol pseudo-header)
                // would need the same parent-walk treatment — see the inline
                // comment block on that method for the full plan.
                //
                // Safety cap.
                // ----------
                // .http2Settings(maxConcurrentStreams = 10): bounds the number
                // of in-flight streams per H2 connection. Reactor Netty's
                // default is Integer.MAX_VALUE, which together with the per-IP
                // rate limiter would let a single peer monopolize the listener
                // with thousands of concurrent streams. The cap is the
                // brief-declared safety bound; CI's release-install-smoke
                // asserts the negotiated value as a regression guard.
                .protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
                .http2Settings(spec -> spec.maxConcurrentStreams(10))
                .doOnChannelInit((observer, channel, address) -> {
                    // Install the rate-limit handler upstream of the SSL handler (rawest
                    // position) and the identity-capture handler immediately BEFORE
                    // reactor.right.reactiveBridge. Reactor Netty's ChannelOperationsHandler
                    // (the bridge) is installed at the pipeline tail via addLast and
                    // consumes user events (releases SslHandshakeCompletionEvent without
                    // propagating to userland), so our handler MUST sit before it to
                    // receive the SSL completion event. The SslHandler is also referenced
                    // by class (not by name) so the lookup survives Reactor Netty version
                    // bumps that rename pipeline positions.
                    channel.pipeline().addFirst("ai-sandbox-rate-limit", new RateLimitingChannelHandler(rateLimiter));
                    channel.pipeline()
                            .addBefore(
                                    NettyPipeline.ReactiveBridge,
                                    "ai-sandbox-identity",
                                    new IdentityCapturingHandler(registry));
                })
                // Reactor-Netty's secure() integrates an SslContext into
                // its pipeline at the right phase (we don't have to wire
                // SslHandler manually).
                .secure(spec -> spec.sslContext(sslHolder.current()));
    }

    /**
     * Tail-of-pipeline handler that listens for
     * {@link SslHandshakeCompletionEvent}. UC04 § B2 flipped the server
     * to {@link io.netty.handler.ssl.ClientAuth#OPTIONAL}, so the
     * completion event no longer guarantees a peer cert. Three outcomes:
     *
     * <ol>
     *   <li><b>Success + peer cert</b> — compute fingerprint + CN and
     *       attach the {@link ClientIdentity} via {@code registry.attach}.</li>
     *   <li><b>Success + no peer cert</b> — call
     *       {@code registry.attachAnonymous} so HTTP filters see
     *       {@link ClientIdentity#ANONYMOUS} instead of a null gap.
     *       {@code SSLSession.getPeerCertificates()} throws
     *       {@code SSLPeerUnverifiedException} when no cert was presented;
     *       we catch + treat as anonymous.</li>
     *   <li><b>Failure</b> — Netty has already torn the channel down; we
     *       leave the registry untouched.</li>
     * </ol>
     */
    static final class IdentityCapturingHandler extends io.netty.channel.ChannelInboundHandlerAdapter {

        private final ActiveConnectionRegistry registry;

        IdentityCapturingHandler(ActiveConnectionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof SslHandshakeCompletionEvent he && he.isSuccess()) {
                io.netty.handler.ssl.SslHandler sslHandler = ctx.pipeline().get(io.netty.handler.ssl.SslHandler.class);
                if (sslHandler == null) {
                    LOG.warn(
                            "SslHandshakeCompletionEvent received but no SslHandler in pipeline — TLS pipeline misconfigured");
                }
                if (sslHandler != null) {
                    SSLSession session = sslHandler.engine().getSession();
                    try {
                        Certificate[] peers = session.getPeerCertificates();
                        if (peers != null && peers.length > 0 && peers[0] instanceof X509Certificate leaf) {
                            String fp = PemUtils.fingerprintHex(leaf);
                            String cn = PemUtils.extractCommonName(leaf);
                            ClientIdentity id = new ClientIdentity(cn, fp, leaf.getSerialNumber());
                            registry.attach(ctx.channel(), fp, id);
                        } else {
                            registry.attachAnonymous(ctx.channel());
                        }
                    } catch (javax.net.ssl.SSLException sslEx) {
                        // UC04 § B2 — covers SSLPeerUnverifiedException
                        // (no client cert presented; the OPTIONAL
                        // clientAuth path) AND any broader handshake
                        // anomaly. Either way the connection completed
                        // anonymously; MtlsEnforcementFilter rejects
                        // every path except /v1/enrollment.
                        if (!(sslEx instanceof javax.net.ssl.SSLPeerUnverifiedException)) {
                            LOG.warn(
                                    "Non-peer-unverified SSLException post-handshake; treating channel as"
                                            + " anonymous: {}",
                                    sslEx.toString());
                        }
                        registry.attachAnonymous(ctx.channel());
                    } catch (CertificateException ce) {
                        LOG.warn("Cannot extract peer identity from completed TLS session: {}", ce.toString());
                        registry.attachAnonymous(ctx.channel());
                    }
                }
            }
            super.userEventTriggered(ctx, evt);
        }
    }
}
