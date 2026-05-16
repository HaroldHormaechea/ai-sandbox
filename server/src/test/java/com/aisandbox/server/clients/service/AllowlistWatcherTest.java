package com.aisandbox.server.clients.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.test.CertFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * AC13 — adding / removing files in the allowlist folder eventually
 * (within ≤ 1s on a normal filesystem) drives the in-memory snapshot
 * to match the disk state, and the registry's {@code terminate} is
 * invoked for revoked fingerprints.
 */
class AllowlistWatcherTest {

    private AllowlistWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null && watcher.isRunning()) {
            watcher.stop();
        }
    }

    @Test
    void picks_up_added_files_within_a_second(@TempDir Path dir) throws Exception {
        CertFixtures.writeClientPemTo(dir, "seed");

        ClientAllowlistService svc = new ClientAllowlistService(new AllowlistDirectory(dir), new ClientCertParser());
        ActiveConnectionRegistry registry = Mockito.mock(ActiveConnectionRegistry.class);
        AuditLogger audit = Mockito.mock(AuditLogger.class);

        watcher = new AllowlistWatcher(svc, new AllowlistDirectory(dir), registry, audit);
        watcher.start();

        // Add a new entry post-start.
        CertFixtures.writeClientPemTo(dir, "added");

        await().atMost(Duration.ofSeconds(3))
                .until(() -> svc.list().stream().anyMatch(c -> c.name().equals("added")));

        assertThat(svc.list()).extracting(c -> c.name()).containsExactlyInAnyOrder("seed", "added");
    }

    @Test
    void terminates_revoked_connections_when_a_file_disappears(@TempDir Path dir) throws Exception {
        Path doomed = CertFixtures.writeClientPemTo(dir, "doomed");
        CertFixtures.writeClientPemTo(dir, "kept");

        ClientAllowlistService svc = new ClientAllowlistService(new AllowlistDirectory(dir), new ClientCertParser());
        ActiveConnectionRegistry registry = Mockito.mock(ActiveConnectionRegistry.class);
        AuditLogger audit = Mockito.mock(AuditLogger.class);

        watcher = new AllowlistWatcher(svc, new AllowlistDirectory(dir), registry, audit);
        watcher.start();

        // svc.rebuild() has already happened in start(); confirm.
        assertThat(svc.list()).hasSize(2);

        Files.delete(doomed);

        await().atMost(Duration.ofSeconds(3)).until(() -> svc.list().size() == 1);

        // registry.terminate must have been called with exactly the revoked fingerprint.
        Mockito.verify(registry, Mockito.atLeastOnce())
                .terminate(Mockito.argThat(set -> set != null && set.size() == 1));
        // logEvent is varargs. Capture invocations and assert at least one carries
        // the CLIENT_REMOVE action + "fs-watch" trigger.
        org.mockito.ArgumentCaptor<com.aisandbox.server.audit.AuditAction> actionCap =
                org.mockito.ArgumentCaptor.forClass(com.aisandbox.server.audit.AuditAction.class);
        Mockito.verify(audit, Mockito.atLeastOnce())
                .logEvent(actionCap.capture(), Mockito.eq("ok"), Mockito.<Object>any(), Mockito.<Object>any(),
                        Mockito.<Object>any(), Mockito.<Object>any());
        assertThat(actionCap.getAllValues()).contains(com.aisandbox.server.audit.AuditAction.CLIENT_REMOVE);
    }

    @Test
    void initial_scan_populates_snapshot_before_listening(@TempDir Path dir) throws Exception {
        CertFixtures.writeClientPemTo(dir, "preexisting");
        ClientAllowlistService svc = new ClientAllowlistService(new AllowlistDirectory(dir), new ClientCertParser());

        watcher = new AllowlistWatcher(
                svc,
                new AllowlistDirectory(dir),
                Mockito.mock(ActiveConnectionRegistry.class),
                Mockito.mock(AuditLogger.class));
        watcher.start();

        // After start() the snapshot reflects disk state — no watch event needed.
        assertThat(svc.list()).extracting(c -> c.name()).containsExactly("preexisting");
    }
}
