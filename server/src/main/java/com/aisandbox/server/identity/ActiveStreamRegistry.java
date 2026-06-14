package com.aisandbox.server.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Live WebSocket sessions indexed by client fingerprint, so the
 * {@link ActiveConnectionRegistry#revoke(Set)} path can issue a graceful
 * WS close (with a custom {@link CloseStatus}) before tearing down the
 * underlying TLS channel.
 *
 * <p>Without this, a cert removal would yank the TCP connection out from
 * under the client with no chance to communicate a close code. UC04
 * AC26 says the Android app must surface the cert-revoked dialog when
 * the server tears the WS down — that requires the close frame to land,
 * which in turn requires a graceful close happen <em>before</em> the
 * TCP RST. UC04 § B2 wires the registries in pairs:
 *
 * <ol>
 *   <li>{@link SessionStreamHandler} attaches the session here at upgrade
 *       and detaches on close.</li>
 *   <li>{@link ActiveConnectionRegistry#revoke} calls
 *       {@link #gracefulClose(String, CloseStatus)} first, waits up to
 *       100 ms for the close handshake, then proceeds to
 *       {@link ActiveConnectionRegistry#terminate} which closes the
 *       underlying Netty channel.</li>
 * </ol>
 *
 * <p>Memory footprint: one entry per active stream; entries are removed
 * on detach. Concurrent operations use a {@link ConcurrentHashMap} +
 * synchronized inner-set blocks — the contention domain is "all live
 * streams for one fingerprint" which is typically 1–N (per-client cap
 * is 10 by default).
 */
@Component
public class ActiveStreamRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ActiveStreamRegistry.class);

    private final Map<String, Set<WebSocketSession>> byFingerprint = new ConcurrentHashMap<>();

    /** Index a freshly-upgraded WS session against its client fingerprint. */
    public void attach(String fingerprintHex, WebSocketSession session) {
        if (fingerprintHex == null || fingerprintHex.isEmpty() || session == null) {
            return;
        }
        byFingerprint
                .computeIfAbsent(fingerprintHex, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                .add(session);
    }

    /** Remove a session — typically called from the handler's doFinally. */
    public void detach(String fingerprintHex, WebSocketSession session) {
        if (fingerprintHex == null || fingerprintHex.isEmpty()) {
            return;
        }
        Set<WebSocketSession> set = byFingerprint.get(fingerprintHex);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            byFingerprint.remove(fingerprintHex, set);
        }
    }

    /**
     * Issue a graceful close to every live WS session for {@code
     * fingerprintHex}. Returns a {@link Mono} that completes when each
     * underlying close-handshake Mono completes (or errors out).
     *
     * <p>The caller — {@link ActiveConnectionRegistry#revoke(Set)} — pairs
     * this with a bounded timeout so a non-responsive client cannot
     * delay the TCP-layer revocation forever.
     */
    public Mono<Void> gracefulClose(String fingerprintHex, CloseStatus status) {
        if (fingerprintHex == null || fingerprintHex.isEmpty()) {
            return Mono.empty();
        }
        Set<WebSocketSession> set = byFingerprint.remove(fingerprintHex);
        if (set == null || set.isEmpty()) {
            return Mono.empty();
        }
        // Snapshot to avoid holding the set's monitor while we issue closes.
        Set<WebSocketSession> snapshot;
        synchronized (set) {
            snapshot = new HashSet<>(set);
        }
        LOG.info(
                "Gracefully closing {} WS session(s) for revoked fingerprint {} with code {}",
                snapshot.size(),
                fingerprintHex,
                status.getCode());
        return Flux.fromIterable(snapshot)
                .flatMap(s -> s.close(status).onErrorResume(t -> {
                    LOG.warn("Graceful close errored: {}", t.toString());
                    return Mono.empty();
                }))
                .then();
    }

    /** Visible-for-testing — snapshot count. */
    public int sessionCountFor(String fingerprintHex) {
        Set<WebSocketSession> set = byFingerprint.get(fingerprintHex);
        return set == null ? 0 : set.size();
    }

    /**
     * UC-74 — issue a graceful close to EVERY live WS session across all
     * fingerprints, concurrently. This registry is the superset of all live
     * sockets (binary streams AND the structured-conversation channel, which
     * registers here only), so {@code GracefulShutdownHandler.stop()} calls
     * this as the drain safety-net after the {@code StreamRegistryService}
     * pass that carries the UC-44 {@code STREAM_CLOSE} text-frame ceremony.
     *
     * <p>Closes fan out via {@code Flux…flatMap} so the returned {@link Mono}
     * completes when the slowest single close-handshake completes — NOT the
     * serial sum. The caller bounds the whole composite with the configured
     * total-grace deadline, so a non-responsive client cannot pin shutdown.
     *
     * <p>Idempotent against the cert-revocation path: a session already closed
     * by {@link #gracefulClose} (or removed from the index) is simply absent
     * here, and a second {@code WebSocketSession.close} on an already-closed
     * session is a no-op.
     */
    public Mono<Void> gracefulCloseAll(CloseStatus status) {
        List<WebSocketSession> all = new ArrayList<>();
        for (String fp : new HashSet<>(byFingerprint.keySet())) {
            Set<WebSocketSession> set = byFingerprint.remove(fp);
            if (set == null) {
                continue;
            }
            synchronized (set) {
                all.addAll(set);
            }
        }
        if (all.isEmpty()) {
            return Mono.empty();
        }
        LOG.info("Gracefully closing {} live WS session(s) on shutdown with code {}", all.size(), status.getCode());
        return Flux.fromIterable(all)
                .flatMap(s -> s.close(status).onErrorResume(t -> {
                    LOG.warn("Shutdown WS close errored: {}", t.toString());
                    return Mono.empty();
                }))
                .then();
    }

    /** UC-74 — total live WS sessions across every fingerprint. */
    public int totalLiveSessions() {
        return byFingerprint.values().stream().mapToInt(Set::size).sum();
    }
}
