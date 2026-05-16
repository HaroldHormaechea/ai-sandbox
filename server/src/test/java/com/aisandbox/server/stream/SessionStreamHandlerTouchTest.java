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
 * Developer's known limitation #5 — {@code SessionStreamHandler.touch(streamId)}
 * is a stub. The idle-timeout sweeper reads {@link ActiveStream#lastIo} but
 * the read / write paths in {@link SessionStreamHandler} never refresh it,
 * so an active stream looks idle to the sweeper.
 *
 * <p>This test asserts the gap is present: invoking {@code touch} on the
 * handler does NOT update an {@link ActiveStream}'s {@code lastIo} (it can't,
 * since the handler holds no registry handle today). The finding is
 * reported back to the developer in the test summary.
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
    void touch_is_currently_a_stub_and_does_not_refresh_lastIo() {
        // This test documents an open bug. When the developer wires touch()
        // into the read/write paths (and the handler gains access to the
        // ActiveStream lastIo field), update this test to assert the
        // OPPOSITE: invoking touch DOES advance lastIo. The current state
        // is intentionally captured to make the regression visible.
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

        // Invoke the public hook — currently a no-op per its own javadoc.
        handler.touch(as.id);

        // KNOWN ISSUE: this assertion is INTENTIONAL — if the developer
        // wires touch() through to the registry, the assertion must flip
        // (assertThat(as.lastIo).isAfter(before)) and the test renamed.
        assertThat(as.lastIo).isEqualTo(before);
    }
}
