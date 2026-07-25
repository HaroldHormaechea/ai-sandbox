package com.aisandbox.server.mux.handler;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mux.channel.ChannelHost;
import com.aisandbox.server.mux.channel.ConversationChannelSession;
import com.aisandbox.server.mux.channel.EventsChannelSession;
import com.aisandbox.server.mux.channel.MuxChannelSession;
import com.aisandbox.server.mux.channel.StreamChannelSession;
import com.aisandbox.server.mux.dto.Envelope;
import com.aisandbox.server.mux.dto.MuxChannel;
import com.aisandbox.server.mux.dto.MuxControlMessage;
import com.aisandbox.server.mux.service.FrameSink;
import com.aisandbox.server.mux.service.MuxCodec;
import com.aisandbox.server.mux.service.MuxOutboundWriter;
import com.aisandbox.server.mux.service.MuxProtocol;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.StreamBridgeRegistry;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import io.netty.channel.ChannelId;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;
import reactor.core.publisher.Mono;

/**
 * UC-100 — the sole WebSocket entrypoint, bound to {@code /v1/mux} (subprotocol
 * {@code ai-sandbox.mux.v1}). Resolves identity once (anonymous → close 1008),
 * runs the {@code hello}/{@code welcome} version + caps handshake, demultiplexes
 * inbound frames by {@code (channel, sessionId)}, drives the
 * {@code subscribe}/{@code unsubscribe} lifecycle, and merges all outbound
 * traffic through one fair {@link MuxOutboundWriter}.
 *
 * <p>The three legacy handlers' business logic lives in the per-channel
 * {@link MuxChannelSession}s; this handler owns only the transport, handshake,
 * routing, cap gate, and the {@link ActiveStreamRegistry} (4401 revoke) +
 * connection-level keepalive registrations.
 */
public class MultiplexWebSocketHandler implements WebSocketHandler {

    /** Key under which the resolved identity is stored on the session (test seam). */
    public static final String IDENTITY_ATTR = "ai-sandbox.client-identity";

    private static final Logger LOG = LoggerFactory.getLogger(MultiplexWebSocketHandler.class);

    private final StreamFacade streamFacade;
    private final ConversationFacade conversationFacade;
    private final ConversationEventMapper conversationMapper;
    private final SessionEventFacade eventsFacade;
    private final SessionEventBroadcaster broadcaster;
    private final StreamControlMessageService controlSvc;
    private final MuxCodec codec;
    private final MuxProtocol protocol;
    private final ServerProperties props;

    private volatile ActiveConnectionRegistry connections;
    private volatile ActiveStreamRegistry activeStreams;
    private volatile StreamRegistryService streamRegistry;
    private volatile StreamBridgeRegistry bridgeRegistry;

    public MultiplexWebSocketHandler(
            StreamFacade streamFacade,
            ConversationFacade conversationFacade,
            ConversationEventMapper conversationMapper,
            SessionEventFacade eventsFacade,
            SessionEventBroadcaster broadcaster,
            StreamControlMessageService controlSvc,
            MuxCodec codec,
            MuxProtocol protocol,
            ServerProperties props) {
        this.streamFacade = streamFacade;
        this.conversationFacade = conversationFacade;
        this.conversationMapper = conversationMapper;
        this.eventsFacade = eventsFacade;
        this.broadcaster = broadcaster;
        this.controlSvc = controlSvc;
        this.codec = codec;
        this.protocol = protocol;
        this.props = props;
    }

    public void setActiveConnectionRegistry(ActiveConnectionRegistry connections) {
        this.connections = connections;
    }

    public void setActiveStreamRegistry(ActiveStreamRegistry activeStreams) {
        this.activeStreams = activeStreams;
    }

