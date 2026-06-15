package com.aisandbox.server.mcp.service;

import com.aisandbox.server.mcp.McpRegistrationException;
import com.aisandbox.server.mcp.dto.McpAddSpec;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-82 — domain service that registers ({@code claude mcp add}) and deregisters
 * ({@code claude mcp remove}) a session's MCP servers in the embedded Claude Code's
 * <b>user scope</b> ({@code --scope user}, persisted via the {@code claude-config →
 * /home/claude/.claude} bind mount). Sibling of {@link McpInventoryService}: pure
 * Java, argv-only {@link ProcessExecutor} calls, no shell, no string interpolation.
 *
 * <p><b>AC4 — injection-safe exec.</b> The argv is assembled here and emitted through
 * the single {@link ClaudeMcpCommand} chokepoint; every user-supplied value (name,
 * command, args, URL, env entries, headers) lands as a discrete argv token, so a
 * crafted value can never break out into an extra command. Positional stdio args are
 * additionally guarded by a {@code --} separator so a flag-looking value is treated
 * as a positional argument, not a new option.
 *
 * <p><b>AC5 — config-only, least-privilege.</b> The {@code claude} verb is fixed to
 * {@code mcp add} / {@code mcp remove} by {@link ClaudeMcpCommand.Sub}; no path can
 * emit a conversation / {@code -p} / arbitrary-{@code claude} invocation, and no
 * {@code -u}/{@code --user}/{@code --privileged} flag is ever added.
 *
 * <p><b>Secret hygiene.</b> This service never logs the env / header VALUES it places
 * on the argv. Failures degrade to a typed {@link McpRegistrationException} whose
 * message carries only the operation, server name, and the process exit/stderr.
 */
@Service
public class McpRegistrationService {

    private static final Logger LOG = LoggerFactory.getLogger(McpRegistrationService.class);

    /**
     * Bounds the whole {@code docker compose exec … claude mcp add/remove} call. A
     * stdio add merely writes config; an http/sse add may run an initial health check
     * against a remote server, so this mirrors {@link McpInventoryService}'s 45s cap
     * (a slow remote can take ~16s) while still bounding a genuinely hung command.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final ProcessExecutor exec;

    public McpRegistrationService(ProcessExecutor exec) {
        this.exec = exec;
    }

    /**
     * Register a new MCP server for session {@code n}. The spec is assumed already
     * validated by the facade (transport normalised, required fields present, no
     * leading-dash name/command). Throws {@link McpRegistrationException} on a
     * non-zero exit or I/O failure.
     */
    public void add(int n, McpAddSpec spec) {
        run(ClaudeMcpCommand.build(n, ClaudeMcpCommand.Sub.ADD, addTail(spec)), "add", spec.name());
    }

    /**
     * Deregister MCP server {@code name} from session {@code n}. AC2: this only
     * deregisters; an already-spawned child process is NOT hand-killed — it exits when
     * the session's MCP servers are next reloaded. Throws {@link
     * McpRegistrationException} on a non-zero exit or I/O failure.
     */
    public void remove(int n, String name) {
        run(ClaudeMcpCommand.build(n, ClaudeMcpCommand.Sub.REMOVE, List.of("--scope", "user", name)), "remove", name);
    }

    private void run(List<String> argv, String op, String serverName) {
        try {
            ProcessExecutor.Result r = exec.run(argv, null, TIMEOUT);
            if (r.exitCode() != 0) {
                LOG.info("claude mcp {} exit={} for '{}'", op, r.exitCode(), serverName);
                throw new McpRegistrationException("claude mcp " + op + " failed for '" + serverName + "' (exit "
                        + r.exitCode() + "): " + r.stderr());
            }
        } catch (IOException io) {
            // Typed degrade — never propagate the raw IOException into the request path.
            throw new McpRegistrationException(
                    "claude mcp " + op + " failed for '" + serverName + "': " + io.getMessage(), io);
        }
    }

    /**
     * Assemble the subcommand-specific tail for {@code claude mcp add}, mirroring the
     * approved proposal exactly:
     *
     * <ul>
     *   <li>stdio — {@code --scope user <name> --transport stdio [-e K=V …] -- <command> <args…>}</li>
     *   <li>http/sse — {@code --scope user --transport <http|sse> <name> <url> [--header "<H>" …]}</li>
     * </ul>
     *
     * Every value is a discrete element; the {@code --} guard before the stdio command
     * makes a flag-looking arg inert (AC4).
     */
    private static List<String> addTail(McpAddSpec spec) {
        List<String> t = new ArrayList<>();
        t.add("--scope");
        t.add("user");
        if ("stdio".equals(spec.transport())) {
            t.add(spec.name());
            t.add("--transport");
            t.add("stdio");
            if (spec.env() != null) {
                for (Map.Entry<String, String> e : spec.env().entrySet()) {
                    t.add("-e");
                    t.add(e.getKey() + "=" + e.getValue());
                }
            }
            // `--` ends option parsing: the command and its args are positional, so a
            // value that looks like a flag (e.g. "--foo") cannot be mistaken for one.
            t.add("--");
            t.add(spec.command());
            if (spec.args() != null) {
                t.addAll(spec.args());
            }
        } else {
            // http / sse
            t.add("--transport");
            t.add(spec.transport());
            t.add(spec.name());
            t.add(spec.url());
            if (spec.headers() != null) {
                for (String h : spec.headers()) {
                    t.add("--header");
                    t.add(h);
                }
            }
        }
        return t;
    }
}
