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
