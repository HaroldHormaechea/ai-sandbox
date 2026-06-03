package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Per-stream tmux bridge. Three-step startup mirrors AC32:
 *
 * <ol>
 *   <li>{@code docker compose -p ai-sandbox-N exec -T claude-sandbox tmux
 *       [-S <socket>] new-session -d -s client-<id> -t <baseSession>} — creates
 *       the per-client tmux session linked to the target's base session.</li>
 *   <li>{@code docker compose -p ai-sandbox-N exec -T claude-sandbox tmux
 *       [-S <socket>] set-option -t client-<id> mouse on} — enable xterm-mouse
 *       mode.</li>
 *   <li>pty4j spawns {@code docker compose -p ai-sandbox-N exec -it
 *       claude-sandbox tmux [-S <socket>] attach -t client-<id>}; PTY stdout
 *       becomes binary WebSocket frames, PTY stdin receives keystrokes + xterm
 *       mouse sequences.</li>
 * </ol>
 *
 * <p>UC-21 generalizes the bridge to target any {@link BridgeTarget}: the main
 * tmux session on the default socket (the original behaviour), or an agent-team
 * pane on a separate {@code claude-swarm-<pid>} socket. When the target names a
 * window/pane, the per-client session focuses + zooms that pane after creation
 * so the single pane fills the client view.
 *
 * <p>This service does NOT manage WebSocket I/O directly — that's the
 * facade's job. It exposes start / size / write / read / close primitives.
 */
@Service
public class TmuxBridgeService {

    private static final Logger LOG = LoggerFactory.getLogger(TmuxBridgeService.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);

    private final ProcessExecutor exec;

    public TmuxBridgeService(ProcessExecutor exec) {
        this.exec = exec;
    }

    /**
     * A bridge destination. The default socket / {@code main} session is the
     * original single-target behaviour; a {@code claude-swarm-<pid>} socket with
     * a window/pane addresses one agent-team teammate.
     *
     * @param socketPath  absolute tmux socket path, or {@code null} for the
     *                    container's default socket (the main session).
     * @param baseSession the source session the per-client session links to
     *                    (e.g. {@code main} or {@code claude-swarm}).
     * @param window      tmux window index to focus, or {@code null} (main).
     * @param pane        tmux pane index to focus + zoom, or {@code null} (main).
     */
    public record BridgeTarget(String socketPath, String baseSession, String window, String pane) {
        /** The default-socket main session — the pre-UC-21 single target. */
        public static BridgeTarget main() {
            return new BridgeTarget(null, "main", null, null);
        }

        public boolean hasPane() {
            return window != null && !window.isBlank() && pane != null && !pane.isBlank();
        }
    }

    /**
     * Main-compatible overload (pre-UC-21 signature). Bridges the default-socket
     * {@code main} session. Retained so existing callers + test mocks compile
     * unchanged.
     */
    public Bridge start(int n, String streamId, int cols, int rows) throws IOException {
        return start(n, streamId, BridgeTarget.main(), cols, rows);
    }

