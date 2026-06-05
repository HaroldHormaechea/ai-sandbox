package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo;
import com.aisandbox.server.stream.service.TmuxBridgeService.BridgeTarget;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-21 — enumerates the stream targets available for a session: the
 * always-present main tmux session plus any Claude Code agent-team members.
 *
 * <p><b>Two upstream layouts are supported.</b> Historically Claude Code rendered
 * agent-team teammates as <b>tmux panes</b> in a window on a separate
 * {@code claude-swarm-<pid>} tmux server (a distinct socket under
 * {@code /tmp/tmux-<uid>/}). The current layout (UC-24 live finding) places the
 * teammates as <b>panes in the {@code main} session's window on the default
 * socket</b> — there is no {@code claude-swarm-*} socket at all. Both are scanned
 * and merged: the default-socket {@code main}-session pane scan (the new primary
 * path) plus the legacy {@code claude-swarm-*} socket scan (kept for older Claude
 * Code builds). In every case panes are tagged via process argv
 * ({@code --agent-name/--agent-type/--agent-color/--team-name}).
 *
 * <p>On the default socket the teammate processes are children of the pane's
 * wrapper shell, so the pane pid's own {@code /proc/<pid>/cmdline} is the shell
 * (no argv flags). Metadata is therefore recovered by walking the pane pid's
 * process subtree ({@code /proc/<pid>/task/<pid>/children}) down to the
 * {@code claude}/{@code claude.exe} descendant and reading <i>its</i> argv. The
 * base-session pane whose claude argv lacks {@code --agent-name} is the
 * orchestrator and becomes the single {@code main} target; every other pane is a
 * teammate tile (never dropped, never a second main — falling back to the lowest
 * {@code window.pane} as main when argv is unreadable/ambiguous).
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
     * The Claude Code base session on the container's default socket. The
     * default-socket pane scan is anchored to this session; per-client
     * {@code client-*} sessions (created by {@link TmuxBridgeService}) are
     * excluded so an attached Android client never enumerates itself.
     */
    static final String BASE_SESSION = "main";

    /** Id-prefix for a default-socket {@code main}-session teammate pane tile. */
    private static final String DEFAULT_SOCKET_TILE_PREFIX = "swarm:main:";

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
     * first (AC#10); teammate panes follow in discovery order, deduped by id.
     * Two scans are merged: the default-socket {@code main}-session pane scan
     * (current Claude Code layout) and the legacy {@code claude-swarm-*} socket
     * scan (older layout). Never throws on a docker/tmux failure — degrades to
     * the main target alone.
     */
    public List<TargetInfo> enumerate(int n) {
        String project = "ai-sandbox-" + n;
        // Insertion order is the wire order; MAIN_ID is inserted first (AC#10).
        Map<String, TargetInfo> byId = new LinkedHashMap<>();

        // Primary path — panes of the base session on the container default socket.
        List<TargetInfo> mainSession = mainSessionTargets(project);
        TargetInfo main = null;
        for (TargetInfo t : mainSession) {
            if (MAIN_ID.equals(t.id())) {
                main = t;
                break;
            }
        }
        // The main session is always present, even when no team is running and
        // even when the default socket has no server yet (placeholder).
        byId.put(MAIN_ID, main != null ? main : mainTarget());
        for (TargetInfo t : mainSession) {
            if (!MAIN_ID.equals(t.id())) {
                byId.putIfAbsent(t.id(), t);
            }
        }

        // Legacy path — older Claude Code builds host the team on a separate
        // claude-swarm-<pid> socket. Merge + dedupe by id.
        for (String socket : discoverSwarmSockets(project)) {
            for (TargetInfo t : panesOnSocket(project, socket)) {
                byId.putIfAbsent(t.id(), t);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Resolve a target id (from a prior {@link #enumerate(int)}) to a
     * {@link BridgeTarget} the {@link TmuxBridgeService} can attach. Re-enumerates
     * so a moved/vanished pane is detected rather than blindly trusted.
     *
     * <p>{@link #MAIN_ID} resolves to the orchestrator pane's coordinates when the
     * default-socket scan recovered them ({@code hasPane()==true} so the per-client
     * bridge focuses + zooms the orchestrator pane rather than whatever pane tmux
     * last focused), falling back to the bare default-socket main session
     * (no window/pane) when the scan is empty — main is always resolvable.
     *
     * @throws NoSuchElementException if a non-main id no longer resolves to a live target.
     */
    public BridgeTarget resolveTarget(int n, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return BridgeTarget.main();
        }
        for (TargetInfo t : enumerate(n)) {
            if (t.id().equals(targetId)) {
                return new BridgeTarget(t.socket(), t.session(), t.window(), t.pane());
            }
        }
        if (MAIN_ID.equals(targetId)) {
            // Main is always selectable even if the scan momentarily returned nothing.
            return BridgeTarget.main();
        }
        throw new NoSuchElementException("Unknown or vanished stream target: " + targetId);
    }

    private static TargetInfo mainTarget() {
        return new TargetInfo(MAIN_ID, "main", "main", null, null, null, null, null, BASE_SESSION, null, null);
    }

    // ──────────────────────── default-socket main-session scan ────────────────────────

    /**
     * Scan the panes of the base session ({@link #BASE_SESSION}) on the container
     * default socket and build one {@link TargetInfo} per pane: exactly one
     * {@link #MAIN_ID} (the orchestrator) plus a teammate tile for every other
     * pane. Returns an empty list when the default socket has no server or the
     * scan fails — the caller then uses the placeholder main target.
     */
    private List<TargetInfo> mainSessionTargets(String project) {
        List<PaneRow> rows = listBaseSessionPanes(project);
        if (rows.isEmpty()) {
            return List.of();
        }
        for (PaneRow row : rows) {
            row.meta = readAgentMetaSubtree(project, row.pid);
        }
        int mainIdx = chooseMainIndex(rows);
        List<TargetInfo> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PaneRow row = rows.get(i);
            if (i == mainIdx) {
                // The orchestrator pane becomes the single main target, carrying its
                // window/pane so an explicit main re-selection zooms that exact pane.
                out.add(new TargetInfo(
                        MAIN_ID, "main", "main", null, null, null, null, null, row.session, row.window, row.pane));
            } else {
                out.add(buildDefaultSocketTile(row));
            }
        }
        return out;
    }

    /**
     * {@code tmux list-panes -a -F <fmt>} on the default socket (no {@code -S}),
     * filtered to the base session (excludes per-client {@code client-*}
     * sessions). One {@link PaneRow} per pane. Empty on any failure.
     */
    private List<PaneRow> listBaseSessionPanes(String project) {
        List<PaneRow> rows = new ArrayList<>();
        try {
            ProcessExecutor.Result r = exec.run(
                    List.of(
                            "docker",
                            "compose",
                            "-p",
                            project,
                            "exec",
                            "-T",
                            "claude-sandbox",
                            "tmux",
                            "list-panes",
                            "-a",
                            "-F",
                            PANE_FORMAT),
                    null,
                    TIMEOUT);
            if (r.exitCode() != 0) {
                // No default-socket server yet (no session running) is the common
                // "no team" case — debug, not warn.
                LOG.debug("default-socket list-panes exit={} stderr={}", r.exitCode(), r.stderr());
                return rows;
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
                // Anchor to the base session; never enumerate our own client-* sessions.
                if (!BASE_SESSION.equals(session) || session.startsWith("client-")) {
                    continue;
                }
                String window = f[1];
                String pane = f[2];
                String pid = f[3];
                String paneTitle = f.length >= 5 ? f[4] : "";
                rows.add(new PaneRow(session, window, pane, pid, paneTitle));
            }
        } catch (IOException io) {
            LOG.info("default-socket list-panes failed for {}: {}", project, io.toString());
        }
        return rows;
    }

    /** Build a teammate tile for a non-orchestrator base-session pane. */
    private TargetInfo buildDefaultSocketTile(PaneRow row) {
        AgentMeta meta = row.meta;
        String id = DEFAULT_SOCKET_TILE_PREFIX + row.window + "." + row.pane;
        String label;
        if (!isBlank(meta.agentName)) {
            label = meta.agentName;
        } else {
            // No recovered name — disambiguate same-type/unknown panes with the
            // window.pane suffix so two tiles never collide on the same label.
            String base =
                    !isBlank(meta.agentType) ? meta.agentType : (!isBlank(row.paneTitle) ? row.paneTitle : "agent");
            label = base + " ·" + row.window + "." + row.pane;
        }
        // socket=null → container default socket; session is the base session so
        // the bridge links the per-client session to it and zooms this pane.
        return new TargetInfo(
                id,
                "swarm",
                label,
                meta.agentName,
                meta.agentType,
                meta.agentColor,
                meta.teamName,
                null,
                row.session,
                row.window,
                row.pane);
    }

    /**
     * Pick the index of the orchestrator pane among {@code rows}. The orchestrator
     * is the base-session pane whose claude argv was read AND lacks
     * {@code --agent-name}; among several such panes the lowest {@code window.pane}
     * wins. When no pane's argv is conclusively a no-agent claude process
     * (unreadable / ambiguous), falls back to the lowest {@code window.pane}
     * overall so there is always exactly one main and it is deterministic. Pure
     * function of the parsed rows — unit-testable without any process mocking.
     */
    static int chooseMainIndex(List<PaneRow> rows) {
        int best = -1;
        for (int i = 0; i < rows.size(); i++) {
            AgentMeta m = rows.get(i).meta;
            boolean noAgentClaude = m != null && m.argvRead && m.agentName == null;
            if (noAgentClaude && (best < 0 || comparePosition(rows.get(i), rows.get(best)) < 0)) {
                best = i;
            }
        }
        if (best >= 0) {
            return best;
        }
        // Fallback — lowest window.pane overall.
        best = 0;
        for (int i = 1; i < rows.size(); i++) {
            if (comparePosition(rows.get(i), rows.get(best)) < 0) {
                best = i;
            }
        }
        return best;
    }

    /** Order two panes by numeric window then numeric pane (string fallback). */
    private static int comparePosition(PaneRow a, PaneRow b) {
        int w = Integer.compare(parseIntSafe(a.window), parseIntSafe(b.window));
        if (w != 0) {
            return w;
        }
        return Integer.compare(parseIntSafe(a.pane), parseIntSafe(b.pane));
    }

    private static int parseIntSafe(String s) {
        if (s == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException nfe) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Recover agent metadata for a default-socket pane by walking the pane pid's
     * process subtree to its {@code claude}/{@code claude.exe} descendant and
     * reading that process's argv. The pane pid is the wrapper shell, so its own
     * cmdline carries no flags. {@code pgrep} is not installed in the sandbox image,
     * so the walk uses the {@code /proc/<pid>/task/<pid>/children} file directly.
     * Degrades to {@code argvRead=false} (all-null) when nothing is readable.
     */
    private AgentMeta readAgentMetaSubtree(String project, String panePid) {
        if (isBlank(panePid) || !isAllDigits(panePid)) {
            return new AgentMeta();
        }
        // Depth 2 covers wrapper-shell → claude, plus one extra level defensively.
        AgentMeta found = findClaudeMeta(project, panePid, 2);
        return found != null ? found : new AgentMeta();
    }

    /** DFS for the first {@code claude*} process at or below {@code pid}; null if none. */
    private AgentMeta findClaudeMeta(String project, String pid, int depthLeft) {
        String cmdline = catInContainer(project, "/proc/" + pid + "/cmdline");
        if (cmdline != null && !cmdline.isEmpty()) {
            String[] argv = cmdline.split("\0");
            if (looksLikeClaude(argv)) {
                return parseAgentFlags(argv);
            }
        }
        if (depthLeft <= 0) {
            return null;
        }
        for (String child : readProcChildren(project, pid)) {
            AgentMeta m = findClaudeMeta(project, child, depthLeft - 1);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /** Child pids from {@code /proc/<pid>/task/<pid>/children} (space-separated). */
    private List<String> readProcChildren(String project, String pid) {
        String out = catInContainer(project, "/proc/" + pid + "/task/" + pid + "/children");
        if (out == null) {
            return List.of();
        }
        List<String> kids = new ArrayList<>();
        for (String tok : out.trim().split("\\s+")) {
            if (!tok.isBlank() && isAllDigits(tok)) {
                kids.add(tok);
            }
        }
        return kids;
    }

    /** {@code cat <path>} in the sandbox container; trimmed stdout or null on failure. */
    private String catInContainer(String project, String path) {
        try {
            ProcessExecutor.Result r = exec.run(
                    List.of("docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", "cat", path),
                    null,
                    TIMEOUT);
            if (r.exitCode() != 0) {
                return null;
            }
            return r.stdout();
        } catch (IOException io) {
            LOG.debug("cat {} failed: {}", path, io.toString());
            return null;
        }
    }

    /** Whether argv[0]'s basename names a claude process ({@code claude}/{@code claude.exe}). */
    private static boolean looksLikeClaude(String[] argv) {
        if (argv.length == 0 || isBlank(argv[0])) {
            return false;
        }
        return basename(argv[0]).startsWith("claude");
    }

    /** Parse the four agent flags from a (claude) argv; marks {@code argvRead=true}. */
    private static AgentMeta parseAgentFlags(String[] argv) {
        AgentMeta meta = new AgentMeta();
        meta.argvRead = true;
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
        return meta;
    }

    private static boolean isAllDigits(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    // ──────────────────────── legacy claude-swarm-* socket scan ────────────────────────

    /** {@code find /tmp -maxdepth 2 -type s -name 'claude-swarm-*'} → socket paths. */
    private List<String> discoverSwarmSockets(String project) {
        List<String> sockets = new ArrayList<>();
        try {
            ProcessExecutor.Result r = exec.run(
                    List.of(
                            "docker",
                            "compose",
                            "-p",
                            project,
                            "exec",
                            "-T",
                            "claude-sandbox",
                            "find",
                            "/tmp",
                            "-maxdepth",
                            "2",
                            "-type",
                            "s",
                            "-name",
                            "claude-swarm-*"),
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
                            "docker",
                            "compose",
                            "-p",
                            project,
                            "exec",
                            "-T",
                            "claude-sandbox",
                            "tmux",
                            "-S",
                            socket,
                            "list-panes",
                            "-a",
                            "-F",
                            PANE_FORMAT),
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
                            "docker",
                            "compose",
                            "-p",
                            project,
                            "exec",
                            "-T",
                            "claude-sandbox",
                            "cat",
                            "/proc/" + pid + "/cmdline"),
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
    static final class AgentMeta {
        String agentName;
        String agentType;
        String agentColor;
        String teamName;

        /**
         * True once a {@code claude*} process's argv was actually read (default-socket
         * subtree walk). Distinguishes a genuine no-agent orchestrator
         * ({@code argvRead && agentName == null}) from an unreadable pane
         * ({@code !argvRead}) in {@link #chooseMainIndex(List)}. The legacy
         * single-level reader leaves it {@code false}.
         */
        boolean argvRead;
    }

    /**
     * A raw tmux pane row from the default-socket scan, plus the metadata recovered
     * for it. Package-visible so {@link #chooseMainIndex(List)} can be unit-tested
     * directly with constructed rows (no process mocking).
     */
    static final class PaneRow {
        final String session;
        final String window;
        final String pane;
        final String pid;
        final String paneTitle;
        AgentMeta meta = new AgentMeta();

        PaneRow(String session, String window, String pane, String pid, String paneTitle) {
            this.session = session;
            this.window = window;
            this.pane = pane;
            this.pid = pid;
            this.paneTitle = paneTitle;
        }

        /** Test helper — set the recovered metadata fluently. */
        PaneRow withMeta(AgentMeta m) {
            this.meta = m;
            return this;
        }
    }
}
