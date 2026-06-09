package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-46 AC3 — the Android [LifecycleAction] is a verbatim mirror of the server's
 * `com.aisandbox.server.sessions.dto.LifecycleAction`. The client greys out
 * exactly the actions the server would reject with 409 `session_state_conflict`,
 * so the [token] (path segment) and the [LifecycleAction.isValidFrom] transition
 * matrix MUST stay byte-identical to the server enum (pinned server-side by
 * `com.aisandbox.server.sessions.LifecycleActionTest`). The same matrix table is
 * asserted on both sides so a drift on either turns a test red.
 *
 * <p>Matrix:
 * <pre>
 *   START   ← stopped
 *   STOP    ← running, provisioning, paused
 *   PAUSE   ← running
 *   UNPAUSE ← paused
 * </pre>
 */
class LifecycleActionMirrorTest {

    /** The full server wire state-set (mirror of SessionRecord's state tokens). */
    private val allStates =
        listOf("running", "starting", "provisioning", "terminating", "paused", "stopped")

    @Test
    fun `wire tokens match the server path segments`() {
        assertThat(LifecycleAction.STOP.token).isEqualTo("stop")
        assertThat(LifecycleAction.START.token).isEqualTo("start")
        assertThat(LifecycleAction.PAUSE.token).isEqualTo("pause")
        assertThat(LifecycleAction.UNPAUSE.token).isEqualTo("unpause")
    }

    @Test
    fun `START is valid only from stopped`() {
        assertValidFromExactly(LifecycleAction.START, "stopped")
    }

    @Test
    fun `STOP is valid from running provisioning and paused`() {
        assertValidFromExactly(LifecycleAction.STOP, "running", "provisioning", "paused")
    }

    @Test
    fun `PAUSE is valid only from running`() {
        assertValidFromExactly(LifecycleAction.PAUSE, "running")
    }

    @Test
    fun `UNPAUSE is valid only from paused`() {
        assertValidFromExactly(LifecycleAction.UNPAUSE, "paused")
    }

    @Test
    fun `isValidFrom is false for an unknown state token`() {
        for (a in LifecycleAction.values()) {
            assertThat(a.isValidFrom("frobnicate")).`as`("%s from unknown", a).isFalse()
        }
    }

    private fun assertValidFromExactly(action: LifecycleAction, vararg validStates: String) {
        val valid = validStates.toSet()
        for (state in allStates) {
            val expected = state in valid
            assertThat(action.isValidFrom(state))
                .`as`("%s.isValidFrom(\"%s\") should be %s", action, state, expected)
                .isEqualTo(expected)
        }
    }
}
