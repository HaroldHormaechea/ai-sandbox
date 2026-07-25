package com.aisandbox.server.mux.channel;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mux.service.FrameSink;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * UC-100 — the global {@code events} channel session. Lifted from the legacy
 * {@code com.aisandbox.server.sessionevents.handler.SessionEventWebSocketHandler}:
 * it registers a per-subscriber sink with the {@link SessionEventBroadcaster}
 * <b>before</b> taking the snapshot (so no delta is lost in the gap), sends the
 * {@link SessionEventMessage.Snapshot} first, then forwards every
 * {@link SessionEventMessage.Delta} onto the shared {@link FrameSink}.
 *
 * <p>The subscribe authorization ({@code draining} / per-fingerprint cap) is run
 * by the handler via {@link SessionEventFacade#authorizeSubscribe} before this
 * session is created, mirroring the per-subscribe gate the other channels use.
 */
public final class EventsChannelSession implements MuxChannelSession {

    private static final Logger LOG = LoggerFactory.getLogger(EventsChannelSession.class);

    private final ClientIdentity identity;
    private final SessionEventFacade facade;
    private final SessionEventBroadcaster broadcaster;
    private final FrameSink sink;

    private volatile SessionEventBroadcaster.Subscriber subscriber;
    private volatile Sinks.Many<SessionEventMessage> feed;
    private volatile Disposable subscription;

    public EventsChannelSession(
            ClientIdentity identity,
            SessionEventFacade facade,
            SessionEventBroadcaster broadcaster,
            FrameSink sink) {
        this.identity = identity;
        this.facade = facade;
        this.broadcaster = broadcaster;
        this.sink = sink;
    }

    @Override
    public void start() {
        // Register BEFORE snapshotting so a concurrent delta buffers rather than being lost.
        Sinks.Many<SessionEventMessage> f = Sinks.many().unicast().onBackpressureBuffer();
        this.feed = f;
        SessionEventBroadcaster.Subscriber sub =
                new SessionEventBroadcaster.Subscriber(identity.fingerprintHex(), f);
        this.subscriber = sub;
        broadcaster.register(sub);

        SessionEventMessage.Snapshot snapshot = facade.snapshot();
        sink.send(snapshot);

        // Forward every subsequent delta (and any delta buffered during the gap) onto the shared writer.
        this.subscription = f.asFlux()
                .subscribe(
                        sink::send,
                        t -> LOG.warn("mux events feed errored: {}", t.toString()));
    }

    @Override
    public void close() {
        SessionEventBroadcaster.Subscriber sub = this.subscriber;
        if (sub != null) {
            broadcaster.unregister(sub);
        }
        Sinks.Many<SessionEventMessage> f = this.feed;
        if (f != null) {
            f.tryEmitComplete();
        }
        Disposable d = this.subscription;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }
}
