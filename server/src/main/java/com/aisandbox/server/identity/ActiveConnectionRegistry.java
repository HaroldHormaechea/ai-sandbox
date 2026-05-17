package com.aisandbox.server.identity;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.AttributeKey;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Mono;

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
 *   <li>TLS handshake completes successfully →
 *       {@code NettyServerCustomizer}'s
 *       {@link io.netty.handler.ssl.SslHandshakeCompletionEvent} listener
 *       calls {@link #attach(Channel, String, ClientIdentity)}, which
 *       writes both indexes.</li>
 *   <li>TLS handshake completes without a peer cert (UC04 § B2:
 *       {@link io.netty.handler.ssl.ClientAuth#OPTIONAL} is the new
 *       default) → the listener catches
 *       {@code SSLPeerUnverifiedException} / {@code SSLException} and
 *       calls {@link #attachAnonymous(Channel)} instead.</li>
 *   <li>Channel goes inactive → the {@code closeFuture} listener calls
 *       {@link #detach(Channel, String)}, which removes from both
 *       indexes.</li>
 *   <li>Allowlist watcher / facade detects a removed fingerprint →
 *       {@link #revoke(Set)} — the new single orchestration entry point
 *       (UC04 § B2). It issues a graceful WS close via
 *       {@link ActiveStreamRegistry} first (so the Android client sees
 *       the AC26 close frame), waits up to
 *       {@link #REVOKE_GRACEFUL_CLOSE_TIMEOUT}, then falls through to
 *       {@link #terminate(Set)} which closes the underlying Netty
 *       channel.</li>
 * </ol>
 *
 * <p>{@link ClientIdentity} is also stored on the channel as a Netty
 * attribute so HTTP filters can recover it without a registry lookup.
 */
@Component
public class ActiveConnectionRegistry {

    public static final AttributeKey<ClientIdentity> IDENTITY_ATTR = AttributeKey.valueOf("ai-sandbox.client-identity");

    /**
     * Bounded time the graceful-close path will wait for the client to
     * acknowledge the WS close frame before tearing down the channel.
     * UC04 § B2 specifies 100 ms; small enough that AC13's overall
     * 1-second budget for "connection terminated within 1 s" is met
     * even when 10 streams have to close in series.
     */
    static final Duration REVOKE_GRACEFUL_CLOSE_TIMEOUT = Duration.ofMillis(100);

    /**
     * UC04 close-code used when revocation tears the WS down. 4401 is
     * a private-use code (per RFC 6455) so the Android client can
     * distinguish "cert revoked" from a generic 1011/1008 close.
     */
    public static final int REVOKED_CLOSE_CODE = 4401;

    /** Reason phrase on the close frame for revocation. */
    public static final String REVOKED_CLOSE_REASON = "revoked";

    private static final Logger LOG = LoggerFactory.getLogger(ActiveConnectionRegistry.class);

    private final Map<String, Set<Channel>> byFingerprint = new ConcurrentHashMap<>();
    private final Map<ChannelId, ClientIdentity> byChannelId = new ConcurrentHashMap<>();

    /**
     * Optional collaborator — wired by Spring on production startup. Tests
     * that mock {@link ActiveConnectionRegistry} via Mockito don't need
     * this dependency at all; tests that construct the registry directly
     * can leave it null and {@link #revoke(Set)} will skip the graceful-
     * close step and proceed straight to {@link #terminate(Set)}.
     */
    private volatile ActiveStreamRegistry streamRegistry;

    @Autowired(required = false)
    public void setStreamRegistry(ActiveStreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    public void attach(Channel channel, String fingerprintHex, ClientIdentity identity) {
        channel.attr(IDENTITY_ATTR).set(identity);
        byFingerprint
                .computeIfAbsent(fingerprintHex, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(channel);
        byChannelId.put(channel.id(), identity);
        channel.closeFuture().addListener(f -> detach(channel, fingerprintHex));
    }

    /**
     * UC04 § B2 — record an anonymous (no-cert) TLS handshake. The
     * channel id maps to {@link ClientIdentity#ANONYMOUS} so HTTP
     * filters can later distinguish "no identity recorded yet" (null)
     * from "completed handshake without a cert" (anonymous).
     */
    public void attachAnonymous(Channel channel) {
        channel.attr(IDENTITY_ATTR).set(ClientIdentity.ANONYMOUS);
        byChannelId.put(channel.id(), ClientIdentity.ANONYMOUS);
        channel.closeFuture().addListener(f -> byChannelId.remove(channel.id()));
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
     * Single orchestration entry point for cert revocation (UC04 § B2).
     * Pairs a graceful WebSocket close (so the Android client sees
     * AC26's "Identity revoked" dialog via its close-frame handler) with
     * the existing TCP-layer tear-down.
     *
     * <ul>
     *   <li>For each revoked fingerprint, issue
     *       {@link ActiveStreamRegistry#gracefulClose(String, CloseStatus)}
     *       with code {@link #REVOKED_CLOSE_CODE}/{@link #REVOKED_CLOSE_REASON}.</li>
     *   <li>Block up to {@link #REVOKE_GRACEFUL_CLOSE_TIMEOUT} on the
     *       combined close-handshake Mono. A non-responsive client
     *       cannot delay the TCP-layer tear-down beyond this budget.</li>
     *   <li>Fall through to {@link #terminate(Set)} which closes the
     *       underlying Netty channels.</li>
     * </ul>
     *
     * <p>This replaces the prior practice of calling {@link #terminate}
     * directly from the {@link com.aisandbox.server.clients.facade.ClientAllowlistFacade}
     * and {@link com.aisandbox.server.clients.service.AllowlistWatcher} —
     * both now go through {@code revoke} so the orchestration lives in
     * one place. {@link #terminate(Set)} stays public for backwards
     * compatibility with existing tests; the proposal's "demote to
     * package-private" follow-up lands once QA has migrated those
     * tests.
     */
    public void revoke(Set<String> revoked) {
        if (revoked == null || revoked.isEmpty()) {
            return;
        }
        ActiveStreamRegistry sr = this.streamRegistry;
        if (sr != null) {
            CloseStatus status = new CloseStatus(REVOKED_CLOSE_CODE, REVOKED_CLOSE_REASON);
            for (String fp : revoked) {
                try {
                    sr.gracefulClose(fp, status)
                            .timeout(REVOKE_GRACEFUL_CLOSE_TIMEOUT)
                            .onErrorResume(t -> {
                                if (t instanceof java.util.concurrent.TimeoutException) {
                                    LOG.info(
                                            "Graceful close timed out after {} ms for revoked fingerprint {}",
                                            REVOKE_GRACEFUL_CLOSE_TIMEOUT.toMillis(),
                                            fp);
                                } else {
                                    LOG.warn("Graceful close error for {}: {}", fp, t.toString());
                                }
                                return Mono.empty();
                            })
                            .block(REVOKE_GRACEFUL_CLOSE_TIMEOUT.plusMillis(50));
                } catch (RuntimeException re) {
                    // Reactor's block may bubble an unrecoverable error;
                    // we still want to fall through to terminate().
                    LOG.warn("Graceful close uncaught for {}: {}", fp, re.toString());
                }
            }
        }
        terminate(revoked);
    }

    /**
     * Close every active channel whose fingerprint appears in {@code revoked}.
     * Closure is fire-and-forget; callers should not block on it.
     *
     * <p>Prefer {@link #revoke(Set)} for new code — {@code terminate}
     * skips the AC26 graceful WS close and rip-cords straight to the
     * TCP layer. UC04 § B2 marks this method for demotion to
     * package-private once test code is migrated.
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
