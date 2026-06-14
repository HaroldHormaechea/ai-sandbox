package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 AC24 + AC25, **UC-71** — backoff schedule + give-up budget.
 *
 * <p>UC-71 changed the policy:
 * <ul>
 *   <li>backoff ceiling is now **10 s** (was 30 s): 1, 2, 4, 8, 10, 10, …;</li>
 *   <li>the default ctor is **unlimited** ([retryBudgetMs] = {@code null}) — the
 *       controller never gives up on its own;</li>
 *   <li>the finite cumulative budget is preserved as an **injectable seam**: a
 *       caller that passes an explicit [retryBudgetMs] (e.g.
 *       [ReconnectController.GIVE_UP_AFTER_MS]) still gets the old give-up.</li>
 * </ul>
 *
 * <p>The controller takes a `nowMs` callable so we can fully drive its clock
 * from a fixture without `Thread.sleep`.
 */
class ReconnectControllerTest {

    /** Mutable clock seam — flips between assignments to [nowMs]. */
    private class FakeClock(var nowMs: Long = 0L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    // ── AC2 / AC3 — backoff schedule now caps at 10 s ────────────────────────

    @Test
    fun `delay sequence is 1 2 4 8 10 then caps at 10`() {
        val clock = FakeClock()
        val c = ReconnectController(nowMs = clock)

        // AC3 — small initial delays grow exponentially (no jump straight to the
        // cap on the first retry); AC2 — the schedule never exceeds 10 s.
        val expected = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L)
        for (e in expected) {
            assertThat(c.nextDelayMs()).isEqualTo(e)
        }
        // Subsequent calls hold at the 10 s cap (index clamp), never 16/30 s.
        assertThat(c.nextDelayMs()).isEqualTo(10_000L)
        assertThat(c.nextDelayMs()).isEqualTo(10_000L)
        assertThat(c.nextDelayMs()).isEqualTo(10_000L)
    }

    @Test
    fun `no delay in the schedule ever exceeds the 10s ceiling (AC2)`() {
        val clock = FakeClock()
        val c = ReconnectController(nowMs = clock)
        // Pull 50 delays — well past the 5-entry schedule — and assert the cap.
        repeat(50) {
            assertThat(c.nextDelayMs()).isLessThanOrEqualTo(10_000L)
        }
    }

    // ── AC5 / AC6 — attempt counting continues past the former 6-entry cap ────

    @Test
    fun `attemptCount tracks the number of nextDelayMs calls`() {
        val clock = FakeClock()
        val c = ReconnectController(nowMs = clock)

        assertThat(c.attemptCount).isEqualTo(0)
        c.nextDelayMs()
        assertThat(c.attemptCount).isEqualTo(1)
        c.nextDelayMs()
        c.nextDelayMs()
        assertThat(c.attemptCount).isEqualTo(3)
    }

    @Test
    fun `attemptCount keeps incrementing well past the former 6-entry cap (AC5 AC6)`() {
        val clock = FakeClock()
        val c = ReconnectController(nowMs = clock)

        // The old schedule capped at 6 entries; the attempt counter must keep
        // climbing unbounded so UC-70/UC-72 can still surface "attempt N".
        repeat(100) { c.nextDelayMs() }
        assertThat(c.attemptCount).isEqualTo(100)
    }

    // ── AC1 / AC6 — the DEFAULT ctor is unlimited (never gives up) ────────────

    @Test
    fun `default ctor never gives up even past 5 minutes of disconnection (AC1 AC6)`() {
        val clock = FakeClock(nowMs = 1_000_000L)
        val c = ReconnectController(nowMs = clock) // unlimited default

        c.nextDelayMs() // records firstFailureAtMs

        // Well past the old 5-minute give-up budget — still retrying.
        clock.nowMs = 1_000_000L + 5L * 60 * 1_000L
        assertThat(c.shouldGiveUp()).isFalse
        // …and a full day later it STILL never gives up.
        clock.nowMs = 1_000_000L + 24L * 60 * 60 * 1_000L
        assertThat(c.shouldGiveUp()).isFalse
    }

