package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.stream.service.OutputRingBuffer;
import org.junit.jupiter.api.Test;

/**
 * AC30 — per-stream output ring is bounded; overflow returns false so the
 * caller can emit a stream_overflow ERROR frame + close 1009. The ring
 * also wraps around correctly so a long-lived stream doesn't leak memory.
 */
class OutputRingBufferTest {

    @Test
    void rejects_zero_or_negative_capacity() {
        assertThatThrownBy(() -> new OutputRingBuffer(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutputRingBuffer(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void write_then_drain_round_trips() {
        OutputRingBuffer ring = new OutputRingBuffer(32);
        byte[] payload = "hello world".getBytes();
        assertThat(ring.write(payload, 0, payload.length)).isTrue();
        assertThat(ring.size()).isEqualTo(payload.length);

        byte[] out = ring.drain(100);
        assertThat(out).isEqualTo(payload);
        assertThat(ring.size()).isZero();
    }

    @Test
    void overflow_returns_false_and_preserves_existing_payload() {
        OutputRingBuffer ring = new OutputRingBuffer(8);
        byte[] first = new byte[] {1, 2, 3, 4};
        byte[] tooBig = new byte[] {5, 6, 7, 8, 9, 10};

        assertThat(ring.write(first, 0, first.length)).isTrue();
        assertThat(ring.write(tooBig, 0, tooBig.length)).isFalse();
        // Existing buffer content must survive an overflow attempt.
        assertThat(ring.size()).isEqualTo(4);
        byte[] out = ring.drain(100);
        assertThat(out).containsExactly(1, 2, 3, 4);
    }

    @Test
    void drain_with_max_smaller_than_size_returns_partial() {
        OutputRingBuffer ring = new OutputRingBuffer(16);
        byte[] payload = "abcdefgh".getBytes();
        ring.write(payload, 0, payload.length);

        byte[] first = ring.drain(3);
        byte[] second = ring.drain(100);
        assertThat(first).containsExactly('a', 'b', 'c');
        assertThat(second).containsExactly('d', 'e', 'f', 'g', 'h');
    }

    @Test
    void wraps_around_capacity_boundary() {
        OutputRingBuffer ring = new OutputRingBuffer(6);
        // Fill, drain half, write a chunk that crosses the wrap.
        ring.write("ABCDEF".getBytes(), 0, 6);
        ring.drain(4); // leaves "EF" at head=4
        boolean ok = ring.write("123".getBytes(), 0, 3); // wraps to index 0..1
        assertThat(ok).isTrue();
        byte[] all = ring.drain(100);
        assertThat(all).containsExactly('E', 'F', '1', '2', '3');
    }

    @Test
    void drain_when_empty_returns_empty_array() {
        OutputRingBuffer ring = new OutputRingBuffer(4);
        assertThat(ring.drain(100)).isEmpty();
    }

    @Test
    void capacity_is_reported() {
        OutputRingBuffer ring = new OutputRingBuffer(256 * 1024);
        assertThat(ring.capacity()).isEqualTo(256 * 1024);
    }
}
