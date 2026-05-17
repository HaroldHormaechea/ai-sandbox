package com.aisandbox.server.enrollment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore.RedemptionOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC04 — single-use semantics for {@link EnrollmentTokenStore}.
 *
 * <p>Covers AC32–AC35 surface: save → redeem happy path, expiry, the
 * three "redeem fails" branches (unknown / expired / already-redeemed),
 * the on-disk fixture (mode 0600 + 0700 dir), atomic delete after
 * success, the concurrent-redeem race (only one of N threads wins), and
 * the purge sweep.
 */
class EnrollmentTokenStoreTest {

    private static final String FULL_TOKEN_A =
            "a".repeat(64); // 64 hex chars = 256 bits. Obvious placeholder; not a real key.
    private static final String FULL_TOKEN_B = "b".repeat(64);

    private static Clock fixed(Instant at) {
        return Clock.fixed(at, ZoneOffset.UTC);
    }

    private static EnrollmentToken token(String full, String name, Instant exp) {
        return new EnrollmentToken(full, name, exp);
    }

    @Test
    void issued_token_redeems_once_and_is_then_unknown(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant now = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice-phone", now.plusSeconds(600)));

        // First redeem → Success.
        RedemptionOutcome first = store.redeem(FULL_TOKEN_A, fixed(now));
        assertThat(first).isInstanceOf(RedemptionOutcome.Success.class);
        assertThat(first.token().name()).isEqualTo("alice-phone");

        // File is gone after a successful redeem (single-use, atomic delete).
        assertThat(Files.exists(store.fileFor(FULL_TOKEN_A))).isFalse();

        // Second redeem hits the in-memory tombstone — distinguishes "already
        // redeemed" from "never existed".
        RedemptionOutcome second = store.redeem(FULL_TOKEN_A, fixed(now.plusSeconds(1)));
        assertThat(second).isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
    }

