package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.facade.StreamFacade.AuthorizeResult;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC28 / AC35 — authorize-open results: session-not-found, cap exceeded
 * (global + per-client), draining, allowed. The facade is the layer that
 * carries the WebSocket-side cap-check + the draining flag.
 */
class StreamFacadeTest {

    private static ServerProperties props(int perClient, int global) {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, perClient, global, 262144, 16384, 262144, 30, 15));
    }

    private static ClientIdentity identity() {
        return new ClientIdentity("alice", "a".repeat(64), BigInteger.ONE);
    }

    private static StreamFacade build(SessionRegistryService sr, StreamRegistryService str) {
        return new StreamFacade(
                sr,
                str,
                mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                props(10, 100));
    }

    private static StreamFacade buildWithCaps(
            SessionRegistryService sr, StreamRegistryService str, int per, int global) {
        return new StreamFacade(
                sr,
                str,
                mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                props(per, global));
    }

    /** UC04 AC37 — convenience factory; state defaults to running. */
    private static SessionRecord runningRecord(int n) {
        return new SessionRecord(n, "", "(idle)", "running", 0L, 0, Instant.EPOCH);
    }

    /** UC04 AC37 — stopped/starting records flow through the new attach-guard path. */
    private static SessionRecord recordWithState(int n, String state) {
        return new SessionRecord(n, "", "(unavailable)", state, 0L, 0, Instant.EPOCH);
    }

    @Test
    void session_not_found_returns_SessionNotFound() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        // UC04 AC37 — authorizeOpen now walks sr.list() rather than sr.exists().
        when(sr.list()).thenReturn(List.of());

        StreamFacade f = build(sr, new StreamRegistryService(props(10, 100)));
        AuthorizeResult r = f.authorizeOpen(7, identity());
        assertThat(r).isInstanceOf(StreamFacade.SessionNotFound.class);
    }

    @Test
    void enumeration_failure_is_treated_as_not_found() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        when(sr.list()).thenThrow(new java.io.IOException("boom"));

        StreamFacade f = build(sr, new StreamRegistryService(props(10, 100)));
        AuthorizeResult r = f.authorizeOpen(7, identity());
        assertThat(r).isInstanceOf(StreamFacade.SessionNotFound.class);
    }

    @Test
    void global_cap_exceeded() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        // UC04 AC37 — list() returns a running record; cap check follows.
        when(sr.list()).thenReturn(List.of(runningRecord(1)));
        StreamRegistryService streams = new StreamRegistryService(props(10, 1));
        // Fill the registry to its global cap.
        streams.register(new StreamRegistryService.ActiveStream(
                StreamRegistryService.StreamId.fresh(),
                1,
                "x",
                mock(org.springframework.web.reactive.socket.WebSocketSession.class)));

        StreamFacade f = buildWithCaps(sr, streams, 10, 1);
        AuthorizeResult r = f.authorizeOpen(1, identity());
        assertThat(r).isInstanceOfSatisfying(StreamFacade.CapExceeded.class, ce -> assertThat(ce.scope())
                .isEqualTo("global"));
    }

    @Test
    void per_client_cap_exceeded() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        when(sr.list()).thenReturn(List.of(runningRecord(1)));
        StreamRegistryService streams = new StreamRegistryService(props(1, 100));
        streams.register(new StreamRegistryService.ActiveStream(
                StreamRegistryService.StreamId.fresh(),
                1,
                identity().fingerprintHex(),
                mock(org.springframework.web.reactive.socket.WebSocketSession.class)));

        StreamFacade f = buildWithCaps(sr, streams, 1, 100);
        AuthorizeResult r = f.authorizeOpen(1, identity());
        assertThat(r).isInstanceOfSatisfying(StreamFacade.CapExceeded.class, ce -> assertThat(ce.scope())
                .isEqualTo("per-client"));
    }

    @Test
    void allowed_when_no_caps_are_tripped() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        when(sr.list()).thenReturn(List.of(runningRecord(1)));
        StreamFacade f = build(sr, new StreamRegistryService(props(10, 100)));
        assertThat(f.authorizeOpen(1, identity())).isInstanceOf(StreamFacade.Allowed.class);
    }

    @Test
    void draining_short_circuits_to_Draining() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        StreamFacade f = build(sr, new StreamRegistryService(props(10, 100)));
        f.setDraining(true);
        assertThat(f.authorizeOpen(1, identity())).isInstanceOf(StreamFacade.Draining.class);
        assertThat(f.isDraining()).isTrue();
    }

    // ── UC04 AC37 — attach-guard ────────────────────────────────────────────

    @Test
    void attach_to_stopped_returns_NotRunning() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        when(sr.list()).thenReturn(List.of(recordWithState(1, "stopped")));

        StreamFacade f = build(sr, new StreamRegistryService(props(10, 100)));
        AuthorizeResult r = f.authorizeOpen(1, identity());
        assertThat(r).isInstanceOfSatisfying(StreamFacade.NotRunning.class, nr -> {
            assertThat(nr.n()).isEqualTo(1);
            assertThat(nr.state()).isEqualTo("stopped");
        });
    }

    @Test
    void attach_to_starting_returns_NotRunning() throws Exception {
        SessionRegistryService sr = mock(SessionRegistryService.class);
        when(sr.list()).thenReturn(List.of(recordWithState(1, "starting")));

        StreamFacade f = build(sr, new StreamRegistryService(props(10, 100)));
        AuthorizeResult r = f.authorizeOpen(1, identity());
        assertThat(r).isInstanceOfSatisfying(StreamFacade.NotRunning.class, nr -> {
            assertThat(nr.state()).isEqualTo("starting");
        });
    }
}
