package com.aisandbox.server.mcp.service;

import com.aisandbox.server.mcp.dto.McpServerStatus;
import com.aisandbox.server.mcp.dto.McpState;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-67 — domain service that inventories a session's MCP servers by running
 * {@code claude mcp list} inside the session's {@code claude-sandbox} container,
 * exactly the way the embedded Claude Code knows them. Mirrors
 * {@link com.aisandbox.server.stream.service.SwarmEnumerationService} and
 * {@link com.aisandbox.server.sessions.service.DockerEnumerationService}: pure
 * Java, argv-only {@link ProcessExecutor} calls, no shell, no string
 * interpolation into a command line — so it is unit-testable with a mocked
 * executor and a hostile MCP name can never smuggle an extra command.
 *
 * <p><b>The {@code claude mcp} CLI surface is upstream-owned and version-
 * volatile</b>, so parsing is deliberately defensive: an unrecognised line is
 * skipped, an unrecognised status maps to {@link McpState#UNKNOWN}, and any
 * failure (non-zero exit, non-running / SERVER_SSH container, unparseable
 * output) degrades to an empty inventory rather than throwing — the posture of
 * the swarm / docker enumerators. The screen then renders the empty state.
 */
@Service
public class McpInventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(McpInventoryService.class);
    // Bounds the WHOLE `docker compose exec … claude mcp list` call, which in turn
    // runs a health-check sweep across every configured MCP server. A single SSE/HTTP
    // server (e.g. the Atlassian remote SSE server) can take ~16s to health-check in
    // this environment, and the cap wraps the entire multi-server sweep — not one
    // server — so a 10s cap made one slow remote server time out the whole command and
    // collapse a populated session to the empty inventory. 45s leaves headroom for a
    // multi-server sweep with one or more slow network health-checks while still
    // bounding a genuinely hung command (which still degrades to an empty list below).
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final ProcessExecutor exec;

    public McpInventoryService(ProcessExecutor exec) {
        this.exec = exec;
    }

    /**
     * List the MCP servers configured for session {@code n}. Never throws —
     * returns an empty list when the container is not running, the command is
     * unavailable, or the output cannot be parsed.
     */
    public List<McpServerStatus> list(int n) {
        String project = "ai-sandbox-" + n;
        String stdout;
        try {
            // UC-82 — route through the single McpCliCommand chokepoint so every
            // `claude mcp …` argv (list/add/remove) is built one way: no shell, and the
            // subcommand comes only from the allowlisted Sub enum, never user input.
            ProcessExecutor.Result r =
                    exec.run(McpCliCommand.build(n, McpCliCommand.Sub.LIST, List.of()), null, TIMEOUT);
            if (r.exitCode() != 0) {
                // Common, non-exceptional cases: no container running, or a
                // SERVER_SSH/non-sandbox session that has no `claude` at all.
                LOG.debug("claude mcp list exit={} for {}: {}", r.exitCode(), project, r.stderr());
                // `claude mcp list` can still print server lines on a non-zero
                // exit (a failing health check makes the overall command fail);
                // parse stdout anyway so a FAILED server still surfaces.
            }
            stdout = r.stdout();
        } catch (IOException io) {
            LOG.info("claude mcp list failed for {} (container not running?): {}", project, io.toString());
            return List.of();
        }
        return parseList(stdout);
    }

    /**
     * Re-inventory the session's MCP servers. Semantically identical to
     * {@link #list(int)} (a fresh {@code claude mcp list} re-runs the health
     * checks), exposed under its own name so the facade reads intention-revealing.
     */
    public List<McpServerStatus> refresh(int n) {
        return list(n);
    }

    // ──────────────────────── parsing ────────────────────────

    /**
     * Parse {@code claude mcp list} stdout into per-server statuses. The format is
     * upstream-owned; the recognised shape is one server per line:
     *
     * <pre>{@code
     *   <name>: <connection-detail> [(TRANSPORT)] - <health status>
     * }</pre>
     *
     * Lines that do not contain a {@code "<name>:"} prefix (banners such as
     * "Checking MCP server health…", blank lines) are skipped. A line with a
     * name but no {@code " - status"} tail is still surfaced as
     * {@link McpState#UNKNOWN} so a configured-but-unhealthy server is not
     * silently dropped. Package-visible for direct unit testing without process
     * mocking.
     */
    static List<McpServerStatus> parseList(String stdout) {
        List<McpServerStatus> out = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return out;
        }
        for (String raw : stdout.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue; // banner / non-entry line
            }
            String name = line.substring(0, colon).strip();
            // A name must look like an MCP identifier, not prose ("Checking MCP…").
            if (!isPlausibleName(name)) {
                continue;
            }
            String rest = line.substring(colon + 1).strip();

            // Split the trailing health status off the LAST " - " separator so a
            // command/URL containing a hyphen stays in the detail.
            String detailAndTransport = rest;
            String statusText = "";
            int sep = rest.lastIndexOf(" - ");
            if (sep >= 0) {
                detailAndTransport = rest.substring(0, sep).strip();
                statusText = rest.substring(sep + 3).strip();
            }

            String transport = detectTransport(detailAndTransport);
            McpState state = McpState.fromStatusText(statusText);
            out.add(new McpServerStatus(name, transport, state, detailAndTransport));
        }
        return out;
    }

    /**
     * Best-effort transport hint from the connection detail. Prefers an explicit
     * upstream-printed {@code (SSE)} / {@code (HTTP)} / {@code (STDIO)} marker,
     * else infers {@code http} from a URL and {@code stdio} from a command,
     * falling back to {@code unknown}.
     */
    static String detectTransport(String detail) {
        if (detail == null || detail.isBlank()) {
            return "unknown";
        }
        String d = detail.toLowerCase(Locale.ROOT);
        if (d.contains("(sse)")) {
            return "sse";
        }
        if (d.contains("(http)") || d.contains("(streamable")) {
            return "http";
        }
        if (d.contains("(stdio)")) {
            return "stdio";
        }
        if (d.startsWith("http://") || d.startsWith("https://")) {
            return "http";
        }
        return "stdio";
    }

    /** A plausible MCP server name: non-blank, no spaces, reasonable length. */
    private static boolean isPlausibleName(String name) {
        if (name.isEmpty() || name.length() > 128 || name.indexOf(' ') >= 0) {
            return false;
        }
        // Reject obvious prose fragments that ended on a colon.
        return name.chars().anyMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
    }
}
