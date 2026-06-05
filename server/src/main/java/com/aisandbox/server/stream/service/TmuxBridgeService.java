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
 * teammate pane (a pane in the {@code main} session on the default socket, or — on
 * older Claude Code builds — a pane on a separate {@code claude-swarm-<pid>}
 * socket). When the target names a window/pane, the per-client session focuses
 * that pane after creation.
 *
 * <p>UC-24 generalizes the single-pane <b>zoom</b> to EVERY target: after the
 * optional focus, if the per-client session's active window has more than one
 * pane and is not already zoomed, the active pane is zoomed so a multi-pane
 * window always paints as one pane. This fixes the unzoomed main initial paint
 * (the "all tmux windows shown" regression) — without a handler/facade change —
 * while staying a strict no-op for a genuine single-pane window.
 *
 * <p>UC-33 closes the <b>mid-stream</b> gap left by UC-24: when subagents spawn
 * <i>while a client is already attached</i>, Claude Code splits the (shared)
 * window and the previously-applied zoom goes stale, painting a transient split.
 * Two server measures re-assert the zoom immediately, without waiting for the
 * client's 3s enumerate poll:
 * <ul>
 *   <li>For the MAIN target the origin pane's stable {@code #{pane_id}} is
 *       captured at bridge time and PINNED — the initial zoom and the hook below
 *       re-zoom that exact pane, never whichever teammate pane became active
 *       after a split (so we do not pin by the volatile literal index 0).</li>
 *   <li>A best-effort {@code window-layout-changed} tmux hook (Step 2e) runs an
 *       in-container, debounced, {@code flock}-serialized snippet that re-applies
 *       the {@code zoomNeeded} guard ({@code >1 pane && !zoomed}) the instant the
 *       layout changes. Agent/swarm targets are deliberately NOT pinned — the
 *       hook zooms the active pane and the client's discrete re-select restores
 *       the exact teammate. See {@link #buildLayoutChangedHookCommand}.</li>
 * </ul>
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
        // Step 2b — focus the requested pane for a targeted bridge (an agent-team
        // teammate, or an explicit main re-selection that carries the orchestrator
        // pane's coordinates). When no pane is named (the pre-UC-21 main bridge),
        // we leave focus on the per-client session's active pane and zoom that
        // below — so the zoom is generalized to EVERY target, not only paned ones.
        // {@code zoomSpec} is the tmux target the generalized zoom queries + zooms.
        //
        // UC-33: for the MAIN target we additionally capture the origin pane's
        // stable {@code #{pane_id}} ({@code mainPaneId}) so the initial zoom and
        // the Step 2e hook can PIN that exact pane after a mid-stream split, rather
        // than re-zooming "the active pane" (which may have become a teammate pane).
        // We pin by the durable {@code %N} pane id, never the volatile index 0.
        // Agent/swarm targets are NOT pinned ({@code mainPaneId} stays null): the
        // hook zooms the active pane and the client's discrete re-select frame
        // restores the exact teammate via its {@code paneSpec}.
        String zoomSpec = session;
        String mainPaneId = null;
        if (target != null && target.hasPane()) {
            String windowSpec = session + ":" + target.window();
            String paneSpec = windowSpec + "." + target.pane();
            runBestEffort(tmuxExec(project, socket, "select-window", "-t", windowSpec));
            runBestEffort(tmuxExec(project, socket, "select-pane", "-t", paneSpec));
            zoomSpec = paneSpec;
        } else {
            String pid = displayMessage(project, socket, session, "#{pane_id}");
            if (pid != null && !pid.trim().isEmpty()) {
                mainPaneId = pid.trim();
            }
        }
        // Step 2d — generalized single-pane zoom for ALL targets (incl. main).
        // When the per-client session's active window has more than one pane and
        // is not already zoomed, zoom the active pane so a multi-pane window paints
        // as a single pane — this is the fix for the unzoomed-main initial paint
        // (the "all windows shown" regression) without a handler/facade change.
        //
        // {@code resize-pane -Z} is a TOGGLE, so zooming an already-zoomed pane (a
        // re-bridge / reconnect / second client) would toggle zoom OFF and re-expose
        // the split. {@code zoomNeeded} guards on {@code >1 pane && !zoomed}, so this
        // stays a strict no-op for a genuine single-pane window (no regression).
        // Operations are scoped to the per-client session so they don't disturb the
        // orchestrator's own view of the shared window.
        //
        // UC-33: when a {@code mainPaneId} was captured (MAIN target) we PIN it —
        // select that exact pane first, then guard+zoom it — so the zoom can never
        // land on a teammate pane that happened to be active. The {@code zoomNeeded}
        // guard reads the same window state for the pinned pane, so the no-op
        // semantics are unchanged.
        String initialZoomSpec = (mainPaneId != null) ? mainPaneId : zoomSpec;
        if (mainPaneId != null) {
            runBestEffort(tmuxExec(project, socket, "select-pane", "-t", mainPaneId));
        }
        if (zoomNeeded(project, socket, initialZoomSpec)) {
            runBestEffort(tmuxExec(project, socket, "resize-pane", "-Z", "-t", initialZoomSpec));
        }
        // Step 2e — install the window-layout-changed hook so a mid-stream pane
        // split re-pins/re-zooms the selected view IMMEDIATELY (tighter than the
        // client's 3s enumerate poll). Best-effort: a hook-set failure never aborts
        // the bridge. The snippet is in-container, debounced, guarded by the same
        // {@code >1 pane && !zoomed} rule, and flock-serialized — see
        // {@link #buildLayoutChangedHookCommand}.
        runBestEffort(tmuxExec(
                project,
                socket,
                "set-hook",
                "-t",
                session,
                "window-layout-changed",
                buildLayoutChangedHookCommand(socket, session, mainPaneId)));
    }

    /**
     * Build the {@code window-layout-changed} hook body (the {@code run-shell -b
     * "…"} command string tmux stores) that re-asserts the per-client zoom the
     * instant a mid-stream split changes the shared window's layout — UC-33
     * Part B. Package-visible so QA can assert the exact SHIPPED string.
     *
     * <p>The snippet runs IN-CONTAINER via {@code run-shell} (a bare {@code tmux}
     * on the same server — NOT {@code docker compose exec}, since the hook already
     * fires inside the container). It is:
     * <ul>
     *   <li><b>debounced</b> — {@code sleep 0.2} so the state is read AFTER the
     *       layout churn settles, not mid-transition;</li>
     *   <li><b>guarded</b> — the {@code zoomNeeded}-equivalent {@code >1 pane &&
     *       !zoomed} check, so the toggle {@code resize-pane -Z} can never
     *       un-zoom an already-correct view (AC#4 / AC#5);</li>
     *   <li><b>serialized</b> — wrapped in a non-blocking {@code flock -n} on a
     *       per-session lock so concurrent fires don't race (AC#1's sub-frame
     *       guarantee depends on this; a fire that can't take the lock exits 0).</li>
     * </ul>
     *
     * <p>When {@code mainPaneId} is non-null (MAIN target) the snippet PINS that
     * exact pane: it re-selects {@code mainPaneId} when some other pane became
     * active, then zooms it if the window is split and unzoomed. When it is null
     * (agent/swarm target) the snippet zooms the active pane of the per-client
     * session — agents are deliberately NOT pinned; the client's discrete
     * re-select restores the exact teammate.
     *
     * <p><b>tmux format escaping:</b> every {@code #{…}} is written {@code ##{…}}
     * so the tmux double-quoted hook body is NOT format-expanded when the hook
     * fires — the {@code ##} collapses to a single {@code #} and the literal
     * {@code #{…}} reaches the inner {@code tmux display-message}, which reads the
     * SETTLED values after the debounce. The {@code flock} uses an FD-redirect
     * subshell ({@code ( … ) 9>lock}) rather than a nested {@code sh -c} so the
     * body needs no inner shell quoting that would collide with the tmux quotes.
     *
     * <p><b>No-view-mode requirement (UC-33 leak fix — must-detach + must-redirect):</b>
     * the subshell MUST be both backgrounded and have its stdout+stderr redirected
     * to {@code /dev/null} — the shipped tail is {@code ( … ) >/dev/null 2>&1 9>lock
     * &}. {@code run-shell -b} runs the body as a foreground command and, on tmux
     * 3.3a, if that command produces ANY captured output/activity (which the
     * synchronous {@code flock}/{@code sleep 0.2}/zoom subshell did), tmux flips the
     * hook's pane into {@code view-mode} — a copy/scrollback overlay that renders the
     * captured command text on top of the live session, leaking it to every attached
     * client (Android bridge OR direct {@code tmux attach}, which share the
     * session/pane). With the trailing {@code &}, {@code run-shell -b} sees a command
     * that only backgrounds the subshell and returns instantly with no output, so
     * tmux never opens view-mode; the {@code >/dev/null 2>&1} additionally swallows
     * any stray output from the detached subshell. The redirect order is significant:
     * {@code >/dev/null 2>&1} and the {@code 9>}-lock FD apply to the subshell as a
     * whole, and the {@code &} backgrounds that whole {@code ( … ) >… 9>lock}
     * construct. The inner {@code $(…)} captures ride their own pipes and are
     * unaffected by the outer redirect, so the zoom logic still reads settled values.
     * Do NOT reintroduce a synchronous (non-{@code &}) or un-redirected tail — that
     * is exactly what leaked the command on attach.
     */
    static String buildLayoutChangedHookCommand(String socket, String session, String mainPaneId) {
        String tmux = (socket != null && !socket.isBlank()) ? "tmux -S " + socket : "tmux";
        String lock = "/tmp/pin-" + session + ".lock";
        String body;
        if (mainPaneId != null && !mainPaneId.isBlank()) {
            String pane = mainPaneId.trim();
            body = "( flock -n 9 || exit 0; sleep 0.2; "
                    + "sel=$(" + tmux + " display-message -p -t " + session + " '##{pane_id}'); "
                    + "[ x$sel = x" + pane + " ] || " + tmux + " select-pane -t " + pane + "; "
                    + "set -- $(" + tmux + " display-message -p -t " + pane
                    + " '##{window_panes} ##{window_zoomed_flag}'); "
                    + "[ $1 -gt 1 ] && [ $2 = 0 ] && " + tmux + " resize-pane -Z -t " + pane
                    + " ) >/dev/null 2>&1 9>" + lock + " &";
        } else {
            body = "( flock -n 9 || exit 0; sleep 0.2; "
                    + "set -- $(" + tmux + " display-message -p -t " + session
                    + " '##{window_panes} ##{window_zoomed_flag}'); "
                    + "[ $1 -gt 1 ] && [ $2 = 0 ] && " + tmux + " resize-pane -Z -t " + session
                    + " ) >/dev/null 2>&1 9>" + lock + " &";
        }
        return "run-shell -b \"" + body + "\"";
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
