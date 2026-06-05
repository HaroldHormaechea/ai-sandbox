package com.aisandbox.server.sessionevents.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Row;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster.Subscriber;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/**
 * UC-32 — the infra sink registry behind the {@code /v1/sessions/events} push
 * channel. Covers the contract the {@code SessionEventWatcher} (producer) and
 * the {@code SessionEventWebSocketHandler} (per-connection registrar) rely on:
 *
 * <ul>
 *   <li>register / unregister mutate {@link SessionEventBroadcaster#subscriberCount()},
 *       which the watcher gates enumeration on (AC1 / the thundering-reconcile
 *       pitfall — zero subscribers ⇒ no work);</li>
 *   <li>{@link SessionEventBroadcaster#countFor(String)} counts only the given
 *       fingerprint — the input to the facade's per-client subscription cap
 *       (AC2 / the mTLS-cap pitfall);</li>
 *   <li>{@link SessionEventBroadcaster#broadcast(SessionEventMessage)} fans one
 *       frame out to every live sink (AC1 / AC3 — a single coalesced delta
 *       reaches all subscribers).</li>
 * </ul>
 */
class SessionEventBroadcasterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static Row row(int n, String state) {
        return new Row(n, "label" + n, "(idle)", state, 0L, 0, null);
    }

    private static Subscriber sub(String fingerprint) {
        return new Subscriber(fingerprint, Sinks.many().unicast().onBackpressureBuffer());
    }

    @Test
    void register_increments_and_unregister_decrements_subscriber_count() {
        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        assertThat(broadcaster.subscriberCount()).isZero();

        Subscriber a = sub("aa".repeat(32));
        Subscriber b = sub("bb".repeat(32));
        broadcaster.register(a);
        broadcaster.register(b);
        assertThat(broadcaster.subscriberCount()).isEqualTo(2);

        broadcaster.unregister(a);
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
        broadcaster.unregister(b);
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    void null_register_and_unregister_are_no_ops() {
        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        broadcaster.register(null);
        broadcaster.unregister(null);
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    void countFor_counts_only_the_matching_fingerprint() {
        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        String alice = "aa".repeat(32);
        String bob = "bb".repeat(32);
        broadcaster.register(sub(alice));
        broadcaster.register(sub(alice));
        broadcaster.register(sub(bob));

        assertThat(broadcaster.countFor(alice)).isEqualTo(2);
        assertThat(broadcaster.countFor(bob)).isEqualTo(1);
        assertThat(broadcaster.countFor("cc".repeat(32))).isZero();
    }

    @Test
    void countFor_null_or_empty_fingerprint_is_zero() {
        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        broadcaster.register(sub("aa".repeat(32)));
        assertThat(broadcaster.countFor(null)).isZero();
        assertThat(broadcaster.countFor("")).isZero();
    }

    @Test
    void broadcast_fans_one_frame_out_to_every_live_sink() {
        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        Subscriber a = sub("aa".repeat(32));
        Subscriber b = sub("bb".repeat(32));
        broadcaster.register(a);
        broadcaster.register(b);

        SessionEventMessage delta = new SessionEventMessage.Delta(List.of(row(1, "stopped")), List.of());
        broadcaster.broadcast(delta);

        // The unicast onBackpressureBuffer sinks retain the emitted frame until
        // drained; each subscriber must see the very same coalesced frame.
        assertThat(a.sink().asFlux().blockFirst(TIMEOUT)).isSameAs(delta);
        assertThat(b.sink().asFlux().blockFirst(TIMEOUT)).isSameAs(delta);
    }

    @Test
    void broadcast_to_no_subscribers_and_null_message_are_safe_no_ops() {
        SessionEventBroadcaster broadcaster = new SessionEventBroadcaster();
        // No subscribers — must not throw.
        broadcaster.broadcast(new SessionEventMessage.Delta(List.of(), List.of(1)));

        // Null message is ignored even with a live subscriber.
        Subscriber a = sub("aa".repeat(32));
        broadcaster.register(a);
        broadcaster.broadcast(null);
        // Nothing was emitted; a subsequent real frame is the first the sink sees.
        SessionEventMessage real = new SessionEventMessage.Snapshot(List.of());
        broadcaster.broadcast(real);
        assertThat(a.sink().asFlux().blockFirst(TIMEOUT)).isSameAs(real);
    }
}