    @Test
    fun `default budget is unlimited — giveUpAfterMs collapses to Long MAX_VALUE`() {
        // Pre-UC-71 the no-arg ctor was a finite 5-min budget; as of UC-71 the
        // default is unlimited, so the budget collapses to Long.MAX_VALUE and the
        // unchanged shouldGiveUp arithmetic simply never crosses it.
        val clock = FakeClock(nowMs = 0L)
        val c = ReconnectController(nowMs = clock)
        assertThat(c.giveUpAfterMs).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `giveUpAtMs is null under the unlimited default even after a failure (AC5)`() {
        val clock = FakeClock(nowMs = 1_000L)
        val c = ReconnectController(nowMs = clock) // unlimited default

        c.nextDelayMs() // firstFailure recorded — but the budget is unlimited
        // No give-up instant to surface → UC-70/UC-72 "limit" line vanishes.
        assertThat(c.giveUpAtMs()).isNull()
    }

    // ── Finite-budget seam — still fully supported when injected ──────────────

    @Test
    fun `shouldGiveUp is false until first failure recorded (finite budget)`() {
        val clock = FakeClock()
        val c = ReconnectController(nowMs = clock, retryBudgetMs = ReconnectController.GIVE_UP_AFTER_MS)

        // No calls yet → never give up.
        assertThat(c.shouldGiveUp()).isFalse
    }

    @Test
    fun `an injected finite budget gives up exactly when it elapses`() {
        val clock = FakeClock(nowMs = 1_000_000L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = ReconnectController.GIVE_UP_AFTER_MS)

        c.nextDelayMs() // records firstFailureAtMs = 1_000_000

        // 4 min 59 s after — still trying.
        clock.nowMs = 1_000_000L + (4 * 60 + 59) * 1_000L
        assertThat(c.shouldGiveUp()).isFalse

        // Exactly 5 min after — give up.
        clock.nowMs = 1_000_000L + 5 * 60 * 1_000L
        assertThat(c.shouldGiveUp()).isTrue
    }

    @Test
    fun `reset clears both firstFailure and attempt counter (finite budget)`() {
        val clock = FakeClock(nowMs = 1_000_000L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = ReconnectController.GIVE_UP_AFTER_MS)

        c.nextDelayMs()
        c.nextDelayMs()
        clock.nowMs += 10 * 60 * 1_000L // way past the give-up cap

        assertThat(c.attemptCount).isEqualTo(2)
        assertThat(c.shouldGiveUp()).isTrue

        c.reset()

        assertThat(c.attemptCount).isEqualTo(0)
        assertThat(c.shouldGiveUp()).isFalse
        // Next delay starts at the head of the schedule again.
        assertThat(c.nextDelayMs()).isEqualTo(1_000L)
    }

    @Test
    fun `give up cap constant matches AC25 spec`() {
        // 5 minutes — pin the constant directly so a typo or refactor surfaces
        // with a blame line on this test. UC-71 keeps the constant as the
        // canonical finite budget callers can opt into.
        assertThat(ReconnectController.GIVE_UP_AFTER_MS).isEqualTo(5L * 60 * 1_000L)
    }

    // ── giveUpAtMs() + the constructor-injected retryBudgetMs (UC-70/UC-71) ───

    @Test
    fun `giveUpAtMs is null before any failure is recorded`() {
        val clock = FakeClock(nowMs = 5_000L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = ReconnectController.GIVE_UP_AFTER_MS)

        // No nextDelayMs() yet → no firstFailure → nothing to surface.
        assertThat(c.giveUpAtMs()).isNull()
    }

    @Test
    fun `giveUpAtMs is firstFailure plus the injected finite budget`() {
        val clock = FakeClock(nowMs = 1_000_000L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = ReconnectController.GIVE_UP_AFTER_MS)

        c.nextDelayMs() // records firstFailureAtMs = 1_000_000

        // The instant is anchored to the FIRST failure, not "now": advancing the
        // clock must not move it.
        assertThat(c.giveUpAtMs()).isEqualTo(1_000_000L + ReconnectController.GIVE_UP_AFTER_MS)
        clock.nowMs = 1_200_000L
        assertThat(c.giveUpAtMs()).isEqualTo(1_000_000L + ReconnectController.GIVE_UP_AFTER_MS)
    }

    @Test
    fun `giveUpAtMs honours a custom finite budget`() {
        val clock = FakeClock(nowMs = 0L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = 30_000L)

        c.nextDelayMs() // firstFailure = 0
        assertThat(c.giveUpAtMs()).isEqualTo(30_000L)
        assertThat(c.giveUpAfterMs).isEqualTo(30_000L)
    }

    @Test
    fun `giveUpAtMs is null for an unlimited (null) budget — the UC-71 forward case`() {
        val clock = FakeClock(nowMs = 1_000L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = null)

        c.nextDelayMs() // firstFailure recorded, but the budget is unlimited
        // Even with a failure on record, an unlimited budget surfaces no instant,
        // so the UI's limit line vanishes with no screen change.
        assertThat(c.giveUpAtMs()).isNull()
    }

    @Test
    fun `an unlimited budget never gives up no matter how long it retries`() {
        val clock = FakeClock(nowMs = 0L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = null)

        c.nextDelayMs() // firstFailure = 0
        // giveUpAfterMs collapses to Long.MAX_VALUE so shouldGiveUp never crosses.
        assertThat(c.giveUpAfterMs).isEqualTo(Long.MAX_VALUE)
        clock.nowMs = 24L * 60 * 60 * 1_000L // a full day of failures
        assertThat(c.shouldGiveUp()).isFalse
    }

    @Test
    fun `reset clears the give-up instant too (finite budget)`() {
        val clock = FakeClock(nowMs = 1_000L)
        val c = ReconnectController(nowMs = clock, retryBudgetMs = ReconnectController.GIVE_UP_AFTER_MS)

        c.nextDelayMs()
        assertThat(c.giveUpAtMs()).isNotNull()

        c.reset()
        assertThat(c.giveUpAtMs()).isNull()
    }
}
