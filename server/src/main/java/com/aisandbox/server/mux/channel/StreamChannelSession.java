package com.aisandbox.server.mux.channel;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mux.dto.MuxChannel;
import com.aisandbox.server.mux.service.FrameSink;
import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.OutputRingBuffer;
import com.aisandbox.server.stream.service.StreamBridgeRegistry;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

/**
 * UC-100 — the {@code stream} channel session for one {@code (stream, n)}
 * subscription. The PTY bridge + generation-token re-bridge + single long-lived
 * stdout pump are lifted verbatim from the legacy
 * {@code com.aisandbox.server.stream.handler.SessionStreamHandler}; only the
 * transport is swapped: stdout goes out as compact binary envelopes via
 * {@link FrameSink#sendBinary(byte[])} (chunked + interleaved by the fair
 * writer, AC7) and control replies via {@link FrameSink#send(Object)}.
 *
 * <p>Cap accounting is unchanged: the handler runs {@link StreamFacade#authorizeOpen}
 * at subscribe-time, then this session registers its own {@code ActiveStream}
 * via {@link StreamFacade#openStream} (so {@code countFor(fingerprint)} counts
 * stream subscriptions = today's per-client cap) and unregisters via
 * {@link StreamFacade#closeStream} on teardown.
 */
public final class StreamChannelSession implements MuxChannelSession {

    /** Id of the always-present main-session target (AC#10 parity). */
    public static final String TARGET_MAIN = "main";

    private static final Logger LOG = LoggerFactory.getLogger(StreamChannelSession.class);

    private final int n;
    private final ClientIdentity identity;
    private final StreamFacade facade;
    private final StreamControlMessageService controlSvc;
    private final FrameSink sink;
    private final ChannelHost host;
    private final StreamBridgeRegistry bridgeRegistry; // nullable
    private final int outputRingBytes;
    private final int maxBinaryBytes;
    private final int maxTextBytes;
    private final Object sessionForRegistry;

    private final AtomicReference<TmuxBridgeService.Bridge> bridgeRef = new AtomicReference<>();
    private final AtomicReference<StreamId> idRef = new AtomicReference<>();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicInteger rebridgeSeq = new AtomicInteger(0);
    private final AtomicInteger cols = new AtomicInteger(80);
    private final AtomicInteger rows = new AtomicInteger(24);
    private final AtomicReference<String> selectedTarget = new AtomicReference<>(TARGET_MAIN);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread reader;

    public StreamChannelSession(
            int n,
            ClientIdentity identity,
            StreamFacade facade,
            StreamControlMessageService controlSvc,
            FrameSink sink,
            ChannelHost host,
            StreamBridgeRegistry bridgeRegistry,
            Object sessionForRegistry,
            int outputRingBytes,
            int maxBinaryBytes,
            int maxTextBytes) {
        this.n = n;
        this.identity = identity;
        this.facade = facade;
        this.controlSvc = controlSvc;
        this.sink = sink;
        this.host = host;
        this.bridgeRegistry = bridgeRegistry;
        this.sessionForRegistry = sessionForRegistry;
        this.outputRingBytes = outputRingBytes;
        this.maxBinaryBytes = maxBinaryBytes;
        this.maxTextBytes = maxTextBytes;
    }

    @Override
    public void start() {
        // Cap accounting: register the ActiveStream for this subscription (per-client cap parity).
        final OutputRingBuffer ring = new OutputRingBuffer(outputRingBytes);
        Schedulers.boundedElastic().schedule(() -> {
            StreamId streamId;
            try {
                streamId = facade.openStream(n, identity, (org.springframework.web.reactive.socket.WebSocketSession)
                        sessionForRegistry);
            } catch (Exception e) {
                LOG.warn("stream open failed for n={}: {}", n, e.toString());
                sink.send(new StreamServerMessage.ServerError(
                        "open_failed", "Stream open failed", e.getMessage() == null ? "" : e.getMessage()));
                host.requestChannelClose(MuxChannel.STREAM, n, "open_failed");
                return;
            }
            idRef.set(streamId);
            try {
                TmuxBridgeService.Bridge bridge = facade.tmux().start(n, streamId.value(), cols.get(), rows.get());
                bridgeRef.set(bridge);
                if (bridgeRegistry != null) {
                    bridgeRegistry.register(streamId, bridge);
                }
            } catch (IOException io) {
                LOG.warn("tmux bridge failed for stream {}: {}", streamId.value(), io.toString());
                sink.send(new StreamServerMessage.ServerError(
                        "bridge_failed", "Bridge failed", io.getMessage() == null ? "" : io.getMessage()));
                host.requestChannelClose(MuxChannel.STREAM, n, "bridge_failed");
                return;
            }
            Thread r = new Thread(() -> pump(ring));
            r.setDaemon(true);
            r.setName("ai-sandbox-mux-pty-out-" + streamId.value());
            this.reader = r;
            r.start();
        });
    }

    /** Inbound binary frame → PTY stdin. */
    public void onBinary(byte[] stdin) {
        TmuxBridgeService.Bridge bridge = bridgeRef.get();
        if (bridge == null) {
            return;
        }
        if (stdin.length > maxBinaryBytes) {
            // Oversized frame: mirror legacy TOO_BIG behaviour by closing just this channel.
            host.requestChannelClose(MuxChannel.STREAM, n, "frame_too_big");
            return;
        }
        try {
            bridge.writeStdin(stdin);
        } catch (IOException io) {
            host.requestChannelClose(MuxChannel.STREAM, n, "stdin_failed");
            return;
        }
        StreamId id = idRef.get();
        if (id != null) {
            facade.streamRegistry().touch(id);
        }
    }

