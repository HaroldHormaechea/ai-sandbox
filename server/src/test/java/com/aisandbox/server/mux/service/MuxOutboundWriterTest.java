package com.aisandbox.server.mux.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.mux.FakeMuxSession;
import com.aisandbox.server.mux.dto.MuxChannel;
import com.aisandbox.server.mux.dto.MuxControlMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.web.reactive.socket.WebSocketMessage;
import reactor.core.publisher.BaseSubscriber;

/**
 * UC-100 (AC7 + AC5) — the single fair outbound writer. Pins the load-bearing
 * transport invariants that let one TCP socket carry every channel without
 * starvation or dropped terminals:
 *
 * <ul>
 *   <li><b>Per-channel FIFO</b> — frames leave a channel in enqueue order.</li>
 *   <li><b>Round-robin fairness (AC7)</b> — a large {@code stream} burst does not
 *       starve {@code conversation}/{@code events}; their frames interleave within
 *       a bounded window.</li>
 *   <li><b>Chunking (AC7)</b> — a big binary payload is split into
 *       {@code ≤ STREAM_CHUNK_BYTES} envelopes so a terminal burst yields between
 *       chunks.</li>
 *   <li><b>Flush-before-teardown (AC5)</b> — {@code complete()} flushes every
 *       queued frame, THEN emits {@code unsubscribed} — the trailing-echo
 *       guarantee that kills the UC-96 FAIL_TERMINATED race.</li>
 *   <li><b>Bounded backpressure</b> — a per-channel queue overflow closes only
 *       that channel with a {@code sub-error}, never the socket.</li>
 * </ul>
 */
class MuxOutboundWriterTest {

    private final MuxCodec codec = new MuxCodec();

    /** A deferred-demand collector: nothing drains until {@link #pull} is called. */
    private static final class Collector extends BaseSubscriber<WebSocketMessage> {
        final List<WebSocketMessage> frames = new CopyOnWriteArrayList<>();

        @Override
        protected void hookOnSubscribe(Subscription s) {
            // Intentionally request nothing — the test controls demand so it can
            // enqueue a full working set before any frame drains (deterministic
            // round-robin), exactly the way a slow socket would.
        }

        @Override
        protected void hookOnNext(WebSocketMessage m) {
            frames.add(m);
        }

        void pull(long n) {
            request(n);
        }
    }

    private static List<String> textOf(List<WebSocketMessage> frames) {
        return frames.stream()
                .filter(m -> m.getType() == WebSocketMessage.Type.TEXT)
                .map(WebSocketMessage::getPayloadAsText)
                .toList();
    }

    // ──────────────────────── FIFO within a channel ────────────────────────

    @Test
    void preserves_strict_fifo_within_a_channel() {
        FakeMuxSession session = new FakeMuxSession();
        MuxOutboundWriter writer = new MuxOutboundWriter(session, codec, 256);
        FrameSink conv = writer.openChannel(MuxChannel.CONVERSATION, 7);

        Collector c = new Collector();
        writer.outbound().subscribe(c);

        conv.send(new ConversationServerMessage.ServerError("a", "t", "1"));
        conv.send(new ConversationServerMessage.ServerError("b", "t", "2"));
        conv.send(new ConversationServerMessage.ServerError("c", "t", "3"));
        c.pull(Long.MAX_VALUE);

        List<String> text = textOf(c.frames);
        assertThat(text).hasSize(3);
        assertThat(text.get(0)).contains("\"code\":\"a\"");
        assertThat(text.get(1)).contains("\"code\":\"b\"");
        assertThat(text.get(2)).contains("\"code\":\"c\"");
    }

    // ──────────────────────── AC7 fairness ────────────────────────

    @Test
    void a_large_stream_burst_does_not_starve_the_conversation_channel() {
        FakeMuxSession session = new FakeMuxSession();
        MuxOutboundWriter writer = new MuxOutboundWriter(session, codec, 256);
        FrameSink stream = writer.openChannel(MuxChannel.STREAM, 7);
        FrameSink conv = writer.openChannel(MuxChannel.CONVERSATION, 7);

        Collector c = new Collector();
        writer.outbound().subscribe(c);

        // Enqueue a big stream burst AND a few conversation frames while the
        // socket has no demand — so the round-robin drain sees a full working set.
        for (int i = 0; i < 20; i++) {
            stream.sendBinary(new byte[] {(byte) i});
        }
        conv.send(new ConversationServerMessage.ServerError("c0", "t", ""));
        conv.send(new ConversationServerMessage.ServerError("c1", "t", ""));
        conv.send(new ConversationServerMessage.ServerError("c2", "t", ""));

        c.pull(Long.MAX_VALUE);

        // All 23 frames drain.
        long binary = c.frames.stream().filter(m -> m.getType() == WebSocketMessage.Type.BINARY).count();
        assertThat(binary).isEqualTo(20);
        List<String> conversation = textOf(c.frames);
        assertThat(conversation).hasSize(3);

        // FAIRNESS: with strict global FIFO the 3 conversation frames would land at
        // output positions 20,21,22 (starved behind the whole burst). Round-robin
        // must interleave them near the front — the last one well within a bounded
        // window, NOT behind all 20 stream chunks.
        int lastConvIdx = lastIndexOfType(c.frames, WebSocketMessage.Type.TEXT);
        assertThat(lastConvIdx)
                .as("conversation frames interleave within a bounded window, not starved behind the stream burst")
                .isLessThanOrEqualTo(8);

        // Per-channel FIFO still holds for the interleaved conversation frames.
        assertThat(conversation.get(0)).contains("\"code\":\"c0\"");
        assertThat(conversation.get(1)).contains("\"code\":\"c1\"");
        assertThat(conversation.get(2)).contains("\"code\":\"c2\"");
    }

