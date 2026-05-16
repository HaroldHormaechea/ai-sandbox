package com.aisandbox.server.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory stand-in for {@code TmuxBridgeService} used by WebSocket
 * integration tests that must run without a Docker daemon (Docker is NOT
 * available in the sandbox). The fake satisfies the same primitives as
 * the production bridge — start, resize, writeStdin, readStdout, close —
 * but routes them through bounded buffers instead of spawning a real
 * {@code docker compose exec ... tmux ...} process.
 *
 * <p>Tests inject this fake via a {@code @TestConfiguration} {@code @Primary}
 * bean (see {@code FakeTmuxBridgeConfiguration} in the integration tests).
 * The fake records every action so assertions can verify framing without
 * relying on tty bytes.
 */
public final class FakeTmuxBridge {

    public record StartCall(int n, String streamId, int cols, int rows) {}

    public record ResizeCall(String streamId, int cols, int rows) {}

    public record WriteCall(String streamId, byte[] data) {}

    public record CloseCall(String streamId) {}

    public final List<StartCall> starts = new CopyOnWriteArrayList<>();
    public final List<ResizeCall> resizes = new CopyOnWriteArrayList<>();
    public final List<WriteCall> writes = new CopyOnWriteArrayList<>();
    public final List<CloseCall> closes = new CopyOnWriteArrayList<>();

    public Bridge start(int n, String streamId, int cols, int rows) {
        starts.add(new StartCall(n, streamId, cols, rows));
        return new Bridge(this, streamId);
    }

    /** Reset the recorded calls — handy between test scenarios. */
    public void clear() {
        starts.clear();
        resizes.clear();
        writes.clear();
        closes.clear();
    }

    /** Bridge handle exposed to the consumer; the WS handler treats this opaquely. */
    public static final class Bridge {
        private final FakeTmuxBridge parent;
        private final String streamId;
        private final List<byte[]> stdinQueue = new ArrayList<>();
        private final List<byte[]> stdoutQueue = new ArrayList<>();
        private boolean alive = true;
        private int cols = 80;
        private int rows = 24;

        Bridge(FakeTmuxBridge parent, String streamId) {
            this.parent = parent;
            this.streamId = streamId;
        }

        public synchronized void resize(int cols, int rows) {
            this.cols = cols;
            this.rows = rows;
            parent.resizes.add(new ResizeCall(streamId, cols, rows));
        }

        public synchronized void writeStdin(byte[] data) {
            stdinQueue.add(data.clone());
            parent.writes.add(new WriteCall(streamId, data.clone()));
        }

        public synchronized int readStdout(byte[] buf) {
            if (stdoutQueue.isEmpty()) {
                return alive ? 0 : -1;
            }
            byte[] head = stdoutQueue.remove(0);
            int n = Math.min(buf.length, head.length);
            System.arraycopy(head, 0, buf, 0, n);
            return n;
        }

        public synchronized boolean isAlive() {
            return alive;
        }

        public synchronized void close() {
            if (alive) {
                alive = false;
                parent.closes.add(new CloseCall(streamId));
            }
        }

        /** Test-side helper: queue synthetic tty bytes to be emitted as the next read. */
        public synchronized void emitStdout(byte[] data) {
            stdoutQueue.add(data.clone());
        }

        public int cols() {
            return cols;
        }

        public int rows() {
            return rows;
        }
    }
}
