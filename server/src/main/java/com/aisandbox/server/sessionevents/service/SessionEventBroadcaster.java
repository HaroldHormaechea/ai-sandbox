package com.aisandbox.server.sessionevents.service;

import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

/**
 * UC-32 — infrastructure sink registry for the {@code /v1/sessions/events}
 * channel. This is the events-channel analogue of
 * {@code com.aisandbox.server.identity.ActiveStreamRegistry} (which indexes
 * terminal-stream sessions): a process-scoped fan-out point that the
 * {@code SessionEventWatcher} (producer) and the
 * {@code SessionEventWebSocketHandler} (per-connection registrar) both talk to
 * <b>directly</b>. It is deliberately NOT wrapped in a facade — it holds no
 * use-case logic, owns no transaction, and performs no domain reads; it is a
 * pure infra sink, exactly like {@code ActiveStreamRegistry}.
 *
 * <p>Each subscriber is a per-connection {@link Sinks.Many} the handler drains
 * onto its WebSocket. {@link #broadcast(SessionEventMessage)} emits one frame to
 * every live sink; back-pressured / terminated sinks simply drop the frame
 * (the per-connection initial {@code Snapshot} + idempotent keyed apply on the
 * client self-heal any miss). The fingerprint is retained per subscriber only
 * so the facade can enforce a light per-client subscription cap.
 */
@Service
public class SessionEventBroadcaster {

    private static final Logger LOG = LoggerFactory.getLogger(SessionEventBroadcaster.class);

    /**
     * One live subscriber: the client fingerprint (for the per-client cap) and
     * the unicast sink the handler drains onto its WebSocket.
     */
    public record Subscriber(String fingerprintHex, Sinks.Many<SessionEventMessage> sink) {}

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    /** Register a freshly-subscribed connection's sink. */
    public void register(Subscriber subscriber) {
        if (subscriber == null) {
            return;
        }
        subscribers.add(subscriber);
    }

    /** Remove a subscriber — called from the handler's {@code doFinally}. */
    public void unregister(Subscriber subscriber) {
        if (subscriber == null) {
            return;
        }
        subscribers.remove(subscriber);
    }

    /** Total live subscribers — the watcher gates its enumeration on this being &gt; 0. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /** Live subscriptions for one client fingerprint — input to the facade's per-client cap. */
    public int countFor(String fingerprintHex) {
        if (fingerprintHex == null || fingerprintHex.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Subscriber s : subscribers) {
            if (fingerprintHex.equals(s.fingerprintHex())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Emit one frame to every live subscriber. A sink that has already
     * terminated (its WebSocket closed between snapshots) silently drops the
     * frame — {@link Sinks.Many#tryEmitNext} returns a failure result we don't
     * act on, because the handler's {@code doFinally} is the authoritative
     * unregister path.
     */
    public void broadcast(SessionEventMessage message) {
        if (message == null) {
            return;
        }
        for (Subscriber s : subscribers) {
            s.sink().tryEmitNext(message);
        }
    }
}
