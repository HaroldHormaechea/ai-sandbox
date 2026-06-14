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

    /** UC-47 — a running row carrying an explicit conversation name. */
    private static Row rowNamed(int n, String state, String conversationName) {
        return new Row(n, "s" + n, "(idle)", state, 0L, 0, null, conversationName);
    }

    /**
     * UC-47 AC4 — the conversation name updates live over the UC-32 push. When
     * ONLY the {@code conversationName} changes between two ticks (identical n /
     * label / title / state / streams), {@link Row} record-equality still sees a
     * difference, so the watcher emits exactly one coalesced {@link Delta}
     * carrying the row with the NEW name — a scanning user sees the current name
     * without a manual refresh.
     */
    @Test
    void a_conversation_name_change_alone_emits_one_delta_with_the_new_name() {
        subscribers(1);

        // Tick 1 baselines [1: running, name="Initial prompt"].
        snapshot(rowNamed(1, "running", "Initial prompt"));
        watcher.tick();

        // Tick 2 — same row in every field EXCEPT the conversation name.
        snapshot(rowNamed(1, "running", "Refactor the SessionRow"));
        watcher.tick();

        ArgumentCaptor<SessionEventMessage> captor = ArgumentCaptor.forClass(SessionEventMessage.class);
        verify(broadcaster, times(1)).broadcast(captor.capture());
        Delta delta = (Delta) captor.getValue();
        assertThat(delta.upserts()).containsExactly(rowNamed(1, "running", "Refactor the SessionRow"));
        assertThat(delta.upserts().get(0).conversationName()).isEqualTo("Refactor the SessionRow");
        assertThat(delta.removed()).isEmpty();
    }

    /** UC-48 — a running row carrying an explicit working flag (name omitted). */
    private static Row rowWorking(int n, String state, boolean working) {
        return new Row(n, "s" + n, "(idle)", state, 0L, 0, null, null, working);
    }

    /**
     * UC-48 AC4 — the working flag updates live over the UC-32 push with NO
     * watcher edit. When ONLY {@code working} flips between two ticks (identical
     * n / label / title / state / streams / name), {@link Row} record-equality
     * still sees a difference, so the watcher emits exactly one coalesced
     * {@link Delta} carrying the row with the NEW working value — the spinner
     * turns on/off without a manual refresh.
     */
    @Test
    void a_working_flag_change_alone_emits_one_delta_with_the_new_value() {
        subscribers(1);

        // Tick 1 baselines [1: running, working=false].
        snapshot(rowWorking(1, "running", false));
        watcher.tick();

        // Tick 2 — same row in every field EXCEPT the working flag (idle → working).
        snapshot(rowWorking(1, "running", true));
        watcher.tick();

        ArgumentCaptor<SessionEventMessage> captor = ArgumentCaptor.forClass(SessionEventMessage.class);
        verify(broadcaster, times(1)).broadcast(captor.capture());
        Delta delta = (Delta) captor.getValue();
        assertThat(delta.upserts()).containsExactly(rowWorking(1, "running", true));
        assertThat(delta.upserts().get(0).working()).isTrue();
        assertThat(delta.removed()).isEmpty();

        // Tick 3 — the working flag is unchanged → no spurious churn from the field.
        snapshot(rowWorking(1, "running", true));
        watcher.tick();
        verify(broadcaster, times(1)).broadcast(any(SessionEventMessage.class));
    }

    /**
     * UC-47 AC4 — a name appearing for the first time (null → a value) is itself
     * a change that pushes a delta; and a tick where the name is unchanged emits
     * nothing (no spurious churn from the new field).
     */
    @Test
    void name_appearing_pushes_a_delta_and_an_unchanged_name_is_silent() {
        subscribers(1);

        snapshot(rowNamed(1, "running", null)); // baseline: no name yet
        watcher.tick();

        snapshot(rowNamed(1, "running", "Now named")); // name appeared → delta
        watcher.tick();

        snapshot(rowNamed(1, "running", "Now named")); // identical → silent
        watcher.tick();

        ArgumentCaptor<SessionEventMessage> captor = ArgumentCaptor.forClass(SessionEventMessage.class);
        verify(broadcaster, times(1)).broadcast(captor.capture());
        assertThat(((Delta) captor.getValue()).upserts()).containsExactly(rowNamed(1, "running", "Now named"));
    }

    /** UC-69 — a running, pending row carrying an explicit first-question body. */
    private static Row rowPendingText(int n, String body) {
        return new Row(n, "s" + n, "(idle)", "running", 0L, 0, null, "Pick a database", false, true, "claude", body);
    }

    /**
     * UC-69 AC3 — the first-question text (notification body) updates live over the
     * UC-32 push with NO watcher edit. When ONLY {@code pendingQuestionText} changes
     * between two ticks (every other field identical), {@link Row} record-equality
     * still sees a difference, so the watcher emits exactly one coalesced
     * {@link Delta} carrying the row with the NEW body — a backgrounded client's
     * notification body updates without a manual refresh. An unchanged body tick is
     * silent (no spurious churn from the new field).
     */
    @Test
    void a_pending_question_text_change_alone_emits_one_delta_with_the_new_body() {
        subscribers(1);

        // Tick 1 baselines [1: pending, body="First question?"].
        snapshot(rowPendingText(1, "First question?"));
        watcher.tick();

        // Tick 2 — same row in every field EXCEPT the first-question body.
        snapshot(rowPendingText(1, "A different question?"));
        watcher.tick();

        ArgumentCaptor<SessionEventMessage> captor = ArgumentCaptor.forClass(SessionEventMessage.class);
        verify(broadcaster, times(1)).broadcast(captor.capture());
        Delta delta = (Delta) captor.getValue();
        assertThat(delta.upserts()).containsExactly(rowPendingText(1, "A different question?"));
        assertThat(delta.upserts().get(0).pendingQuestionText()).isEqualTo("A different question?");
        assertThat(delta.removed()).isEmpty();

        // Tick 3 — the body is unchanged → no spurious churn from the field.
        snapshot(rowPendingText(1, "A different question?"));
        watcher.tick();
        verify(broadcaster, times(1)).broadcast(any(SessionEventMessage.class));
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
