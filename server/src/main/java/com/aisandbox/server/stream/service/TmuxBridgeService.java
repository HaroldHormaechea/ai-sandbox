package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.IOException;
import java.time.Duration;
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
 *       new-session -d -s client-<id> -t main} — creates the per-client
 *       tmux session linked to {@code main}.</li>
 *   <li>{@code docker compose -p ai-sandbox-N exec -T claude-sandbox tmux
 *       set-option -t client-<id> mouse on} — enable xterm-mouse mode.</li>
 *   <li>pty4j spawns {@code docker compose -p ai-sandbox-N exec -it
 *       claude-sandbox tmux attach -t client-<id>}; PTY stdout becomes
 *       binary WebSocket frames, PTY stdin receives keystrokes + xterm
 *       mouse sequences.</li>
 * </ol>
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
     * Starts the per-client tmux session and the PTY-attached process.
     */
    public Bridge start(int n, String streamId, int cols, int rows) throws IOException {
        String project = "ai-sandbox-" + n;
        String session = "client-" + streamId;
        // Step 1.
        ProcessExecutor.Result r1 = exec.run(
                List.of(
                        "docker",
                        "compose",
                        "-p",
                        project,
                        "exec",
                        "-T",
                        "claude-sandbox",
                        "tmux",
                        "new-session",
                        "-d",
                        "-s",
                        session,
                        "-t",
                        "main"),
                null,
                STARTUP_TIMEOUT);
        if (r1.exitCode() != 0) {
            throw new IOException("tmux new-session failed: " + r1.stderr());
        }
        // Step 2.
        ProcessExecutor.Result r2 = exec.run(
                List.of(
                        "docker",
                        "compose",
                        "-p",
                        project,
                        "exec",
                        "-T",
                        "claude-sandbox",
                        "tmux",
                        "set-option",
                        "-t",
                        session,
                        "mouse",
                        "on"),
                null,
                STARTUP_TIMEOUT);
        if (r2.exitCode() != 0) {
            LOG.warn("tmux set-option mouse on failed (continuing): {}", r2.stderr());
        }
        // Step 3 — PTY attach.
        PtyProcessBuilder pb = new PtyProcessBuilder()
                .setCommand(new String[] {
                    "docker", "compose", "-p", project, "exec", "-it", "claude-sandbox", "tmux", "attach", "-t", session
                })
                .setEnvironment(Map.of("TERM", "xterm-256color"))
                .setInitialColumns(cols > 0 ? cols : 80)
                .setInitialRows(rows > 0 ? rows : 24);
        PtyProcess proc;
        try {
            proc = pb.start();
        } catch (IOException io) {
            // Best-effort kill of the orphaned tmux session.
            try {
                exec.run(
                        List.of(
                                "docker",
                                "compose",
                                "-p",
                                project,
                                "exec",
                                "-T",
                                "claude-sandbox",
                                "tmux",
                                "kill-session",
                                "-t",
                                session),
                        null,
                        STARTUP_TIMEOUT);
            } catch (IOException ignored) {
                // best-effort
            }
            throw io;
        }
        return new Bridge(project, session, proc, exec);
    }

    /** Active bridge handle returned by {@link #start}. */
    public static final class Bridge {
        private final String project;
        private final String session;
        private final PtyProcess process;
        private final ProcessExecutor exec;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Bridge(String project, String session, PtyProcess process, ProcessExecutor exec) {
            this.project = project;
            this.session = session;
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
                exec.run(
                        List.of(
                                "docker",
                                "compose",
                                "-p",
                                project,
                                "exec",
                                "-T",
                                "claude-sandbox",
                                "tmux",
                                "kill-session",
                                "-t",
                                session),
                        null,
                        STARTUP_TIMEOUT);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
