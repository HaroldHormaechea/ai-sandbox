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

    data class UserMessage(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val text: String,
    ) : ConversationItem {
        override val key: String get() = "$uuid|user|${text.hashCode()}"
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

    data class ToolUse(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val toolName: String,
        val toolUseId: String,
        val inputSummary: String,
    ) : ConversationItem {
        override val key: String get() = "$uuid|tooluse|$toolUseId"
    }

    data class ToolResult(
        override val uuid: String,
        override val source: String,
        override val isSidechain: Boolean,
        val toolUseId: String,
        val isError: Boolean,
        val summary: String,
    ) : ConversationItem {
        override val key: String get() = "$uuid|toolresult|$toolUseId"
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
