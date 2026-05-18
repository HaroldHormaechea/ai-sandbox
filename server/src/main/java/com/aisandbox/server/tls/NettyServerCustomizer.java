package com.aisandbox.server.tls;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.pki.PemUtils;
import io.netty.channel.ChannelHandler;
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
                // Single source of truth for the listener's protocol set.
                //
                // v0.0.6 ships HTTP/1.1 only. HTTP/2 was attempted in v0.0.5/v0.0.6
                // development and dropped: under H2 the per-stream Http2StreamChannel
                // does not inherit the parent channel's IDENTITY_ATTR, so the L7
                // MtlsEnforcementFilter sees no client identity and rejects every
                // request with 401 mtls_required. Re-adding H2 needs (a) a different
                // cert-propagation mechanism (likely ServerHttpRequest.getSslInfo()
                // or a stream-channel-aware identity handler) AND (b) a JUnit-tier
                // mTLS-over-H2 test that exercises the dispatch end-to-end. Both are
                // scoped for v0.0.7.
                //
                // ALPN advertisements ("http/1.1") are owned by
                // ReloadableSslContextHolder.rebuild(); keep the two in sync.
                .protocol(HttpProtocol.HTTP11)
                .doOnChannelInit((observer, channel, address) -> {
                    // Install the rate-limit handler upstream of every
                    // codec — Reactor-Netty's HTTP/1.1 pipeline names:
                    // codec, ssl, reactor.left.httpTrafficHandler.
                    // (Pre-v0.0.6 also added "http2" between ssl and
                    // httpTrafficHandler when ALPN selected h2; that path
                    // is dormant in v0.0.6 — see the .protocol() comment above.)
                    channel.pipeline().addFirst("ai-sandbox-rate-limit", new RateLimitingChannelHandler(rateLimiter));
                    // Identity capture: when SslHandler completes the
                    // handshake successfully, build the ClientIdentity and
                    // store it on the channel + registry.
                    channel.pipeline().addLast("ai-sandbox-identity", new IdentityCapturingHandler(registry));
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
                ChannelHandler sslHandler = ctx.pipeline().get("ssl");
                if (sslHandler instanceof io.netty.handler.ssl.SslHandler ssl) {
                    SSLSession session = ssl.engine().getSession();
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
