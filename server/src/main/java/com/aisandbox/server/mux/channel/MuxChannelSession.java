package com.aisandbox.server.mux.channel;

/**
 * UC-100 — per-{@code (channel, sessionId)} logic extracted from the three
 * legacy reactive {@code WebSocketHandler}s. Each implementation
 * ({@link StreamChannelSession}, {@link ConversationChannelSession},
 * {@link EventsChannelSession}) drives one logical channel against a
 * {@link com.aisandbox.server.mux.service.FrameSink} + inbound callbacks,
 * delegating all business logic to the same facades the legacy handlers used
 * (Controller/Job → Facade chain preserved).
 *
 * <p>Inbound frames are delivered by the {@link com.aisandbox.server.mux.handler.MultiplexWebSocketHandler}
 * via the concrete session's typed methods (the handler already knows the
 * channel from the envelope). This interface holds only the lifecycle contract.
 */
public interface MuxChannelSession {

    /**
     * Begin producing (spawn the PTY reader / transcript tail / register with the
     * events broadcaster). Called once, right after the subscribe is authorized
     * and the channel queue is opened.
     */
    void start();

    /**
     * Stop all producers for this channel (kill the pump thread, close the tail,
     * unregister from the broadcaster, drop the bridge). Idempotent. Does NOT
     * emit the {@code unsubscribed} ack — the handler owns that via
     * {@link com.aisandbox.server.mux.service.FrameSink#complete()} after this
     * returns, so the channel's already-queued frames flush first.
     */
    void close();
}
