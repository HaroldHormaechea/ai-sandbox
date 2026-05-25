package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.stream.dto.StreamServerMessage.TargetInfo;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handler.SessionStreamHandler;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.SwarmEnumerationService;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import com.aisandbox.server.stream.service.TmuxBridgeService.BridgeTarget;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * UC-21 AC#11 / AC#13 + challenger guardrail #1 — the {@link SessionStreamHandler}
 * agent-switcher protocol, driven end-to-end through {@code handle(session)} with
 * a recording {@link WebSocketSession} double and controllable bridge stubs (no
 * Docker, no real PTY).
 *
 * <p>What this pins:
 *
 * <ul>
 *   <li><b>enumerate-targets</b> → a {@code targets} frame listing the main
 *       session (first) plus the swarm panes, with the current selection.</li>
 *   <li><b>select-target — swap-then-close ordering</b>: the NEW bridge is
 *       started (and the generation bumped) BEFORE the OLD bridge is closed.
 *       Closing the old bridge first would unblock the pump while it still held
 *       the old generation, completing the shared outbound sink and killing the
 *       WebSocket. We assert the strict order start-new → close-old.</li>
 *   <li><b>single-sink continuity</b>: a swap does NOT complete the outbound
 *       sink — after the swap the pump picks up the new bridge and its stdout
 *       still surfaces as binary frames; the session is never closed.</li>
 *   <li><b>off-event-loop dispatch</b>: the blocking re-bridge runs on a
 *       {@code boundedElastic} worker, not the Reactor event loop.</li>
 *   <li><b>TargetSelected / error replies</b> on success / failure.</li>
 * </ul>
 */
class SessionStreamHandlerRebridgeTest {

    private static final DataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;

    private Disposable subscription;

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

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

    private static SessionStreamHandler newHandler(StreamFacade facade) {
        return new SessionStreamHandler(facade, new StreamControlMessageService(), 262144, 262144, 16384);
    }

    private static ClientIdentity identity() {
        return new ClientIdentity("alice", "f".repeat(64), BigInteger.ONE);
    }

    private static SessionRecord running(int n) {
        return new SessionRecord(n, "", "(idle)", "running", 0L, 0, Instant.EPOCH);
    }