    @Test
    void unknown_token_returns_Unknown(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        store.ensureDir();

        RedemptionOutcome outcome = store.redeem(FULL_TOKEN_B, fixed(Instant.now()));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Unknown.class);
    }

    @Test
    void expired_token_returns_Expired_and_is_deleted(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant issued = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice-phone", issued.plusSeconds(60)));

        // Clock advanced past the expiry → Expired outcome and the file
        // is cleaned up on the way out.
        Instant later = issued.plusSeconds(120);
        RedemptionOutcome outcome = store.redeem(FULL_TOKEN_A, fixed(later));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Expired.class);
        assertThat(outcome.token().name()).isEqualTo("alice-phone");

        assertThat(Files.exists(store.fileFor(FULL_TOKEN_A))).isFalse();
    }

    @Test
    void exactly_at_expiry_is_treated_as_expired(@TempDir Path dir) throws Exception {
        // UC04 § Resolved: tokens must be refused at or after expiresAt.
        // EnrollmentToken#isExpiredAt(now) returns !expiresAt.isAfter(now),
        // so now == expiresAt is expired (defensive — never treat tokens as
        // valid at the exact second of expiry).
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant exp = Instant.parse("2026-05-17T10:10:00Z");
        store.save(token(FULL_TOKEN_A, "alice-phone", exp));

        RedemptionOutcome outcome = store.redeem(FULL_TOKEN_A, fixed(exp));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Expired.class);
    }

    @Test
    void token_dir_is_created_with_mode_0700_and_file_is_mode_0600(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("enrollment");
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        store.save(token(FULL_TOKEN_A, "alice-phone", Instant.now().plusSeconds(600)));

        // Directory exists and is mode 0700.
        assertThat(Files.isDirectory(dir)).isTrue();
        Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(dir);
        assertThat(dirPerms)
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);

        // Token file is mode 0600 — operator-only.
        Path file = store.fileFor(FULL_TOKEN_A);
        assertThat(Files.exists(file)).isTrue();
        Set<PosixFilePermission> filePerms = Files.getPosixFilePermissions(file);
        assertThat(filePerms)
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void filename_uses_16_char_prefix_not_full_token(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        store.save(token(FULL_TOKEN_A, "alice-phone", Instant.now().plusSeconds(600)));

        // Filename is "<first 16 chars>.json", never the full token —
        // protects token confidentiality even when an operator lists
        // the directory.
        String expectedPrefix = FULL_TOKEN_A.substring(0, EnrollmentTokenStore.FILENAME_PREFIX_LEN);
        assertThat(Files.exists(dir.resolve(expectedPrefix + ".json"))).isTrue();
    }

    @Test
    void prefix_collision_with_wrong_full_token_returns_Unknown(@TempDir Path dir) throws Exception {
        // Same 16-char prefix, different full token — extremely unlikely
        // in practice but the store must not surrender the legitimate
        // token to a near-collision attempt.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        String stored = "0".repeat(16) + "abcd".repeat(12); // 64 chars, prefix 0000...
        String attacker = "0".repeat(16) + "ffff".repeat(12); // same prefix, different tail
        store.save(token(stored, "alice-phone", Instant.now().plusSeconds(600)));

        RedemptionOutcome outcome = store.redeem(attacker, fixed(Instant.now()));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Unknown.class);

        // The legitimate token file MUST still exist — we matched by prefix
        // but the full-token check rejected, so we don't punish the real owner.
        assertThat(Files.exists(store.fileFor(stored))).isTrue();
    }

    @Test
    void concurrent_redeem_only_one_thread_wins(@TempDir Path dir) throws Exception {
        // The synchronized-block + atomic-delete contract: exactly one of N
        // concurrent redemptions of the same token succeeds; every other
        // thread sees AlreadyRedeemed (or Unknown if it raced past the
        // tombstone window). UC04 § Edge cases — "Token-store concurrency".
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant now = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice-phone", now.plusSeconds(600)));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger alreadyRedeemed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger unknown = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        RedemptionOutcome o = store.redeem(FULL_TOKEN_A, fixed(now));
                        if (o instanceof RedemptionOutcome.Success) {
                            successes.incrementAndGet();
                        } else if (o instanceof RedemptionOutcome.AlreadyRedeemed) {
                            alreadyRedeemed.incrementAndGet();
                        } else if (o instanceof RedemptionOutcome.Unknown) {
                            unknown.incrementAndGet();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(2, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Exactly one Success. The rest split between AlreadyRedeemed (saw
        // the tombstone) and Unknown (raced past, file already gone, not
        // yet in tombstone) — but the AlreadyRedeemed count is the dominant
        // one in practice because the tombstone write is inside the
        // synchronized block.
        assertThat(successes.get()).isEqualTo(1);
        assertThat(alreadyRedeemed.get() + unknown.get()).isEqualTo(threads - 1);
        // Defensive — at least most losers should land in AlreadyRedeemed.
        assertThat(alreadyRedeemed.get()).isGreaterThanOrEqualTo(threads - 2);

        // File is gone.
        assertThat(Files.exists(store.fileFor(FULL_TOKEN_A))).isFalse();
    }

    @Test
    void purgeExpired_removes_only_past_expiry_files(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token("a".repeat(64), "expired-a", t0.minusSeconds(60))); // past
        store.save(token("b".repeat(64), "expired-b", t0.minusSeconds(1))); // past
        store.save(token("c".repeat(64), "live-c", t0.plusSeconds(120))); // future
        store.save(token("d".repeat(64), "live-d", t0.plusSeconds(600))); // future

        int removed = store.purgeExpired(fixed(t0));
        assertThat(removed).isEqualTo(2);

        // Living tokens still on disk.
        assertThat(Files.exists(store.fileFor("c".repeat(64)))).isTrue();
        assertThat(Files.exists(store.fileFor("d".repeat(64)))).isTrue();
        // Expired tokens removed.
        assertThat(Files.exists(store.fileFor("a".repeat(64)))).isFalse();
        assertThat(Files.exists(store.fileFor("b".repeat(64)))).isFalse();
    }

    @Test
    void purgeExpired_handles_unparseable_files(@TempDir Path dir) throws IOException {
        // Hand-written garbage at the directory must not break the sweep.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("dead-beef-cafe-1234.json"), "{ not valid json");

        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        int removed = store.purgeExpired(fixed(Instant.now()));

        // Unparseable file is removed defensively (logged at WARN).
        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(dir.resolve("dead-beef-cafe-1234.json"))).isFalse();
    }

    @Test
    void ensureDir_is_idempotent(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("enr");
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        store.ensureDir();
        store.ensureDir(); // second call: noop, no exception
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void recently_redeemed_set_distinguishes_redeemed_from_unknown(@TempDir Path dir) throws Exception {
        // Persist + redeem two distinct tokens; the never-issued third token
        // must come back Unknown, never AlreadyRedeemed.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant now = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice", now.plusSeconds(600)));
        store.save(token(FULL_TOKEN_B, "bob", now.plusSeconds(600)));
        store.redeem(FULL_TOKEN_A, fixed(now));
        store.redeem(FULL_TOKEN_B, fixed(now));

        Set<String> redeemed = new HashSet<>(Set.of(FULL_TOKEN_A, FULL_TOKEN_B));
        for (String t : redeemed) {
            assertThat(store.redeem(t, fixed(now))).isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
        }
        String neverIssued = "c".repeat(64);
        assertThat(store.redeem(neverIssued, fixed(now))).isInstanceOf(RedemptionOutcome.Unknown.class);
    }
}
