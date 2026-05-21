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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC04 + UC11 — single-use semantics for {@link EnrollmentTokenStore}.
 *
 * <p>UC04 covered the original AC32–AC35 surface: save → redeem happy
 * path, expiry, the three "redeem fails" branches (unknown / expired /
 * already-redeemed), the on-disk fixture (mode 0600 + 0700 dir), atomic
 * delete after success, the concurrent-redeem race (only one of N
 * threads wins), and the purge sweep.
 *
 * <p>UC11 § AC7 split the original single {@code redeem(String, Clock)}
 * into a side-effect-free {@link EnrollmentTokenStore#verify(String,
 * Clock)} and a downstream {@link EnrollmentTokenStore#markRedeemed(
 * String, Clock)} so the facade can roll back on a failed cert write.
 * These tests pin the split contract:
 *
 * <ul>
 *   <li>{@code verify} never deletes a file, never updates the
 *       tombstone, and never mutates any shared state.</li>
 *   <li>{@code markRedeemed} deletes the file and records the
 *       tombstone, returning {@code true} iff a file was actually
 *       removed by this call.</li>
 *   <li>Concurrency: under N concurrent {@code verify+markRedeemed}
 *       pairs, exactly one {@code markRedeemed} call returns
 *       {@code true} — the others see {@code false} because the file
 *       is already gone.</li>
 * </ul>
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
    void verify_then_markRedeemed_happy_path(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant now = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice-phone", now.plusSeconds(600)));

        // verify alone leaves the file on disk and does NOT tombstone.
        RedemptionOutcome verified = store.verify(FULL_TOKEN_A, fixed(now));
        assertThat(verified).isInstanceOf(RedemptionOutcome.Success.class);
        assertThat(verified.token().name()).isEqualTo("alice-phone");
        assertThat(Files.exists(store.fileFor(FULL_TOKEN_A)))
                .as("UC11 § AC7 — verify must NOT delete the on-disk token file")
                .isTrue();

        // Re-verifying the same token is still Success (no tombstone
        // was set by the first verify call).
        RedemptionOutcome reVerified = store.verify(FULL_TOKEN_A, fixed(now.plusSeconds(1)));
        assertThat(reVerified)
                .as("UC11 § AC7 — verify is side-effect-free so a follow-up verify still returns Success")
                .isInstanceOf(RedemptionOutcome.Success.class);

        // markRedeemed deletes the file and populates the tombstone.
        boolean deleted = store.markRedeemed(FULL_TOKEN_A, fixed(now.plusSeconds(2)));
        assertThat(deleted)
                .as("first markRedeemed call MUST return true (file was on disk)")
                .isTrue();
        assertThat(Files.exists(store.fileFor(FULL_TOKEN_A)))
                .as("UC11 § AC7 — markRedeemed deletes the on-disk file")
                .isFalse();

        // Now verify returns AlreadyRedeemed (file missing + tombstone set).
        RedemptionOutcome afterMark = store.verify(FULL_TOKEN_A, fixed(now.plusSeconds(3)));
        assertThat(afterMark).isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
    }

    @Test
    void markRedeemed_returns_false_when_file_already_gone(@TempDir Path dir) throws Exception {
        // UC11 § AC7 — the boolean return distinguishes "this call did
        // the delete" from "another caller raced past us / the file
        // was never there". Either way, the tombstone gets set so
        // subsequent verifies see AlreadyRedeemed.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        store.ensureDir();

        boolean deleted = store.markRedeemed(FULL_TOKEN_A, fixed(Instant.now()));
        assertThat(deleted)
                .as("markRedeemed on a never-issued token must return false (no file to delete)")
                .isFalse();

        // Tombstone is still set — subsequent verify returns AlreadyRedeemed
        // rather than Unknown, so a race-loser caller can be distinguished
        // from a never-existed token.
        assertThat(store.verify(FULL_TOKEN_A, fixed(Instant.now())))
                .as("UC11 § AC7 — tombstone is set even when the file delete is a no-op")
                .isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
    }

    @Test
    void unknown_token_returns_Unknown(@TempDir Path dir) throws Exception {
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        store.ensureDir();

        RedemptionOutcome outcome = store.verify(FULL_TOKEN_B, fixed(Instant.now()));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Unknown.class);
    }

    @Test
    void expired_token_returns_Expired_and_is_not_deleted_by_verify(@TempDir Path dir) throws Exception {
        // UC11 § AC7 — pre-UC11 the original redeem deleted the
        // expired file on the way out. Post-UC11 verify is purely
        // read-only. The on-disk Expired file is cleaned up later by
        // the scheduled purgeExpired() sweep.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant issued = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice-phone", issued.plusSeconds(60)));

        Instant later = issued.plusSeconds(120);
        RedemptionOutcome outcome = store.verify(FULL_TOKEN_A, fixed(later));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Expired.class);
        assertThat(outcome.token().name()).isEqualTo("alice-phone");

        assertThat(Files.exists(store.fileFor(FULL_TOKEN_A)))
                .as("UC11 § AC7 — verify on an expired token must NOT delete; that is purgeExpired's job")
                .isTrue();
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

        RedemptionOutcome outcome = store.verify(FULL_TOKEN_A, fixed(exp));
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

        RedemptionOutcome outcome = store.verify(attacker, fixed(Instant.now()));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Unknown.class);

        // The legitimate token file MUST still exist — we matched by prefix
        // but the full-token check rejected, so we don't punish the real owner.
        assertThat(Files.exists(store.fileFor(stored))).isTrue();
    }

    @Test
    void concurrent_verify_plus_markRedeemed_exactly_one_winner(@TempDir Path dir) throws Exception {
        // UC11 § AC7 — the verify+markRedeemed pair under contention.
        // Threads each call verify() (no side effects, all see Success)
        // and then markRedeemed(). Exactly one markRedeemed call MUST
        // return true (the one whose Files.deleteIfExists actually
        // removed the file); the others return false because by the
        // time they enter the synchronized block the file is already
        // gone. Verifies the AC9 single-shot guarantee survives the
        // verify/mark split.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant now = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice-phone", now.plusSeconds(600)));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger verifySuccesses = new AtomicInteger();
        AtomicInteger markTrue = new AtomicInteger();
        AtomicInteger markFalse = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        RedemptionOutcome v = store.verify(FULL_TOKEN_A, fixed(now));
                        if (v instanceof RedemptionOutcome.Success) {
                            verifySuccesses.incrementAndGet();
                        }
                        boolean deleted = store.markRedeemed(FULL_TOKEN_A, fixed(now));
                        if (deleted) {
                            markTrue.incrementAndGet();
                        } else {
                            markFalse.incrementAndGet();
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

        // Exactly one markRedeemed wins the delete race.
        assertThat(markTrue.get())
                .as("UC11 § AC7 — exactly one markRedeemed call may delete the file")
                .isEqualTo(1);
        assertThat(markTrue.get() + markFalse.get()).isEqualTo(threads);
        // Most verifies should see Success (since they ran before the
        // first markRedeemed completed). Not strictly required by the
        // contract but a useful sanity check.
        assertThat(verifySuccesses.get())
                .as("at least one concurrent verify() lands before the first markRedeemed completes")
                .isGreaterThan(0);

        // File is gone, tombstone is set.
        assertThat(Files.exists(store.fileFor(FULL_TOKEN_A))).isFalse();
        assertThat(store.verify(FULL_TOKEN_A, fixed(now))).isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
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
    void verify_on_unparseable_file_returns_Unknown_and_does_not_delete(@TempDir Path dir) throws IOException {
        // UC11 § AC7 — verify is side-effect-free even for corrupt
        // files. The unparseable file lingers on disk until the
        // scheduled purgeExpired() sweep picks it up. Pre-UC11 the
        // single redeem call deleted the corrupt file on the way out;
        // post-UC11 that responsibility shifts to purge.
        Files.createDirectories(dir);
        String junkPrefix = "0".repeat(16);
        Path junkFile = dir.resolve(junkPrefix + ".json");
        Files.writeString(junkFile, "{ not valid json");

        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        String fakeFullToken = junkPrefix + "a".repeat(48);
        RedemptionOutcome outcome = store.verify(fakeFullToken, fixed(Instant.now()));
        assertThat(outcome).isInstanceOf(RedemptionOutcome.Unknown.class);

        // Side-effect-free — the corrupt file is still there.
        assertThat(Files.exists(junkFile))
                .as("UC11 § AC7 — verify must NOT delete unparseable files")
                .isTrue();
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
        // Persist + verify+markRedeemed two distinct tokens; the never-
        // issued third token must come back Unknown, never AlreadyRedeemed.
        EnrollmentTokenStore store = new EnrollmentTokenStore(dir);
        Instant now = Instant.parse("2026-05-17T10:00:00Z");
        store.save(token(FULL_TOKEN_A, "alice", now.plusSeconds(600)));
        store.save(token(FULL_TOKEN_B, "bob", now.plusSeconds(600)));
        store.verify(FULL_TOKEN_A, fixed(now));
        store.markRedeemed(FULL_TOKEN_A, fixed(now));
        store.verify(FULL_TOKEN_B, fixed(now));
        store.markRedeemed(FULL_TOKEN_B, fixed(now));

        Set<String> redeemed = new HashSet<>(Set.of(FULL_TOKEN_A, FULL_TOKEN_B));
        for (String t : redeemed) {
            assertThat(store.verify(t, fixed(now))).isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
        }
        String neverIssued = "c".repeat(64);
        assertThat(store.verify(neverIssued, fixed(now))).isInstanceOf(RedemptionOutcome.Unknown.class);
    }
}
