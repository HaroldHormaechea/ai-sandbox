package com.aisandbox.android.conversation

/**
 * UC-37 — UI-facing model for the structured-conversation view. These types
 * mirror the server's `ConversationServerMessage` frames (see
 * `server/CONVERSATION_PROTOCOL.md`) reduced to what the renderer needs.
 *
 * <p>Every [ConversationItem] carries the transcript `uuid`, its `source`
 * (`main` | `subagent:<id>`), and `isSidechain` so the view can distinguish
 * subagent/teammate activity (AC17). The controller dedupes by [ConversationItem.key]
 * across backfill/reconnect overlap (AC6/AC22): a single transcript line may
 * carry several blocks (thinking + text + tool_use) sharing one `uuid`, so the
 * key folds in the item kind + payload, not the uuid alone.
 */
sealed interface ConversationItem {
    val uuid: String
    val source: String
    val isSidechain: Boolean

    /** Stable dedupe key — distinct per block even when blocks share a uuid. */
    val key: String

    /**
     * UC-45 — a user message bubble. When [localSeq] is non-null this is an
     * **optimistic local echo**: the composer inserts the bubble the instant the
     * user hits send, before the server's authoritative `turn-start` echo arrives.
     * The [key] then derives from [localSeq] only (`localuser|<seq>`), making it
     * uuid- and text-independent and therefore STABLE for the bubble's whole
     * lifetime. That stability is load-bearing for AC3: when the server echo is
     * reconciled in place (uuid/text/source backfilled via `copy`, localSeq kept),
     * the key does not change, so Compose updates the existing row rather than
     * removing and re-adding it (no flicker). A confirmed/server-origin bubble has
     * `localSeq == null` and uses the original server-derived key.
     */
    data class UserMessage(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val text: String,
        val localSeq: Long? = null,
    ) : ConversationItem {
        override val key: String get() =
            if (localSeq != null) "localuser|$localSeq" else "$uuid|user|${text.hashCode()}"
    }

    data class Thinking(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val text: String,
    ) : ConversationItem {
        override val key: String get() = "$uuid|thinking|${text.hashCode()}"
    }

    data class AssistantMessage(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val text: String,
    ) : ConversationItem {
        override val key: String get() = "$uuid|assistant|${text.hashCode()}"
    }

    /**
     * UC-41 (AC4) — ONE collapsed, type-aware row per tool call, merging the
     * `tool_use` with its paired `tool_result` (matched on [toolUseId]). The
     * controller upserts this item on both the `tool-use` and `tool-result` frames,
     * keyed on [toolUseId] so the two never render as separate rows. [result] is
     * `null` until the paired `tool_result` arrives — the row then shows an
     * "awaiting result" state (AC8); it becomes non-null in place when the result
     * lands. [primaryText] is the server-extracted type-aware label value (the skill
     * name / command / summary, AC1/AC2/AC3); the view formats the surrounding label.
     *
     * <p>[key] is **uuid-independent** (`toolactivity|<toolUseId>`): the `tool_use`
     * and `tool_result` lines carry DIFFERENT transcript uuids, so a uuid-folded key
     * would split the pair — keying on the shared [toolUseId] is what merges them and
     * also survives a backfill boundary that splits the pair (AC4 / pitfall).
     */
    data class ToolActivity(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val toolName: String,
        val toolUseId: String,
        val inputSummary: String,
        val primaryText: String,
        val result: ToolResultData?,
    ) : ConversationItem {
        override val key: String get() = "toolactivity|$toolUseId"
    }

    /** An `AskUserQuestion` rendered inline in the transcript (the sheet is separate state). */
    data class Question(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val toolUseId: String,
        val questions: List<ConvQuestion>,
    ) : ConversationItem {
        override val key: String get() = "$uuid|question|$toolUseId"
    }

    /** An `ExitPlanMode` plan-approval prompt (AC13). */
    data class PlanApproval(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val toolUseId: String,
        val plan: String,
    ) : ConversationItem {
        override val key: String get() = "$uuid|plan|$toolUseId"
    }

    /**
     * UC-42 (AC4) — a harness-injected `user` line with NO host tool bubble to fold
     * into: a slash-command wrapper, a `<local-command-stdout>` line, or a generic
     * `isMeta:true` system note. Rendered **collapsed, left-aligned, non-user**
     * (MetaLine style) — NEVER the right-aligned user [UserMessage] bubble. [label] is
     * the short summary (`Command: /foo`, `Command output`, `System note`); [detail] is
     * the full injected body carried **inline** in the `system-note` frame, so tapping
     * to expand needs no `fetch-detail` round-trip (unlike a tool bubble's detail).
     */
    data class SystemNote(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val label: String,
        val detail: String,
    ) : ConversationItem {
        override val key: String get() = "$uuid|systemnote|${label.hashCode()}"
    }
}

/**
 * UC-41 — the merged `tool_result` half of a [ConversationItem.ToolActivity]: the
 * (bounded, streaming) result summary plus its error flag (AC7). Present once the
 * `tool-result` frame arrives; the full untruncated output is fetched on demand into
 * a [ToolDetailState] when the bubble is tapped.
 */
data class ToolResultData(
    val isError: Boolean,
    val summary: String,
)

/**
 * UC-41 (AC5/AC6/AC9) — the on-demand detail-dialog state for a tapped tool bubble.
 * [Loading] while the `fetch-detail` round-trip is in flight, [Loaded] with the full
 * untruncated input + output, or [Unavailable] when the id could not be resolved
 * (miss / timeout / disconnect-while-pending) — the dialog then shows a clear
 * "detail unavailable" state rather than hanging. `null` means no dialog is open.
 */
sealed interface ToolDetailState {
    data object Loading : ToolDetailState

    data class Loaded(
        val input: String,
        val result: String,
        val isError: Boolean,
    ) : ToolDetailState

    data object Unavailable : ToolDetailState
}

/** One question within an `AskUserQuestion` (AC10). */
data class ConvQuestion(
    val question: String,
    val header: String,
    val multiSelect: Boolean,
    val options: List<ConvOption>,
)

/** One selectable option; the "Other" free-text path is rendered by the sheet (AC10). */
data class ConvOption(
    val label: String,
    val description: String,
)

/**
 * UC-43 — one question's buffered answer within a multi-question (N>1) sheet. The
 * paged [com.aisandbox.android.ui.components.QuestionSheet] buffers one of these
 * per question (keyed by [questionIndex]); on the final submit they are sent
 * together in a single `answer-batch` frame, in `questionIndex` order.
 * [selections] are the chosen option indices; [freeText] is the always-present
 * "Other" value for that question (empty when unused).
 */
data class AnswerItem(
    val questionIndex: Int,
    val selections: List<Int>,
    val freeText: String,
)

/**
 * The pending interactive prompt the [com.aisandbox.android.ui.components.QuestionSheet]
 * renders. Cleared when answered or when the transcript advances past it (AC12).
 */
sealed interface PendingSheet {
    /** The question's identifier echoed back in the answer frame. */
    val questionUuid: String

    data class Questions(
        override val questionUuid: String,
        val questions: List<ConvQuestion>,
    ) : PendingSheet

    data class Plan(
        override val questionUuid: String,
        val plan: String,
    ) : PendingSheet
}

/** The thinking/working spinner state driven by the transcript turn lifecycle (AC14/AC15). */
enum class TurnPhase {
    /** No active turn — composer enabled, no spinner. */
    IDLE,

    /** Submitted / turn started, awaiting the first assistant block. */
    WORKING,

    /** A `thinking` block is active. */
    THINKING,
}
