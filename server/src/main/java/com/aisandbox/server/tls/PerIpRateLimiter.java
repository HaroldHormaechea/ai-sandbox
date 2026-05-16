package com.aisandbox.server.tls;

import com.aisandbox.server.config.ServerProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Token-bucket + concurrent-counter rate limiter applied per source IP at
 * the TCP layer, before the TLS handshake (AC16).
 *
 * <p>Two independent caps:
 *
 * <ul>
 *   <li><b>Rate</b> — N new connections per window-seconds, default 10/10s.</li>
 *   <li><b>Concurrent</b> — N simultaneous live connections per source IP,
 *       default 10.</li>
 * </ul>
 *
 * <p>Memory: one entry per source IP; entries are removed when the
 * concurrent counter reaches zero and the bucket has not refilled in the
 * last hour. The expected operational footprint is tens of clients, so an
 * aggressive cleanup policy is unnecessary.
 */
@Component
public class PerIpRateLimiter {

    private final int maxNew;
    private final long windowMillis;
    private final int maxConcurrent;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public PerIpRateLimiter(ServerProperties props) {
        ServerProperties.Limits l = props.limits();
        this.maxNew = l.perIpNewConnPerWindow();
        this.windowMillis = l.perIpWindowSeconds() * 1000L;
        this.maxConcurrent = l.perIpConcurrent();
    }

    /**
     * Attempt to reserve a connection slot for the given source IP. Returns
     * the bucket if accepted (caller MUST call {@link #release(String)}
     * when the connection closes); returns {@code null} if the limit is
     * tripped.
     */
    public boolean tryAcquire(String sourceIp) {
        Bucket b = buckets.computeIfAbsent(sourceIp, ip -> new Bucket());
        long now = System.currentTimeMillis();
        synchronized (b) {
            // Refill: drop entries older than the window.
            if (now - b.windowStart >= windowMillis) {
                b.windowStart = now;
                b.newInWindow = 0;
            }
            if (b.newInWindow >= maxNew) {
                return false;
            }
            if (b.concurrent.get() >= maxConcurrent) {
                return false;
            }
            b.newInWindow++;
            b.concurrent.incrementAndGet();
            return true;
        }
    }

    public void release(String sourceIp) {
        Bucket b = buckets.get(sourceIp);
        if (b == null) {
            return;
        }
        int v = b.concurrent.decrementAndGet();
        if (v <= 0 && System.currentTimeMillis() - b.windowStart > 3_600_000L) {
            buckets.remove(sourceIp, b);
        }
    }

    static final class Bucket {
        final AtomicInteger concurrent = new AtomicInteger(0);
        final AtomicLong creates = new AtomicLong(0);
        volatile long windowStart = System.currentTimeMillis();
        volatile int newInWindow = 0;
    }
}
