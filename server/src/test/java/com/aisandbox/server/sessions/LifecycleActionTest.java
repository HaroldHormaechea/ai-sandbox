package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.sessions.dto.LifecycleAction;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-46 AC3 — the canonical state→action transition matrix encoded by
 * {@link LifecycleAction#isValidFrom(String)}. This is the single source of
 * truth for which lifecycle actions the server accepts from each state; the
 * Android client's {@code com.aisandbox.android.net.LifecycleAction} mirror MUST
 * stay byte-identical (asserted on that side by {@code LifecycleActionMirrorTest}).
 *
 * <p>The matrix (also pinned in the enum's Javadoc):
 *
 * <table border="1">
 *   <tr><th>Action</th><th>Valid from</th></tr>
 *   <tr><td>START</td><td>stopped</td></tr>
 *   <tr><td>STOP</td><td>running, provisioning, paused</td></tr>
 *   <tr><td>PAUSE</td><td>running</td></tr>
 *   <tr><td>UNPAUSE</td><td>paused</td></tr>
 * </table>
 *
 * <p>Every (action × state) pair in the wire state-set
 * {@code running | starting | provisioning | terminating | paused | stopped}
 * is asserted exhaustively below, so a future edit to the matrix can't silently
 * widen or narrow an action without turning this test red.
 */
class LifecycleActionTest {

    /** The full server wire state-set (see {@code SessionRecord} Javadoc). */
    private static final List<String> ALL_STATES =
            List.of("running", "starting", "provisioning", "terminating", "paused", "stopped");

    @Test
    void flag_tokens_match_the_wire_path_segments() {
        assertThat(LifecycleAction.STOP.flag()).isEqualTo("stop");
        assertThat(LifecycleAction.START.flag()).isEqualTo("start");
        assertThat(LifecycleAction.PAUSE.flag()).isEqualTo("pause");
        assertThat(LifecycleAction.UNPAUSE.flag()).isEqualTo("unpause");
    }

    @Test
    void fromToken_parses_each_action_case_insensitively() {
        assertThat(LifecycleAction.fromToken("stop")).isEqualTo(LifecycleAction.STOP);
        assertThat(LifecycleAction.fromToken("START")).isEqualTo(LifecycleAction.START);
        assertThat(LifecycleAction.fromToken("Pause")).isEqualTo(LifecycleAction.PAUSE);
        assertThat(LifecycleAction.fromToken("unPAUSE")).isEqualTo(LifecycleAction.UNPAUSE);
    }

    @Test
    void fromToken_rejects_unknown_or_null_tokens() {
        assertThatThrownBy(() -> LifecycleAction.fromToken("restart"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restart");
        assertThatThrownBy(() -> LifecycleAction.fromToken(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LifecycleAction.fromToken("")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── AC3 — the transition matrix, asserted exhaustively per action ────────

    @Test
    void START_is_valid_only_from_stopped() {
        assertValidFromExactly(LifecycleAction.START, "stopped");
    }

    @Test
    void STOP_is_valid_from_running_provisioning_and_paused() {
        assertValidFromExactly(LifecycleAction.STOP, "running", "provisioning", "paused");
    }

    @Test
    void PAUSE_is_valid_only_from_running() {
        assertValidFromExactly(LifecycleAction.PAUSE, "running");
    }

    @Test
    void UNPAUSE_is_valid_only_from_paused() {
        assertValidFromExactly(LifecycleAction.UNPAUSE, "paused");
    }

    @Test
    void isValidFrom_is_false_for_null_or_unknown_state() {
        for (LifecycleAction a : LifecycleAction.values()) {
            assertThat(a.isValidFrom(null))
                    .as("%s.isValidFrom(null) must be false (defensive)", a)
                    .isFalse();
            assertThat(a.isValidFrom("frobnicate"))
                    .as("%s.isValidFrom(unknown) must be false", a)
                    .isFalse();
        }
    }

    /**
     * Assert {@code action.isValidFrom(s)} is {@code true} for exactly the
     * states in {@code validStates} and {@code false} for every other state in
     * the wire set — the bidirectional pin that catches both over- and
     * under-permissive matrix edits.
     */
    private static void assertValidFromExactly(LifecycleAction action, String... validStates) {
        List<String> valid = List.of(validStates);
        for (String state : ALL_STATES) {
            boolean expected = valid.contains(state);
            assertThat(action.isValidFrom(state))
                    .as("%s.isValidFrom(\"%s\") should be %s", action, state, expected)
                    .isEqualTo(expected);
        }
    }
}
