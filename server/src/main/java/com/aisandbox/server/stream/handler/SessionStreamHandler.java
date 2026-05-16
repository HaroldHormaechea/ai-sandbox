package com.aisandbox.server.stream.handler;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.OutputRingBuffer;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive WebSocket handler bound to {@code /v1/sessions/{n}/stream}.
 * Spawns a {@link TmuxBridgeService.Bridge} via the facade, then bridges
 * binary frames ↔ PTY stdio and text frames ↔ control messages.
 *
 * <p>The {@code n} path variable is resolved from the URI in
 * {@link #handle(WebSocketSession)} since reactive Spring does not
 * thread path-variables into the handler directly.
 */
public class SessionStreamHandler implements WebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SessionStreamHandler.class);

    private final StreamFacade facade;
    private final StreamControlMessageService controlSvc;
    private final int outputRingBytes;
    private final int maxBinaryBytes;
    private final int maxTextBytes;

    public SessionStreamHandler(
            StreamFacade facade,
            StreamControlMessageService controlSvc,
            int outputRingBytes,
            int maxBinaryBytes,
            int maxTextBytes) {
        this.facade = facade;
        this.controlSvc = controlSvc;
        this.outputRingBytes = outputRingBytes;
        this.maxBinaryBytes = maxBinaryBytes;
        this.maxTextBytes = maxTextBytes;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        int n;
        try {
            n = extractN(session);
        } catch (IllegalArgumentException iae) {
            return session.close(CloseStatus.BAD_DATA);
        }
        ClientIdentity identity = (ClientIdentity) session.getAttributes().get(ClientIdentityExtractor.ATTR);
        if (identity == null) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        OutputRingBuffer ring = new OutputRingBuffer(outputRingBytes);
        Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<TmuxBridgeService.Bridge> bridgeRef = new AtomicReference<>();
        AtomicReference<StreamId> idRef = new AtomicReference<>();

        return Mono.fromCallable(() -> facade.openStream(n, identity, session))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(streamId -> {
                    idRef.set(streamId);
                    try {
                        TmuxBridgeService.Bridge bridge = facade.tmux().start(n, streamId.value(), 80, 24);
                        bridgeRef.set(bridge);
                    } catch (IOException io) {
                        LOG.warn("tmux bridge failed for stream {}: {}", streamId.value(), io.toString());
                        return session.send(Mono.just(session.textMessage(controlError(io))))
                                .then(session.close(CloseStatus.SERVER_ERROR));
                    }

                    // Reader thread: PTY stdout → outbound binary frames.
                    Thread reader = new Thread(() -> pump(bridgeRef.get(), ring, outbound, session));
                    reader.setDaemon(true);
                    reader.setName("ai-sandbox-pty-out-" + streamId.value());
                    reader.start();

                    // Incoming pipeline: text → control, binary → PTY stdin.
                    Flux<Void> incoming = session.receive()
                            .flatMap(msg -> handleIncoming(msg, bridgeRef.get(), session))
                            .then()
                            .flux();

                    Mono<Void> outboundCompletion = session.send(outbound.asFlux());
                    return Mono.when(incoming, outboundCompletion);
                })
                .doFinally(sig -> {
                    TmuxBridgeService.Bridge b = bridgeRef.get();
                    if (b != null) {
                        b.close();
                    }
                    StreamId id = idRef.get();
                    if (id != null) {
                        facade.closeStream(id, 1000, sig.name());
                    }
                })
                .onErrorResume(t -> {
                    LOG.warn("Stream handler error: {}", t.toString());
                    return session.close(CloseStatus.SERVER_ERROR);
                });
    }

    private Mono<Void> handleIncoming(WebSocketMessage msg, TmuxBridgeService.Bridge bridge, WebSocketSession session) {
        if (bridge == null) {
            return Mono.empty();
        }
        switch (msg.getType()) {
            case BINARY:
                ByteBuffer bb = msg.getPayload().asByteBuffer();
                if (bb.remaining() > maxBinaryBytes) {
                    return session.close(CloseStatus.TOO_BIG_TO_PROCESS);
                }
                byte[] stdin = new byte[bb.remaining()];
                bb.get(stdin);
                try {
                    bridge.writeStdin(stdin);
                } catch (IOException io) {
                    return session.close(CloseStatus.SERVER_ERROR);
                }
                return Mono.empty();
            case TEXT:
                String text = msg.getPayloadAsText();
                if (text.length() > maxTextBytes) {
                    return session.close(CloseStatus.TOO_BIG_TO_PROCESS);
                }
                try {
                    ControlMessage cm = controlSvc.parse(text);
                    return applyControl(cm, bridge, session);
                } catch (IllegalArgumentException iae) {
                    return session.send(Mono.just(session.textMessage(controlError(iae))))
                            .then();
                }
            default:
                return Mono.empty();
        }
    }

    private Mono<Void> applyControl(ControlMessage cm, TmuxBridgeService.Bridge bridge, WebSocketSession session) {
        switch (cm) {
            case ControlMessage.Resize r -> bridge.resize(r.cols(), r.rows());
            case ControlMessage.MouseControl m -> {
                try {
                    bridge.writeStdin(controlSvc.toXtermSgr(m));
                } catch (IOException io) {
                    return session.close(CloseStatus.SERVER_ERROR);
                }
            }
            case ControlMessage.CloseControl c -> {
                return session.close(CloseStatus.NORMAL.withReason(c.reason() == null ? "client-close" : c.reason()));
            }
            case ControlMessage.ErrorMessage e -> {
                // Server side does not act on client-emitted error frames.
            }
        }
        return Mono.empty();
    }

    private void pump(
            TmuxBridgeService.Bridge bridge,
            OutputRingBuffer ring,
            Sinks.Many<WebSocketMessage> outbound,
            WebSocketSession session) {
        byte[] buf = new byte[8192];
        try {
            while (bridge.isAlive()) {
                int n = bridge.readStdout(buf);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                if (!ring.write(buf, 0, n)) {
                    String err = "{\"type\":\"error\",\"code\":\"stream_overflow\",\"detail\":\"output buffer full\"}";
                    outbound.tryEmitNext(session.textMessage(err));
                    session.close(CloseStatus.TOO_BIG_TO_PROCESS).subscribe();
                    return;
                }
                byte[] drained = ring.drain(maxBinaryBytes);
                if (drained.length > 0) {
                    outbound.tryEmitNext(session.binaryMessage(bf -> bf.wrap(drained)));
                }
            }
        } catch (IOException io) {
            LOG.info("PTY reader done: {}", io.toString());
        } finally {
            outbound.tryEmitComplete();
        }
    }

    private String controlError(Exception e) {
        return "{\"type\":\"error\",\"code\":\"bad_request\",\"detail\":\""
                + (e.getMessage() == null ? "" : e.getMessage().replace("\"", "'")) + "\"}";
    }

    private static int extractN(WebSocketSession session) {
        // URI shape: wss://host/v1/sessions/{n}/stream
        String path = session.getHandshakeInfo().getUri().getPath();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("sessions".equals(parts[i]) && i + 1 < parts.length) {
                try {
                    return Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("bad session number in path: " + parts[i + 1]);
                }
            }
        }
        throw new IllegalArgumentException("session number missing in path: " + path);
    }

    /**
     * Reserved hook for the keepalive sweeper to refresh lastIo on a
     * stream — currently a no-op pending a registry-handle accessor. The
     * sweeper independently inspects each stream's {@code lastIo}.
     */
    public void touch(StreamId id) {
        // intentionally empty
    }
}
