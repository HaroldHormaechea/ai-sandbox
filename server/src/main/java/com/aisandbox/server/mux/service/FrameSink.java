package com.aisandbox.server.mux.service;

/**
 * UC-100 — the per-{@code (channel, sessionId)} outbound seam a
 * {@link com.aisandbox.server.mux.channel.MuxChannelSession} writes to, instead
 * of a raw {@code Sinks.Many}. The {@link MuxOutboundWriter} implements it:
 * it assigns the per-subscription {@code seq}, wraps the typed model in the
 * envelope, and enqueues onto this channel's bounded FIFO queue for the fair
 * round-robin drain.
 *
 * <p>Strict FIFO within a channel is preserved (single serialization point);
 * fairness across channels is the writer's round-robin drain. A channel session
 * never touches the shared transport sink directly, so the legacy
 * {@code outboundLock} / {@code FAIL_NON_SERIALIZED} hazard is structurally
 * gone.
 */
public interface FrameSink {

    /**
     * Enqueue a typed server model ({@code StreamServerMessage},
     * {@code ConversationServerMessage}, {@code SessionEventMessage}). The writer
     * renders it through its sealed-interface static type so the {@code type}
     * discriminator is written, wraps it in the envelope with the next {@code seq},
     * and offers it to this channel's queue.
     */
    void send(Object serverModel);

    /**
     * Enqueue raw {@code stream}-channel PTY stdout bytes. The writer chunks the
     * payload into {@code ≤ STREAM_CHUNK_BYTES} compact binary envelopes (each
     * with its own {@code seq}) so a large terminal burst yields between chunks
     * and never stalls the other channels (AC7).
     */
    void sendBinary(byte[] data);

    /**
     * Enqueue the {@code ChannelComplete} sentinel: the writer flushes every
     * pre-sentinel frame already queued for this channel, then removes the
     * channel and emits the {@code unsubscribed} control ack. Called on a clean
     * unsubscribe / teardown only.
     */
    void complete();
}
