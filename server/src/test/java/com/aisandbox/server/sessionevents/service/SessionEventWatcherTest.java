package com.aisandbox.server.sessionevents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Delta;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Row;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Snapshot;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC-32 — the scheduled reconcile-and-diff watcher that turns "the server
 * observed a state change" into one coalesced {@link Delta} for every live
 * subscriber. Drives {@link SessionEventWatcher#tick()} directly with a mocked
 * {@link SessionEventFacade} (snapshot source) and {@link SessionEventBroadcaster}
 * (subscriber gate + fan-out), so each tick's diff/gating decision is asserted
 * in isolation.
 *
 * <p>Coverage:
 * <ul>
 *   <li>AC1 — a status change on an already-baselined session emits exactly ONE
 *       coalesced delta carrying the new row;</li>
 *   <li>AC3 — a brand-new session is an upsert; a vanished session is a removal;</li>
 *   <li>thundering-reconcile pitfall — a zero-subscriber tick enumerates nothing
 *       and broadcasts nothing;</li>
 *   <li>resubscribe correctness — the first tick after (re)gaining subscribers
 *       only baselines (no spurious delta), and dropping to zero subscribers
 *       clears the baseline;</li>
 *   <li>enumeration-outage resilience — an empty snapshot (the facade's
 *       log-and-skip degradation) never throws into the scheduler thread.</li>
 * </ul>
 */
class SessionEventWatcherTest {

    private static Row row(int n, String state) {
        return new Row(n, "s" + n, "(idle)", state, 0L, 0, null);
    }

    private final SessionEventFacade facade = mock(SessionEventFacade.class);
    private final SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    private final SessionEventWatcher watcher = new SessionEventWatcher(facade, broadcaster);

    private void subscribers(int n) {
        when(broadcaster.subscriberCount()).thenReturn(n);
    }

    private void snapshot(Row... rows) {
        when(facade.snapshot()).thenReturn(new Snapshot(List.of(rows)));
    }

    @Test
    void zero_subscribers_does_not_enumerate_or_broadcast() {
        subscribers(0);

        watcher.tick();

        verify(facade, never()).snapshot();
        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void first_tick_with_subscribers_only_baselines_no_delta() {
        subscribers(1);
        snapshot(row(1, "running"));

        watcher.tick();

        // Baseline established from the handler-sent Snapshot; no redundant delta.
        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void status_change_emits_exactly_one_coalesced_delta_with_the_new_row() {
        subscribers(1);

        // Tick 1 baselines [1: running].
        snapshot(row(1, "running"));
        watcher.tick();

        // Tick 2 sees [1: stopped] — a single upsert, no removals.
        snapshot(row(1, "stopped"));
        watcher.tick();

        ArgumentCaptor<SessionEventMessage> captor = ArgumentCaptor.forClass(SessionEventMessage.class);
        verify(broadcaster, times(1)).broadcast(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(Delta.class);
        Delta delta = (Delta) captor.getValue();
        assertThat(delta.upserts()).containsExactly(row(1, "stopped"));
        assertThat(delta.removed()).isEmpty();
    }

    @Test
    void unchanged_tick_broadcasts_nothing() {
        subscribers(1);
        snapshot(row(1, "running"));
        watcher.tick(); // baseline
        watcher.tick(); // identical → no diff

        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void new_session_is_an_upsert_and_vanished_session_is_a_removal() {
        subscribers(1);

        snapshot(row(1, "running"));
        watcher.tick(); // baseline [1]

        // [1] gone, [2] appeared.
        snapshot(row(2, "starting"));
        watcher.tick();

        ArgumentCaptor<SessionEventMessage> captor = ArgumentCaptor.forClass(SessionEventMessage.class);
        verify(broadcaster, times(1)).broadcast(captor.capture());
        Delta delta = (Delta) captor.getValue();
        assertThat(delta.upserts()).containsExactly(row(2, "starting"));
        assertThat(delta.removed()).containsExactly(1);
    }

    @Test
    void dropping_to_zero_subscribers_clears_baseline_so_resubscribe_does_not_spuriously_delta() {
        // Baseline [1: running] while subscribed.
        subscribers(1);
        snapshot(row(1, "running"));
        watcher.tick();

        // Subscribers drop to zero — baseline must reset (no enumeration).
        subscribers(0);
        watcher.tick();
        verify(facade, times(1)).snapshot(); // still only the first enumeration

        // New subscriber arrives; same data. Because the baseline was cleared
        // this tick only re-baselines — it must NOT emit a delta even though the
        // pre-drop baseline was identical.
        subscribers(1);
        snapshot(row(1, "running"));
        watcher.tick();

        verify(broadcaster, never()).broadcast(any());
    }

    @Test
    void empty_snapshot_from_enumeration_outage_never_throws() {
        subscribers(1);
        when(facade.snapshot()).thenReturn(new Snapshot(List.of()));

        assertThatCode(() -> {
                    watcher.tick(); // baseline empty
                    watcher.tick(); // still empty
                })
                .doesNotThrowAnyException();
        verify(broadcaster, never()).broadcast(any());
    }
}
