package com.aisandbox.server.clients.service;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Watches the allowlist directory and, on debounced filesystem changes,
 * asks the service to rebuild and tears down channels whose certs were
 * removed.
 *
 * <p>Debounce window is 250 ms — operators editing in a text editor often
 * write through a tmp + rename pair plus a swap-file, all in tens of
 * milliseconds. We collapse those into one rebuild.
 *
 * <p>Disabled under the {@code docs-only} profile: OAS rendering never
 * needs filesystem watches.
 */
@Component
@Profile("!docs-only")
public class AllowlistWatcher implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AllowlistWatcher.class);
    private static final long DEBOUNCE_MS = 250L;

    private final ClientAllowlistService service;
    private final AllowlistDirectory directory;
    private final ActiveConnectionRegistry registry;
    private final AuditLogger audit;
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private volatile WatchService watchService;
    private volatile boolean running = false;

    public AllowlistWatcher(
            ClientAllowlistService service,
            AllowlistDirectory directory,
            ActiveConnectionRegistry registry,
            AuditLogger audit) {
        this.service = service;
        this.directory = directory;
        this.registry = registry;
        this.audit = audit;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        // Initial scan synchronously so the TLS layer sees a populated
        // snapshot from the first handshake onwards.
        service.rebuild();

        try {
            Path dir = directory.dir();
            watchService = FileSystems.getDefault().newWatchService();
            dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException io) {
            throw new IllegalStateException("Cannot register allowlist watch on " + directory.dir(), io);
        }

        Thread t = new Thread(this::loop, "ai-sandbox-allowlist-watcher");
        t.setDaemon(true);
        worker.set(t);
        running = true;
        t.start();
    }

    private void loop() {
        long pendingUntil = 0L;
        while (running) {
            try {
                WatchKey key = watchService.poll(50L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key != null) {
                    // Drain events; we don't actually care which one — a
                    // single rebuild handles every shape of change.
                    key.pollEvents();
                    if (!key.reset()) {
                        LOG.warn("Allowlist watch key invalidated; reinstalling on next interval.");
                    }
                    pendingUntil = System.currentTimeMillis() + DEBOUNCE_MS;
                }
                if (pendingUntil > 0 && System.currentTimeMillis() >= pendingUntil) {
                    pendingUntil = 0L;
                    Set<String> revoked = service.rebuild();
                    if (!revoked.isEmpty()) {
                        // UC04 § B2 — go through the orchestration entry
                        // point: graceful WS close first (so the Android
                        // client surfaces AC26's "Identity revoked"
                        // dialog) then TCP-layer tear-down. terminate()
                        // stays public for back-compat; this is the
                        // primary production path.
                        registry.revoke(revoked);
                        for (String fp : revoked) {
                            audit.logEvent(AuditAction.CLIENT_REMOVE, "ok", "fingerprint", fp, "trigger", "fs-watch");
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException re) {
                LOG.warn("Allowlist watch loop hiccup: {}", re.toString());
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread t = worker.getAndSet(null);
        if (t != null) {
            t.interrupt();
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
        // Start AFTER NettyServerCustomizer's bean has been registered
        // (lower phase = earlier; SmartLifecycle default is Integer.MAX_VALUE).
        return 100;
    }
}
