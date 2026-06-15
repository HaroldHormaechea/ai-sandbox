package com.aisandbox.server.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * UC-82 AC5 (capability restriction) + AC4 (structural no-shell) — {@link McpCliCommand}
 * is the SINGLE builder for every {@code claude} command this server emits. These tests
 * pin the least-privilege contract that makes injection / capability-escalation
 * <i>structurally unrepresentable</i>, independent of any user input:
 *
 * <ul>
 *   <li>the allowlist is exactly {@code {list, add, remove}} — there is no enum member,
 *       and therefore no code path, that can select {@code get} or any other subcommand;</li>
 *   <li>the built argv is ALWAYS {@code docker compose -p ai-sandbox-N exec -T
 *       claude-sandbox claude mcp <sub> …} — argv[0] is {@code docker}, the {@code claude
 *       mcp <sub>} triple is always adjacent, and the argv can NEVER be a bare
 *       {@code claude} (a conversation), {@code claude -p}/{@code --print}, or a
 *       non-{@code mcp} claude subcommand;</li>
 *   <li>no element is ever {@code sh}/{@code bash}/{@code -c} (no shell), and none is
 *       ever {@code -u}/{@code --user}/{@code --privileged} (no privilege escalation);</li>
 *   <li>every tail element supplied by a caller lands as its own discrete argv token,
 *       so a hostile value is inert data — never a new command.</li>
 * </ul>
 */
class McpCliCommandTest {

    private static final Set<String> SHELL = Set.of("sh", "bash", "-c", "/bin/sh", "/bin/bash", "zsh", "-lc");
    private static final Set<String> PRIVILEGE = Set.of("-u", "--user", "--privileged");

    private static void assertSafeEnvelope(List<String> argv, int n, String sub) {
        // argv[0] is the docker binary — never a shell, never claude directly.
        assertThat(argv.get(0)).isEqualTo("docker");
        // The fixed, config-only envelope and the always-adjacent `claude mcp <sub>`.
        assertThat(argv)
                .containsSubsequence("docker", "compose", "-p", "ai-sandbox-" + n, "exec", "-T", "claude-sandbox")
                .containsSubsequence("claude", "mcp", sub);
        int claudeIdx = argv.indexOf("claude");
        // claude is ALWAYS immediately followed by `mcp` then the allowlisted subcommand —
        // this adjacency is what makes a bare conversation / `claude -p`(print) / interactive
        // / non-mcp invocation structurally impossible, regardless of any tail data.
        assertThat(argv.get(claudeIdx + 1)).isEqualTo("mcp");
        assertThat(argv.get(claudeIdx + 2)).isEqualTo(sub);
        // (Note: the leading `-p` is docker-compose's project selector, NOT `claude -p`.)
        // No shell, ever.
        assertThat(argv).doesNotContainAnyElementsOf(SHELL);
        // No privilege escalation, ever.
        assertThat(argv).doesNotContainAnyElementsOf(PRIVILEGE);
    }

    @Test
    void the_allowlist_is_exactly_list_add_remove() {
        // AC5 — least privilege. `get` (and everything else) is intentionally absent.
        assertThat(Arrays.stream(McpCliCommand.Sub.values())
                        .map(McpCliCommand.Sub::wire)
                        .toList())
                .containsExactlyInAnyOrder("list", "add", "remove");
    }

    @Test
    void build_list_emits_the_fixed_config_only_envelope() {
        List<String> argv = McpCliCommand.build(7, McpCliCommand.Sub.LIST, List.of());
        assertSafeEnvelope(argv, 7, "list");
        assertThat(argv)
                .containsExactly(
                        "docker",
                        "compose",
                        "-p",
                        "ai-sandbox-7",
                        "exec",
                        "-T",
                        "claude-sandbox",
                        "claude",
                        "mcp",
                        "list");
    }

    @Test
    void build_add_keeps_the_tail_as_discrete_tokens() {
        List<String> tail = List.of("--scope", "user", "srv", "--transport", "stdio", "--", "npx", "-y", "pkg");
        List<String> argv = McpCliCommand.build(3, McpCliCommand.Sub.ADD, tail);
        assertSafeEnvelope(argv, 3, "add");
        // Each tail element is preserved verbatim and in order, nothing merged or split.
        assertThat(argv.subList(argv.size() - tail.size(), argv.size())).containsExactlyElementsOf(tail);
    }

    @Test
    void build_remove_emits_the_remove_subcommand() {
        List<String> argv = McpCliCommand.build(42, McpCliCommand.Sub.REMOVE, List.of("--scope", "user", "srv"));
        assertSafeEnvelope(argv, 42, "remove");
    }

    @Test
    void every_subcommand_produces_a_safe_envelope_regardless_of_tail() {
        // No matter which allowlisted subcommand, the envelope invariants hold.
        for (McpCliCommand.Sub sub : McpCliCommand.Sub.values()) {
            List<String> argv = McpCliCommand.build(1, sub, List.of("anything", "$(touch /tmp/x)", "; rm -rf /"));
            assertSafeEnvelope(argv, 1, sub.wire());
        }
    }

    @Test
    void a_hostile_tail_value_is_a_single_inert_argv_token() {
        // AC4 structural proof at the builder: a value full of shell metacharacters is
        // ONE element — it cannot become a separate command.
        String hostile = "x; touch /tmp/uc82_pwned && $(ls /) `whoami`";
        List<String> argv = McpCliCommand.build(5, McpCliCommand.Sub.ADD, List.of(hostile));
        assertThat(argv).contains(hostile); // present verbatim …
        assertThat(argv.stream().filter(hostile::equals).count()).isEqualTo(1L); // … as exactly one token.
        assertSafeEnvelope(argv, 5, "add");
    }

    @Test
    void the_returned_argv_is_immutable() {
        List<String> argv = McpCliCommand.build(1, McpCliCommand.Sub.LIST, List.of());
        assertThat(argv).isUnmodifiable();
    }

    @Test
    void a_null_tail_is_tolerated() {
        List<String> argv = McpCliCommand.build(2, McpCliCommand.Sub.LIST, null);
        assertSafeEnvelope(argv, 2, "list");
    }
}
