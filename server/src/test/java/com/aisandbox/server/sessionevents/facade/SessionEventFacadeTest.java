package com.aisandbox.server.sessionevents.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Row;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage.Snapshot;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade.SubscribeDecision;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.facade.SessionFacade;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-32 — the use-case boundary for the {@code /v1/sessions/events} channel.
 *
 * <p>Covers:
 * <ul>
 *   <li>AC2 — {@link SessionEventFacade#snapshot()} reads the authoritative list
 *       facade→facade and maps each {@link SessionRecord} field-for-field onto a
 *       wire {@link Row} (state / uptimeSec / activeStreams / startedAt carried);</li>
 *   <li>AC5 / the enumeration-outage pitfall — an {@link IOException} from
 *       {@code SessionFacade.listSessions()} is swallowed: {@code snapshot()}
 *       returns an empty {@link Snapshot} instead of propagating, so neither the
 *       scheduler thread nor a fresh subscribe is killed by an outage;</li>
 *   <li>AC2 / the mTLS-cap + draining pitfalls —
 *       {@link SessionEventFacade#authorizeSubscribe(ClientIdentity)} returns
 *       {@code DRAINING} while draining, {@code CAP_EXCEEDED} at the
 *       per-fingerprint cap, and {@code ALLOWED} otherwise.</li>
 * </ul>
 */
class SessionEventFacadeTest {

    private static ClientIdentity identity(String fingerprint) {
        return new ClientIdentity("alice", fingerprint, BigInteger.ONE);
    }

    private final SessionFacade sessionFacade = mock(SessionFacade.class);
    private final SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);
    private final SessionEventFacade facade = new SessionEventFacade(sessionFacade, broadcaster);

    @Test
    void snapshot_maps_every_session_record_field_onto_a_wire_row() throws IOException {
        Instant started = Instant.parse("2026-06-05T10:15:30Z");
        when(sessionFacade.listSessions())
                .thenReturn(List.of(
                        new SessionRecord(1, "build", "vim", "running", 42L, 2, started),
                        new SessionRecord(2, "", "(idle)", "provisioning", 0L, 0, null)));

        Snapshot snapshot = facade.snapshot();

        assertThat(snapshot.sessions())
                .containsExactly(
                        new Row(1, "build", "vim", "running", 42L, 2, started),
                        new Row(2, "", "(idle)", "provisioning", 0L, 0, null));
    }

    /**
     * UC-47 AC2 — the new {@code conversationName} field flows verbatim from the
     * {@link SessionRecord} onto the wire {@link Row} (the same {@code Row} the
     * UC-32 snapshot/delta push and the REST DTO carry), and a {@code null} name
     * round-trips as {@code null} so the client falls back to the tmux title.
     */
    @Test
    void snapshot_carries_the_conversation_name_field_through_to_the_wire_row() throws IOException {
        Instant started = Instant.parse("2026-06-05T10:15:30Z");
        when(sessionFacade.listSessions())
                .thenReturn(List.of(
                        new SessionRecord(1, "build", "vim", "running", 42L, 2, started, "Refactor the SessionRow"),
                        new SessionRecord(2, "", "(idle)", "running", 0L, 0, started, null)));

        Snapshot snapshot = facade.snapshot();

        assertThat(snapshot.sessions())
                .containsExactly(
                        new Row(1, "build", "vim", "running", 42L, 2, started, "Refactor the SessionRow"),
                        new Row(2, "", "(idle)", "running", 0L, 0, started, null));
        assertThat(snapshot.sessions().get(0).conversationName()).isEqualTo("Refactor the SessionRow");
        assertThat(snapshot.sessions().get(1).conversationName()).isNull();
    }

    /**
     * UC-48 AC4 — the new {@code working} flag flows verbatim from the
     * {@link SessionRecord} onto the wire {@link Row} (the same {@code Row} the
     * UC-32 snapshot/delta push and the REST DTO carry), so a working-state flip
     * reaches the client. The first row is working, the second is idle.
     */
    @Test
    void snapshot_carries_the_working_flag_through_to_the_wire_row() throws IOException {
        Instant started = Instant.parse("2026-06-05T10:15:30Z");
        when(sessionFacade.listSessions())
                .thenReturn(List.of(
                        new SessionRecord(
                                1, "build", "vim", "running", 42L, 2, started, "Refactor the SessionRow", true),
                        new SessionRecord(2, "", "(idle)", "running", 0L, 0, started, null, false)));

        Snapshot snapshot = facade.snapshot();

        assertThat(snapshot.sessions().get(0).working())
                .as("AC4 — working=true flows to the wire row")
                .isTrue();
        assertThat(snapshot.sessions().get(1).working()).isFalse();
        // Whole-row value-equality (the field the UC-32 watcher diffs on).
        assertThat(snapshot.sessions())
                .containsExactly(
                        new Row(1, "build", "vim", "running", 42L, 2, started, "Refactor the SessionRow", true),
                        new Row(2, "", "(idle)", "running", 0L, 0, started, null, false));
    }

    /**
     * UC-49 AC3/AC6 — the new {@code pendingQuestion} flag flows verbatim from the
     * {@link SessionRecord} onto the wire {@link Row} (the same {@code Row} the
     * REST DTO and the UC-32 snapshot/delta push carry), and it participates in the
     * {@code Row}'s value-equality, so a pending-state flip changes the row's hash
     * and the UC-32 watcher emits a Delta automatically (no watcher edit needed).
     */
    @Test
    void snapshot_carries_the_pending_question_flag_through_to_the_wire_row() throws IOException {
        Instant started = Instant.parse("2026-06-05T10:15:30Z");
        when(sessionFacade.listSessions())
                .thenReturn(List.of(
                        new SessionRecord(
                                1, "build", "vim", "running", 42L, 2, started, "Pick a database", false, true),
                        new SessionRecord(2, "", "(idle)", "running", 0L, 0, started, null, false, false)));

        Snapshot snapshot = facade.snapshot();

        assertThat(snapshot.sessions().get(0).pendingQuestion())
                .as("AC3 — pendingQuestion=true flows to the wire row")
                .isTrue();
        assertThat(snapshot.sessions().get(1).pendingQuestion()).isFalse();
        assertThat(snapshot.sessions())
                .containsExactly(
                        new Row(1, "build", "vim", "running", 42L, 2, started, "Pick a database", false, true),
                        new Row(2, "", "(idle)", "running", 0L, 0, started, null, false, false));
    }

    /**
     * UC-49 AC6 — a pending-question flip alone (every other field identical)
     * changes the {@link Row}'s value-equality, which is exactly what the UC-32
     * watcher diffs on to emit a Delta. Proves the live "?" appear/clear path needs
     * no watcher change.
     */
    @Test
    void a_pending_flip_changes_row_value_equality_so_the_watcher_emits_a_delta() {
        Instant started = Instant.parse("2026-06-05T10:15:30Z");
        Row notPending = new Row(1, "build", "vim", "running", 42L, 2, started, "Pick a database", false, false);
        Row pending = new Row(1, "build", "vim", "running", 42L, 2, started, "Pick a database", false, true);

        assertThat(pending)
                .as("AC6 — a pending flip alone makes the row unequal (drives the delta)")
                .isNotEqualTo(notPending);
    }

    @Test
    void snapshot_swallows_ioexception_and_serves_an_empty_snapshot() throws IOException {
        when(sessionFacade.listSessions()).thenThrow(new IOException("docker enumeration down"));

        Snapshot snapshot = facade.snapshot();

        assertThat(snapshot.sessions()).isEmpty();
    }

    @Test
    void snapshot_does_not_propagate_enumeration_failure_into_the_caller() throws IOException {
        when(sessionFacade.listSessions()).thenThrow(new IOException("boom"));
        assertThatCode(facade::snapshot).doesNotThrowAnyException();
    }

    @Test
    void authorizeSubscribe_allows_under_the_cap() {
        String fp = "aa".repeat(32);
        when(broadcaster.countFor(fp)).thenReturn(0);
        assertThat(facade.authorizeSubscribe(identity(fp))).isEqualTo(SubscribeDecision.ALLOWED);
    }

    @Test
    void authorizeSubscribe_rejects_at_the_per_fingerprint_cap() {
        String fp = "aa".repeat(32);
        // MAX_SUBSCRIPTIONS_PER_FINGERPRINT == 4 — at the cap the next is refused.
        when(broadcaster.countFor(fp)).thenReturn(4);
        assertThat(facade.authorizeSubscribe(identity(fp))).isEqualTo(SubscribeDecision.CAP_EXCEEDED);
    }

    @Test
    void authorizeSubscribe_rejects_while_draining_before_consulting_the_cap() {
        String fp = "aa".repeat(32);
        facade.setDraining(true);
        assertThat(facade.isDraining()).isTrue();
        assertThat(facade.authorizeSubscribe(identity(fp))).isEqualTo(SubscribeDecision.DRAINING);
    }
}
