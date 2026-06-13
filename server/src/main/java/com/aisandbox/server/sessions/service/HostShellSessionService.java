package com.aisandbox.server.sessions.service;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.dto.SessionRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-62 — manages the single always-on server host-shell ("SERVER SSH SESSION").
 *
 * <p>Unlike a Claude session (a Docker container enumerated by
 * {@link DockerEnumerationService}), this is a bare {@code tmux} running a
 * login shell on the management-server HOST itself, as the server process's own
 * user and working dir. It is managed in-process here — deliberately NOT via a
 * new host script — so it carries none of the operator-zip / {@code .deb} / CI
 * packaging burden a new {@code *.sh} would.
 *
 * <p>There is no separate registry: existence is derived live from
 * {@code tmux -S <socket> has-session}, so the row survives terminal detach,
 * WebSocket disconnect, and app restart for free (AC7, AC10), and is visible to
 * any device that lists sessions. The tmux is created on demand
 * ({@link #ensureCreated()}, idempotent — the server-side singleton guard of
 * AC2/AC13) and destroyed only on explicit Remove ({@link #kill()}, AC11).
 *
 * <p>All values come from {@link ServerProperties.ServerSsh}; absent ones are
 * resolved to runtime defaults here (socket under the sessions host-state-root,
 * {@code $SHELL} else {@code /bin/bash}, the JVM cwd). When {@code enabled} is
 * {@code false} the row is never listed and create is a no-op.
 *
 * <p>Layering: this is a sessions-domain {@code Service} (called by
 * {@link com.aisandbox.server.sessions.facade.SessionFacade}, and read by
 * {@link SessionRegistryService}); it depends only on {@link ProcessExecutor},
 * matching the rest of the sessions service layer.
 */
@Service
public class HostShellSessionService {

    private static final Logger LOG = LoggerFactory.getLogger(HostShellSessionService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private final ProcessExecutor exec;
    private final ServerProperties.ServerSsh cfg;
    private final Path socketPath;
    private final String sessionName;
    private final String shell;
    private final Path workdir;

    /** Serialises {@link #ensureCreated()} so two near-simultaneous creates yield one tmux (AC13). */
    private final ReentrantLock createLock = new ReentrantLock();

    public HostShellSessionService(ProcessExecutor exec, ServerProperties props) {
        this.exec = exec;
        this.cfg = props.serverSsh();
        this.socketPath = cfg.socketPath() != null
                ? cfg.socketPath()
                : props.sessions().hostStateRoot().resolve("server-ssh.sock");
        this.sessionName = (cfg.sessionName() != null && !cfg.sessionName().isBlank())
                ? cfg.sessionName()
                : "ai-sandbox-server-ssh";
        this.shell = resolveShell(cfg.shell());
        this.workdir = cfg.workdir() != null ? cfg.workdir() : Path.of(System.getProperty("user.dir", "/"));
    }

    private static String resolveShell(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String envShell = System.getenv("SHELL");
        return (envShell != null && !envShell.isBlank()) ? envShell : "/bin/bash";
    }

    /** Whether the host-shell feature is enabled at all (the master config switch). */
    public boolean enabled() {
        return cfg.enabled();
    }

    /** Absolute tmux socket path used for the host-shell server. */
    public String socketPathString() {
        return socketPath.toString();
    }

    /** tmux base session name for the host shell. */
    public String sessionName() {
        return sessionName;
    }

    /**
     * Whether the host tmux currently exists. Derived live from
     * {@code tmux -S <socket> has-session -t <name>} (exit 0 ⇒ present). Always
     * {@code false} when the feature is disabled, or when tmux/the socket cannot
     * be reached (treated as "absent", never as an error that would break
     * enumeration — {@link SessionRegistryService} swallows nothing else).
     */
    public boolean exists() {
        if (!cfg.enabled()) {
            return false;
        }
        try {
            ProcessExecutor.Result r = exec.run(tmux("has-session", "-t", sessionName), null, baseEnv(), TIMEOUT);
            return r.exitCode() == 0;
        } catch (IOException io) {
            LOG.debug("server-ssh has-session check failed (treating as absent): {}", io.toString());
            return false;
        }
    }

    /**
     * Create the host tmux if it does not already exist — idempotent, so a
     * second tap simply focuses the existing row (AC2). Serialised under
     * {@link #createLock} so two concurrent creates produce exactly one tmux
     * and one row (server-side singleton, AC13). No-op when disabled.
     *
     * @throws IOException if {@code tmux new-session} fails (non-zero exit).
     */
    public void ensureCreated() throws IOException {
        if (!cfg.enabled()) {
            LOG.debug("server-ssh disabled; ensureCreated is a no-op");
            return;
        }
        createLock.lock();
        try {
            if (exists()) {
                return; // singleton — focusing the existing session is a client-side concern
            }
            // tmux -S <sock> new-session -d -s <name> <shell> -l
            // Running detached as the server's own user/cwd. PATH/TERM are set
            // explicitly: under a systemd unit with a minimal environment, a
            // bare "tmux" / the login shell would otherwise fail to resolve.
            ProcessExecutor.Result r =
                    exec.run(tmux("new-session", "-d", "-s", sessionName, shell, "-l"), workdir, baseEnv(), TIMEOUT);
            if (r.exitCode() != 0) {
                throw new IOException(
                        "tmux new-session for server-ssh failed (exit " + r.exitCode() + "): " + r.stderr());
            }
            LOG.info(
                    "Created server-ssh host tmux '{}' on socket {} (shell={}, cwd={})",
                    sessionName,
                    socketPath,
                    shell,
                    workdir);
        } finally {
            createLock.unlock();
        }
    }

    /**
     * Destroy the host tmux (explicit Remove, AC11). Best-effort: an
     * already-gone session (no server / unknown session) is treated as success,
     * since the desired post-condition — "the tmux no longer exists" — already
     * holds.
     */
    public void kill() {
        if (!cfg.enabled()) {
            return;
        }
        try {
            ProcessExecutor.Result r = exec.run(tmux("kill-session", "-t", sessionName), null, baseEnv(), TIMEOUT);
            if (r.exitCode() != 0) {
                LOG.debug("server-ssh kill-session exit {} (likely already gone): {}", r.exitCode(), r.stderr());
            }
        } catch (IOException io) {
            LOG.debug("server-ssh kill-session failed (best-effort): {}", io.toString());
        }
    }

    /**
     * The pinned list row for the host shell. Mirrors the shape of a Claude row
     * but with the reserved id, the {@code server-ssh} type, and inert
     * conversation fields (a host shell has no Claude transcript). The Android
     * client pins this row to the top and badges it.
     */
    public SessionRecord row() {
        return new SessionRecord(
                SpecialSessions.SERVER_SSH_N,
                "",
                "(idle)",
                "running",
                0L,
                0,
                Instant.EPOCH,
                null,
                false,
                false,
                SpecialSessions.TYPE_SERVER_SSH);
    }

    /** Build {@code tmux -S <socket> <args...>}. */
    private List<String> tmux(String... args) {
        java.util.List<String> argv = new java.util.ArrayList<>(List.of("tmux", "-S", socketPath.toString()));
        argv.addAll(List.of(args));
        return argv;
    }

    /** Minimal environment overlay for the tmux subprocess (systemd minimal-PATH gotcha). */
    private Map<String, String> baseEnv() {
        String path = System.getenv("PATH");
        return Map.of("PATH", (path != null && !path.isBlank()) ? path : DEFAULT_PATH, "TERM", "xterm-256color");
    }
}
