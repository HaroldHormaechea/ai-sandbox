package com.aisandbox.server.sessionevents.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;

/**
 * UC-32 — sealed discriminated union for <b>server→client</b> JSON text frames
 * on the {@code /v1/sessions/events} WebSocket (the live sessions-list status
 * push channel).
 *
 * <p>This hierarchy is the events-channel analogue of
 * {@code com.aisandbox.server.stream.dto.StreamServerMessage} (the per-session
 * terminal stream's server frames): it carries its <b>own</b>
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes} discriminator (property
 * {@code "type"}), so its type-name namespace is disjoint from every other
 * WebSocket frame hierarchy. The two never share a serializer either — these
 * frames go out through a plain Spring-managed {@code ObjectMapper} in
 * {@code SessionEventWebSocketHandler}, never through
 * {@code StreamControlMessageService}. There is deliberately NO source
 * dependency on the {@code stream} package.
 *
 * <p>The events channel is one-way server→client; the client sends nothing on
 * it (inbound frames are ignored by the handler). Two frame shapes:
 *
 * <ul>
 *   <li>{@link Snapshot} — the authoritative full list, sent once at subscribe
 *       (and re-sent on every reconnect). The client applies it as a full
 *       resync (AC5).</li>
 *   <li>{@link Delta} — a coalesced incremental update: {@code upserts} (rows
 *       to insert-or-replace, keyed by {@code n}) and {@code removed} (the
 *       {@code n}s that vanished). The client applies it idempotently keyed by
 *       {@code n} (AC3, AC4).</li>
 * </ul>
 *
 * <p>{@link Row} mirrors {@code ApiDtos.SessionSummary} field-for-field so the
 * push payload updates an existing list row without any extra mapping on the
 * client (AC2). It is a distinct type from the REST API DTO on purpose: this is
 * a WebSocket-frame DTO, not a REST request/response body, so the
 * API-DTO-separation rule of {@code profile-java-server-architecture} (which
 * targets {@code ..api} bodies) is satisfied by keeping it here in the
 * sessionevents domain.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = SessionEventMessage.Snapshot.class, name = "snapshot"),
    @JsonSubTypes.Type(value = SessionEventMessage.Delta.class, name = "delta")
})
public sealed interface SessionEventMessage permits SessionEventMessage.Snapshot, SessionEventMessage.Delta {

    /**
     * The authoritative full session list. Sent once when a client subscribes
     * and again on every reconnect; the client treats it as a full resync that
     * replaces its working set (AC5).
     */
    record Snapshot(List<Row> sessions) implements SessionEventMessage {}

    /**
     * A coalesced incremental update. {@code upserts} are rows to
     * insert-or-replace keyed by {@code n}; {@code removed} are the session
     * numbers that disappeared from the server. Both lists may be empty, but
     * the watcher never broadcasts a {@code Delta} where both are empty.
     */
    record Delta(List<Row> upserts, List<Integer> removed) implements SessionEventMessage {}

    /**
     * One session row — mirrors {@code ApiDtos.SessionSummary} field-for-field
     * (and therefore the Android {@code SessionSummary} wire DTO). {@code state}
     * is the live field that drives the list's status pill + filter-chip counts;
     * {@code uptimeSec} / {@code activeStreams} / {@code startedAt} are carried
     * for parity with REST (today the server's enumerator hardcodes them to 0 —
     * see the use case's scoped-out note).
     *
     * @param n             session number (the stable key the client upserts/removes by)
     * @param label         free-form label, may be empty
     * @param tmuxTitle     tmux window title, or {@code (idle)} / {@code (unavailable)}
     * @param state         running | starting | provisioning | terminating | stopped
     * @param uptimeSec     seconds since container start, or 0 when unknown
     * @param activeStreams currently-attached terminal-stream count
     * @param startedAt     container start time, or null when unknown
     * @param conversationName UC-47 — the Claude conversation name for the row's
     *     main pane, or {@code null} when none is known. Carried so a name change
     *     flips the {@link Row} record's value-equality and the UC-32 watcher emits
     *     a Delta (AC4); the client prefers it over {@code tmuxTitle}.
     * @param working UC-48 — whether Claude is actively working in the row's main
     *     pane. Carried so a working-state flip flips the {@link Row} record's
     *     value-equality and the UC-32 watcher emits a Delta automatically (no
     *     watcher edit needed — AC4); the client animates the working spinner while
     *     true (double-gated on {@code state==running}).
     * @param pendingQuestion UC-49 — whether the row's main pane is showing an
     *     {@code AskUserQuestion} awaiting an answer. Carried so a pending-state flip
     *     flips the {@link Row} record's value-equality and the UC-32 watcher emits a
     *     Delta automatically (no watcher edit needed — AC6); the client shows a "?"
     *     badge and suppresses the spinner while true (double-gated on running).
     */
    record Row(
            int n,
            String label,
            String tmuxTitle,
            String state,
            long uptimeSec,
            int activeStreams,
            Instant startedAt,
            String conversationName,
            boolean working,
            boolean pendingQuestion) {

        /**
         * UC-49 back-compat 9-arg constructor for call sites built after UC-48 but
         * before this use case appended {@code pendingQuestion}. Delegates with
         * {@code false}.
         */
        public Row(
                int n,
                String label,
                String tmuxTitle,
                String state,
                long uptimeSec,
                int activeStreams,
                Instant startedAt,
                String conversationName,
                boolean working) {
            this(n, label, tmuxTitle, state, uptimeSec, activeStreams, startedAt, conversationName, working, false);
        }

        /**
         * UC-48 back-compat 8-arg constructor for call sites built after UC-47 but
         * before that use case appended {@code working}. Delegates with {@code false}
         * (and {@code pendingQuestion=false}).
         */
        public Row(
                int n,
                String label,
                String tmuxTitle,
                String state,
                long uptimeSec,
                int activeStreams,
                Instant startedAt,
                String conversationName) {
            this(n, label, tmuxTitle, state, uptimeSec, activeStreams, startedAt, conversationName, false);
        }

        /**
         * UC-47 back-compat 7-arg constructor for call sites built before this use
         * case appended {@code conversationName}. Delegates with {@code null} name
         * (and {@code working=false}).
         */
        public Row(
                int n,
                String label,
                String tmuxTitle,
                String state,
                long uptimeSec,
                int activeStreams,
                Instant startedAt) {
            this(n, label, tmuxTitle, state, uptimeSec, activeStreams, startedAt, null);
        }
    }
}
