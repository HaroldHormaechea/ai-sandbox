package com.aisandbox.server.stream.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed discriminated union for the JSON text-frame protocol carried on
 * the {@code /v1/sessions/{n}/stream} WebSocket. Concrete shapes (parsing
 * + writing) are documented in {@code server/STREAM_PROTOCOL.md}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ControlMessage.Resize.class, name = "resize"),
    @JsonSubTypes.Type(value = ControlMessage.MouseControl.class, name = "mouse"),
    @JsonSubTypes.Type(value = ControlMessage.CloseControl.class, name = "close"),
    @JsonSubTypes.Type(value = ControlMessage.ErrorMessage.class, name = "error"),
    @JsonSubTypes.Type(value = ControlMessage.EnumerateTargets.class, name = "enumerate-targets"),
    @JsonSubTypes.Type(value = ControlMessage.SelectTarget.class, name = "select-target")
})
public sealed interface ControlMessage
        permits ControlMessage.Resize,
                ControlMessage.MouseControl,
                ControlMessage.CloseControl,
                ControlMessage.ErrorMessage,
                ControlMessage.EnumerateTargets,
                ControlMessage.SelectTarget {

    record Resize(int cols, int rows) implements ControlMessage {}

    /**
     * Mouse event. {@code button}: 0..4 (left, middle, right, wheel-up,
     * wheel-down). {@code modifiers}: bitmask, 1=shift, 2=alt, 4=ctrl.
     * Coordinates are 1-indexed tmux cells.
     */
    record MouseControl(int x, int y, int button, int modifiers, String action) implements ControlMessage {}

    /** Client-side requested close, optional reason. */
    record CloseControl(String reason) implements ControlMessage {}

    /** Server-emitted error frame (RFC 9457 shape). */
    record ErrorMessage(String code, String title, String detail) implements ControlMessage {}

    /**
     * UC-21 — client asks the server to enumerate the stream targets
     * available for this session (the main tmux session plus any agent-team
     * panes). The server replies with a {@link StreamServerMessage.Targets}
     * frame. No payload.
     */
    record EnumerateTargets() implements ControlMessage {}

    /**
     * UC-21 — client asks the server to switch this stream to a different
     * target mid-stream (the server re-bridges on the same WebSocket). The
     * server replies with {@link StreamServerMessage.TargetSelected} on
     * success or a {@link StreamServerMessage.ServerError} frame on failure.
     * {@code targetId} is the id from a prior {@code Targets} enumeration.
     */
    record SelectTarget(String targetId) implements ControlMessage {}
}
