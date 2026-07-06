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
    @JsonSubTypes.Type(value = ConversationClientMessage.AnswerBatch.class, name = "answer-batch"),
    @JsonSubTypes.Type(value = ConversationClientMessage.SelectTarget.class, name = "select-target"),
    @JsonSubTypes.Type(value = ConversationClientMessage.Interrupt.class, name = "interrupt"),
    @JsonSubTypes.Type(value = ConversationClientMessage.EnumerateTargets.class, name = "enumerate-targets"),
    @JsonSubTypes.Type(value = ConversationClientMessage.FetchDetail.class, name = "fetch-detail"),
    @JsonSubTypes.Type(value = ConversationClientMessage.LoadOlder.class, name = "load-older"),
    @JsonSubTypes.Type(value = ConversationClientMessage.ResyncPending.class, name = "resync-pending"),
    @JsonSubTypes.Type(value = ConversationClientMessage.Close.class, name = "close")
})
public sealed interface ConversationClientMessage
        permits ConversationClientMessage.ComposerInput,
                ConversationClientMessage.Answer,
                ConversationClientMessage.AnswerBatch,
                ConversationClientMessage.SelectTarget,
                ConversationClientMessage.Interrupt,
                ConversationClientMessage.EnumerateTargets,
                ConversationClientMessage.FetchDetail,
                ConversationClientMessage.LoadOlder,
                ConversationClientMessage.ResyncPending,
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

    /**
     * UC-43 — a batched answer to a <b>multi-question</b> {@code AskUserQuestion}
     * (N&gt;1). {@code questionUuid} echoes the pending question's {@code toolUseId}
     * (or {@code uuid}), exactly like {@link Answer}. {@code answers} carries one
     * {@link AnswerItem} per question in the batch; the server sorts them by
     * {@code questionIndex} and injects them as a SINGLE scheduled keystroke
     * sequence so the whole tabbed sheet is resolved in one tool turn (matching
     * the live TUI wizard, where each question is its own tab and the cached
     * {@code AskUserQuestion} is only evicted once the entire form is submitted).
     *
     * <p>The single-question case continues to use {@link Answer}; only N&gt;1
     * uses this frame. See {@code server/CONVERSATION_PROTOCOL.md} for the wire
     * shape and the verified keystroke model.
     */
    record AnswerBatch(String questionUuid, List<AnswerItem> answers) implements ConversationClientMessage {}

    /**
     * One question's answer within an {@link AnswerBatch} (UC-43).
     * {@code questionIndex} is the 0-based position within the
     * {@code AskUserQuestion}'s {@code questions[]}; {@code selections} are the
     * chosen option indices for that question; {@code freeText} is the
     * always-present "Other" value for that question (empty/null when unused).
     */
    record AnswerItem(int questionIndex, List<Integer> selections, String freeText) {}

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

    /**
     * UC-79 (AC2) — request the next OLDER page of the selected target's transcript,
     * sent when the user scrolls up toward the top of the loaded window. No payload:
     * the server owns the per-connection oldest-line cursor (seeded from the
     * {@code backfill-start} window start and advanced as pages load), so it knows
     * exactly which slice {@code [cursor-pageSize, cursor)} to fetch next. The server
     * replies with a {@link ConversationServerMessage.PageStart}, the older
     * transcript-derived frames, then a {@link ConversationServerMessage.PageEnd}
     * (with {@code atStart=true} once the beginning of the transcript is reached, so
     * the client stops paging — AC4). Like {@code fetch-detail} it is a server-local
     * read; it is NOT injected into the tmux session.
     */
    record LoadOlder() implements ConversationClientMessage {}

    /**
     * UC-97 — ask the server to re-derive the CURRENT pane pending-state and re-emit it,
     * sent by the client on a WARM (re-)attach. The streaming re-emit path no-ops on a warm
     * socket ({@code ConversationController.attach} returns early when the connect job is
     * still active) and the helper's once-per-key guard won't re-send a still-blocked prompt,
     * so a pending sheet the client cleared on a transient (racing {@code pending-clear} /
     * {@code turn-end}) while the ask is STILL live never re-populates until a fresh
     * connection ("exit and re-enter to see the question"). This frame closes that gap: the
     * server re-derives the pane (not the transcript, which is blind to a live ask — UC-49/
     * UC-50) and re-emits a {@link ConversationServerMessage.PendingPrompt} /
     * {@link ConversationServerMessage.PendingClear} regardless of tail state. Like
     * {@code fetch-detail}/{@code load-older} it is a server-local read; NOT injected into the
     * tmux session. No payload — it always targets the connection's currently-selected target.
     */
    record ResyncPending() implements ConversationClientMessage {}

    /** Client-initiated clean close. */
    record Close(String reason) implements ConversationClientMessage {}
}
