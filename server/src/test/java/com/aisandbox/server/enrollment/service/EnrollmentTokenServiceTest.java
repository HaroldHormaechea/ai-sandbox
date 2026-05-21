package com.aisandbox.server.enrollment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore.RedemptionOutcome;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC04 AC32 — token entropy ≥ 256 bits, TTL config wiring, purge sweep
 * orchestration. The service is a thin orchestrator over the store; this
 * test pins the contract the proposal called out explicitly.
 */
class EnrollmentTokenServiceTest {

    private static ServerProperties propsWith(Path enrollmentDir, int defaultTtlMinutes) {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15),
                new ServerProperties.Enrollment(enrollmentDir, defaultTtlMinutes, 1, 60));
    }

    private static Clock fixed(Instant at) {
        return Clock.fixed(at, ZoneOffset.UTC);
    }

    @Test
    void issued_token_has_at_least_256_bits_of_entropy(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 10), new SecureRandom(), fixed(Instant.now()));

        // 32 bytes = 256 bits, hex-encoded → 64 chars. Anything shorter
        // would weaken the AC32 entropy floor.
        EnrollmentToken issued = svc.issue("alice-phone", null);
        assertThat(issued.token().length()).isEqualTo(64);
        assertThat(issued.token()).matches("[0-9a-f]{64}");
        // Sanity — 256 bits of SecureRandom output should be high-entropy
        // hex; reject all-zeros / repeated chars.
        assertThat(new HashSet<>(java.util.Arrays.asList(issued.token().split(""))).size())
                .as("token entropy character variety")
                .isGreaterThan(4);
    }

    @Test
    void two_consecutive_tokens_differ(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 10), new SecureRandom(), fixed(Instant.now()));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            EnrollmentToken t = svc.issue("user-" + i, null);
            assertThat(seen.add(t.token()))
                    .as("token %s already seen", t.token())
                    .isTrue();
        }
    }

    @Test
    void default_ttl_is_wired_from_server_properties(@TempDir Path dir) throws Exception {
        // defaultTtlMinutes=15 → expiresAt = now + 15 min.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant fixedNow = Instant.parse("2026-05-17T10:00:00Z");
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 15), new SecureRandom(), fixed(fixedNow));

        EnrollmentToken t = svc.issue("alice-phone", null);
        assertThat(t.expiresAt()).isEqualTo(fixedNow.plus(Duration.ofMinutes(15)));
    }

    @Test
    void explicit_ttl_overrides_default(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant fixedNow = Instant.parse("2026-05-17T10:00:00Z");
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 10), new SecureRandom(), fixed(fixedNow));

        EnrollmentToken t = svc.issue("alice-phone", Duration.ofSeconds(42));
        assertThat(t.expiresAt()).isEqualTo(fixedNow.plusSeconds(42));
    }

    @Test
    void issue_persists_to_disk_so_verify_can_consume(@TempDir Path dir) throws Exception {
        // UC11 § AC7 — the on-disk shape is the contract between
        // `aisandboxctl client invite` (which constructs a store
        // directly) and the HTTP redemption path. verify() reads the
        // file without consuming it; markRedeemed() consumes it. We
        // exercise both halves here so the round-trip stays pinned.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant fixedNow = Instant.parse("2026-05-17T10:00:00Z");
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 10), new SecureRandom(), fixed(fixedNow));

        EnrollmentToken issued = svc.issue("alice-phone", null);

        RedemptionOutcome outcome = svc.verify(issued.token());
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Success.class);
        assertThat(outcome.token().name()).isEqualTo("alice-phone");
        // verify is side-effect-free — the on-disk file is still there.
        assertThat(store.fileFor(issued.token())).exists();

        boolean deleted = svc.markRedeemed(issued.token());
        assertThat(deleted)
                .as("UC11 § AC7 — first markRedeemed call must return true (the file was on disk)")
                .isTrue();
        assertThat(store.fileFor(issued.token())).doesNotExist();

        // A follow-up verify returns AlreadyRedeemed thanks to the
        // in-memory tombstone the store recorded inside markRedeemed.
        assertThat(svc.verify(issued.token())).isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
    }

    @Test
    void verify_then_markRedeemed_is_the_documented_split(@TempDir Path dir) throws Exception {
        // UC11 § AC7 — the regression guard: verifying a token MUST NOT
        // delete it, MUST NOT update the tombstone, and MUST be safe
        // to call repeatedly until the caller (the facade) succeeds
        // in writing the cert and then invokes markRedeemed. This is
        // what enables the "token survives cert-write failure" flow
        // tested in EnrollmentFacadeTest.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant fixedNow = Instant.parse("2026-05-17T10:00:00Z");
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 10), new SecureRandom(), fixed(fixedNow));

        EnrollmentToken issued = svc.issue("alice-phone", null);

        // Verify three times in a row — each one returns Success and
        // leaves the file alone.
        for (int i = 0; i < 3; i++) {
            assertThat(svc.verify(issued.token())).isInstanceOf(RedemptionOutcome.Success.class);
            assertThat(store.fileFor(issued.token())).exists();
        }
        // markRedeemed is the only operation that removes the file.
        assertThat(svc.markRedeemed(issued.token())).isTrue();
        assertThat(store.fileFor(issued.token())).doesNotExist();
    }

    @Test
    void purgeExpired_drops_past_expiry_files(@TempDir Path dir) throws Exception {
        // Issue a short-lived token, advance the clock past expiry, run
        // the scheduled sweep, assert the file is gone.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        // Mutable clock holder so we can advance time between actions.
        java.util.concurrent.atomic.AtomicReference<Instant> nowRef =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-05-17T10:00:00Z"));
        Clock movingClock = new Clock() {
            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 10), new SecureRandom(), movingClock);

        EnrollmentToken issued = svc.issue("alice-phone", Duration.ofSeconds(30));
        assertThat(store.fileFor(issued.token())).exists();

        // Advance the clock past expiry; sweep.
        nowRef.set(nowRef.get().plusSeconds(60));
        svc.purgeExpired();

        assertThat(store.fileFor(issued.token())).doesNotExist();
    }

    @Test
    void verify_returns_unknown_for_never_issued_token(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant fixedNow = Instant.parse("2026-05-17T10:00:00Z");
        EnrollmentTokenService svc =
                new EnrollmentTokenService(store, propsWith(dir, 10), new SecureRandom(), fixed(fixedNow));

        // Cleanly-formed but never-issued — the controller maps this to
        // 401 enrollment_token_invalid via the facade's typed exception.
        RedemptionOutcome outcome = svc.verify("f".repeat(64));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Unknown.class);
    }
}
