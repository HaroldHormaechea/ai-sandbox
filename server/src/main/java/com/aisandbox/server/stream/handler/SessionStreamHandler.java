package com.aisandbox.server.stream.handler;

import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.OutputRingBuffer;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import io.netty.channel.ChannelId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive WebSocket handler bound to {@code /v1/sessions/{n}/stream}.
 * Spawns a {@link TmuxBridgeService.Bridge} via the facade, then bridges
 * binary frames ↔ PTY stdio and text frames ↔ control messages.
 *
 * <p><b>Identity propagation.</b> The {@code WebSocketSession} produced by
 * Reactor-Netty does NOT carry the authenticated {@link ClientIdentity}
 * in its attribute map — Spring's {@code HandshakeWebSocketService}
 * builds those attributes from the HTTP web-session, which we don't use.
 * Instead, the TLS handshake-completion handler in
 * {@code NettyServerCustomizer} indexes the identity by Netty
 * {@link ChannelId} on {@link ActiveConnectionRegistry}. The Reactor-Netty
 * {@code WebSocketSession} exposes its underlying channel id; we look up
 * the identity via the registry (injected post-construct via
 * {@link #setActiveConnectionRegistry(ActiveConnectionRegistry)}) and
 * stash it on the session's attribute map so downstream code can read it
 * without another registry hop.
 *
 * <p>Identity resolution falls back to a pre-stashed session attribute
 * (key {@link #IDENTITY_ATTR}) so unit tests can inject identity without
 * spinning up a real TLS pipeline.
 *
 * <p>The {@code n} path variable is resolved from the URI in
 * {@link #handle(WebSocketSession)} since reactive Spring does not
 * thread path-variables into the handler directly.
 */
public class SessionStreamHandler implements WebSocketHandler {

    /** Key under which the resolved identity is stored on the WebSocket session. */
    public static final String IDENTITY_ATTR = "ai-sandbox.client-identity";

    private static final Logger LOG = LoggerFactory.getLogger(SessionStreamHandler.class);

    private final StreamFacade facade;
    private final StreamControlMessageService controlSvc;
    private final int outputRingBytes;
    private final int maxBinaryBytes;
    private final int maxTextBytes;

    /** Optional — set by {@code WebSocketConfiguration} at bean construction time. */
    private volatile ActiveConnectionRegistry connections;

    /**
     * UC04 § B2 — set by {@code WebSocketConfiguration} so the handler
     * can index every live WS session against its client fingerprint.
     * The connection registry's {@code revoke(...)} path consults this
     * index to issue a graceful close (code 4401, "revoked") before
     * tearing down the underlying TCP channel — AC26 surfaces the
     * Android cert-revoked dialog on the resulting close-frame event.
     */
    private volatile ActiveStreamRegistry streamRegistry;

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

    /**
     * Late binding of the TLS-side connection registry so production
     * wiring can inject it without changing the unit-test constructor
     * signature. Tests that don't need identity resolution leave it
     * unset and {@link #resolveIdentity(WebSocketSession)} either reads
     * a pre-stashed attribute or returns {@code null}.
     */
    public void setActiveConnectionRegistry(ActiveConnectionRegistry connections) {
        this.connections = connections;
    }

    /**
     * Late binding of the UC04 {@link ActiveStreamRegistry}. Tests that
     * don't care about the graceful-close path leave this unset; the
     * handler treats it as a no-op.
     */
    public void setActiveStreamRegistry(ActiveStreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        int n;
        try {
            n = extractN(session);
        } catch (IllegalArgumentException iae) {
            return session.close(CloseStatus.BAD_DATA);
        }
        ClientIdentity identity = resolveIdentity(session);
        if (identity == null) {
            LOG.warn(
                    "Closing stream: no client identity recorded for channel {} (TLS handshake completion handler"
                            + " did not run, or fingerprint was just revoked)",
                    channelIdOf(session));
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        // Stash for downstream consumers.
        session.getAttributes().put(IDENTITY_ATTR, identity);

        OutputRingBuffer ring = new OutputRingBuffer(outputRingBytes);
        Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<TmuxBridgeService.Bridge> bridgeRef = new AtomicReference<>();
        AtomicReference<StreamId> idRef = new AtomicReference<>();

        // UC04 § B2 — index this WS session against its fingerprint so
        // the revoke() orchestration can graceful-close it on cert
        // removal. detach happens in the doFinally below regardless of
        // exit path (normal close, server error, client disconnect).
        ActiveStreamRegistry streams = this.streamRegistry;
        final String fingerprintForStreamIndex = identity.fingerprintHex();
        if (streams != null) {
            streams.attach(fingerprintForStreamIndex, session);
        }

        final ClientIdentity capturedIdentity = identity;
        return Mono.fromCallable(() -> facade.openStream(n, capturedIdentity, session))
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
                    Thread reader = new Thread(() -> pump(bridgeRef.get(), ring, outbound, session, streamId));
                    reader.setDaemon(true);
                    reader.setName("ai-sandbox-pty-out-" + streamId.value());
                    reader.start();

                    // Incoming pipeline: text → control, binary → PTY stdin.
                    Flux<Void> incoming = session.receive()
                            .flatMap(msg -> handleIncoming(msg, bridgeRef.get(), session, streamId))
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
                    if (streams != null) {
                        streams.detach(fingerprintForStreamIndex, session);
                    }
                })
                .onErrorResume(t -> {
                    LOG.warn("Stream handler error: {}", t.toString());
                    return session.close(CloseStatus.SERVER_ERROR);
                });
    }

    /**
     * Resolve the authenticated {@link ClientIdentity} for an incoming
     * WebSocket session. Order:
     *
     * <ol>
     *   <li>Already-stashed attribute on the session (allows tests to
     *       inject identity without spinning up a real TLS pipeline).</li>
     *   <li>Channel-id lookup against {@link ActiveConnectionRegistry} —
     *       the production path. Requires {@link #setActiveConnectionRegistry}
     *       to have been called.</li>
     * </ol>
     */
    private ClientIdentity resolveIdentity(WebSocketSession session) {
        Object stashed = session.getAttributes().get(IDENTITY_ATTR);
        if (stashed instanceof ClientIdentity ci) {
            return ci;
        }
        ActiveConnectionRegistry reg = connections;
        if (reg == null) {
            return null;
        }
        ChannelId cid = channelIdOf(session);
        return cid == null ? null : reg.identityFor(cid);
    }

    /**
     * Resolve the Netty {@link ChannelId} of the WebSocket session's underlying
     * transport channel.
     *
     * <h2>WS-over-H2 — deferred to a follow-up</h2>
     *
     * <p>UC07 re-enabled HTTP/2 for the REST surface (see
     * {@code ClientIdentityExtractor#channelIdOf} for the parent-walk fix
     * that makes mTLS identity propagate across {@code Http2StreamChannel}
     * boundaries). The stream/WebSocket leg is NOT migrated to H2 by UC07
     * and intentionally remains on the HTTP/1.1 Upgrade path:
     *
     * <ul>
     *   <li>Production browsers and Java {@code HttpClient} open WebSockets
     *       via the H1.1 {@code Upgrade: websocket} handshake; ALPN-
     *       negotiated H2 connections back-channel back to H1.1 for the
     *       WebSocket leg, which keeps this path working unchanged.</li>
     *   <li>{@code ReactorNettyWebSocketSession#getChannelId()} returns the
     *       id of the H1.1 upgraded connection channel — the same channel
     *       that received the TLS handshake — so the registry lookup
     *       continues to find the {@link ClientIdentity}.</li>
     * </ul>
     *
     * <p>Bringing the WebSocket leg under H2 ("WS-over-H2", per RFC 8441
     * via the {@code :protocol = websocket} pseudo-header on a CONNECT
     * stream) is a future ticket. When it lands, this method must
     * mirror the {@code ClientIdentityExtractor#channelIdOf} pattern:
     * detect {@link io.netty.handler.codec.http2.Http2StreamChannel}
     * under the session's transport, walk to {@code parent()}, and use
     * the parent's id. Reactor Netty's WebSocket support does not
     * advertise the {@code :protocol} extended-CONNECT pseudo-header
     * today, which is why we defer rather than pre-emptively walk
     * here.
     */
    private static ChannelId channelIdOf(WebSocketSession session) {
        if (session instanceof ReactorNettyWebSocketSession rnws) {
            return rnws.getChannelId();
        }
        return null;
    }

    private Mono<Void> handleIncoming(
            WebSocketMessage msg, TmuxBridgeService.Bridge bridge, WebSocketSession session, StreamId streamId) {
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
                facade.streamRegistry().touch(streamId);
                return Mono.empty();
            case TEXT:
                String text = msg.getPayloadAsText();
                if (text.length() > maxTextBytes) {
                    return session.close(CloseStatus.TOO_BIG_TO_PROCESS);
                }
                try {
                    ControlMessage cm = controlSvc.parse(text);
                    facade.streamRegistry().touch(streamId);
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
            WebSocketSession session,
            StreamId streamId) {
        byte[] buf = new byte[8192];
        StreamRegistryService registry = facade.streamRegistry();
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
                    registry.touch(streamId);
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
     * Public hook for refreshing the {@code lastIo} watermark on a
     * stream. Delegates to the facade-exposed
     * {@link StreamRegistryService#touch(StreamId)}. Production paths
     * inside this class call the registry directly; external callers can
     * use this entry point.
     */
    public void touch(StreamId id) {
        facade.streamRegistry().touch(id);
    }
}