    public void setStreamRegistryService(StreamRegistryService streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    public void setStreamBridgeRegistry(StreamBridgeRegistry bridgeRegistry) {
        this.bridgeRegistry = bridgeRegistry;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(MuxProtocol.SUBPROTOCOL);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        ClientIdentity identity = resolveIdentity(session);
        if (identity == null || identity.isAnonymous()) {
            LOG.warn("Closing /v1/mux: no authenticated client identity for channel {}", channelIdOf(session));
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        session.getAttributes().put(IDENTITY_ATTR, identity);

        MuxOutboundWriter writer =
                new MuxOutboundWriter(session, codec, props.streams().perClientCap() * 256);
        Connection conn = new Connection(session, identity, writer);
        writer.setOnChannelOverflow(ref -> conn.onOverflow(ref));

        final ActiveStreamRegistry streams = this.activeStreams;
        final String fingerprint = identity.fingerprintHex();
        if (streams != null) {
            streams.attach(fingerprint, session);
        }
        final StreamRegistryService connReg = this.streamRegistry;
        if (connReg != null) {
            connReg.registerConnection(session);
        }

        Mono<Void> outbound = session.send(writer.outbound());
        Mono<Void> inbound = session.receive()
                .flatMap(msg -> conn.onInbound(msg))
                .then();

        return Mono.when(inbound, outbound).doFinally(sig -> {
            conn.teardownAll();
            if (streams != null) {
                streams.detach(fingerprint, session);
            }
            if (connReg != null) {
                connReg.unregisterConnection(session);
            }
        });
    }

    // ──────────────────────── per-connection context ────────────────────────

    private final class Connection implements ChannelHost {
        private final WebSocketSession session;
        private final ClientIdentity identity;
        private final MuxOutboundWriter writer;
        private final Map<String, ChannelEntry> channels = new ConcurrentHashMap<>();
        private volatile boolean helloDone = false;

        Connection(WebSocketSession session, ClientIdentity identity, MuxOutboundWriter writer) {
            this.session = session;
            this.identity = identity;
            this.writer = writer;
        }

        Mono<Void> onInbound(WebSocketMessage msg) {
            touchKeepalive();
            try {
                switch (msg.getType()) {
                    case BINARY -> {
                        return onBinary(msg.getPayload().asByteBuffer());
                    }
                    case TEXT -> {
                        return onText(msg.getPayloadAsText());
                    }
                    default -> {
                        return Mono.empty();
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("mux inbound frame error: {}", e.toString());
                return Mono.empty();
            }
        }

        private Mono<Void> onBinary(ByteBuffer bb) {
            MuxCodec.BinaryFrame frame;
            try {
                frame = codec.decodeBinary(bb);
            } catch (IllegalArgumentException iae) {
                LOG.warn("mux: bad binary frame: {}", iae.getMessage());
                return Mono.empty();
            }
            if (frame.channel() == MuxChannel.STREAM) {
                ChannelEntry e = channels.get(key(MuxChannel.STREAM, frame.sessionId()));
                if (e != null && e.session instanceof StreamChannelSession scs) {
                    scs.onBinary(frame.data());
                }
            }
            return Mono.empty();
        }

        private Mono<Void> onText(String text) {
            if (text.length() > props.streams().maxTextFrameBytes()) {
                writer.control(new MuxControlMessage.Error(
                        "frame_too_big", "Frame too big", "text frame exceeds maxTextFrameBytes"));
                return Mono.empty();
            }
            Envelope env = codec.decode(text);
            MuxChannel channel = MuxChannel.fromWire(env.channel());
            if (channel == null) {
                writer.control(new MuxControlMessage.Error("bad_channel", "Unknown channel", String.valueOf(env.channel())));
                return Mono.empty();
            }
            return switch (channel) {
                case CONTROL -> onControl(env);
                case STREAM -> {
                    ChannelEntry e = channels.get(key(MuxChannel.STREAM, env.sessionId()));
                    if (e != null && e.session instanceof StreamChannelSession scs) {
                        scs.onControl(codec.asStreamControl(env.payload()));
                    }
                    yield Mono.empty();
                }
                case CONVERSATION -> {
                    ChannelEntry e = channels.get(key(MuxChannel.CONVERSATION, env.sessionId()));
                    if (e != null && e.session instanceof ConversationChannelSession ccs) {
                        ccs.onConversation(codec.asConversation(env.payload()));
                    }
                    yield Mono.empty();
                }
                case EVENTS -> Mono.empty(); // events is server→client only; ignore inbound
            };
        }

        private Mono<Void> onControl(Envelope env) {
            MuxControlMessage cm = codec.asControl(env.payload());
            return switch (cm) {
                case MuxControlMessage.Hello h -> {
                    if (!protocol.accepts(h.protocol())) {
                        LOG.info("mux hello version mismatch: client={} server={}", h.protocol(), MuxProtocol.VERSION);
                        writer.control(new MuxControlMessage.Error(
                                "upgrade_required",
                                "Upgrade required",
                                "server speaks " + MuxProtocol.VERSION + "; client offered " + h.protocol()));
                        yield session.close(new CloseStatus(
                                MuxProtocol.CLOSE_UPGRADE_REQUIRED, "upgrade_required"));
                    }
                    helloDone = true;
                    writer.control(new MuxControlMessage.Welcome(MuxProtocol.VERSION, protocol.capabilities()));
                    yield Mono.empty();
                }
                case MuxControlMessage.Subscribe s -> {
                    handleSubscribe(s.channel(), s.sessionId());
                    yield Mono.empty();
                }
                case MuxControlMessage.Unsubscribe u -> {
                    handleUnsubscribe(MuxChannel.fromWire(u.channel()), u.sessionId(), true);
                    yield Mono.empty();
                }
                default -> Mono.empty(); // clients never originate the server→client control types
            };
        }

        // ── subscribe / unsubscribe ──

        private void handleSubscribe(String channelWire, Integer sessionId) {
            MuxChannel channel = MuxChannel.fromWire(channelWire);
            if (channel == null || channel == MuxChannel.CONTROL) {
                writer.control(new MuxControlMessage.SubError(
                        channelWire, sessionId, "bad_channel", "Unknown channel", "cannot subscribe to " + channelWire));
                return;
            }
            if (channel.isPerSession() && sessionId == null) {
                writer.control(new MuxControlMessage.SubError(
                        channelWire, null, "bad_request", "Missing sessionId", "per-session channel requires sessionId"));
                return;
            }
            if (!helloDone) {
                writer.control(new MuxControlMessage.SubError(
                        channelWire, sessionId, "no_handshake", "Handshake required", "send hello before subscribe"));
                return;
            }
            String key = key(channel, sessionId);
            // AC6 — idempotent: re-subscribing a live channel just re-acks (dedupe).
            if (channels.containsKey(key) && writer.isOpen(channel, sessionId)) {
                writer.control(new MuxControlMessage.Subscribed(channelWire, sessionId));
                return;
            }

            switch (channel) {
                case STREAM -> {
                    StreamFacade.AuthorizeResult auth = streamFacade.authorizeOpen(sessionId, identity);
                    if (!(auth instanceof StreamFacade.Allowed)) {
                        subError(channel, sessionId, auth);
                        return;
                    }
                    FrameSink sink = writer.openChannel(channel, sessionId);
                    StreamChannelSession cs = new StreamChannelSession(
                            sessionId,
                            identity,
                            streamFacade,
                            controlSvc,
                            sink,
                            this,
                            bridgeRegistry,
                            session,
                            props.streams().outputRingBytes(),
                            props.streams().maxBinaryFrameBytes(),
                            props.streams().maxTextFrameBytes());
                    channels.put(key, new ChannelEntry(cs, sink));
                    writer.control(new MuxControlMessage.Subscribed(channelWire, sessionId));
                    cs.start();
                }
                case CONVERSATION -> {
                    StreamFacade.AuthorizeResult auth = conversationFacade.authorizeOpen(sessionId, identity);
                    if (!(auth instanceof StreamFacade.Allowed)) {
                        subError(channel, sessionId, auth);
                        return;
                    }
                    FrameSink sink = writer.openChannel(channel, sessionId);
                    ConversationChannelSession cs = new ConversationChannelSession(
                            sessionId, identity, conversationFacade, conversationMapper, sink, this);
                    channels.put(key, new ChannelEntry(cs, sink));
                    writer.control(new MuxControlMessage.Subscribed(channelWire, sessionId));
                    cs.start();
                }
                case EVENTS -> {
                    SessionEventFacade.SubscribeDecision decision = eventsFacade.authorizeSubscribe(identity);
                    if (decision != SessionEventFacade.SubscribeDecision.ALLOWED) {
                        String code = decision == SessionEventFacade.SubscribeDecision.DRAINING ? "draining" : "cap_exceeded";
                        writer.control(new MuxControlMessage.SubError(
                                channelWire, sessionId, code, "Events subscribe refused", decision.name().toLowerCase()));
                        return;
                    }
                    FrameSink sink = writer.openChannel(channel, sessionId);
                    EventsChannelSession cs = new EventsChannelSession(identity, eventsFacade, broadcaster, sink);
                    channels.put(key, new ChannelEntry(cs, sink));
                    writer.control(new MuxControlMessage.Subscribed(channelWire, sessionId));
                    cs.start();
                }
                default -> {
                    /* unreachable */
                }
            }
        }

        private void subError(MuxChannel channel, Integer sessionId, StreamFacade.AuthorizeResult auth) {
            String code;
            String detail;
            switch (auth) {
                case StreamFacade.SessionNotFound nf -> {
                    code = "session_not_found";
                    detail = "session " + nf.n() + " not found";
                }
                case StreamFacade.NotRunning nr -> {
                    code = "session_not_running";
                    detail = "session " + nr.n() + " is " + nr.state();
                }
                case StreamFacade.CapExceeded ce -> {
                    code = "stream_cap_exceeded";
                    detail = "stream cap exceeded (" + ce.scope() + ")";
                }
                case StreamFacade.Draining d -> {
                    code = "draining";
                    detail = "server is shutting down";
                }
                case StreamFacade.Allowed a -> {
                    code = "ok";
                    detail = "";
                }
            }
            writer.control(new MuxControlMessage.SubError(channel.wire(), sessionId, code, "Cannot subscribe", detail));
        }

        private void handleUnsubscribe(MuxChannel channel, Integer sessionId, boolean ack) {
            if (channel == null) {
                return;
            }
            ChannelEntry e = channels.remove(key(channel, sessionId));
            if (e != null) {
                e.session.close();
                e.sink.complete(); // flush queued frames, then emit unsubscribed
            } else if (ack) {
                // Idempotent: unsubscribe of an absent channel still acks.
                writer.control(new MuxControlMessage.Unsubscribed(channel.wire(), sessionId));
            }
        }

        // ── ChannelHost: a producer (EOF) or a data-channel close control asked to tear down ──
        @Override
        public void requestChannelClose(MuxChannel channel, Integer sessionId, String reason) {
            LOG.debug("mux channel {}/{} self-close: {}", channel.wire(), sessionId, reason);
            handleUnsubscribe(channel, sessionId, true);
        }

        // ── writer overflow: the channel queue is already removed + sub-error emitted ──
        void onOverflow(MuxOutboundWriter.ChannelRef ref) {
            ChannelEntry e = channels.remove(key(ref.channel(), ref.sessionId()));
            if (e != null) {
                e.session.close();
            }
        }

        void teardownAll() {
            for (String k : channels.keySet()) {
                ChannelEntry e = channels.remove(k);
                if (e != null) {
                    e.session.close();
                }
            }
        }

        private void touchKeepalive() {
            StreamRegistryService reg = streamRegistry;
            if (reg != null) {
                reg.touchConnection(session);
            }
        }
    }

    private record ChannelEntry(MuxChannelSession session, FrameSink sink) {}

    private static String key(MuxChannel channel, Integer sessionId) {
        return channel.isPerSession() ? channel.wire() + ":" + sessionId : channel.wire();
    }

    // ──────────────────────── identity resolution (mirrors legacy handlers) ────────────────────────

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

    private static ChannelId channelIdOf(WebSocketSession session) {
        if (session instanceof ReactorNettyWebSocketSession rnws) {
            return rnws.getChannelId();
        }
        return null;
    }
}
