package com.aisandbox.android.net

/**
 * Backoff schedule + 5-minute cumulative cap for the terminal stream
 * reconnect path (UC04 AC24, AC25).
 *
 * <p>Schedule:
 * ```
 *  attempt 1 → 1 s
 *  attempt 2 → 2 s
 *  attempt 3 → 4 s
 *  attempt 4 → 8 s
 *  attempt 5 → 16 s
 *  attempt 6 → 30 s (cap; subsequent attempts stay at 30 s)
 * ```
 *
 * <p>Once the **cumulative** elapsed time of failed attempts crosses
 * [GIVE_UP_AFTER_MS] (5 min), [shouldGiveUp] returns {@code true}; the
 * caller then dismisses the foreground notification and surfaces
 * "Disconnected — tap to reconnect" in the toolbar. Tapping resets the
 * controller via [reset].
 *
 * <p>Stateful (the attempt counter + elapsed counter are mutated on
 * every call). Pair one controller per [StreamClient]; do NOT share
 * across streams.
 */
class ReconnectController(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Successive delays in milliseconds, in order. Last value is the cap. */
    private val schedule: LongArray = longArrayOf(
        1_000L,
        2_000L,
        4_000L,
        8_000L,
        16_000L,
        30_000L,
    )

    /** Cumulative-failure budget — 5 minutes (UC04 AC25). */
    val giveUpAfterMs: Long = GIVE_UP_AFTER_MS

    private var firstFailureAtMs: Long = -1L
    private var attempt: Int = 0

    /**
     * Compute the delay for the next reconnect attempt. Caller calls
     * this AFTER each failed attempt, then `delay(nextDelayMs())` before
     * trying again. Increments the internal attempt counter as a side
     * effect.
     */
    fun nextDelayMs(): Long {
        if (firstFailureAtMs < 0) {
            firstFailureAtMs = nowMs()
        }
        val idx = attempt.coerceIn(0, schedule.size - 1)
        attempt++
        return schedule[idx]
    }

    /** {@code true} iff cumulative reconnect attempts exceeded the cap. */
    fun shouldGiveUp(): Boolean {
        if (firstFailureAtMs < 0) return false
        return (nowMs() - firstFailureAtMs) >= giveUpAfterMs
    }

    /** Number of failed attempts so far (read-only for UI surfacing). */
    val attemptCount: Int get() = attempt

    /** Reset state — called after a successful (re)connect, or when user taps "reconnect". */
    fun reset() {
        firstFailureAtMs = -1L
        attempt = 0
    }

    companion object {
        const val GIVE_UP_AFTER_MS: Long = 5L * 60L * 1000L
    }
}
