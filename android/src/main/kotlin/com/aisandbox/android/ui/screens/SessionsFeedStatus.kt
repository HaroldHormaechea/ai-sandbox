package com.aisandbox.android.ui.screens

/**
 * UC-70 — the live sessions-events feed's connection status, surfaced from
 * [SessionEventsController] to the sessions list so a disconnected/reconnecting
 * feed can render the light-grey "Not connected, retrying…" background instead
 * of a blank empty area.
 *
 * <p>It is a pure presentation projection of the feed's reconnect machinery
 * ([com.aisandbox.android.net.ReconnectController]): the controller emits a new
 * value on every meaningful transition and the screen renders it. All timing
 * fields are absolute wall-clock instants on the controller's shared clock, so
 * the UI ticker can recompute a live countdown without holding the controller.
 *
 * @param phase        the current feed phase (see [Phase]).
 * @param attempt      the reconnect attempt count from `ReconnectController.attemptCount`
 *                     (0 until the first failure; AC3 surfaces it while RECONNECTING).
 * @param nextRetryAtMs the instant the next reconnect attempt is scheduled to fire
 *                     (`now + nextDelayMs()`), or null when no retry is pending. AC4's
 *                     live countdown ticks toward this.
 * @param giveUpAtMs   the instant the cumulative retry budget runs out
 *                     (`ReconnectController.giveUpAtMs()`), or null when retries are
 *                     unlimited (UC-71) or no failure has been recorded. AC5's limit
 *                     line shows ONLY while this is non-null.
 */
data class SessionsFeedStatus(
    val phase: Phase = Phase.CONNECTED,
    val attempt: Int = 0,
    val nextRetryAtMs: Long? = null,
    val giveUpAtMs: Long? = null,
) {
    /**
     * The feed's lifecycle phase.
     *
     *  • [CONNECTED]   — the socket is open (or, as the default, nothing has
     *    gone wrong yet); the list renders normally.
     *  • [CONNECTING]  — the first connect attempt is in flight before any
     *    failure (silent — emitted only when `attemptCount == 0`, never shown as
     *    a "retrying" surface, so an initial connect doesn't flash the message).
     *  • [RECONNECTING] — at least one attempt has failed and the feed is backing
     *    off / retrying; this is the state that drives the visible background.
     *  • [STOPPED]     — the cumulative budget was exhausted and the feed gave
     *    up; the background shows a static "Not connected" (no countdown).
     */
    enum class Phase { CONNECTED, CONNECTING, RECONNECTING, STOPPED }

    /** True while the feed is actively backing off / retrying after a failure. */
    val reconnecting: Boolean get() = phase == Phase.RECONNECTING
}
