package com.aisandbox.server.enrollment.service;

import com.aisandbox.server.config.ServerProperties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Per-source-IP token-bucket rate limiter applied at the
 * {@code POST /v1/enrollment} controller boundary (UC04 AC34).
 *
 * <p>Default policy: {@value #DEFAULT_PER_WINDOW} redemption(s) per
 * {@value #DEFAULT_WINDOW_SECONDS} s. Both knobs are overridable from
 * {@link ServerProperties.Enrollment} so an operator behind a NAT can
 * relax the cap if every Android client on the network shares an
 * external address.
 *
 * <p>Independent from {@code com.aisandbox.server.tls.PerIpRateLimiter}
 * — that one fires at the TCP layer (before TLS) and counts all new
 * connections; this one fires at the HTTP layer and counts only
 * <em>redemption attempts</em>. A burst of healthy GETs to other paths
 * does NOT consume an enrollment slot; conversely, exhausting the
 * enrollment cap does not affect normal-traffic rate limits.
 */
@Service
public class EnrollmentRateLimiterService {

    /** Default per-window redemption cap (AC34). */
    public static final int DEFAULT_PER_WINDOW = 1;

    /** Default window length in seconds (AC34). */
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    private final int perWindow;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public EnrollmentRateLimiterService(ServerProperties props) {
        this.perWindow = props.enrollment().rateLimitPerWindow();
        this.windowMillis = props.enrollment().rateLimitWindowSeconds() * 1000L;
    }

    /**
     * Reserve a redemption slot for the given source IP. Returns
     * {@code true} if the request is admitted, {@code false} if the cap
     * has tripped — the caller MUST then return a {@code 429
     * enrollment_rate_limited} ProblemDetails response.
     *
     * <p>The bucket is incremented on the admit path; there is no
     * matching {@code release} because enrollment is a single shot — the
     * counter naturally decays as the window rolls over.
     */
    public boolean tryAcquire(String sourceIp) {
        if (sourceIp == null || sourceIp.isEmpty()) {
            // Unknown source — be conservative and admit. The proposal's
            // §15 risks list calls out that operators behind a hostile
            // proxy may want a stricter policy, but that is a future
            // concern.
            return true;
        }
        Bucket b = buckets.computeIfAbsent(sourceIp, ip -> new Bucket());
        long now = System.currentTimeMillis();
        synchronized (b) {
            if (now - b.windowStart >= windowMillis) {
                b.windowStart = now;
                b.count = 0;
            }
            if (b.count >= perWindow) {
                return false;
            }
            b.count++;
            return true;
        }
    }

    /** Visible-for-testing. */
    int countFor(String sourceIp) {
        Bucket b = buckets.get(sourceIp);
        return b == null ? 0 : b.count;
    }

    static final class Bucket {
        volatile long windowStart = System.currentTimeMillis();
        volatile int count = 0;
    }
}
