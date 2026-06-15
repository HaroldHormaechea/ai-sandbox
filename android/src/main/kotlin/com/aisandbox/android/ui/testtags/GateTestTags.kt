package com.aisandbox.android.ui.testtags

/**
 * UC-85 — stable Compose `testTag`s for the deterministic functional gate. These live in the
 * production `main` source set (not `androidTest`) on purpose: the on-device instrumented gate
 * suite (written by QA) drives the real UI by these tags only — never by `adb input tap`
 * coordinates or screenshot eyeballing — so the tags are part of the production UI contract and
 * must stay stable. Renaming one is a breaking change for the gate; update the suite in lockstep.
 *
 * <p>Grouped by surface: [ConversationTestTags] (the transcript list + bubbles), [QuestionTestTags]
 * (the AskUserQuestion / plan sheet), and [ComposerTestTags] (the input + send).
 */
object ConversationTestTags {
    /** The transcript `LazyColumn`. */
    const val LIST = "conv_list"

    /** The top "loading earlier messages…" affordance (UC-79). */
    const val LOADING_OLDER = "conv_loading_older"

    /** A right-aligned user message bubble. */
    const val BUBBLE_USER = "conv_bubble_user"

    /** A left-aligned assistant message bubble. */
    const val BUBBLE_ASSISTANT = "conv_bubble_assistant"

    /** A left-aligned, sender-attributed teammate/subagent message bubble (UC-58). */
    const val BUBBLE_TEAMMATE = "conv_bubble_teammate"
}

object QuestionTestTags {
    /** The pinned question / plan sheet card. */
    const val SHEET = "question_sheet"

    /** The "Other" free-text field (UC-75). */
    const val OTHER_FIELD = "question_other_field"

    /** The final submit control ("Send answer" / "Submit all"). */
    const val SUBMIT = "question_submit"

    /** Multi-question paging: advance to the next question. */
    const val NEXT = "question_next"

    /** Multi-question paging: go back to the previous question. */
    const val BACK = "question_back"

    /** Multi-question paging: the "Question X of N" progress label. */
    const val PROGRESS = "question_progress"

    /** The not-in-app-answerable fallback body ("answer in tmux"). */
    const val NOT_ANSWERABLE = "question_not_answerable"

    /** Plan-approval: Approve. */
    const val PLAN_APPROVE = "question_plan_approve"

    /** Plan-approval: Keep planning / Reject. */
    const val PLAN_REJECT = "question_plan_reject"

    /** Per-option selectable row, by 0-based option index within the current question. */
    fun option(index: Int): String = "question_option_$index"
}

object ComposerTestTags {
    /** The composer text input. */
    const val INPUT = "composer_input"

    /** The composer send button. */
    const val SEND = "composer_send"
}
