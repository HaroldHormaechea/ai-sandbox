package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.stream.service.SwarmEnumerationService.AgentMeta;
import com.aisandbox.server.stream.service.SwarmEnumerationService.PaneRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-24 — pure-function coverage for
 * {@link SwarmEnumerationService#chooseMainIndex(List)}, the deterministic
 * single-{@code main} selector for the default-socket pane scan. Lives in the
 * service package so it can construct {@link PaneRow} / {@link AgentMeta} and
 * call the package-visible static directly — ZERO process mocking, no Docker, no
 * tmux. This is the unit-level companion to the behavioural default-socket tests
 * in {@code com.aisandbox.server.stream.SwarmEnumerationServiceTest} (which drive
 * the same logic through the public {@code enumerate(int)} with a mocked
 * {@code ProcessExecutor}).
 *
 * <p>The contract: the orchestrator is the base-session pane whose claude argv
 * was read AND lacks {@code --agent-name}; among several such panes the lowest
 * {@code window.pane} wins; when NO pane is a conclusive no-agent claude
 * (unreadable / ambiguous argv) it falls back to the lowest {@code window.pane}
 * overall so there is always exactly one, deterministic, main.
 */
class SwarmEnumerationChooseMainTest {

    /** A pane row with its recovered metadata; the title is irrelevant to the pick. */
    private static PaneRow row(String window, String pane, AgentMeta meta) {
        return new PaneRow("main", window, pane, "pid-" + window + "-" + pane, "").withMeta(meta);
    }

    /** argv was read and carried no {@code --agent-name} → the orchestrator signature. */
    private static AgentMeta noAgentClaude() {
        AgentMeta m = new AgentMeta();
        m.argvRead = true;
        m.agentName = null;
        return m;
    }

    /** argv was read and carried a name → a teammate (never the main). */
    private static AgentMeta teammate(String name) {
        AgentMeta m = new AgentMeta();
        m.argvRead = true;
        m.agentName = name;
        return m;
    }

    /** argv could not be read (proc race / permission / no claude descendant). */
    private static AgentMeta unreadable() {
        return new AgentMeta(); // argvRead == false, all-null
    }

    @Test
    void picks_the_no_agent_claude_pane_as_main_over_named_teammates() {
        List<PaneRow> rows = List.of(
                row("0", "0", teammate("alice")),
                row("0", "1", noAgentClaude()), // the orchestrator
                row("0", "2", teammate("bob")));
        assertThat(SwarmEnumerationService.chooseMainIndex(rows)).isEqualTo(1);
    }

    @Test
    void among_several_no_agent_claude_panes_the_lowest_window_pane_wins() {
        List<PaneRow> rows = List.of(
                row("0", "2", noAgentClaude()),
                row("0", "0", noAgentClaude()), // lowest window.pane → main
                row("1", "0", noAgentClaude()));
        assertThat(SwarmEnumerationService.chooseMainIndex(rows)).isEqualTo(1);
    }

    @Test
    void window_index_takes_precedence_over_pane_index_in_ordering() {
        List<PaneRow> rows = List.of(
                row("1", "0", noAgentClaude()),
                row("0", "9", noAgentClaude())); // window 0 < window 1 → main even though pane 9 > 0
        assertThat(SwarmEnumerationService.chooseMainIndex(rows)).isEqualTo(1);
    }

    @Test
    void falls_back_to_the_lowest_window_pane_when_no_argv_is_readable() {
        List<PaneRow> rows = List.of(
                row("1", "3", unreadable()),
                row("0", "5", unreadable()),
                row("0", "2", unreadable())); // lowest window.pane → main
        assertThat(SwarmEnumerationService.chooseMainIndex(rows)).isEqualTo(2);
    }

    @Test
    void a_named_only_team_still_yields_a_deterministic_main_via_the_fallback() {
        // No orchestrator pane present (every pane is a named teammate, argvRead).
        // None qualifies as a no-agent claude, so the lowest window.pane is main.
        List<PaneRow> rows = List.of(row("0", "1", teammate("alice")), row("0", "0", teammate("bob"))); // lowest → main
        assertThat(SwarmEnumerationService.chooseMainIndex(rows)).isEqualTo(1);
    }
}
