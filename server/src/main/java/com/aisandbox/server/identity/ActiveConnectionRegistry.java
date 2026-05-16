package com.aisandbox.server.identity;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks every live TLS connection by client-cert fingerprint so that, when
 * {@code AllowlistWatcher} sees a cert removed, we can synchronously close
 * the still-attached channels (AC13).
 *
 * <p>Connection lifecycle:
 *
 * <ol>
 *   <li>TLS handshake completes → {@code NettyServerCustomizer}'s
 *       {@link io.netty.handler.ssl.SslHandshakeCompletionEvent} listener
 *       calls {@link #attach(Channel, String, ClientIdentity)}.</li>
 *   <li>Channel goes inactive → the same listener (or the registered
 *       {@code channelInactive} hook) calls {@link #detach(Channel, String)}.</li>
 *   <li>Watcher detects a removed fingerprint →
 *       {@link #terminate(Set)} iterates and closes channels.</li>
 * </ol>
 *
 * <p>{@link ClientIdentity} is also stored on the channel as a Netty
 * attribute so HTTP/WebSocket filters can recover it without a registry
 * lookup.
 */
@Component
public class ActiveConnectionRegistry {

    public static final AttributeKey<ClientIdentity> IDENTITY_ATTR = AttributeKey.valueOf("ai-sandbox.client-identity");

    private static final Logger LOG = LoggerFactory.getLogger(ActiveConnectionRegistry.class);

    private final Map<String, Set<Channel>> byFingerprint = new ConcurrentHashMap<>();

    public void attach(Channel channel, String fingerprintHex, ClientIdentity identity) {
        channel.attr(IDENTITY_ATTR).set(identity);
        byFingerprint
                .computeIfAbsent(fingerprintHex, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(channel);
        channel.closeFuture().addListener(f -> detach(channel, fingerprintHex));
    }

    public void detach(Channel channel, String fingerprintHex) {
        Set<Channel> set = byFingerprint.get(fingerprintHex);
        if (set != null) {
            set.remove(channel);
            if (set.isEmpty()) {
                byFingerprint.remove(fingerprintHex, set);
            }
        }
    }

    /**
     * Close every active channel whose fingerprint appears in {@code revoked}.
     * Closure is fire-and-forget; callers should not block on it.
     */
    public void terminate(Set<String> revoked) {
        for (String fp : revoked) {
            Set<Channel> channels = byFingerprint.remove(fp);
            if (channels == null) {
                continue;
            }
            int n = 0;
            for (Channel c : channels) {
                if (c.isActive()) {
                    c.close();
                    n++;
                }
            }
            if (n > 0) {
                LOG.info("Closed {} channels for revoked fingerprint {}", n, fp);
            }
        }
    }

    public int activeConnectionsFor(String fingerprintHex) {
        Set<Channel> set = byFingerprint.get(fingerprintHex);
        return set == null ? 0 : set.size();
    }

    public int totalActiveConnections() {
        return byFingerprint.values().stream().mapToInt(Set::size).sum();
    }
}
