package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo;
import com.aisandbox.server.stream.service.TmuxBridgeService.BridgeTarget;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-21 — enumerates the stream targets available for a session: the
 * always-present main tmux session plus any Claude Code agent-team members.
 *
 * <p>Agent-team teammates are rendered by Claude Code as <b>tmux panes</b> in a
 * window on a separate {@code claude-swarm-<pid>} tmux server (a distinct socket
 * under {@code /tmp/tmux-<uid>/}), tagged via process argv
 * ({@code --agent-name/--agent-type/--agent-color/--team-name}). The main
 * session lives on the container's default socket.
 *
 * <p>This service mirrors {@link com.aisandbox.server.sessions.service.DockerEnumerationService}:
 * pure Java, argv-only {@link ProcessExecutor} calls, no Docker SDK — so it is
 * unit-testable by mocking {@code ProcessExecutor}. Everything is discovered
 * dynamically; the swarm pid is <b>never</b> hard-coded. The socket naming, the
 * pane-not-window layout, and the argv metadata source are upstream-owned and
 * version-volatile, so discovery is defensive and degrades gracefully
 * ({@code unknown}/generic metadata) when a pane's {@code /proc/<pid>/cmdline}
 * is unreadable.
 */
@Service
public class SwarmEnumerationService {

    private static final Logger LOG = LoggerFactory.getLogger(SwarmEnumerationService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Id of the always-present main-session target (AC#10). */
    public static final String MAIN_ID = "main";

    /**
     * tmux list-panes format: tab-separated
     * {@code session_name \t window_index \t pane_index \t pane_pid \t pane_title}.
     * The title is last because it may contain spaces (e.g. "✳ general-purpose").
     */
    private static final String PANE_FORMAT =
            "#{session_name}\t#{window_index}\t#{pane_index}\t#{pane_pid}\t#{pane_title}";

    private final ProcessExecutor exec;

    public SwarmEnumerationService(ProcessExecutor exec) {
        this.exec = exec;
    }

    /**
     * Enumerate the targets for session {@code n}. The main target is always
     * first (AC#10); swarm panes follow in discovery order. Never throws on a
     * docker/tmux failure — degrades to the main target alone.
     */
    public List<TargetInfo> enumerate(int n) {
        List<TargetInfo> out = new ArrayList<>();
        // The main session is always present, even when no team is running, and
        // even when the default socket has no server yet (placeholder).
        out.add(mainTarget());

        String project = "ai-sandbox-" + n;
        for (String socket : discoverSwarmSockets(project)) {
            out.addAll(panesOnSocket(project, socket));
        }
        return out;
    }

    /**
     * Resolve a target id (from a prior {@link #enumerate(int)}) to a
     * {@link BridgeTarget} the {@link TmuxBridgeService} can attach. Re-enumerates
     * so a moved/vanished pane is detected rather than blindly trusted.
     *
     * @throws NoSuchElementException if the id no longer resolves to a live target.
     */
    public BridgeTarget resolveTarget(int n, String targetId) {
        if (targetId == null || targetId.isBlank() || MAIN_ID.equals(targetId)) {
            return BridgeTarget.main();
        }
        for (TargetInfo t : enumerate(n)) {
            if (t.id().equals(targetId)) {
                if (MAIN_ID.equals(t.id())) {
                    return BridgeTarget.main();
                }
                return new BridgeTarget(t.socket(), t.session(), t.window(), t.pane());
            }
        }
        throw new NoSuchElementException("Unknown or vanished stream target: " + targetId);
    }

    private static TargetInfo mainTarget() {
        return new TargetInfo(MAIN_ID, "main", "main", null, null, null, null, null, "main", null, null);
    }

    /** {@code find /tmp -maxdepth 2 -type s -name 'claude-swarm-*'} → socket paths. */
    private List<String> discoverSwarmSockets(String project) {
        List<String> sockets = new ArrayList<>();
        try {
            ProcessExecutor.Result r = exec.run(
                    List.of(
                            "docker", "compose", "-p", project, "exec", "-T", "claude-sandbox",
                            "find", "/tmp", "-maxdepth", "2", "-type", "s", "-name", "claude-swarm-*"),
                    null,
                    TIMEOUT);
            if (r.exitCode() != 0) {
                // find returns non-zero on permission noise even when it printed
                // matches, so still parse stdout; just log at debug.
                LOG.debug("swarm socket find exit={} stderr={}", r.exitCode(), r.stderr());
            }
            for (String line : r.stdout().split("\\R")) {
                String s = line.trim();
                if (!s.isEmpty()) {
                    sockets.add(s);
                }
            }
        } catch (IOException io) {
            LOG.info("swarm socket discovery failed for {} (no team running?): {}", project, io.toString());
        }
        return sockets;
    }

    /** {@code tmux -S <socket> list-panes -a -F <fmt>} → one {@link TargetInfo} per pane. */
    private List<TargetInfo> panesOnSocket(String project, String socket) {
        List<TargetInfo> out = new ArrayList<>();
        String socketName = basename(socket);
        try {
            ProcessExecutor.Result r = exec.run(
                    List.of(
                            "docker", "compose", "-p", project, "exec", "-T", "claude-sandbox",
                            "tmux", "-S", socket, "list-panes", "-a", "-F", PANE_FORMAT),
                    null,
                    TIMEOUT);
            if (r.exitCode() != 0) {
                LOG.debug("tmux list-panes on {} exit={} stderr={}", socket, r.exitCode(), r.stderr());
                return out;
            }
            for (String line : r.stdout().split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split("\t", 5);
                if (f.length < 4) {
                    continue;
                }
                String session = f[0];
                String window = f[1];
                String pane = f[2];
                String pid = f[3];
                String paneTitle = f.length >= 5 ? f[4] : "";
                out.add(buildPaneTarget(project, socketName, socket, session, window, pane, pid, paneTitle));
            }
        } catch (IOException io) {
            LOG.info("tmux list-panes failed on socket {}: {}", socket, io.toString());
        }
        return out;
    }

    private TargetInfo buildPaneTarget(
            String project,
            String socketName,
            String socketPath,
            String session,
            String window,
            String pane,
            String pid,
            String paneTitle) {
        AgentMeta meta = readAgentMeta(project, pid);
        // A teammate exposes --agent-name; a pane without it is the orchestrator
        // (or an unidentifiable process — degrade to orchestrator).
        String kind = (meta.agentName != null) ? "swarm" : "orchestrator";
        String title = !isBlank(paneTitle) ? paneTitle : (meta.agentName != null ? meta.agentName : "(agent)");
        String id = "swarm:" + socketName + ":" + window + "." + pane;
        return new TargetInfo(
                id,
                kind,
                title,
                meta.agentName,
                meta.agentType,
                meta.agentColor,
                meta.teamName,
                socketPath,
                session,
                window,
                pane);
    }

    /**
     * Enrich a pane from {@code /proc/<pid>/cmdline} (NUL-separated argv).
     * Degrades to all-null when the pid is unreadable (race, permission, exited).
     */
    private AgentMeta readAgentMeta(String project, String pid) {
        AgentMeta meta = new AgentMeta();
        if (isBlank(pid) || !pid.chars().allMatch(Character::isDigit)) {
            return meta;
        }
        try {
            ProcessExecutor.Result r = exec.run(
                    List.of(
                            "docker", "compose", "-p", project, "exec", "-T", "claude-sandbox",
                            "cat", "/proc/" + pid + "/cmdline"),
                    null,
                    TIMEOUT);
            if (r.exitCode() != 0 || r.stdout().isEmpty()) {
                return meta;
            }
            String[] argv = r.stdout().split("\0");
            for (int i = 0; i < argv.length; i++) {
                String tok = argv[i];
                if (tok.startsWith("--agent-name")) {
                    meta.agentName = flagValue(argv, i);
                } else if (tok.startsWith("--agent-type")) {
                    meta.agentType = flagValue(argv, i);
                } else if (tok.startsWith("--agent-color")) {
                    meta.agentColor = flagValue(argv, i);
                } else if (tok.startsWith("--team-name")) {
                    meta.teamName = flagValue(argv, i);
                }
            }
        } catch (IOException io) {
            LOG.debug("cmdline read failed for pid {}: {}", pid, io.toString());
        }
        return meta;
    }

    /** Read a flag's value, supporting both {@code --flag value} and {@code --flag=value}. */
    private static String flagValue(String[] argv, int i) {
        String tok = argv[i];
        int eq = tok.indexOf('=');
        if (eq >= 0) {
            String v = tok.substring(eq + 1);
            return v.isBlank() ? null : v;
        }
        if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
            String v = argv[i + 1];
            return v.isBlank() ? null : v;
        }
        return null;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Mutable holder for the four argv-sourced metadata fields. */
    private static final class AgentMeta {
        String agentName;
        String agentType;
        String agentColor;
        String teamName;
    }
}
