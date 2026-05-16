package com.aisandbox.server.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.tls.PerIpRateLimiter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * AC16 — per-IP rate limiter applies before TLS handshake. Verifies both
 * caps in isolation (rate within window, concurrent slots) and the
 * release-cycle behaviour.
 */
class PerIpRateLimiterTest {

    private static ServerProperties props(int rate, int windowSec, int concurrent) {
        return new ServerProperties(
                new ServerProperties.Tls(12410, "0.0.0.0"),
                new ServerProperties.Pki(Path.of("/tmp/p")),
                new ServerProperties.Clients(Path.of("/tmp/c")),
                new ServerProperties.Hostscripts(Path.of("/tmp/r")),
                new ServerProperties.Limits(rate, windowSec, concurrent, 60, 65536),
                new ServerProperties.Audit(Path.of("/tmp/a.log"), 7),
                new ServerProperties.Shutdown(30, 60),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
    }

    @Test
    void allows_up_to_rate_cap_within_window() {
        PerIpRateLimiter rl = new PerIpRateLimiter(props(3, 60, 10));

        assertThat(rl.tryAcquire("10.0.0.1")).isTrue();
        assertThat(rl.tryAcquire("10.0.0.1")).isTrue();
        assertThat(rl.tryAcquire("10.0.0.1")).isTrue();
        // 4th within the window trips.
        assertThat(rl.tryAcquire("10.0.0.1")).isFalse();
    }

    @Test
    void independent_ips_have_independent_buckets() {
        PerIpRateLimiter rl = new PerIpRateLimiter(props(1, 60, 10));
        assertThat(rl.tryAcquire("10.0.0.1")).isTrue();
        assertThat(rl.tryAcquire("10.0.0.1")).isFalse();
        // Different IP is unaffected.
        assertThat(rl.tryAcquire("10.0.0.2")).isTrue();
    }

    @Test
    void concurrent_cap_trips_even_with_a_fresh_rate_bucket() {
        PerIpRateLimiter rl = new PerIpRateLimiter(props(100, 60, 2));
        assertThat(rl.tryAcquire("10.0.0.1")).isTrue();
        assertThat(rl.tryAcquire("10.0.0.1")).isTrue();
        // Same source IP, two concurrent live connections, cap=2.
        assertThat(rl.tryAcquire("10.0.0.1")).isFalse();
    }

    @Test
    void release_frees_a_concurrent_slot() {
        PerIpRateLimiter rl = new PerIpRateLimiter(props(100, 60, 2));
        rl.tryAcquire("10.0.0.1");
        rl.tryAcquire("10.0.0.1");
        rl.release("10.0.0.1");
        // After release we have one free slot.
        assertThat(rl.tryAcquire("10.0.0.1")).isTrue();
    }

    @Test
    void rate_window_refills_after_elapsed_window() throws Exception {
        // window = 1s
        PerIpRateLimiter rl = new PerIpRateLimiter(props(1, 1, 10));
        assertThat(rl.tryAcquire("10.0.0.5")).isTrue();
        assertThat(rl.tryAcquire("10.0.0.5")).isFalse();

        Thread.sleep(1_100);
        // After the window we are allowed again.
        assertThat(rl.tryAcquire("10.0.0.5")).isTrue();
    }
}