    /** A select-target / enumerate-targets text frame as the wire delivers it. */
    private static WebSocketMessage textFrame(String json) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT, BUFFERS.wrap(json.getBytes(StandardCharsets.UTF_8)));
    }

    // ── select-target: swap-then-close ordering + continuity ────────────────

    @Test
    void select_target_starts_new_bridge_before_closing_old_and_keeps_the_stream_alive() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> dispatchThreads = new CopyOnWriteArrayList<>();

        BridgeStub b0 = new BridgeStub("b0", events);
        BridgeStub b1 = new BridgeStub("b1", events);
        // The new bridge already has output queued, so once the pump swaps onto
        // it the bytes surface as a binary frame (proves the sink lived).
        b1.queueOutput("B1-OUT".getBytes(StandardCharsets.UTF_8));

        TmuxBridgeService tmux = mock(TmuxBridgeService.class);
        // initial bridge (4-arg overload, default 80x24)
        when(tmux.start(eq(7), anyString(), eq(80), eq(24))).thenReturn(b0.bridge);
        // re-bridge (5-arg overload) — record dispatch thread + start ordering
        when(tmux.start(eq(7), anyString(), any(BridgeTarget.class), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    dispatchThreads.add(Thread.currentThread().getName());
                    events.add("start-b1");
                    return b1.bridge;
                });

        SwarmEnumerationService swarm = mock(SwarmEnumerationService.class);
        when(swarm.resolveTarget(eq(7), eq("swarm:claude-swarm-1:0.1")))
                .thenReturn(new BridgeTarget("/tmp/tmux-997/claude-swarm-1", "claude-swarm", "0", "1"));

        FakeSession session = startStream(tmux, swarm, "{\"type\":\"select-target\",\"targetId\":\"swarm:claude-swarm-1:0.1\"}");

        // Wait until both the swap and the close happened, and the new bridge's
        // output surfaced (continuity).
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> events.contains("start-b1")
                        && events.contains("close-b0")
                        && !session.sentBinary.isEmpty());

        // ── guardrail #1 — new started BEFORE old closed ──
        assertThat(events.indexOf("start-b1"))
                .as("the new bridge must be started before the old one is closed")
                .isLessThan(events.indexOf("close-b0"));

        // ── continuity — the sink was NOT completed on the swap ──
        assertThat(session.sentBinary).anySatisfy(b -> assertThat(new String(b, StandardCharsets.UTF_8))
                .isEqualTo("B1-OUT"));
        assertThat(session.closedWith).as("a successful swap must not close the WebSocket").isNull();

        // ── off-event-loop dispatch ──
        assertThat(dispatchThreads).isNotEmpty();
        assertThat(dispatchThreads.get(0))
                .as("the blocking re-bridge must run on a boundedElastic worker")
                .contains("boundedElastic");

        // ── TargetSelected reply ──
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> session.sentText.stream().anyMatch(t -> t.contains("\"type\":\"target-selected\"")));
        assertThat(session.sentText)
                .anySatisfy(t -> assertThat(t).contains("\"targetId\":\"swarm:claude-swarm-1:0.1\""));
    }

    @Test
    void enumerate_targets_replies_with_main_first_and_swarm_panes() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        BridgeStub b0 = new BridgeStub("b0", events);

        TmuxBridgeService tmux = mock(TmuxBridgeService.class);
        when(tmux.start(eq(7), anyString(), eq(80), eq(24))).thenReturn(b0.bridge);

        SwarmEnumerationService swarm = mock(SwarmEnumerationService.class);
        TargetInfo main = new TargetInfo("main", "main", "main", null, null, null, null, null, "main", null, null);
        TargetInfo pane = new TargetInfo(
                "swarm:claude-swarm-1:0.0", "swarm", "ping", "ping", "general-purpose", "blue", "t",
                "/sock", "claude-swarm", "0", "0");
        when(swarm.enumerate(7)).thenReturn(List.of(main, pane));

        FakeSession session = startStream(tmux, swarm, "{\"type\":\"enumerate-targets\"}");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> session.sentText.stream().anyMatch(t -> t.contains("\"type\":\"targets\"")));

        String frame = session.sentText.stream()
                .filter(t -> t.contains("\"type\":\"targets\""))
                .findFirst()
                .orElseThrow();
        assertThat(frame).contains("\"selectedId\":\"main\"");
        assertThat(frame).contains("\"id\":\"main\"");
        assertThat(frame).contains("\"id\":\"swarm:claude-swarm-1:0.0\"");
        // main is listed before the swarm pane.
        assertThat(frame.indexOf("\"id\":\"main\"")).isLessThan(frame.indexOf("\"id\":\"swarm:claude-swarm-1:0.0\""));
    }

    @Test
    void failed_select_target_emits_a_server_error_and_keeps_the_stream() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        BridgeStub b0 = new BridgeStub("b0", events);

        TmuxBridgeService tmux = mock(TmuxBridgeService.class);
        when(tmux.start(eq(7), anyString(), eq(80), eq(24))).thenReturn(b0.bridge);

        SwarmEnumerationService swarm = mock(SwarmEnumerationService.class);
        when(swarm.resolveTarget(eq(7), anyString())).thenThrow(new NoSuchElementException("vanished"));

        FakeSession session = startStream(tmux, swarm, "{\"type\":\"select-target\",\"targetId\":\"swarm:gone:9.9\"}");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> session.sentText.stream().anyMatch(t -> t.contains("\"code\":\"rebridge_failed\"")));

        // The original bridge is untouched and the stream stays open.
        assertThat(events).doesNotContain("close-b0");
        assertThat(session.closedWith).isNull();
    }

    // ── harness ─────────────────────────────────────────────────────────────

    /** Build a real facade over the supplied mocks, then subscribe handle(). */
    private FakeSession startStream(TmuxBridgeService tmux, SwarmEnumerationService swarm, String inboundFrame) {
        SessionRegistryService sessions = mock(SessionRegistryService.class);
        try {
            when(sessions.list()).thenReturn(List.of(running(7)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        StreamFacade facade = new StreamFacade(
                sessions,
                new StreamRegistryService(props()),
                tmux,
                new PerSessionMutexRegistry(),
                mock(AuditLogger.class),
                props());
        facade.setSwarmEnumeration(swarm);

        SessionStreamHandler handler = newHandler(facade);

        // Emit the one control frame, then hold the receive() pipeline open so
        // handle() doesn't tear down while we observe the async re-bridge.
        Flux<WebSocketMessage> incoming = Flux.concat(Flux.just(textFrame(inboundFrame)), Flux.never());
        FakeSession session = new FakeSession(URI.create("/v1/sessions/7/stream"), incoming);
        session.attributes.put(SessionStreamHandler.IDENTITY_ATTR, identity());

        subscription = handler.handle(session).subscribe();
        return session;
    }

    /** A Mockito {@link TmuxBridgeService.Bridge} with a blocking, queue-backed reader. */
    private static final class BridgeStub {
        final TmuxBridgeService.Bridge bridge = mock(TmuxBridgeService.Bridge.class);
        final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
        final AtomicBoolean closed = new AtomicBoolean(false);

        BridgeStub(String name, List<String> events) {
            try {
                when(bridge.readStdout(any())).thenAnswer(inv -> {
                    byte[] buf = inv.getArgument(0);
                    while (true) {
                        byte[] chunk = out.poll(50, TimeUnit.MILLISECONDS);
                        if (chunk != null) {
                            int n = Math.min(buf.length, chunk.length);
                            System.arraycopy(chunk, 0, buf, 0, n);
                            return n;
                        }
                        if (closed.get()) {
                            return -1;
                        }
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            when(bridge.isAlive()).thenReturn(true);
            doAnswer(inv -> {
                        events.add("close-" + name);
                        closed.set(true);
                        return null;
                    })
                    .when(bridge)
                    .close();
        }

        void queueOutput(byte[] data) {
            out.add(data);
        }
    }

    /** Minimal recording {@link WebSocketSession} double. */
    static final class FakeSession implements WebSocketSession {
        private final URI uri;
        private final Flux<WebSocketMessage> incoming;
        private final HandshakeInfo handshakeInfo;
        final java.util.Map<String, Object> attributes = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> sentText = new CopyOnWriteArrayList<>();
        final List<byte[]> sentBinary = new CopyOnWriteArrayList<>();
        volatile CloseStatus closedWith;

        FakeSession(URI uri, Flux<WebSocketMessage> incoming) {
            this.uri = uri;
            this.incoming = incoming;
            this.handshakeInfo = new HandshakeInfo(uri, new HttpHeaders(), Mono.empty(), null);
        }

        private void record(WebSocketMessage m) {
            if (m.getType() == WebSocketMessage.Type.TEXT) {
                sentText.add(m.getPayloadAsText());
            } else if (m.getType() == WebSocketMessage.Type.BINARY) {
                DataBuffer payload = m.getPayload();
                byte[] arr = new byte[payload.readableByteCount()];
                payload.read(arr);
                sentBinary.add(arr);
            }
        }

        @Override
        public String getId() {
            return "fake-session";
        }

        @Override
        public HandshakeInfo getHandshakeInfo() {
            return handshakeInfo;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return BUFFERS;
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Flux<WebSocketMessage> receive() {
            return incoming;
        }

        @Override
        public Mono<Void> send(org.reactivestreams.Publisher<WebSocketMessage> messages) {
            return Flux.from(messages).doOnNext(this::record).then();
        }

        @Override
        public boolean isOpen() {
            return closedWith == null;
        }

        @Override
        public Mono<Void> close(CloseStatus status) {
            this.closedWith = status;
            return Mono.empty();
        }

        @Override
        public Mono<CloseStatus> closeStatus() {
            return Mono.justOrEmpty(closedWith);
        }

        @Override
        public WebSocketMessage textMessage(String payload) {
            return new WebSocketMessage(
                    WebSocketMessage.Type.TEXT, BUFFERS.wrap(payload.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public WebSocketMessage binaryMessage(java.util.function.Function<DataBufferFactory, DataBuffer> factory) {
            return new WebSocketMessage(WebSocketMessage.Type.BINARY, factory.apply(BUFFERS));
        }

        @Override
        public WebSocketMessage pingMessage(java.util.function.Function<DataBufferFactory, DataBuffer> factory) {
            return new WebSocketMessage(WebSocketMessage.Type.PING, factory.apply(BUFFERS));
        }

        @Override
        public WebSocketMessage pongMessage(java.util.function.Function<DataBufferFactory, DataBuffer> factory) {
            return new WebSocketMessage(WebSocketMessage.Type.PONG, factory.apply(BUFFERS));
        }
    }
}
