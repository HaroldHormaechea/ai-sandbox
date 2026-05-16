package com.aisandbox.server.stream.service;

import com.aisandbox.server.config.ServerProperties;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Live-stream bookkeeping. Tracks every open WebSocket so the facade
 * can enforce caps (per-cert, global), the keepalive can iterate, and
 * the idle-timeout sweeper can close zombies (AC28).
 */
@Service
public class StreamRegistryService {

    public record StreamId(String value) {
        public static StreamId fresh() {
            return new StreamId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        }
    }

    public static final class ActiveStream {
        public final StreamId id;
        public final int n;
        public final String fingerprintHex;
        public final WebSocketSession session;
        public volatile Instant lastIo;

        public ActiveStream(StreamId id, int n, String fingerprintHex, WebSocketSession session) {
            this.id = id;
            this.n = n;
            this.fingerprintHex = fingerprintHex;
            this.session = session;
            this.lastIo = Instant.now();
        }
    }

    private final ServerProperties props;
    private final Map<StreamId, ActiveStream> streams = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perFingerprint = new ConcurrentHashMap<>();
    private final AtomicInteger global = new AtomicInteger(0);

    public StreamRegistryService(ServerProperties props) {
        this.props = props;
    }

    public boolean register(ActiveStream stream) {
        if (global.get() >= props.streams().globalCap()) {
            return false;
        }
        AtomicInteger perFp = perFingerprint.computeIfAbsent(stream.fingerprintHex, k -> new AtomicInteger(0));
        if (perFp.get() >= props.streams().perClientCap()) {
            return false;
        }
        streams.put(stream.id, stream);
        perFp.incrementAndGet();
        global.incrementAndGet();
        return true;
    }

    public void unregister(StreamId id) {
        ActiveStream s = streams.remove(id);
        if (s != null) {
            AtomicInteger perFp = perFingerprint.get(s.fingerprintHex);
            if (perFp != null && perFp.decrementAndGet() <= 0) {
                perFingerprint.remove(s.fingerprintHex, perFp);
            }
            global.decrementAndGet();
        }
    }

    public int countFor(String fingerprintHex) {
        AtomicInteger c = perFingerprint.get(fingerprintHex);
        return c == null ? 0 : c.get();
    }

    public int globalCount() {
        return global.get();
    }

    public Iterable<ActiveStream> snapshot() {
        return Map.copyOf(streams).values();
    }

    /**
     * Refresh the {@code lastIo} watermark for a live stream. Called by
     * {@code SessionStreamHandler} on every binary frame in / out and on
     * every text-frame control message, so the idle-timeout sweeper
     * measures elapsed time since last real activity rather than since
     * stream open (AC28).
     */
    public void touch(StreamId id) {
        ActiveStream s = streams.get(id);
        if (s != null) {
            s.lastIo = Instant.now();
        }
    }

    /** Idle-timeout sweeper; fires every 30 s. */
    @Scheduled(fixedDelay = 30_000L)
    public void sweep() {
        long idleMs = props.streams().idleTimeoutSeconds() * 1000L;
        long deadline = System.currentTimeMillis() - idleMs;
        for (var entry : Map.copyOf(streams).entrySet()) {
            ActiveStream s = entry.getValue();
            if (s.lastIo.toEpochMilli() < deadline) {
                s.session.close().subscribe();
                unregister(entry.getKey());
            }
        }
    }
}
