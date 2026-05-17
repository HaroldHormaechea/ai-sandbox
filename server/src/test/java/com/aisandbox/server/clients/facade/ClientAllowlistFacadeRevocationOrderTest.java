package com.aisandbox.server.clients.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.clients.service.AllowlistDirectory;
import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.clients.service.ClientCertParser;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC04 § B2 — invariant: when {@link ClientAllowlistFacade#removeClient}
 * deletes a cert, it routes the revocation through
 * {@link ActiveConnectionRegistry#revoke(Set)} (the single orchestration
 * entry point that issues a graceful WS close before TCP-layer tear-down)
 * — never via {@code .terminate(Set)} directly.
 *
 * <p>The orchestration order itself (gracefulClose → block(timeout) →
 * terminate) is owned by the registry and exercised by
 * {@link com.aisandbox.server.identity.ActiveStreamRegistryTest}. This
 * test pins the call-site invariant.
 */
class ClientAllowlistFacadeRevocationOrderTest {

    private static AllowedClient sample(String name) {
        return new AllowedClient(name, name, "fa".repeat(32), BigInteger.ONE, Instant.EPOCH);
    }

    @Test
    void remove_routes_through_revoke_not_terminate() throws Exception {
        AllowedClient existing = sample("doomed");
        Set<String> revokedSet = Set.of(existing.fingerprintHex());

        ClientAllowlistService service = mock(ClientAllowlistService.class);
        AllowlistDirectory directory = mock(AllowlistDirectory.class);
        ClientCertParser parser = mock(ClientCertParser.class);
        ActiveConnectionRegistry registry = mock(ActiveConnectionRegistry.class);
        AuditLogger audit = mock(AuditLogger.class);

        // service.snapshot() backs the lookup-by-name path.
        when(service.snapshot()).thenReturn(Map.of(existing.fingerprintHex(), existing));
        when(directory.deleteByName("doomed")).thenReturn(true);
        when(service.rebuild()).thenReturn(revokedSet);

        ClientAllowlistFacade facade = new ClientAllowlistFacade(service, directory, parser, registry, audit);
        boolean removed = facade.removeClient("doomed");
        assertThat(removed).isTrue();

        // The orchestration entry point is the only call onto the registry.
        ArgumentCaptor<Set<String>> setCap = ArgumentCaptor.forClass(Set.class);
        verify(registry).revoke(setCap.capture());
        assertThat(setCap.getValue()).containsExactly(existing.fingerprintHex());

        // .terminate must NOT be called from this code path. Production
        // keeps terminate() public for back-compat (UC04 § B2 comment)
        // but the facade has migrated to revoke().
        verify(registry, never()).terminate(any());
    }

    @Test
    void remove_of_nonexistent_client_does_not_touch_registry() throws Exception {
        ClientAllowlistService service = mock(ClientAllowlistService.class);
        AllowlistDirectory directory = mock(AllowlistDirectory.class);
        ClientCertParser parser = mock(ClientCertParser.class);
        ActiveConnectionRegistry registry = mock(ActiveConnectionRegistry.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(service.snapshot()).thenReturn(Map.of());

        ClientAllowlistFacade facade = new ClientAllowlistFacade(service, directory, parser, registry, audit);
        boolean removed = facade.removeClient("ghost");
        assertThat(removed).isFalse();

        // Nothing to revoke; registry untouched.
        verify(registry, never()).revoke(any());
        verify(registry, never()).terminate(any());
    }
}
