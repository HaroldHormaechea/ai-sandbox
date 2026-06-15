package com.aisandbox.server.mcp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * UC-82 (AC4 + AC5) — the single, centralized builder for <b>every</b> {@code claude}
 * command this server emits into a session container. There is exactly one public
 * entry point, {@link #build(int, Sub, List)}, and every MCP exec path
 * ({@link McpInventoryService}, {@link McpRegistrationService}) routes through it —
 * verifiable with the call-graph tool ({@code find-callers} on this method).
 *
 * <p><b>Why this is structurally injection- and privilege-safe:</b>
 *
 * <ul>
 *   <li><b>No shell (AC4).</b> The returned argv is handed to {@link
 *       com.aisandbox.server.sessions.service.ProcessExecutor}, which runs {@code new
 *       ProcessBuilder(argv)} — no {@code sh -c}, no string interpolation. Every
 *       user-supplied value (a name, command, URL, arg, env entry, header) is a
 *       discrete argv element, so a crafted value containing {@code ;}, {@code $(…)},
 *       backticks, {@code &&} or a newline is inert data, never a new command.</li>
 *   <li><b>Config-only, allowlisted subcommand (AC5).</b> The {@code claude} verb is
 *       fixed to {@code mcp} and the {@code mcp} subcommand is chosen ONLY from the
 *       compile-time {@link Sub} enum — never from user input. A runtime assert
 *       additionally rejects any subcommand outside the closed {@link #ALLOWED} set.
 *       So a conversation / {@code -p} / interactive / arbitrary-{@code claude}
 *       invocation is <i>structurally unrepresentable</i>: there is no code path that
 *       can emit a non-{@code mcp} {@code claude} command, and user input can neither
 *       select the subcommand nor inject a leading flag.</li>
 *   <li><b>No privilege escalation.</b> The argv contains no {@code -u} / {@code --user}
 *       / {@code --privileged}; {@code docker compose exec} runs as the compose
 *       service's configured (non-root) user, preserving the v2 confinement model.</li>
 * </ul>
 */
final class McpCliCommand {

    private McpCliCommand() {}

    /**
     * AC5 — the closed, compile-time set of {@code claude mcp} subcommands this server
     * may ever invoke. Least-privilege: {@code get} is intentionally absent (no caller
     * needs it). Adding a member is a deliberate source change, not a runtime decision.
     */
    enum Sub {
        LIST,
        ADD,
        REMOVE;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** AC5 runtime assert backstop — wire forms of {@link Sub}. */
    private static final Set<String> ALLOWED = Set.of("list", "add", "remove");

    /**
     * Build the argv for {@code docker compose -p ai-sandbox-{n} exec -T claude-sandbox
     * claude mcp <sub> <tail…>}.
     *
     * @param n    session number
     * @param sub  the allowlisted subcommand (never derived from user input)
     * @param tail subcommand-specific argv elements (flags, name, command/URL, args,
     *             {@code -e}/{@code --header} pairs); each becomes a discrete argv token.
     *             May be empty; must not contain nulls.
     * @return an immutable argv list ready for {@link
     *     com.aisandbox.server.sessions.service.ProcessExecutor#run}
     */
    static List<String> build(int n, Sub sub, List<String> tail) {
        Objects.requireNonNull(sub, "sub");
        String subcommand = sub.wire();
        // AC5 defense-in-depth on top of the enum: a subcommand outside the closed
        // allowlist can never be emitted. Unreachable given the enum, by design.
        if (!ALLOWED.contains(subcommand)) {
            throw new IllegalStateException("disallowed claude mcp subcommand: " + subcommand);
        }
        List<String> argv = new ArrayList<>(16);
        argv.add("docker");
        argv.add("compose");
        argv.add("-p");
        argv.add("ai-sandbox-" + n);
        argv.add("exec");
        argv.add("-T");
        argv.add("claude-sandbox");
        argv.add("claude");
        argv.add("mcp");
        argv.add(subcommand);
        if (tail != null) {
            argv.addAll(tail);
        }
        return List.copyOf(argv);
    }
}
