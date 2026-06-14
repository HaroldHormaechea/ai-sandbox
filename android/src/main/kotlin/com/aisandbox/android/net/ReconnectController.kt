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
 *
 * <p>UC-70 — the cumulative give-up budget is now constructor-injected as
 * [retryBudgetMs] (defaulting to the historical [GIVE_UP_AFTER_MS] 5 min so
 * every existing caller — the terminal / conversation streams — is byte-for-
 * byte unchanged). A {@code null} budget means "unlimited retries":
 * [shouldGiveUp] then never fires and [giveUpAtMs] returns {@code null}. This
 * keeps the type forward-compatible with UC-71, which flips the sessions feed
 * to an unlimited budget; the sessions UI derives its "limit" line purely from
 * [giveUpAtMs] being non-null, so it vanishes automatically when the budget
 * becomes {@code null} — no UI change required.
 */
class ReconnectController(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryBudgetMs: Long? = GIVE_UP_AFTER_MS,
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

    /**
     * Cumulative-failure budget in ms (UC04 AC25 — default 5 minutes). Derived
     * from the injected [retryBudgetMs]; an unlimited budget ({@code null})
     * collapses to [Long.MAX_VALUE] here so the unchanged [shouldGiveUp]
     * arithmetic simply never crosses it.
     */
    val giveUpAfterMs: Long = retryBudgetMs ?: Long.MAX_VALUE

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

    /**
     * UC-70 — the wall-clock instant (per the shared [nowMs] clock) at which
     * the cumulative budget runs out and [shouldGiveUp] starts returning
     * {@code true}, or {@code null} when there is nothing to surface: either no
     * failure has been recorded yet ([firstFailureAtMs] still unset) or the
     * budget is unlimited ([retryBudgetMs] is {@code null}). The sessions UI
     * shows its "giving up" line only while this is non-null, so an unlimited
     * budget (UC-71) drops the line with no screen change.
     */
    fun giveUpAtMs(): Long? {
        if (firstFailureAtMs < 0) return null
        val budget = retryBudgetMs ?: return null
        return firstFailureAtMs + budget
    }

    /** Reset state — called after a successful (re)connect, or when user taps "reconnect". */
    fun reset() {
        firstFailureAtMs = -1L
        attempt = 0
    }

    companion object {
        const val GIVE_UP_AFTER_MS: Long = 5L * 60L * 1000L
    }
}
