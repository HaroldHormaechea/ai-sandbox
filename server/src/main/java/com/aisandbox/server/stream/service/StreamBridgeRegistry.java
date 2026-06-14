package com.aisandbox.server.stream.service;

import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService.Bridge;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * UC-74 — central registry of the live {@link Bridge} (pty4j {@code PtyProcess}
 * + per-client tmux session) backing each open binary stream, keyed by
 * {@link StreamId}.
 *
 * <p>Without this, graceful shutdown had no deterministic handle on the spawned
 * PTY children: closing a WebSocket relies on the handler's reactive
 * {@code doFinally} to fire {@code Bridge.close()} asynchronously, which is racy
 * against the bounded shutdown deadline. {@code GracefulShutdownHandler.stop()}
 * iterates this registry and calls {@link Bridge#close()} on every remaining
 * bridge as a synchronous backstop, destroying the {@code PtyProcess} and killing
 * its per-client tmux session so no lingering handle can keep the JVM alive past
 * the systemd stop budget (AC4).
 *
 * <p>{@link Bridge#close()} is idempotent (guarded by an {@code AtomicBoolean}),
 * so the backstop is safe even when the handler's {@code doFinally} has already
 * closed the same bridge.
 *
 * <p>Lifecycle: the stream handler {@link #register registers} the bridge right
 * after {@code TmuxBridgeService.start(...)} and {@link #unregister unregisters}
 * it in its {@code doFinally}. A mid-stream re-bridge (UC-21) re-registers the
 * fresh bridge under the same {@link StreamId}, replacing the now-closed old one,
 * so the backstop always tracks the live bridge.
 */
@Component
public class StreamBridgeRegistry {

    private final Map<StreamId, Bridge> bridges = new ConcurrentHashMap<>();

    /** Track {@code bridge} as the live bridge for {@code id}. */
    public void register(StreamId id, Bridge bridge) {
        if (id == null || bridge == null) {
            return;
        }
        bridges.put(id, bridge);
    }

    /** Stop tracking the bridge for {@code id} — called on stream teardown. */
    public void unregister(StreamId id) {
        if (id == null) {
            return;
        }
        bridges.remove(id);
    }

    /** Immutable snapshot of the currently-registered bridges. */
    public Collection<Bridge> snapshot() {
        return Map.copyOf(bridges).values();
    }

    /** Number of live bridges currently tracked. */
    public int count() {
        return bridges.size();
    }
}
