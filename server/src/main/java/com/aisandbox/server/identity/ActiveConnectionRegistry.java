package com.aisandbox.server.identity;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.AttributeKey;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks every live TLS connection so that:
 *
 * <ul>
 *   <li>The {@code AllowlistWatcher} can synchronously close still-attached
 *       channels when a cert is removed (AC13) — keyed by SHA-256
 *       fingerprint.</li>
 *   <li>The WebSocket layer can recover the authenticated
 *       {@link ClientIdentity} of an upgrade request — keyed by
 *       {@link ChannelId}. Reactor-Netty's {@code WebSocketSession}
 *       exposes only the channel id (not the channel itself), so we keep
 *       a second index here that the stream handler can read.</li>
 * </ul>
 *
 * <p>Connection lifecycle:
 *
 * <ol>
 *   <li>TLS handshake completes → {@code NettyServerCustomizer}'s
 *       {@link io.netty.handler.ssl.SslHandshakeCompletionEvent} listener
 *       calls {@link #attach(Channel, String, ClientIdentity)}, which
 *       writes both indexes.</li>
 *   <li>Channel goes inactive → the {@code closeFuture} listener calls
 *       {@link #detach(Channel, String)}, which removes from both
 *       indexes.</li>
 *   <li>Watcher detects a removed fingerprint →
 *       {@link #terminate(Set)} iterates and closes channels.</li>
 * </ol>
 *
 * <p>{@link ClientIdentity} is also stored on the channel as a Netty
 * attribute so HTTP filters can recover it without a registry lookup.
 */
@Component
public class ActiveConnectionRegistry {

    public static final AttributeKey<ClientIdentity> IDENTITY_ATTR = AttributeKey.valueOf("ai-sandbox.client-identity");

    private static final Logger LOG = LoggerFactory.getLogger(ActiveConnectionRegistry.class);

    private final Map<String, Set<Channel>> byFingerprint = new ConcurrentHashMap<>();
    private final Map<ChannelId, ClientIdentity> byChannelId = new ConcurrentHashMap<>();

    public void attach(Channel channel, String fingerprintHex, ClientIdentity identity) {
        channel.attr(IDENTITY_ATTR).set(identity);
        byFingerprint
                .computeIfAbsent(fingerprintHex, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(channel);
        byChannelId.put(channel.id(), identity);
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
        byChannelId.remove(channel.id());
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
                    byChannelId.remove(c.id());
                    c.close();
                    n++;
                }
            }
            if (n > 0) {
                LOG.info("Closed {} channels for revoked fingerprint {}", n, fp);
            }
        }
    }

    /**
     * Look up the authenticated identity by Netty channel id. Returns
     * {@code null} when no identity is recorded — typically because the
     * TLS handshake-completion handler has not yet fired, or because the
     * channel has already detached.
     */
    public ClientIdentity identityFor(ChannelId channelId) {
        return byChannelId.get(channelId);
    }

    public int activeConnectionsFor(String fingerprintHex) {
        Set<Channel> set = byFingerprint.get(fingerprintHex);
        return set == null ? 0 : set.size();
    }

    public int totalActiveConnections() {
        return byFingerprint.values().stream().mapToInt(Set::size).sum();
    }
}
