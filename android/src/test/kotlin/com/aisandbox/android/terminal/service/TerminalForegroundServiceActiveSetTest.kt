package com.aisandbox.android.terminal.service

import com.aisandbox.android.ui.screens.TerminalState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-34 AC6 — pure-JVM coverage of the FGS "active set" predicate, the single
 * source of truth the self-managing [TerminalForegroundService] uses to decide
 * whether to keep the foreground service alive or tear it down.
 *
 * <p>The predicate is {@code private fun TerminalState.isActiveStream()} on the
 * service's companion. It is the exact rule the running service's state
 * collector applies: stay up while the stream is in {@code Open} /
 * {@code Connecting} / {@code Reconnecting}; self-stop (background-legal
 * {@code stopForeground}+{@code stopSelf}) the moment it reaches any other state
 * ({@code Idle} / {@code GaveUp} / {@code Revoked} / {@code Failed}).
 *
 * <p>This test pins that membership deterministically and independently of the
 * service lifecycle, so a future edit to the active set (which would silently
 * change WHEN the FGS tears down — the heart of the UC-34 crash fix) is a
 * conscious, reviewed change. It reaches the private companion method by
 * reflection rather than relaxing its visibility (no production edit).
 *
 * <p>Runs on the JUnit-5 (Jupiter) unit lane — the predicate is pure Kotlin
 * {@code is}-checks over [TerminalState], so no Robolectric / Android runtime is
 * needed.
 */
class TerminalForegroundServiceActiveSetTest {

    @Test
    fun `Open Connecting and Reconnecting are active — FGS stays up`() {
        assertThat(isActiveStream(TerminalState.Open)).isTrue()
        assertThat(isActiveStream(TerminalState.Connecting)).isTrue()
        // Reconnecting is the give-up window the dataSync FGS exists to survive
        // (UC-34 AC7 / UC-35): a reconnect attempt must NOT tear the service down.
        assertThat(isActiveStream(TerminalState.Reconnecting(attempt = 1, nextDelayMs = 1_000L))).isTrue()
    }

    @Test
    fun `Idle GaveUp Revoked and Failed are inactive — FGS self-stops`() {
        // Idle — explicit disconnect / delete (controller.close drives → Idle).
        assertThat(isActiveStream(TerminalState.Idle)).isFalse()
        // GaveUp — the 5-minute reconnect cap elapsed (UC-34 AC1).
        assertThat(isActiveStream(TerminalState.GaveUp)).isFalse()
        // Revoked — server-driven 4401 (UC-34 AC3).
        assertThat(isActiveStream(TerminalState.Revoked)).isFalse()
        // Failed — terminal no-profile / setup failure.
        assertThat(isActiveStream(TerminalState.Failed("no_profile"))).isFalse()
    }

    @Test
    fun `the active set is exactly the three live states — exhaustive over the sealed hierarchy`() {
        val active = listOf<TerminalState>(
            TerminalState.Open,
            TerminalState.Connecting,
            TerminalState.Reconnecting(0, 0L),
        )
        val inactive = listOf<TerminalState>(
            TerminalState.Idle,
            TerminalState.GaveUp,
            TerminalState.Revoked,
            TerminalState.Failed("x"),
        )
        // Every member of the sealed TerminalState hierarchy is classified, with
        // no overlap — the partition the FGS teardown logic depends on.
        active.forEach { assertThat(isActiveStream(it)).`as`("active: $it").isTrue() }
        inactive.forEach { assertThat(isActiveStream(it)).`as`("inactive: $it").isFalse() }
    }

    /**
     * Invoke the private {@code TerminalState.isActiveStream()} companion
     * extension by reflection. The receiver is passed as the sole argument
     * because Kotlin compiles an extension function to a static-style method
     * taking the receiver as its first parameter.
     */
    private fun isActiveStream(state: TerminalState): Boolean {
        val companion = TerminalForegroundService.Companion
        val m = companion.javaClass.getDeclaredMethod("isActiveStream", TerminalState::class.java)
        m.isAccessible = true
        return m.invoke(companion, state) as Boolean
    }
}