    /** Inbound text control frame (length guard applied by the handler against maxTextBytes). */
    public void onControl(ControlMessage cm) {
        applyControl(cm);
    }

    private void applyControl(ControlMessage cm) {
        StreamId id = idRef.get();
        if (id != null) {
            facade.streamRegistry().touch(id);
        }
        switch (cm) {
            case ControlMessage.Resize r -> {
                cols.set(r.cols());
                rows.set(r.rows());
                TmuxBridgeService.Bridge bridge = bridgeRef.get();
                if (bridge != null) {
                    bridge.resize(r.cols(), r.rows());
                }
            }
            case ControlMessage.MouseControl m -> {
                TmuxBridgeService.Bridge bridge = bridgeRef.get();
                if (bridge != null) {
                    try {
                        bridge.writeStdin(controlSvc.toXtermSgr(m));
                    } catch (IOException io) {
                        host.requestChannelClose(MuxChannel.STREAM, n, "stdin_failed");
                    }
                }
            }
            case ControlMessage.CloseControl c -> host.requestChannelClose(
                    MuxChannel.STREAM, n, c.reason() == null ? "client-close" : c.reason());
            case ControlMessage.ErrorMessage e -> {
                /* server does not act on client-emitted error frames */
            }
            case ControlMessage.EnumerateTargets et -> Schedulers.boundedElastic()
                    .schedule(() -> {
                        List<StreamServerMessage.TargetInfo> targets = facade.enumerateTargets(n);
                        sink.send(new StreamServerMessage.Targets(targets, selectedTarget.get()));
                    });
            case ControlMessage.SelectTarget st -> Schedulers.boundedElastic()
                    .schedule(() -> rebridge(st.targetId()));
        }
    }

    /**
     * Re-bridge mid-stream, honouring the swap-ordering invariant: start new
     * bridge → swap bridgeRef + bump generation → THEN close old (so the pump
     * picks up the fresh bridge instead of treating the old EOF as teardown).
     */
    private void rebridge(String targetId) {
        StreamId id = idRef.get();
        if (id == null) {
            return;
        }
        try {
            String bridgeSessionId = id.value() + "-g" + rebridgeSeq.incrementAndGet();
            TmuxBridgeService.Bridge fresh = facade.rebridge(n, bridgeSessionId, targetId, cols.get(), rows.get());
            TmuxBridgeService.Bridge old = bridgeRef.getAndSet(fresh);
            generation.incrementAndGet();
            if (bridgeRegistry != null) {
                bridgeRegistry.register(id, fresh);
            }
            if (old != null) {
                old.close();
            }
            selectedTarget.set(targetId == null ? TARGET_MAIN : targetId);
            fresh.resize(cols.get(), rows.get());
            sink.send(new StreamServerMessage.TargetSelected(targetId));
        } catch (Exception e) {
            LOG.warn("mux re-bridge to target {} failed: {}", targetId, e.toString());
            sink.send(new StreamServerMessage.ServerError(
                    "rebridge_failed", "Target switch failed", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private void pump(OutputRingBuffer ring) {
        byte[] buf = new byte[8192];
        int myGen = generation.get();
        TmuxBridgeService.Bridge bridge = bridgeRef.get();
        try {
            while (!stopped.get()) {
                if (generation.get() != myGen) {
                    myGen = generation.get();
                    bridge = bridgeRef.get();
                }
                if (bridge == null) {
                    break;
                }
                int rd;
                try {
                    rd = bridge.readStdout(buf);
                } catch (IOException io) {
                    if (generation.get() != myGen) {
                        continue;
                    }
                    LOG.info("mux PTY reader done: {}", io.toString());
                    break;
                }
                if (rd < 0) {
                    if (generation.get() != myGen) {
                        continue;
                    }
                    break; // true EOF → teardown
                }
                if (rd == 0) {
                    continue;
                }
                if (!ring.write(buf, 0, rd)) {
                    sink.send(new StreamServerMessage.ServerError(
                            "stream_overflow", "Stream overflow", "output buffer full"));
                    host.requestChannelClose(MuxChannel.STREAM, n, "overflow");
                    return;
                }
                byte[] drained = ring.drain(maxBinaryBytes);
                if (drained.length > 0) {
                    sink.sendBinary(drained);
                    StreamId id = idRef.get();
                    if (id != null) {
                        facade.streamRegistry().touch(id);
                    }
                }
            }
        } finally {
            if (!stopped.get()) {
                // True EOF (not a close()-driven stop): tear down just this channel.
                host.requestChannelClose(MuxChannel.STREAM, n, "eof");
            }
        }
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        TmuxBridgeService.Bridge b = bridgeRef.get();
        if (b != null) {
            b.close();
        }
        StreamId id = idRef.get();
        if (id != null) {
            if (bridgeRegistry != null) {
                bridgeRegistry.unregister(id);
            }
            facade.closeStream(id, 1000, "unsubscribe");
        }
        Thread r = this.reader;
        if (r != null) {
            r.interrupt();
        }
    }
}
