package com.aisandbox.server.stream.service;

/**
 * Bounded byte ring buffer used to stage PTY output before it is shipped
 * as WebSocket binary frames. A {@link #write(byte[], int, int)} that
 * would exceed capacity signals overflow; the caller is expected to emit
 * an ERROR control frame ({@code stream_overflow}) and close the socket
 * with code 1009 (AC30).
 *
 * <p>Thread-safety: a single writer (PTY reader) + a single reader
 * (WebSocket emitter) coexist; internally synchronized.
 */
public final class OutputRingBuffer {

    private final byte[] buf;
    private int head;
    private int tail;
    private int size;

    public OutputRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.buf = new byte[capacity];
    }

    public synchronized boolean write(byte[] data, int off, int len) {
        if (size + len > buf.length) {
            return false;
        }
        int first = Math.min(len, buf.length - tail);
        System.arraycopy(data, off, buf, tail, first);
        int second = len - first;
        if (second > 0) {
            System.arraycopy(data, off + first, buf, 0, second);
        }
        tail = (tail + len) % buf.length;
        size += len;
        return true;
    }

    /**
     * Drain up to {@code max} bytes into a freshly-allocated array.
     * Returns an empty array when nothing is available.
     */
    public synchronized byte[] drain(int max) {
        if (size == 0) {
            return new byte[0];
        }
        int n = Math.min(max, size);
        byte[] out = new byte[n];
        int first = Math.min(n, buf.length - head);
        System.arraycopy(buf, head, out, 0, first);
        int second = n - first;
        if (second > 0) {
            System.arraycopy(buf, 0, out, first, second);
        }
        head = (head + n) % buf.length;
        size -= n;
        return out;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return buf.length;
    }
}
