package com.aisandbox.server.stream.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * UC-37 — sealed discriminated union for <b>client→server</b> JSON text frames
 * on the {@code /v1/sessions/{n}/conversation} WebSocket. Distinct
 * {@code @JsonTypeInfo} namespace from {@link ConversationServerMessage} (the
 * server→client frames) and {@link ControlMessage} (the binary stream's
 * client→server frames).
 *
 * <p>Input has no structured channel into an interactive {@code claude} session
 * (RND §11) — every client frame here is translated server-side into either a
 * tmux {@code send-keys} into the same live session (composer input, answer,
 * interrupt) or a server-local action (target select, enumerate, close). The
 * keystroke mapping is centralized + version-pinned in
 * {@code InputInjectionService}.
 *
 * <p>Wire shapes are documented in {@code server/CONVERSATION_PROTOCOL.md}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ConversationClientMessage.ComposerInput.class, name = "composer-input"),
    @JsonSubTypes.Type(value = ConversationClientMessage.Answer.class, name = "answer"),
    @JsonSubTypes.Type(value = ConversationClientMessage.SelectTarget.class, name = "select-target"),
    @JsonSubTypes.Type(value = ConversationClientMessage.Interrupt.class, name = "interrupt"),
    @JsonSubTypes.Type(value = ConversationClientMessage.EnumerateTargets.class, name = "enumerate-targets"),
    @JsonSubTypes.Type(value = ConversationClientMessage.FetchDetail.class, name = "fetch-detail"),
    @JsonSubTypes.Type(value = ConversationClientMessage.Close.class, name = "close")
})
public sealed interface ConversationClientMessage
        permits ConversationClientMessage.ComposerInput,
                ConversationClientMessage.Answer,
                ConversationClientMessage.SelectTarget,
                ConversationClientMessage.Interrupt,
                ConversationClientMessage.EnumerateTargets,
                ConversationClientMessage.FetchDetail,
                ConversationClientMessage.Close {

    /**
     * Submitted composer text (AC8/AC9). Server injects it into the selected
     * target's tmux session via {@code send-keys} (literal text + submit);
     * embedded newlines are delivered using the session's multiline convention.
     */
    record ComposerInput(String text) implements ConversationClientMessage {}

    /**
     * A structured answer to an {@code AskUserQuestion} / {@code ExitPlanMode}
     * sheet (AC11/AC13). {@code questionUuid} echoes the {@link
     * ConversationServerMessage.Question#toolUseId()} (or {@code uuid}) of the
     * pending question. {@code questionIndex} selects which question within the
     * {@code questions[]} (0 for the common single-question case).
     * {@code selections} are the chosen option indices (one for single-select,
     * many for {@code multiSelect}); {@code freeText} carries the always-present
     * "Other" free-text value (empty/null when not used). The server translates
     * the selection into the session's selection keystrokes.
     */
    record Answer(String questionUuid, int questionIndex, List<Integer> selections, String freeText)
            implements ConversationClientMessage {}

    /** Switch the conversation to a different target (AC17), echoing a prior {@code targets} id. */
    record SelectTarget(String targetId) implements ConversationClientMessage {}

    /** Interrupt the current turn (ESC) — {@code send-keys Escape} into the session. */
    record Interrupt() implements ConversationClientMessage {}

    /** Ask the server to (re)enumerate the conversation targets (AC16/AC18). No payload. */
    record EnumerateTargets() implements ConversationClientMessage {}

    /**
     * UC-41 (AC5) — request the full, untruncated input + result for a single
     * tool call, sent when the user taps a collapsed tool bubble. {@code toolUseId}
     * keys the tool call (the merge key on the client); {@code uuid} is the
     * transcript message id of the originating {@code tool_use} line, carried so
     * the server-side fetch can scope its transcript re-read. The server replies
     * with a {@link ConversationServerMessage.ToolDetail} frame (with
     * {@code available=false} on a miss — AC9). It is NOT injected into the tmux
     * session; it is a server-local read.
     */
    record FetchDetail(String toolUseId, String uuid) implements ConversationClientMessage {}

    /** Client-initiated clean close. */
    record Close(String reason) implements ConversationClientMessage {}
}
