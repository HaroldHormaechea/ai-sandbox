package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.SwarmEnumerationService;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-62 — {@link StreamFacade} treats the reserved host-shell session as a
 * single-target stream: {@link StreamFacade#enumerateTargets(int)} short-circuits
 * to the lone {@code main} target for the reserved id, BEFORE consulting the
 * swarm enumerator. A host login shell has no agent-team teammate panes, so the
 * Android {@code AgentSwitcherBar} self-hides (AC10) and the presence of the
 * host row never perturbs the swarm enumeration of real Claude sessions (AC12).
 */
class StreamFacadeServerSshTest {

    private static ServerProperties props() {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
    }

    private static StreamFacade build() {
        return new StreamFacade(
                mock(SessionRegistryService.class),
                new StreamRegistryService(props()),
                mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                props());
    }

    @Test
    void enumerateTargets_reservedId_shortCircuits_to_single_main_without_consulting_swarm() {
        SwarmEnumerationService swarm = mock(SwarmEnumerationService.class);
        StreamFacade f = build();
        f.setSwarmEnumeration(swarm);

        List<TargetInfo> targets = f.enumerateTargets(SpecialSessions.SERVER_SSH_N);

        // A single "main" target (AC10 — the switcher self-hides for the host shell).
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).id()).isEqualTo(SwarmEnumerationService.MAIN_ID);
        assertThat(targets.get(0).kind()).isEqualTo("main");

        // Short-circuited BEFORE swarm.enumerate — the host shell is never swarm-scanned.
        verify(swarm, never()).enumerate(anyInt());
    }

    @Test
    void enumerateTargets_realSession_still_delegates_to_swarm_unaffected_by_uc62() {
        SwarmEnumerationService swarm = mock(SwarmEnumerationService.class);
        TargetInfo main = new TargetInfo("main", "main", "main", null, null, null, null, null, "main", null, null);
        org.mockito.Mockito.when(swarm.enumerate(7)).thenReturn(List.of(main));

        StreamFacade f = build();
        f.setSwarmEnumeration(swarm);

        // AC12 — a real session id still routes through the swarm enumerator.
        assertThat(f.enumerateTargets(7)).containsExactly(main);
        verify(swarm).enumerate(7);
    }
}
