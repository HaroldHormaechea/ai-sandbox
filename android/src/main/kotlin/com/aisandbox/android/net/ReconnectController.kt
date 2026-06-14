package com.aisandbox.android.net

/**
 * Backoff schedule + optional cumulative give-up budget for the terminal
 * stream reconnect path (UC04 AC24, AC25; UC-71).
 *
 * <p>Schedule:
 * ```
 *  attempt 1 → 1 s
 *  attempt 2 → 2 s
 *  attempt 3 → 4 s
 *  attempt 4 → 8 s
 *  attempt 5 → 10 s (cap; subsequent attempts stay at 10 s)
 * ```
 *
 * <p>UC-71 — the backoff ceiling is **10 s** (was 30 s): delays grow
 * exponentially from 1 s and then hold at the 10 s cap. The index clamp in
 * [nextDelayMs] yields 1, 2, 4, 8, 10, 10, … for all later attempts.
 *
 * <p>Give-up is **opt-in**. When a finite [retryBudgetMs] is injected, then
 * once the **cumulative** elapsed time of failed attempts crosses it,
 * [shouldGiveUp] returns {@code true}; the caller then dismisses the
 * foreground notification and surfaces "Disconnected — tap to reconnect" in
 * the toolbar, and tapping resets the controller via [reset]. The historical
 * 5-minute value is preserved as [GIVE_UP_AFTER_MS] for callers (and tests)
 * that still want a finite budget.
 *
 * <p>Stateful (the attempt counter + elapsed counter are mutated on
 * every call). Pair one controller per [StreamClient]; do NOT share
 * across streams.
 *
 * <p>UC-70 / UC-71 — the cumulative give-up budget is constructor-injected as
 * [retryBudgetMs]. As of UC-71 the **default is {@code null} (unlimited
 * retries)**: [shouldGiveUp] never fires and [giveUpAtMs] returns
 * {@code null}, so every consumer using the default ctor (the terminal /
 * conversation / sessions-events streams) now retries indefinitely. Callers
 * that still need a finite budget pass an explicit [retryBudgetMs] (e.g.
 * [GIVE_UP_AFTER_MS]); the give-up machinery stays fully supported as an
 * injectable seam (UC-70's UI derives its "limit" line purely from
 * [giveUpAtMs] being non-null, so it vanishes automatically under the
 * unlimited default — no UI change required).
 */
class ReconnectController(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryBudgetMs: Long? = null,
) {

    /** Successive delays in milliseconds, in order. Last value is the 10 s cap. */
    private val schedule: LongArray = longArrayOf(
        1_000L,
        2_000L,
        4_000L,
        8_000L,
        10_000L,
    )

    /**
     * Cumulative-failure budget in ms (UC04 AC25). Derived from the injected
     * [retryBudgetMs]; the UC-71 default of {@code null} (unlimited) collapses
     * to [Long.MAX_VALUE] here so the unchanged [shouldGiveUp] arithmetic
     * simply never crosses it. A finite budget (e.g. [GIVE_UP_AFTER_MS], 5 min)
     * is still honoured when injected.
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
