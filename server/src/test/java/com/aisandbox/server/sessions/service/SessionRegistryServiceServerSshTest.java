package com.aisandbox.server.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.dto.SessionRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-62 — {@link SessionRegistryService} prepends the always-on host-shell row
 * (when its tmux exists) ahead of the Docker enumeration, and omits it
 * otherwise. {@link DockerEnumerationService} and {@link HostShellSessionService}
 * are mocked.
 *
 * <p>AC mapping:
 *
 * <ul>
 *   <li><b>AC3</b> — the host-shell row is PINNED FIRST (server-side), above
 *       every Claude row regardless of n-ordering.</li>
 *   <li><b>AC7</b> — the row is re-listed on every (un-cached) enumeration for as
 *       long as the host tmux exists ({@code hostShell.exists()} is consulted on
 *       each fresh enumeration).</li>
 *   <li><b>AC12</b> — when the host tmux is absent, the list is exactly the
 *       Docker enumeration (the reserved id never appears); normal sessions are
 *       unaffected.</li>
 * </ul>
 */
class SessionRegistryServiceServerSshTest {

    private static SessionRecord claude(int n) {
        return new SessionRecord(n, "", "(idle)", "running", 1L, 0, Instant.EPOCH, null, false, false);
    }

    private static SessionRecord hostRow() {
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

    @Test
    void prepends_host_row_pinned_first_when_tmux_exists() throws Exception {
        DockerEnumerationService docker = mock(DockerEnumerationService.class);
        when(docker.enumerate()).thenReturn(List.of(claude(1), claude(2)));
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        when(hostShell.exists()).thenReturn(true);
        when(hostShell.row()).thenReturn(hostRow());

        List<SessionRecord> list = new SessionRegistryService(docker, hostShell).list();

        // AC3 — the reserved-id server-ssh row is FIRST, ahead of every Claude row.
        assertThat(list).extracting(SessionRecord::n).containsExactly(SpecialSessions.SERVER_SSH_N, 1, 2);
        assertThat(list.get(0).type()).isEqualTo(SpecialSessions.TYPE_SERVER_SSH);
    }

    @Test
    void omits_host_row_when_tmux_absent() throws Exception {
        DockerEnumerationService docker = mock(DockerEnumerationService.class);
        when(docker.enumerate()).thenReturn(List.of(claude(1), claude(2)));
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        when(hostShell.exists()).thenReturn(false);

        List<SessionRecord> list = new SessionRegistryService(docker, hostShell).list();

        // AC12 — without the host tmux, enumeration is exactly the Docker list.
        assertThat(list).extracting(SessionRecord::n).containsExactly(1, 2);
        assertThat(list).noneMatch(r -> r.n() == SpecialSessions.SERVER_SSH_N);
    }

    @Test
    void relists_host_row_on_every_uncached_enumeration() throws Exception {
        // AC7 — persistence: as long as the host tmux exists the row reappears on
        // each fresh (cache-invalidated) enumeration. exists() is consulted each time.
        DockerEnumerationService docker = mock(DockerEnumerationService.class);
        when(docker.enumerate()).thenReturn(List.of(claude(1)));
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        when(hostShell.exists()).thenReturn(true);
        when(hostShell.row()).thenReturn(hostRow());

        SessionRegistryService registry = new SessionRegistryService(docker, hostShell);

        assertThat(registry.list()).extracting(SessionRecord::n).contains(SpecialSessions.SERVER_SSH_N);
        registry.invalidate();
        assertThat(registry.list()).extracting(SessionRecord::n).contains(SpecialSessions.SERVER_SSH_N);

        verify(hostShell, atLeast(2)).exists();
    }

    @Test
    void exists_sees_reserved_id_when_host_row_present() throws Exception {
        DockerEnumerationService docker = mock(DockerEnumerationService.class);
        when(docker.enumerate()).thenReturn(List.of(claude(1)));
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        when(hostShell.exists()).thenReturn(true);
        when(hostShell.row()).thenReturn(hostRow());

        SessionRegistryService registry = new SessionRegistryService(docker, hostShell);
        assertThat(registry.exists(SpecialSessions.SERVER_SSH_N)).isTrue();
        assertThat(registry.exists(1)).isTrue();
        assertThat(registry.exists(99)).isFalse();
    }
}
