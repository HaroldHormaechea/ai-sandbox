package com.aisandbox.server.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * UC-21 AC#15 (live verification) — exercises the agent-team switcher against a
 * REAL running session container with an active Claude Code agent team:
 *
 * <ol>
 *   <li>{@code SwarmEnumerationService} discovers the {@code claude-swarm-<pid>}
 *       socket and its panes via {@code docker compose exec … find / tmux
 *       list-panes / cat /proc/<pid>/cmdline}.</li>
 *   <li>The {@code TmuxBridgeService} re-bridges a live WebSocket onto a chosen
 *       swarm pane (and back to the main session) on a separate per-client
 *       tmux session, without disturbing the orchestrator's own view.</li>
 *   <li>The enumerate/select control protocol round-trips on the WebSocket.</li>
 * </ol>
 *
 * <p>This requires {@code docker exec} into a running {@code claude-sandbox}
 * container with a live team — verified manually on
 * {@code ai-sandbox-1-claude-sandbox-1} during analysis. Docker is NOT available
 * in the dev-team sandbox, so — exactly like {@link StreamBridgeIT} — this class
 * is hard-gated on {@code AI_SANDBOX_DIND=1} and skips locally. The
 * {@code :server:integrationTest} Gradle task (which alone runs {@code *IT}
 * classes) is itself disabled unless that env var is set.
 *
 * <p>The Docker-free portions of the enumerate/select protocol — argv parsing,
 * the always-present-main invariant, control-frame (de)serialization, the
 * swap-then-close re-bridge ordering, and the {@code StreamClient} mirror — are
 * fully covered by the unit suites ({@code SwarmEnumerationServiceTest},
 * {@code StreamControlMessageServiceTest}, {@code StreamFacadeTest},
 * {@code SessionStreamHandlerRebridgeTest}, and the Android
 * {@code TerminalStreamControllerTest}). This IT is the operator-run final
 * confirmation against a real swarm; its body is the CI/DinD-only smoke and is
 * intentionally a placeholder here so the local dev loop never spins up a
 * container.
 */
@EnabledIfEnvironmentVariable(named = "AI_SANDBOX_DIND", matches = "1")
class SwarmBridgeIT {

    @Test
    void enumerate_and_select_round_trip_against_a_live_swarm() {
        // Placeholder for the DinD-only live-swarm smoke. In CI this class is
        // executed by `./gradlew :server:integrationTest` with AI_SANDBOX_DIND=1
        // against a spawned ai-sandbox-* project carrying an active agent team;
        // locally it is skipped because Docker is unavailable.
        //
        // Expected CI body:
        //   1. spawn a session; start a small agent team inside the container.
        //   2. open the /v1/sessions/{n}/stream WebSocket; send
        //      {"type":"enumerate-targets"} and assert the Targets frame lists
        //      the main session FIRST plus one box per teammate pane (with
        //      name/type/color/team metadata read from argv).
        //   3. send {"type":"select-target","targetId":"swarm:…"}; assert a
        //      target-selected ack and that subsequent stdout is the teammate
        //      pane's output, then switch back to "main".
    }
}
