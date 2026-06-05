package com.aisandbox.server.sessionevents.handler;

import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade.SubscribeDecision;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelId;
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

/**
 * UC-32 — reactive WebSocket handler bound to {@code /v1/sessions/events}, the
 * live sessions-list status-push channel. One-way server→client: it sends an
 * initial {@link SessionEventMessage.Snapshot} then a stream of
 * {@link SessionEventMessage.Delta} frames produced by
 * {@code SessionEventWatcher}; inbound frames are ignored.
 *
 * <p>Modelled on {@code com.aisandbox.server.stream.handler.SessionStreamHandler}
 * (identity resolution, {@link ActiveStreamRegistry} attach/detach for the 4401
 * revocation path) but with NO source dependency on the {@code stream} package —
 * the events channel is its own no-cycle slice.
 *
 * <p>mTLS gating (AC2): identity is resolved exactly as the terminal stream does
 * (channel-id lookup against {@link ActiveConnectionRegistry}, with a pre-stashed
 * session attribute as a test seam). A null or {@link ClientIdentity#isAnonymous()
 * anonymous} principal is closed with {@code POLICY_VIOLATION} — the same
 * allowlist the REST + stream endpoints enforce. Because the session is indexed
 * on the shared {@link ActiveStreamRegistry}, a cert revocation closes this feed
 * with code 4401 too, so a revoked client stops receiving pushes (the use case's
 * "mTLS + push channel" pitfall).
 *
 * <p>Ordering invariant (the "no Delta missed in the gap" note): the per-session
 * sink is registered with the {@link SessionEventBroadcaster} <b>before</b> the
 * snapshot is taken, and the outbound flux is {@code concat(snapshot, sink)}. Any
 * delta the watcher broadcasts while the snapshot is being built buffers in the
 * sink and is delivered <i>after</i> the snapshot — never lost — and the client's
 * idempotent keyed apply reconciles the overlap.
 */
public class SessionEventWebSocketHandler implements WebSocketHandler {

    /** Key under which a resolved identity may be pre-stashed on the session (test seam). */
    public static final String IDENTITY_ATTR = "ai-sandbox.client-identity";

    private static final Logger LOG = LoggerFactory.getLogger(SessionEventWebSocketHandler.class);

    private final SessionEventFacade facade;
    private final SessionEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    /** Late-bound (mirrors the stream handler) so the test constructor stays minimal. */
    private volatile ActiveConnectionRegistry connections;

    private volatile ActiveStreamRegistry streamRegistry;

    public SessionEventWebSocketHandler(
            SessionEventFacade facade, SessionEventBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.facade = facade;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    /** Late binding of the TLS-side connection registry (production identity resolution). */
    public void setActiveConnectionRegistry(ActiveConnectionRegistry connections) {
        this.connections = connections;
    }

    /** Late binding of the {@link ActiveStreamRegistry} so revocation can 4401-close this feed. */
    public void setActiveStreamRegistry(ActiveStreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        ClientIdentity identity = resolveIdentity(session);
        if (identity == null || identity.isAnonymous()) {
            LOG.warn(
                    "Closing sessions-events feed: no authenticated client identity for channel {}",
                    channelIdOf(session));
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        SubscribeDecision decision = facade.authorizeSubscribe(identity);
        switch (decision) {
            case ALLOWED -> {
                /* fall through to the subscription below */
            }
            case DRAINING -> {
                return session.close(CloseStatus.GOING_AWAY);
            }
            case CAP_EXCEEDED -> {
                LOG.info("Refusing sessions-events feed for {} — per-client cap reached", identity.displayName());
                return session.close(CloseStatus.SERVICE_OVERLOAD);
            }
        }

        // ── Index for the 4401 revocation path (shared with the terminal stream). ──
        final ActiveStreamRegistry streams = this.streamRegistry;
        final String fingerprint = identity.fingerprintHex();
        if (streams != null) {
            streams.attach(fingerprint, session);
        }

        // ── Register the sink BEFORE taking the snapshot so a concurrent delta
        //    buffers rather than being lost in the gap; outbound is then
        //    concat(snapshot, sink) so the snapshot always lands first. ──
        Sinks.Many<SessionEventMessage> sink = Sinks.many().unicast().onBackpressureBuffer();
        SessionEventBroadcaster.Subscriber subscriber = new SessionEventBroadcaster.Subscriber(fingerprint, sink);
        broadcaster.register(subscriber);

        SessionEventMessage.Snapshot snapshot = facade.snapshot();

        Flux<WebSocketMessage> outbound = Flux.concat(Mono.just(snapshot), sink.asFlux())
                .map(msg -> session.textMessage(serialize(msg)));

        // Drain inbound frames so the socket's receive side is read (and any
        // client chatter is discarded) without affecting the outbound feed.
        Mono<Void> inbound = session.receive().then();

        return Mono.when(session.send(outbound), inbound).doFinally(sig -> {
            broadcaster.unregister(subscriber);
            sink.tryEmitComplete();
            if (streams != null) {
                streams.detach(fingerprint, session);
            }
        });
    }

    private String serialize(SessionEventMessage msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            LOG.warn("sessionevents frame serialization failed: {}", jpe.getMessage());
            return "{\"type\":\"delta\",\"upserts\":[],\"removed\":[]}";
        }
    }

    /**
     * Resolve the authenticated {@link ClientIdentity}: a pre-stashed session
     * attribute first (test seam), then a Netty channel-id lookup against
     * {@link ActiveConnectionRegistry} (the production path). Mirrors the
     * terminal stream handler.
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

    private static ChannelId channelIdOf(WebSocketSession session) {
        if (session instanceof ReactorNettyWebSocketSession rnws) {
            return rnws.getChannelId();
        }
        return null;
    }
}
