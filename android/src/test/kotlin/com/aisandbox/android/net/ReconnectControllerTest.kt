package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 AC24 + AC25 — backoff schedule (1, 2, 4, 8, 16, 30 s, capped) +
 * 5-minute cumulative give-up cap.
 *
 * <p>The controller takes a `nowMs` callable so we can fully drive its
 * clock from a [LongArray]/`MutableLong` fixture without `Thread.sleep`.
 */
class ReconnectControllerTest {

    /** Mutable clock seam — flips between `set(x)` calls. */
    private class FakeClock(var nowMs: Long = 0L) : () -> Long {
        override fun invoke(): Long = nowMs
    }

    @Test
    fun `delay sequence is 1 2 4 8 16 30 then caps at 30`() {
        val clock = FakeClock()
        val c = ReconnectController(nowMs = clock)

        val expected = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        for (e in expected) {
            assertThat(c.nextDelayMs()).isEqualTo(e)
        }
        // Subsequent calls stay at the 30 s cap.
        assertThat(c.nextDelayMs()).isEqualTo(30_000L)
        assertThat(c.nextDelayMs()).isEqualTo(30_000L)
    }

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
    fun `shouldGiveUp is false until first failure recorded`() {
        val clock = FakeClock()
        val c = ReconnectController(nowMs = clock)

        // No calls yet → never give up.
        assertThat(c.shouldGiveUp()).isFalse
    }

    @Test
    fun `shouldGiveUp is true exactly when 5 minutes elapse from first failure`() {
        val clock = FakeClock(nowMs = 1_000_000L)
        val c = ReconnectController(nowMs = clock)

        c.nextDelayMs() // records firstFailureAtMs = 1_000_000

        // 4 min 59 s after — still trying.
        clock.nowMs = 1_000_000L + (4 * 60 + 59) * 1_000L
        assertThat(c.shouldGiveUp()).isFalse

        // Exactly 5 min after — give up.
        clock.nowMs = 1_000_000L + 5 * 60 * 1_000L
        assertThat(c.shouldGiveUp()).isTrue
    }

    @Test
    fun `reset clears both firstFailure and attempt counter`() {
        val clock = FakeClock(nowMs = 1_000_000L)
        val c = ReconnectController(nowMs = clock)

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
        // 5 minutes — pin the constant directly so a typo or refactor
        // surfaces with a blame line on this test.
        assertThat(ReconnectController.GIVE_UP_AFTER_MS).isEqualTo(5L * 60 * 1_000L)
    }

    // ── UC-70 — giveUpAtMs() + the constructor-injected retryBudgetMs ─────────
    //
    // The sessions feed surfaces the cumulative give-up instant so the
    // "Not connected, retrying…" background can show its limit/countdown line.
    // The budget is now ctor-injected (default = the historical 5 min so every
    // existing caller is unchanged) and a null budget means "unlimited" — the
    // UC-71 forward case, which must collapse the limit line to nothing.

    @Test
    fun `giveUpAtMs is null before any failure is recorded`() {
        val clock = FakeClock(nowMs = 5_000L)
        val c = ReconnectController(nowMs = clock)

        // No nextDelayMs() yet → no firstFailure → nothing to surface.
        assertThat(c.giveUpAtMs()).isNull()
    }

    @Test
    fun `giveUpAtMs is firstFailure plus the finite budget (default 5 min)`() {
        val clock = FakeClock(nowMs = 1_000_000L)
        val c = ReconnectController(nowMs = clock)

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
    fun `reset clears the give-up instant too`() {
        val clock = FakeClock(nowMs = 1_000L)
        val c = ReconnectController(nowMs = clock)

        c.nextDelayMs()
        assertThat(c.giveUpAtMs()).isNotNull()

        c.reset()
        assertThat(c.giveUpAtMs()).isNull()
    }

    @Test
    fun `the default budget matches the historical 5-minute cap (caller-compat)`() {
        // The no-arg/no-budget ctor must behave byte-for-byte like before UC-70:
        // the terminal / conversation streams rely on the 5-min give-up.
        val clock = FakeClock(nowMs = 0L)
        val c = ReconnectController(nowMs = clock)
        assertThat(c.giveUpAfterMs).isEqualTo(ReconnectController.GIVE_UP_AFTER_MS)
    }
}