    @Test
    void large_binary_payload_is_chunked_below_the_cap() {
        FakeMuxSession session = new FakeMuxSession();
        MuxOutboundWriter writer = new MuxOutboundWriter(session, codec, 256);
        FrameSink stream = writer.openChannel(MuxChannel.STREAM, 3);

        Collector c = new Collector();
        writer.outbound().subscribe(c);

        int total = MuxProtocol.STREAM_CHUNK_BYTES + 7232; // spills into a 2nd chunk
        stream.sendBinary(new byte[total]);
        c.pull(Long.MAX_VALUE);

        List<byte[]> chunks = c.frames.stream()
                .filter(m -> m.getType() == WebSocketMessage.Type.BINARY)
                .map(m -> {
                    java.nio.ByteBuffer bb = m.getPayload().asByteBuffer();
                    byte[] a = new byte[bb.remaining()];
                    bb.get(a);
                    return a;
                })
                .toList();

        assertThat(chunks).hasSize(2);
        int sum = 0;
        for (byte[] framed : chunks) {
            MuxCodec.BinaryFrame f = codec.decodeBinary(java.nio.ByteBuffer.wrap(framed));
            assertThat(f.data().length).isLessThanOrEqualTo(MuxProtocol.STREAM_CHUNK_BYTES);
            sum += f.data().length;
        }
        assertThat(sum).as("no PTY bytes lost across the chunk boundary").isEqualTo(total);
    }

    // ──────────────────────── AC5 flush-before-teardown ────────────────────────

    @Test
    void complete_flushes_queued_frames_before_emitting_unsubscribed() {
        FakeMuxSession session = new FakeMuxSession();
        MuxOutboundWriter writer = new MuxOutboundWriter(session, codec, 256);
        FrameSink conv = writer.openChannel(MuxChannel.CONVERSATION, 7);

        Collector c = new Collector();
        writer.outbound().subscribe(c);

        // Trailing echo, then teardown — the exact UC-96 shape.
        conv.send(new ConversationServerMessage.AnswerEcho("uq", 0, List.of(0), ""));
        conv.send(new ConversationServerMessage.AnswerEcho("uq", 1, List.of(1), ""));
        conv.complete();

        c.pull(Long.MAX_VALUE);
        c.pull(1); // one more demand tick lets the drain run its finalize pass

        List<String> text = textOf(c.frames);
        // Both echoes precede the terminal unsubscribed ack (flush-before-teardown).
        int e0 = indexOfContaining(text, "\"questionIndex\":0");
        int e1 = indexOfContaining(text, "\"questionIndex\":1");
        int unsub = indexOfContaining(text, "\"type\":\"unsubscribed\"");
        assertThat(e0).isGreaterThanOrEqualTo(0);
        assertThat(e1).isGreaterThanOrEqualTo(0);
        assertThat(unsub).as("unsubscribed is emitted").isGreaterThanOrEqualTo(0);
        assertThat(e0).as("echo[0] flushed before teardown").isLessThan(unsub);
        assertThat(e1).as("echo[1] flushed before teardown").isLessThan(unsub);
        assertThat(writer.isOpen(MuxChannel.CONVERSATION, 7)).isFalse();
    }

    // ──────────────────────── bounded backpressure ────────────────────────

    @Test
    void channel_overflow_closes_only_that_channel_with_a_sub_error() {
        FakeMuxSession session = new FakeMuxSession();
        // perChannelQueueCap is floored at 16 by the writer.
        MuxOutboundWriter writer = new MuxOutboundWriter(session, codec, 4);
        AtomicReference<MuxOutboundWriter.ChannelRef> overflowed = new AtomicReference<>();
        writer.setOnChannelOverflow(overflowed::set);

        FrameSink conv = writer.openChannel(MuxChannel.CONVERSATION, 9);
        // No subscriber / no demand: frames accumulate until the bound (16) trips.
        for (int i = 0; i < 17; i++) {
            conv.send(new ConversationServerMessage.ServerError("e" + i, "t", ""));
        }

        // The overflow callback fired for exactly this channel …
        assertThat(overflowed.get()).isNotNull();
        assertThat(overflowed.get().channel()).isEqualTo(MuxChannel.CONVERSATION);
        assertThat(overflowed.get().sessionId()).isEqualTo(9);
        // … and the channel is closed (removed), never the socket.
        assertThat(writer.isOpen(MuxChannel.CONVERSATION, 9)).isFalse();

        // The control-channel sub-error is queued; drain it to confirm the taxonomy.
        Collector c = new Collector();
        writer.outbound().subscribe(c);
        c.pull(Long.MAX_VALUE);
        assertThat(textOf(c.frames))
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"stream_overflow\""));
    }

    @Test
    void control_frames_are_emitted() {
        FakeMuxSession session = new FakeMuxSession();
        MuxOutboundWriter writer = new MuxOutboundWriter(session, codec, 256);
        Collector c = new Collector();
        writer.outbound().subscribe(c);

        writer.control(new MuxControlMessage.Welcome("mux.v1", java.util.Map.of()));
        c.pull(Long.MAX_VALUE);

        assertThat(textOf(c.frames)).anyMatch(t -> t.contains("\"type\":\"welcome\""));
    }

    // ── helpers ──

    private static int lastIndexOfType(List<WebSocketMessage> frames, WebSocketMessage.Type type) {
        int idx = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).getType() == type) {
                idx = i;
            }
        }
        return idx;
    }

    private static int indexOfContaining(List<String> text, String needle) {
        for (int i = 0; i < text.size(); i++) {
            if (text.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
