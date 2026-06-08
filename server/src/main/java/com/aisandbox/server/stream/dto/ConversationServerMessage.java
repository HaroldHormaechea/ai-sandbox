package com.aisandbox.server.stream.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * UC-37 — sealed discriminated union for <b>server→client</b> JSON text frames
 * on the {@code /v1/sessions/{n}/conversation} WebSocket (the structured
 * "conversation" mode that runs in parallel to the binary PTY
 * {@code /v1/sessions/{n}/stream}).
 *
 * <p>This is its OWN {@code @JsonTypeInfo}/{@code @JsonSubTypes} namespace,
 * distinct from {@link StreamServerMessage} (the binary stream's frames) and
 * {@link ConversationClientMessage} (the client→server conversation frames), so
 * the three never collide on a {@code type} discriminator. Serialization goes
 * through a dedicated
 * {@code StreamControlMessageService#serialize(ConversationServerMessage)} path.
 *
 * <p>Per the approved proposal (decision D5), these conversation frames live in
 * {@code stream/dto} alongside {@link StreamServerMessage} — the
 * API-DTO-separation rule of {@code profile-java-server-architecture} targets
 * REST request/response bodies, not WebSocket frames, and {@link
 * StreamServerMessage} is the established precedent for a WS DTO living here.
 *
 * <p>Wire shapes are documented in {@code server/CONVERSATION_PROTOCOL.md}.
 *
 * <p>Output is <b>block-level</b>, not token-level: the source transcript JSONL
 * is written one line per content block as the block completes (RND §11), so
 * every transcript-derived frame corresponds to a finalized block. The
 * transcript-derived frames carry the per-line {@code uuid} (transcript message
 * DAG id, for client-side dedupe across reconnect/backfill), {@code isSidechain}
 * (true for subagent/teammate lines), and {@code source} ({@code main} or
 * {@code subagent:<agentId>}, set by the in-container tail helper). The
 * channel-control frames ({@link Targets}, {@link TargetSelected}, {@link
 * BackfillStart}, {@link BackfillEnd}, {@link ServerError}) are not
 * transcript-derived and carry only their own fields.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ConversationServerMessage.TurnStart.class, name = "turn-start"),
    @JsonSubTypes.Type(value = ConversationServerMessage.ThinkingState.class, name = "thinking"),
    @JsonSubTypes.Type(value = ConversationServerMessage.AssistantText.class, name = "assistant-text"),
    @JsonSubTypes.Type(value = ConversationServerMessage.ToolUse.class, name = "tool-use"),
    @JsonSubTypes.Type(value = ConversationServerMessage.ToolResult.class, name = "tool-result"),
    @JsonSubTypes.Type(value = ConversationServerMessage.ToolDetail.class, name = "tool-detail"),
    @JsonSubTypes.Type(value = ConversationServerMessage.Question.class, name = "question"),
    @JsonSubTypes.Type(value = ConversationServerMessage.PlanApproval.class, name = "plan-approval"),
    @JsonSubTypes.Type(value = ConversationServerMessage.TurnEnd.class, name = "turn-end"),
    @JsonSubTypes.Type(value = ConversationServerMessage.Targets.class, name = "targets"),
    @JsonSubTypes.Type(value = ConversationServerMessage.TargetSelected.class, name = "target-selected"),
    @JsonSubTypes.Type(value = ConversationServerMessage.BackfillStart.class, name = "backfill-start"),
    @JsonSubTypes.Type(value = ConversationServerMessage.BackfillEnd.class, name = "backfill-end"),
    @JsonSubTypes.Type(value = ConversationServerMessage.ServerError.class, name = "error")
})
public sealed interface ConversationServerMessage
        permits ConversationServerMessage.TurnStart,
                ConversationServerMessage.ThinkingState,
                ConversationServerMessage.AssistantText,
                ConversationServerMessage.ToolUse,
                ConversationServerMessage.ToolResult,
                ConversationServerMessage.ToolDetail,
                ConversationServerMessage.Question,
                ConversationServerMessage.PlanApproval,
                ConversationServerMessage.TurnEnd,
                ConversationServerMessage.Targets,
                ConversationServerMessage.TargetSelected,
                ConversationServerMessage.BackfillStart,
                ConversationServerMessage.BackfillEnd,
                ConversationServerMessage.ServerError {

    /**
     * A {@code user} transcript line that is a real prompt (not a {@code
     * tool_result}) — the start of a new turn (AC14 spinner anchor). {@code text}
     * carries the submitted prompt so backfilled history (AC6) and reconnect
     * (AC22) render the user's own messages, not just the assistant's replies;
     * it may be empty for a user line whose text could not be extracted.
     */
    record TurnStart(String uuid, boolean isSidechain, String source, String text)
            implements ConversationServerMessage {}

    /** An {@code assistant:thinking} block (AC5) — surfaced as a distinct "thinking" state. */
    record ThinkingState(String uuid, boolean isSidechain, String source, String text)
            implements ConversationServerMessage {}

    /** An {@code assistant:text} block (AC3) — a conversation message rendered as-is, no ANSI. */
    record AssistantText(String uuid, boolean isSidechain, String source, String text)
            implements ConversationServerMessage {}

    /**
     * An {@code assistant:tool_use} block (AC4) other than {@code AskUserQuestion}
     * / {@code ExitPlanMode} (those map to {@link Question} / {@link PlanApproval}).
     * {@code inputSummary} is a compact, human-readable rendering of the key
     * tool inputs — internal tool noise is summarized, not dumped raw.
     *
     * <p>UC-41 — {@code primaryText} is the single, type-aware label <em>value</em>
     * extracted server-side (decision D2): the skill name for a {@code Skill} call,
     * the command for a {@code Bash} call, or the bounded summary for any other
     * tool. The client formats the surrounding label text ("Skill loaded …",
     * "Command used: …") and applies its own ~20-char snippet budget; the server
     * only supplies the raw value so the formatting policy stays client-side.
     */
    record ToolUse(
            String uuid,
            boolean isSidechain,
            String source,
            String toolName,
            String toolUseId,
            String inputSummary,
            String primaryText)
            implements ConversationServerMessage {}

    /** A {@code user:tool_result} block (AC4) paired with its {@link ToolUse} via {@code toolUseId}. */
    record ToolResult(
            String uuid, boolean isSidechain, String source, String toolUseId, boolean isError, String summary)
            implements ConversationServerMessage {}

    /**
     * UC-41 (AC5/AC6/AC9) — the on-demand, untruncated detail for a single tool
     * call, produced in response to a client {@link ConversationClientMessage.FetchDetail}.
     * Unlike the live {@link ToolUse}/{@link ToolResult} frames (bounded to the
     * 600-char streaming summary), this frame carries the FULL tool {@code input}
     * and {@code result} re-read from the transcript on demand, bounded only to a
     * generous device-safe cap ({@code ServerProperties.conversationDetailMaxBytes},
     * 48&nbsp;KB). {@code available} is {@code false} when the id could not be
     * resolved (scrolled out of the retained window, expired, helper miss/timeout)
     * — the client then shows a "detail unavailable" state rather than hanging
     * (AC9). When {@code available} is {@code false}, {@code input}/{@code result}
     * are empty and {@code isError} is {@code false}.
     */
    record ToolDetail(
            String toolUseId, String toolName, String input, String result, boolean isError, boolean available)
            implements ConversationServerMessage {}

    /**
     * An {@code AskUserQuestion} {@code tool_use} (AC10). Carries the complete
     * structured {@code questions[]} so the client renders a native question
     * sheet — no screen-scraping. {@code toolUseId} lets the client correlate
     * the resolving/aborting transcript advance (AC12).
     */
    record Question(String uuid, boolean isSidechain, String source, String toolUseId, List<QuestionItem> questions)
            implements ConversationServerMessage {}

    /**
     * An {@code ExitPlanMode} {@code tool_use} (AC13) — the plan-mode approval
     * prompt, rendered through the same sheet mechanism. {@code plan} is the
     * proposed plan text. <b>RISK 1 (proposal):</b> if a live check shows
     * {@code ExitPlanMode} is NOT emitted as a transcript {@code tool_use} on the
     * pinned Claude Code build, AC13 degrades to long-press→tmux and this frame
     * is simply never produced — see {@code CONVERSATION_PROTOCOL.md}.
     */
    record PlanApproval(String uuid, boolean isSidechain, String source, String toolUseId, String plan)
            implements ConversationServerMessage {}

    /**
     * A {@code system:turn_duration} line (AC15) — the explicit end-of-turn
     * marker. Clears the spinner and re-enables the composer deterministically
     * (not on a fixed timeout).
     */
    record TurnEnd(String uuid, boolean isSidechain, String source, long durationMs, int messageCount)
            implements ConversationServerMessage {}

    /**
     * The enumerated conversation targets for a session (AC16/AC18) — the
     * always-present main session plus any agent-team panes, each carrying the
     * additive {@code pendingActivity}/{@code pendingQuestion} flags so a
     * non-selected target can be badged. Reuses {@link StreamServerMessage.TargetInfo}
     * so the conversation and binary-stream switchers share one target shape.
     */
    record Targets(List<StreamServerMessage.TargetInfo> targets, String selectedId)
            implements ConversationServerMessage {}

    /** Acknowledges a successful target switch (AC17). */
    record TargetSelected(String targetId) implements ConversationServerMessage {}

    /**
     * Marks the beginning of the bounded backfill window for {@code source}
     * (AC6/AC22) — the recent transcript history replayed on open / reconnect
     * before live-append resumes. The client uses {@code uuid} dedupe so a
     * backfill that overlaps already-seen lines never double-renders.
     */
    record BackfillStart(String source) implements ConversationServerMessage {}

    /** Marks the end of the backfill window for {@code source}; live append follows. */
    record BackfillEnd(String source) implements ConversationServerMessage {}

    /** Server-emitted error frame (RFC 9457-ish shape), typically followed by a close. */
    record ServerError(String code, String title, String detail) implements ConversationServerMessage {}

    /** One question within an {@code AskUserQuestion} (AC10). */
    record QuestionItem(String question, String header, boolean multiSelect, List<Option> options) {}

    /** One selectable option of a {@link QuestionItem}. The "Other" free-text path is client-side (AC10). */
    record Option(String label, String description) {}
}
