package com.aisandbox.server.mux.service;

import com.aisandbox.server.mux.dto.MuxChannel;
import com.aisandbox.server.mux.dto.MuxControlMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * UC-100 — the single fair outbound writer for one mux connection. Owns
 * per-channel bounded FIFO queues drained round-robin onto the one
 * {@code session.send(...)}. Replaces the legacy per-handler {@code Sinks.Many}
 * + {@code outboundLock}: enqueue is the single serialization point, so the
 * {@code FAIL_TERMINATED}/{@code FAIL_NON_SERIALIZED} hazard is structurally
 * removed.
 *
 * <p><b>Fairness (AC7).</b> The drain rotates across channels one frame at a
 * time, so a large {@code stream} burst — chunked into {@code ≤ STREAM_CHUNK_BYTES}
 * envelopes — interleaves with conversation/events frames instead of starving
 * them. Strict FIFO is preserved within each channel.
 *
 * <p><b>Backpressure.</b> The drain only emits while the downstream (the socket)
 * has outstanding demand ({@link FluxSink#requestedFromDownstream()}); frames
 * that can't be sent stay in their per-channel queue. A queue that overflows its
 * bound closes <i>that channel</i> with a {@code sub-error} (never the socket),
 * so server-side buffering stays bounded.
 *
 * <p><b>Sentinel.</b> {@link FrameSink#complete()} enqueues a completion marker;
 * the drain flushes every pre-sentinel frame, then removes the channel and emits
 * the {@code unsubscribed} ack. The merged {@code session.send} flux sees
 * {@code onComplete} only at connection close, never per-channel EOF.
 */
public class MuxOutboundWriter {

    private static final Logger LOG = LoggerFactory.getLogger(MuxOutboundWriter.class);

    private final WebSocketSession session;
    private final MuxCodec codec;
    private final int perChannelQueueCap;

    private final Map<String, ChannelQueue> queues = new ConcurrentHashMap<>();
    private final List<ChannelQueue> order = new ArrayList<>();
    private final Object orderLock = new Object();
    private int cursor = 0;

    private final AtomicInteger wip = new AtomicInteger(0);
    private volatile FluxSink<WebSocketMessage> emitter;

    /**
     * Invoked when a channel is force-closed by overflow (not a clean
     * unsubscribe) so the handler can tear down that channel's session. Set once
     * by the handler.
     */
    private volatile Consumer<ChannelRef> onChannelOverflow = ref -> {};

    private final ChannelQueue control;

    public MuxOutboundWriter(WebSocketSession session, MuxCodec codec, int perChannelQueueCap) {
        this.session = session;
        this.codec = codec;
        this.perChannelQueueCap = Math.max(16, perChannelQueueCap);
        this.control = new ChannelQueue(MuxChannel.CONTROL, null);
        register(control);
    }

    /** A channel identity for the overflow callback. */
    public record ChannelRef(MuxChannel channel, Integer sessionId) {}

    public void setOnChannelOverflow(Consumer<ChannelRef> cb) {
        this.onChannelOverflow = cb == null ? ref -> {} : cb;
    }

    /** The merged outbound flux handed to {@code session.send(...)}. Subscribe once. */
    public Flux<WebSocketMessage> outbound() {
        return Flux.<WebSocketMessage>create(
                        sink -> {
                            this.emitter = sink;
                            sink.onRequest(n -> drain());
                            drain();
                        },
                        FluxSink.OverflowStrategy.ERROR)
                .doFinally(sig -> this.emitter = null);
    }

    // ──────────────────────── control-channel frames (handler-driven) ────────────────────────

    /** Enqueue a {@code control}-channel frame (welcome / subscribed / unsubscribed / sub-error / error). */
    public void control(MuxControlMessage msg) {
        JsonNode payload = codec.tree(msg);
        long seq = control.seq.getAndIncrement();
        offer(control, session.textMessage(codec.encode(MuxChannel.CONTROL, null, seq, payload)));
    }

    // ──────────────────────── channel lifecycle ────────────────────────

    /**
     * Open a per-channel queue and return the {@link FrameSink} the channel
     * session writes to. Idempotent for a live channel — re-subscribing to an
     * already-open channel returns the existing sink (AC6 dedupe).
     */
    public FrameSink openChannel(MuxChannel channel, Integer sessionId) {
        String key = key(channel, sessionId);
        ChannelQueue q = queues.get(key);
        if (q == null || q.removed) {
            q = new ChannelQueue(channel, sessionId);
            register(q);
        }
        return new Sink(q);
    }

    /** {@code true} when a live (non-removed) queue exists for this channel. */
    public boolean isOpen(MuxChannel channel, Integer sessionId) {
        ChannelQueue q = queues.get(key(channel, sessionId));
        return q != null && !q.removed;
    }

    private void register(ChannelQueue q) {
        queues.put(key(q.channel, q.sessionId), q);
        synchronized (orderLock) {
            order.add(q);
        }
    }

    private static String key(MuxChannel channel, Integer sessionId) {
        return channel.isPerSession() ? channel.wire() + ":" + sessionId : channel.wire();
    }

    // ──────────────────────── enqueue + overflow ────────────────────────

    private void offer(ChannelQueue q, WebSocketMessage msg) {
        boolean overflowed = false;
        synchronized (q.deque) {
            if (q.removed) {
                return;
            }
            if (q.deque.size() >= perChannelQueueCap) {
                overflowed = true;
            } else {
                q.deque.add(msg);
            }
        }
        if (overflowed) {
            overflow(q);
            return;
        }
        drain();
    }

    private void overflow(ChannelQueue q) {
        LOG.warn("mux channel {} overflowed (>{} queued frames); closing that channel", q.debug(), perChannelQueueCap);
        synchronized (q.deque) {
            q.deque.clear();
            q.removed = true;
        }
        removeFromOrder(q);
        // Mirror the legacy "stream_overflow" — refuse just this channel, never the socket.
        control(new MuxControlMessage.SubError(
                q.channel.wire(),
                q.sessionId,
                "stream_overflow",
                "Channel overflow",
                "server outbound buffer full for this channel"));
        try {
            onChannelOverflow.accept(new ChannelRef(q.channel, q.sessionId));
        } catch (RuntimeException e) {
            LOG.warn("mux overflow callback failed for {}: {}", q.debug(), e.toString());
        }
    }

    private void removeFromOrder(ChannelQueue q) {
        queues.remove(key(q.channel, q.sessionId), q);
        synchronized (orderLock) {
            int idx = order.indexOf(q);
            if (idx >= 0) {
                order.remove(idx);
                if (cursor > idx) {
                    cursor--;
                }
                if (order.isEmpty()) {
                    cursor = 0;
                } else {
                    cursor %= order.size();
                }
            }
        }
    }

    // ──────────────────────── round-robin drain ────────────────────────

    private void drain() {
        FluxSink<WebSocketMessage> e = this.emitter;
        if (e == null) {
            return;
        }
        if (wip.getAndIncrement() != 0) {
            return;
        }
        do {
            finalizeCompletedEmptyChannels();
            while (e.requestedFromDownstream() > 0) {
                WebSocketMessage m = pollRoundRobin();
                if (m == null) {
                    break;
                }
                e.next(m);
            }
        } while (wip.decrementAndGet() != 0);
    }

    private WebSocketMessage pollRoundRobin() {
        synchronized (orderLock) {
            int size = order.size();
            for (int i = 0; i < size; i++) {
                int idx = (cursor + i) % size;
                ChannelQueue q = order.get(idx);
                WebSocketMessage m;
                synchronized (q.deque) {
                    m = q.deque.poll();
                }
                if (m != null) {
                    cursor = (idx + 1) % size;
                    return m;
                }
            }
            return null;
        }
    }

    private void finalizeCompletedEmptyChannels() {
        List<ChannelQueue> done = new ArrayList<>();
        synchronized (orderLock) {
            for (ChannelQueue q : order) {
                if (q == control) {
                    continue;
                }
                boolean empty;
                synchronized (q.deque) {
                    empty = q.completing && q.deque.isEmpty() && !q.removed;
                    if (empty) {
                        q.removed = true;
                    }
                }
                if (empty) {
                    done.add(q);
                }
            }
        }
        for (ChannelQueue q : done) {
            removeFromOrder(q);
            control(new MuxControlMessage.Unsubscribed(q.channel.wire(), q.sessionId));
        }
    }

    // ──────────────────────── the FrameSink handed to channel sessions ────────────────────────

    private final class Sink implements FrameSink {
        private final ChannelQueue q;

        private Sink(ChannelQueue q) {
            this.q = q;
        }

        @Override
        public void send(Object serverModel) {
            JsonNode payload = toTree(serverModel);
            if (payload == null) {
                LOG.warn(
                        "mux writer: unroutable server model {}",
                        serverModel.getClass().getName());
                return;
            }
            long seq = q.seq.getAndIncrement();
            offer(q, session.textMessage(codec.encode(q.channel, q.sessionId, seq, payload)));
        }

        @Override
        public void sendBinary(byte[] data) {
            if (data == null || data.length == 0) {
                return;
            }
            int off = 0;
            while (off < data.length) {
                int len = Math.min(MuxProtocol.STREAM_CHUNK_BYTES, data.length - off);
                long seq = q.seq.getAndIncrement();
                ByteBuffer framed = codec.encodeBinary(q.sessionId == null ? 0 : q.sessionId, seq, data, off, len);
                byte[] arr = new byte[framed.remaining()];
                framed.get(arr);
                offer(q, session.binaryMessage(bf -> bf.wrap(arr)));
                off += len;
            }
        }

        @Override
        public void complete() {
            synchronized (q.deque) {
                q.completing = true;
            }
            drain();
        }
    }

    private JsonNode toTree(Object model) {
        if (model instanceof StreamServerMessage m) {
            return codec.tree(m);
        }
        if (model instanceof ConversationServerMessage m) {
            return codec.tree(m);
        }
        if (model instanceof SessionEventMessage m) {
            return codec.tree(m);
        }
        if (model instanceof MuxControlMessage m) {
            return codec.tree(m);
        }
        return null;
    }

    private static final class ChannelQueue {
        final MuxChannel channel;
        final Integer sessionId;
        final ArrayDeque<WebSocketMessage> deque = new ArrayDeque<>();
        final AtomicLong seq = new AtomicLong(0);
        volatile boolean completing = false;
        volatile boolean removed = false;

        ChannelQueue(MuxChannel channel, Integer sessionId) {
            this.channel = channel;
            this.sessionId = sessionId;
        }

        String debug() {
            return channel.wire() + (sessionId == null ? "" : ("/" + sessionId));
        }
    }
}
