package com.aisandbox.server.tls;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty {@link io.netty.channel.ChannelInboundHandler} installed upstream
 * of the {@code SslHandler}. Tries the per-IP rate limiter on
 * {@code channelActive}; closes the channel before any TLS bytes are
 * exchanged when the cap is tripped.
 *
 * <p>On acceptance the handler removes itself from the pipeline — every
 * subsequent read flows directly into TLS. The release is wired via
 * {@link io.netty.channel.Channel#closeFuture()} to fire exactly once.
 */
public class RateLimitingChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitingChannelHandler.class);

    private final PerIpRateLimiter limiter;

    public RateLimitingChannelHandler(PerIpRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketAddress remote = ctx.channel().remoteAddress();
        String ip = (remote instanceof InetSocketAddress isa)
                ? isa.getAddress().getHostAddress()
                : (remote == null ? "unknown" : remote.toString());
        if (!limiter.tryAcquire(ip)) {
            LOG.info("Rate-limit reject ip={}", ip);
            ctx.close();
            return;
        }
        // Release on close.
        ctx.channel().closeFuture().addListener(f -> limiter.release(ip));
        // Self-removal — TLS bytes follow directly.
        ctx.pipeline().remove(this);
        super.channelActive(ctx);
    }
}
