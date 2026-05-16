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
                .doOnChannelInit((observer, channel, address) -> {
                    // Install the rate-limit handler upstream of every
                    // codec — Reactor-Netty's pipeline names: codec, ssl,
                    // http2 (when negotiated), reactor.left.httpTrafficHandler.
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
     * {@link SslHandshakeCompletionEvent} and, on success, computes
     * fingerprint + CN from the peer cert and hands them to the registry.
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
                        }
                    } catch (CertificateException ce) {
                        LOG.warn("Cannot extract peer identity from completed TLS session: {}", ce.toString());
                    }
                }
            }
            super.userEventTriggered(ctx, evt);
        }
    }
}
