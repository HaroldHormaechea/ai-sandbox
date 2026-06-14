package com.aisandbox.server.stream.handler;

import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.OutputRingBuffer;
import com.aisandbox.server.stream.service.StreamBridgeRegistry;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import io.netty.channel.ChannelId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Id of the always-present main-session target (AC#10). */
    public static final String TARGET_MAIN = "main";

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

    /**
     * UC-74 — set by {@code WebSocketConfiguration} so the handler registers
     * each live PTY {@link TmuxBridgeService.Bridge} (keyed by stream id) for
     * deterministic teardown on graceful shutdown. The shutdown handler
     * iterates this registry and closes any remaining bridge, killing the
     * pty4j child + per-client tmux session so they cannot pin the JVM (AC4).
     * Tests that don't exercise shutdown leave it unset; the handler treats a
     * null registry as a no-op.
     */
    private volatile StreamBridgeRegistry bridgeRegistry;

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

    /**
     * UC-74 — late binding of the {@link StreamBridgeRegistry}. Tests that
     * don't exercise the shutdown teardown leave this unset; the handler
     * treats it as a no-op.
     */
    public void setStreamBridgeRegistry(StreamBridgeRegistry bridgeRegistry) {
        this.bridgeRegistry = bridgeRegistry;
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
        // UC-21 — a generation token bumped on every mid-stream re-bridge so the
        // single long-lived pump knows to pick up the swapped-in bridge; a
        // separate sequence keeps per-client tmux session names unique across
        // swaps. cols/rows track the last resize so a re-bridge starts at the
        // right geometry. selectedTarget is reflected in Targets enumerations.
        AtomicInteger generation = new AtomicInteger(0);
        AtomicInteger rebridgeSeq = new AtomicInteger(0);
        AtomicInteger cols = new AtomicInteger(80);
        AtomicInteger rows = new AtomicInteger(24);
        AtomicReference<String> selectedTarget = new AtomicReference<>(TARGET_MAIN);

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
                        TmuxBridgeService.Bridge bridge =
                                facade.tmux().start(n, streamId.value(), cols.get(), rows.get());
                        bridgeRef.set(bridge);
                        // UC-74 — track the live bridge for deterministic
                        // teardown on graceful shutdown (unregistered in the
                        // doFinally below; re-registered on a mid-stream
                        // re-bridge so the backstop always sees the live one).
                        StreamBridgeRegistry br = this.bridgeRegistry;
                        if (br != null) {
                            br.register(streamId, bridge);
                        }
                    } catch (IOException io) {
                        LOG.warn("tmux bridge failed for stream {}: {}", streamId.value(), io.toString());
                        return session.send(Mono.just(session.textMessage(
                                        serverErrorFrame("bridge_failed", "Bridge failed", io.getMessage()))))
                                .then(session.close(CloseStatus.SERVER_ERROR));
                    }

                    StreamCtx ctx = new StreamCtx(
                            n, streamId, bridgeRef, generation, rebridgeSeq, cols, rows, selectedTarget, outbound);

                    // Single long-lived reader: PTY stdout → outbound binary
                    // frames, surviving mid-stream bridge swaps via the
                    // generation token (it never completes the outbound sink on a
                    // swap — only on true teardown).
                    Thread reader = new Thread(() -> pump(ring, session, ctx));
                    reader.setDaemon(true);
                    reader.setName("ai-sandbox-pty-out-" + streamId.value());
                    reader.start();

                    // Incoming pipeline: text → control, binary → PTY stdin.
                    Flux<Void> incoming = session.receive()
                            .flatMap(msg -> handleIncoming(msg, session, ctx))
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
                        // UC-74 — drop the bridge from the shutdown-teardown
                        // registry; the close above already destroyed it.
                        StreamBridgeRegistry br = this.bridgeRegistry;
                        if (br != null) {
                            br.unregister(id);
                        }
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

    private Mono<Void> handleIncoming(WebSocketMessage msg, WebSocketSession session, StreamCtx ctx) {
        switch (msg.getType()) {
            case BINARY:
                TmuxBridgeService.Bridge bridge = ctx.bridgeRef.get();
                if (bridge == null) {
                    return Mono.empty();
                }
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
                facade.streamRegistry().touch(ctx.streamId);
                return Mono.empty();
            case TEXT:
                String text = msg.getPayloadAsText();
                if (text.length() > maxTextBytes) {
                    return session.close(CloseStatus.TOO_BIG_TO_PROCESS);
                }
                try {
                    ControlMessage cm = controlSvc.parse(text);
                    facade.streamRegistry().touch(ctx.streamId);
                    return applyControl(cm, session, ctx);
                } catch (IllegalArgumentException iae) {
                    ctx.outbound.tryEmitNext(session.textMessage(
                            serverErrorFrame("bad_request", "Bad control message", iae.getMessage())));
                    return Mono.empty();
                }
            default:
                return Mono.empty();
        }
    }

    private Mono<Void> applyControl(ControlMessage cm, WebSocketSession session, StreamCtx ctx) {
        switch (cm) {
            case ControlMessage.Resize r -> {
                ctx.cols.set(r.cols());
                ctx.rows.set(r.rows());
                TmuxBridgeService.Bridge bridge = ctx.bridgeRef.get();
                if (bridge != null) {
                    bridge.resize(r.cols(), r.rows());
                }
            }
            case ControlMessage.MouseControl m -> {
                TmuxBridgeService.Bridge bridge = ctx.bridgeRef.get();
                if (bridge != null) {
                    try {
                        bridge.writeStdin(controlSvc.toXtermSgr(m));
                    } catch (IOException io) {
                        return session.close(CloseStatus.SERVER_ERROR);
                    }
                }
            }
            case ControlMessage.CloseControl c -> {
                return session.close(CloseStatus.NORMAL.withReason(c.reason() == null ? "client-close" : c.reason()));
            }
            case ControlMessage.ErrorMessage e -> {
                // Server side does not act on client-emitted error frames.
            }
            case ControlMessage.EnumerateTargets et -> {
                // Enumeration shells docker/tmux — never on the Reactor event loop.
                Schedulers.boundedElastic().schedule(() -> {
                    List<StreamServerMessage.TargetInfo> targets = facade.enumerateTargets(ctx.n);
                    emit(ctx.outbound, session, new StreamServerMessage.Targets(targets, ctx.selectedTarget.get()));
                });
            }
            case ControlMessage.SelectTarget st -> {
                // Re-bridge mid-stream — off the event loop (blocking docker/tmux).
                Schedulers.boundedElastic().schedule(() -> rebridge(session, ctx, st.targetId()));
            }
        }
        return Mono.empty();
    }

    /**
     * Switch the stream's bridged target on the same WebSocket. Honours the
     * swap-ordering invariant (challenger guardrail #1):
     *
     * <pre>
     *   start new bridge → swap bridgeRef + bump generation → THEN close old.
     * </pre>
     *
     * <p>Closing the old bridge first would unblock the pump's {@code readStdout}
     * while it still holds the OLD generation, so it would treat the EOF as true
     * teardown and complete the shared outbound sink — killing the WebSocket
     * instead of continuing on the new bridge.
     */
    private void rebridge(WebSocketSession session, StreamCtx ctx, String targetId) {
        try {
            String bridgeSessionId = ctx.streamId.value() + "-g" + ctx.rebridgeSeq.incrementAndGet();
            TmuxBridgeService.Bridge fresh =
                    facade.rebridge(ctx.n, bridgeSessionId, targetId, ctx.cols.get(), ctx.rows.get());
            // ── guardrail #1 swap ordering: new started above → swap + bump → close old ──
            TmuxBridgeService.Bridge old = ctx.bridgeRef.getAndSet(fresh); // swap
            ctx.generation.incrementAndGet(); // bump → the pump picks up `fresh`
            // UC-74 — point the shutdown-teardown registry at the fresh bridge
            // (same stream id) so the backstop tracks the live one, not the
            // about-to-be-closed old bridge.
            StreamBridgeRegistry br = this.bridgeRegistry;
            if (br != null) {
                br.register(ctx.streamId, fresh);
            }
            if (old != null) {
                old.close(); // close old LAST (unblocks the pump's now-stale read)
            }
            ctx.selectedTarget.set(targetId == null ? TARGET_MAIN : targetId);
            fresh.resize(ctx.cols.get(), ctx.rows.get());
            emit(ctx.outbound, session, new StreamServerMessage.TargetSelected(targetId));
        } catch (Exception e) {
            LOG.warn("re-bridge to target {} failed: {}", targetId, e.toString());
            emit(
                    ctx.outbound,
                    session,
                    new StreamServerMessage.ServerError(
                            "rebridge_failed", "Target switch failed", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** Serialize a server frame and enqueue it on the shared outbound sink. */
    private void emit(Sinks.Many<WebSocketMessage> outbound, WebSocketSession session, StreamServerMessage msg) {
        outbound.tryEmitNext(session.textMessage(new String(controlSvc.serialize(msg), StandardCharsets.UTF_8)));
    }

    private String serverErrorFrame(String code, String title, String detail) {
        return new String(
                controlSvc.serialize(new StreamServerMessage.ServerError(code, title, detail == null ? "" : detail)),
                StandardCharsets.UTF_8);
    }

    /**
     * Single long-lived PTY reader. Reads from the <em>current</em> bridge
     * ({@code ctx.bridgeRef}); on a mid-stream re-bridge the generation token
     * advances and this loop picks up the swapped-in bridge instead of treating
     * the old bridge's EOF as teardown. The outbound sink is completed ONLY on
     * true teardown (the loop exit, in the finally) — never on a swap.
     */
    private void pump(OutputRingBuffer ring, WebSocketSession session, StreamCtx ctx) {
        byte[] buf = new byte[8192];
        StreamRegistryService registry = facade.streamRegistry();
        int myGen = ctx.generation.get();
        TmuxBridgeService.Bridge bridge = ctx.bridgeRef.get();
        try {
            while (true) {
                if (ctx.generation.get() != myGen) {
                    // A re-bridge swapped the bridge in; switch to it.
                    myGen = ctx.generation.get();
                    bridge = ctx.bridgeRef.get();
                }
                if (bridge == null) {
                    break; // true teardown
                }
                int rd;
                try {
                    rd = bridge.readStdout(buf);
                } catch (IOException io) {
                    if (ctx.generation.get() != myGen) {
                        continue; // old bridge closed by a swap — pick up the new one
                    }
                    LOG.info("PTY reader done: {}", io.toString());
                    break;
                }
                if (rd < 0) {
                    if (ctx.generation.get() != myGen) {
                        continue; // old bridge EOF due to a swap — continue on the new one
                    }
                    break; // true EOF → teardown
                }
                if (rd == 0) {
                    continue;
                }
                if (!ring.write(buf, 0, rd)) {
                    ctx.outbound.tryEmitNext(session.textMessage(
                            serverErrorFrame("stream_overflow", "Stream overflow", "output buffer full")));
                    session.close(CloseStatus.TOO_BIG_TO_PROCESS).subscribe();
                    return;
                }
                byte[] drained = ring.drain(maxBinaryBytes);
                if (drained.length > 0) {
                    ctx.outbound.tryEmitNext(session.binaryMessage(bf -> bf.wrap(drained)));
                    registry.touch(ctx.streamId);
                }
            }
        } finally {
            // Reached only on true teardown — never on a bridge swap.
            ctx.outbound.tryEmitComplete();
        }
    }

    /** Per-stream context shared between the incoming pipeline and the pump. */
    private static final class StreamCtx {
        final int n;
        final StreamId streamId;
        final AtomicReference<TmuxBridgeService.Bridge> bridgeRef;
        final AtomicInteger generation;
        final AtomicInteger rebridgeSeq;
        final AtomicInteger cols;
        final AtomicInteger rows;
        final AtomicReference<String> selectedTarget;
        final Sinks.Many<WebSocketMessage> outbound;

        StreamCtx(
                int n,
                StreamId streamId,
                AtomicReference<TmuxBridgeService.Bridge> bridgeRef,
                AtomicInteger generation,
                AtomicInteger rebridgeSeq,
                AtomicInteger cols,
                AtomicInteger rows,
                AtomicReference<String> selectedTarget,
                Sinks.Many<WebSocketMessage> outbound) {
            this.n = n;
            this.streamId = streamId;
            this.bridgeRef = bridgeRef;
            this.generation = generation;
            this.rebridgeSeq = rebridgeSeq;
            this.cols = cols;
            this.rows = rows;
            this.selectedTarget = selectedTarget;
            this.outbound = outbound;
        }
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
