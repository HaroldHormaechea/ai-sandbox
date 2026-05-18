package com.aisandbox.server.tls;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Watches the PKI directory for changes to {@code server.crt} / {@code server.key}
 * and triggers {@link ReloadableSslContextHolder#rebuild(Path, Path)}
 * (AC14). In-flight TLS sessions keep their original cert; only new
 * handshakes after the rebuild see the new material.
 */
@Component
@Profile("!docs-only")
public class ServerCertWatcher implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ServerCertWatcher.class);
    private static final long DEBOUNCE_MS = 500L;

    private final ServerProperties props;
    private final ReloadableSslContextHolder holder;
    private final AuditLogger audit;
    private volatile WatchService watchService;
    private volatile Thread worker;
    private volatile boolean running = false;

    public ServerCertWatcher(ServerProperties props, ReloadableSslContextHolder holder, AuditLogger audit) {
        this.props = props;
        this.holder = holder;
        this.audit = audit;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Path pkiDir = props.pki().dir();
        try {
            watchService = FileSystems.getDefault().newWatchService();
            pkiDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot register WatchService for " + pkiDir, e);
        }
        worker = new Thread(this::loop, "ai-sandbox-server-cert-watcher");
        worker.setDaemon(true);
        running = true;
        worker.start();
    }

    private void loop() {
        long pendingUntil = 0L;
        Path pkiDir = props.pki().dir();
        Path crt = pkiDir.resolve("server.crt");
        Path key = pkiDir.resolve("server.key");
        while (running) {
            try {
                WatchKey wk = watchService.poll(100L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (wk != null) {
                    wk.pollEvents();
                    wk.reset();
                    pendingUntil = System.currentTimeMillis() + DEBOUNCE_MS;
                }
                if (pendingUntil > 0 && System.currentTimeMillis() >= pendingUntil) {
                    pendingUntil = 0L;
                    try {
                        holder.rebuild(crt, key);
                        audit.logEvent(AuditAction.SERVER_CERT_ROTATION, "ok", "cert", crt.toString());
                    } catch (Exception e) {
                        LOG.warn("Server-cert rebuild failed; keeping previous context: {}", e.toString());
                        audit.logEvent(
                                AuditAction.SERVER_CERT_ROTATION,
                                "fail",
                                "cert",
                                crt.toString(),
                                "error",
                                e.getClass().getSimpleName());
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException re) {
                LOG.warn("Server-cert watcher hiccup: {}", re.toString());
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start BEFORE the Netty listener (lower phase = earlier).
        return -100;
    }
}