    /**
     * Starts the per-client tmux session against {@code target} and the
     * PTY-attached process.
     */
    public Bridge start(int n, String streamId, BridgeTarget target, int cols, int rows) throws IOException {
        String project = "ai-sandbox-" + n;
        String session = "client-" + streamId;
        String socket = (target == null ? null : target.socketPath());

        // Steps 1–2c — create + configure the per-client tmux session. Extracted
        // so it is unit-testable with a mocked ProcessExecutor (mirrors
        // SwarmEnumerationService); start() only adds the PTY attach below.
        prepareClientSession(project, socket, session, target);

        // Step 3 — PTY attach.
        // pty4j's setEnvironment REPLACES the child environment, so we must
        // inherit the JVM env (notably $PATH, matching ProcessExecutor's
        // ProcessBuilder behaviour) and overlay TERM. Otherwise the bare
        // "docker" argv can't be resolved ("Unable to get $PATH" /
        // Exec_tty error). The PATH fallback covers a systemd unit started
        // without one in its environment.
        Map<String, String> ptyEnv = new HashMap<>(System.getenv());
        ptyEnv.put("TERM", "xterm-256color");
        ptyEnv.computeIfAbsent("PATH", k -> "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        PtyProcessBuilder pb = new PtyProcessBuilder()
                .setCommand(attachArgv(project, socket, session))
                .setEnvironment(ptyEnv)
                .setInitialColumns(cols > 0 ? cols : 80)
                .setInitialRows(rows > 0 ? rows : 24);
        PtyProcess proc;
        try {
            proc = pb.start();
        } catch (IOException io) {
            // Best-effort kill of the orphaned tmux session.
            runBestEffort(tmuxExec(project, socket, "kill-session", "-t", session));
            throw io;
        }
        return new Bridge(project, session, socket, proc, exec);
    }

    /**
     * Pre-PTY tmux setup for the per-client session: create it linked to the
     * target's base session, enable mouse mode, hide the per-client status
     * chrome, and idempotently zoom the requested pane. Package-visible and
     * {@code exec}-driven so it can be unit-tested with a mocked
     * {@link ProcessExecutor} (mirrors {@link SwarmEnumerationService}).
     *
     * @throws IOException if the per-client session cannot be created (step 1);
     *     the remaining steps are best-effort and never abort the bridge.
     */
    void prepareClientSession(String project, String socket, String session, BridgeTarget target) throws IOException {
        String baseSession = (target == null || target.baseSession() == null) ? "main" : target.baseSession();

        // Step 1 — create the per-client session linked to the base session.
        ProcessExecutor.Result r1 = exec.run(
                tmuxExec(project, socket, "new-session", "-d", "-s", session, "-t", baseSession),
                null,
                STARTUP_TIMEOUT);
        if (r1.exitCode() != 0) {
            throw new IOException("tmux new-session failed: " + r1.stderr());
        }
        // Step 2 — enable mouse mode (best-effort).
        ProcessExecutor.Result r2 =
                exec.run(tmuxExec(project, socket, "set-option", "-t", session, "mouse", "on"), null, STARTUP_TIMEOUT);
        if (r2.exitCode() != 0) {
            LOG.warn("tmux set-option mouse on failed (continuing): {}", r2.stderr());
        }
        // Step 2c — hide the per-client status line for ALL targets (best-effort).
        // tmux's status line renders the window list to the client (the visible
        // "all windows shown" chrome the user reported). The option is scoped to
        // the per-client session via {@code -t <session>}, so other clients of the
        // shared base session keep their own status setting.
        runBestEffort(tmuxExec(project, socket, "set-option", "-t", session, "status", "off"));
        // Step 2b — focus + idempotently zoom the requested pane (agent-team
        // target). {@code resize-pane -Z} is a TOGGLE, so running it
        // unconditionally on an already-zoomed pane (a re-bridge / reconnect /
        // second client) toggles zoom OFF and exposes the unzoomed multi-pane
        // split — the regression this fix targets. We therefore zoom only when the
        // window is not already zoomed and actually has more than one pane.
        // Operations are scoped to the per-client session so they don't disturb
        // the orchestrator's own view of the shared window.
        if (target != null && target.hasPane()) {
            String windowSpec = session + ":" + target.window();
            String paneSpec = windowSpec + "." + target.pane();
            runBestEffort(tmuxExec(project, socket, "select-window", "-t", windowSpec));
            runBestEffort(tmuxExec(project, socket, "select-pane", "-t", paneSpec));
            if (zoomNeeded(project, socket, paneSpec)) {
                runBestEffort(tmuxExec(project, socket, "resize-pane", "-Z", "-t", paneSpec));
            }
        }
    }

    /**
     * Whether {@code paneSpec}'s window should be zoomed: true only for a
     * multi-pane window that is not already zoomed. Reads {@code #{window_panes}}
     * and {@code #{window_zoomed_flag}} via {@code display-message}. On any read
     * failure it falls back to {@code true}, preserving the pre-fix
     * "always attempt the zoom" behaviour rather than risk leaving a split view.
     */
    private boolean zoomNeeded(String project, String socket, String paneSpec) {
        String out = displayMessage(project, socket, paneSpec, "#{window_panes} #{window_zoomed_flag}");
        if (out == null) {
            return true; // can't determine state — fall back to attempting the zoom
        }
        String[] parts = out.trim().split("\\s+");
        if (parts.length < 2) {
            return true;
        }
        boolean singlePane = "1".equals(parts[0]);
        boolean alreadyZoomed = "1".equals(parts[1]);
        return !singlePane && !alreadyZoomed;
    }

    /**
     * Run {@code display-message -p -t <target> <format>} and return trimmed
     * stdout, or {@code null} on a non-zero exit or {@link IOException}.
     */
    private String displayMessage(String project, String socket, String target, String format) {
        try {
            ProcessExecutor.Result r = exec.run(
                    tmuxExec(project, socket, "display-message", "-p", "-t", target, format), null, STARTUP_TIMEOUT);
            if (r.exitCode() != 0) {
                return null;
            }
            return r.stdout();
        } catch (IOException io) {
            return null;
        }
    }

    private void runBestEffort(List<String> argv) {
        try {
            exec.run(argv, null, STARTUP_TIMEOUT);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Build {@code docker compose … exec -T claude-sandbox tmux [-S socket] <args>}. */
    private static List<String> tmuxExec(String project, String socket, String... tmuxArgs) {
        List<String> argv =
                new ArrayList<>(List.of("docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", "tmux"));
        if (socket != null && !socket.isBlank()) {
            argv.add("-S");
            argv.add(socket);
        }
        argv.addAll(List.of(tmuxArgs));
        return argv;
    }

    /** Build the interactive PTY-attach argv ({@code exec -it … tmux [-S socket] attach}). */
    private static String[] attachArgv(String project, String socket, String session) {
        List<String> argv =
                new ArrayList<>(List.of("docker", "compose", "-p", project, "exec", "-it", "claude-sandbox", "tmux"));
        if (socket != null && !socket.isBlank()) {
            argv.add("-S");
            argv.add(socket);
        }
        argv.add("attach");
        argv.add("-t");
        argv.add(session);
        return argv.toArray(new String[0]);
    }

    /** Active bridge handle returned by {@link #start}. */
    public static final class Bridge {
        private final String project;
        private final String session;
        private final String socket;
        private final PtyProcess process;
        private final ProcessExecutor exec;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Bridge(String project, String session, String socket, PtyProcess process, ProcessExecutor exec) {
            this.project = project;
            this.session = session;
            this.socket = socket;
            this.process = process;
            this.exec = exec;
        }

        public PtyProcess process() {
            return process;
        }

        public void resize(int cols, int rows) {
            process.setWinSize(new WinSize(cols, rows));
        }

        public void writeStdin(byte[] data) throws IOException {
            process.getOutputStream().write(data);
            process.getOutputStream().flush();
        }

        public int readStdout(byte[] buf) throws IOException {
            return process.getInputStream().read(buf);
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                process.destroy();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                exec.run(tmuxExec(project, socket, "kill-session", "-t", session), null, STARTUP_TIMEOUT);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
