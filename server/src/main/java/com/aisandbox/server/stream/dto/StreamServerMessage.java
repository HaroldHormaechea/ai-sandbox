package com.aisandbox.server.stream.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * UC-21 — sealed discriminated union for <b>server→client</b> JSON text frames
 * on the {@code /v1/sessions/{n}/stream} WebSocket.
 *
 * <p>This is a distinct hierarchy from {@link ControlMessage} (the client→server
 * frames) with its <b>own</b> {@code @JsonTypeInfo}/{@code @JsonSubTypes}
 * discriminator, so the two never share a type-name namespace. Serialization
 * goes through a dedicated
 * {@code StreamControlMessageService#serialize(StreamServerMessage)} path —
 * {@code serialize(ControlMessage)} does not cover it (challenger guardrail #3).
 *
 * <p>Per the approved proposal (decision b), these stream DTOs live alongside
 * {@link ControlMessage} in {@code stream/dto}; the API-DTO-separation rule of
 * {@code profile-java-server-architecture} targets REST {@code ..api} request /
 * response bodies, not WebSocket stream frames.
 *
 * <p>Wire shapes are documented in {@code server/STREAM_PROTOCOL.md}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = StreamServerMessage.Targets.class, name = "targets"),
    @JsonSubTypes.Type(value = StreamServerMessage.TargetSelected.class, name = "target-selected"),
    @JsonSubTypes.Type(value = StreamServerMessage.ServerError.class, name = "error")
})
public sealed interface StreamServerMessage
        permits StreamServerMessage.Targets,
                StreamServerMessage.TargetSelected,
                StreamServerMessage.ServerError {

    /**
     * The enumerated stream targets for a session — the always-present main
     * session plus any agent-team panes. {@code selectedId} is the target the
     * stream is currently bridged to.
     */
    record Targets(List<TargetInfo> targets, String selectedId) implements StreamServerMessage {}

    /** Acknowledges a successful mid-stream target switch. */
    record TargetSelected(String targetId) implements StreamServerMessage {}

    /**
     * Server-emitted error frame (RFC 9457-ish shape). Migrates the previously
     * hand-built {@code {"type":"error",...}} JSON onto the typed hierarchy.
     */
    record ServerError(String code, String title, String detail) implements StreamServerMessage {}

    /**
     * A single selectable target. {@code id} round-trips back as
     * {@link ControlMessage.SelectTarget#targetId()}.
     *
     * <ul>
     *   <li>{@code kind} — {@code main} | {@code swarm} | {@code orchestrator}.</li>
     *   <li>{@code agent*} / {@code teamName} — agent-team metadata (null for the
     *       main target or when the pane's argv could not be read).</li>
     *   <li>{@code socket} / {@code session} / {@code window} / {@code pane} — the
     *       tmux coordinates the server uses to re-bridge (null fields default to
     *       the main session's default socket).</li>
     * </ul>
     */
    record TargetInfo(
            String id,
            String kind,
            String title,
            String agentName,
            String agentType,
            String agentColor,
            String teamName,
            String socket,
            String session,
            String window,
            String pane) {}
}
