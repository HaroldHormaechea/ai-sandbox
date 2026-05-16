package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handler.SessionStreamHandler;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.StreamRegistryService.ActiveStream;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * AC28 — the idle-timeout sweeper must measure elapsed time since the
 * last <em>real</em> I/O, not since stream-open. {@code touch(StreamId)}
 * advances {@code lastIo} to "now" on every frame so an actively
 * streaming client is never evicted.
 *
 * <p>This test pinned the gap when {@code touch()} was a stub (round 1);
 * after the developer wired the registry-side hook on every read / write
 * path, the assertion was flipped to confirm advancement. If a future
 * change re-stubs the method, this test goes red — that's the regression
 * signal.
 */
class SessionStreamHandlerTouchTest {

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

    @Test
    void touch_advances_lastIo_to_now() {
        StreamRegistryService streams = new StreamRegistryService(props());
        ClientIdentity id = new ClientIdentity("alice", "a".repeat(64), BigInteger.ONE);
        ActiveStream as =
                new ActiveStream(StreamId.fresh(), 1, id.fingerprintHex(), Mockito.mock(WebSocketSession.class));
        Instant before = Instant.now().minusSeconds(60);
        as.lastIo = before;
        streams.register(as);

        StreamFacade facade = new StreamFacade(
                Mockito.mock(SessionRegistryService.class),
                streams,
                Mockito.mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                Mockito.mock(AuditLogger.class),
                props());
        SessionStreamHandler handler =
                new SessionStreamHandler(facade, new StreamControlMessageService(), 262144, 262144, 16384);

        Instant beforeCall = Instant.now();
        handler.touch(as.id);

        // touch() must move lastIo forward to (approximately) "now".
        assertThat(as.lastIo).isAfter(before);
        assertThat(as.lastIo).isAfterOrEqualTo(beforeCall.minusMillis(1));
    }

    @Test
    void touch_on_unknown_streamId_is_a_safe_no_op() {
        StreamRegistryService streams = new StreamRegistryService(props());
        StreamFacade facade = new StreamFacade(
                Mockito.mock(SessionRegistryService.class),
                streams,
                Mockito.mock(TmuxBridgeService.class),
                new PerSessionMutexRegistry(),
                Mockito.mock(AuditLogger.class),
                props());
        SessionStreamHandler handler =
                new SessionStreamHandler(facade, new StreamControlMessageService(), 262144, 262144, 16384);

        // Must not throw; nothing registered for this id.
        handler.touch(StreamId.fresh());
    }
}
