package com.aisandbox.server.clients.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
 * UC04 § B2 — same revocation-order invariant as
 * {@link com.aisandbox.server.clients.facade.ClientAllowlistFacadeRevocationOrderTest}
 * but covering the filesystem-driven primary call site
 * ({@link AllowlistWatcher#loop}). When a cert file disappears, the
 * watcher MUST go through {@link ActiveConnectionRegistry#revoke(java.util.Set)}
 * (graceful WS close → TCP-layer tear-down) and MUST NOT call
 * {@code .terminate(Set)} directly.
 *
 * <p>The companion test {@link AllowlistWatcherTest} already pins the
 * "revoke is called with the right set" half. This test pins the "and
 * terminate is NOT called" half, on this code path. Two tests so a
 * future regression that re-wires either half surfaces with the right
 * blame line.
 */
class AllowlistWatcherRevocationOrderTest {

    private AllowlistWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null && watcher.isRunning()) {
            watcher.stop();
        }
    }

    @Test
    void watcher_routes_revocation_through_revoke_and_never_calls_terminate(@TempDir Path dir) throws Exception {
        Path doomed = CertFixtures.writeClientPemTo(dir, "doomed");
        CertFixtures.writeClientPemTo(dir, "kept");

        ClientAllowlistService svc = new ClientAllowlistService(new AllowlistDirectory(dir), new ClientCertParser());
        ActiveConnectionRegistry registry = Mockito.mock(ActiveConnectionRegistry.class);
        AuditLogger audit = Mockito.mock(AuditLogger.class);

        watcher = new AllowlistWatcher(svc, new AllowlistDirectory(dir), registry, audit);
        watcher.start();

        assertThat(svc.list()).hasSize(2);

        Files.delete(doomed);
        await().atMost(Duration.ofSeconds(3)).until(() -> svc.list().size() == 1);

        // Orchestration entry point: revoke().
        verify(registry, atLeastOnce()).revoke(any());
        // Direct TCP-layer tear-down: never called from the watcher path.
        // Production keeps terminate() public for back-compat but the
        // primary call site has migrated.
        verify(registry, never()).terminate(any());
    }
}
