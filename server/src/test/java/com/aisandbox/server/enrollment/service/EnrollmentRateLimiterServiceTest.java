package com.aisandbox.server.enrollment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.config.ServerProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * UC04 AC34 — per-source-IP token-bucket rate limit on
 * {@code POST /v1/enrollment}. Default policy: 1 redemption per 60 s
 * per IP, configurable.
 */
class EnrollmentRateLimiterServiceTest {

    private static ServerProperties propsWith(int perWindow, int windowSeconds) {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15),
                new ServerProperties.Enrollment(Path.of("/enrollment"), 10, perWindow, windowSeconds));
    }

    @Test
    void defaults_match_AC34_one_per_60s() {
        // Static defaults exposed on the class — a regression here means
        // the wire AC34 contract drifted.
        assertThat(EnrollmentRateLimiterService.DEFAULT_PER_WINDOW).isEqualTo(1);
        assertThat(EnrollmentRateLimiterService.DEFAULT_WINDOW_SECONDS).isEqualTo(60);
    }

    @Test
    void first_request_admitted_second_in_same_window_rejected() {
        // Default policy: 1 per 60 s.
        EnrollmentRateLimiterService limiter = new EnrollmentRateLimiterService(propsWith(1, 60));

        assertThat(limiter.tryAcquire("198.51.100.10")).isTrue();
        // Second request from the same IP within the window → rejected,
        // controller returns 429 enrollment_rate_limited.
        assertThat(limiter.tryAcquire("198.51.100.10")).isFalse();
    }

    @Test
    void distinct_ips_are_isolated() {
        // A burst from one IP must not deny a different IP.
        EnrollmentRateLimiterService limiter = new EnrollmentRateLimiterService(propsWith(1, 60));

        assertThat(limiter.tryAcquire("198.51.100.10")).isTrue();
        // The cap on .10 is now tripped; .20 should still be free.
        assertThat(limiter.tryAcquire("198.51.100.20")).isTrue();
        // And .20's own cap is now tripped.
        assertThat(limiter.tryAcquire("198.51.100.20")).isFalse();
    }

    @Test
    void per_window_is_configurable() {
        // perWindow=3 means three are admitted before the cap trips.
        EnrollmentRateLimiterService limiter = new EnrollmentRateLimiterService(propsWith(3, 60));

        assertThat(limiter.tryAcquire("198.51.100.30")).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.30")).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.30")).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.30")).isFalse();
    }

    @Test
    void null_and_empty_source_ip_are_admitted() {
        // Unknown source → admit (the prod-code comment calls this out
        // explicitly as conservative-by-design; future hostile-proxy
        // policy is a separate concern).
        EnrollmentRateLimiterService limiter = new EnrollmentRateLimiterService(propsWith(1, 60));
        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire("")).isTrue();
    }

    @Test
    void short_window_resets_so_a_second_request_eventually_admitted() throws Exception {
        // windowSeconds=1 → after one second the bucket resets. This is
        // the only place we sleep — the limiter uses System.currentTimeMillis
        // internally so we can't fully mock it. Bound the wait at 2s; flake-
        // free in practice.
        EnrollmentRateLimiterService limiter = new EnrollmentRateLimiterService(propsWith(1, 1));

        assertThat(limiter.tryAcquire("198.51.100.99")).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.99")).isFalse();

        Thread.sleep(1_100L);

        // Window rolled over; cap is fresh.
        assertThat(limiter.tryAcquire("198.51.100.99")).isTrue();
    }

    @Test
    void countFor_reflects_admitted_count() {
        EnrollmentRateLimiterService limiter = new EnrollmentRateLimiterService(propsWith(3, 60));
        assertThat(limiter.countFor("198.51.100.40")).isEqualTo(0);
        limiter.tryAcquire("198.51.100.40");
        limiter.tryAcquire("198.51.100.40");
        assertThat(limiter.countFor("198.51.100.40")).isEqualTo(2);
    }
}
