# Use Case 39: Chat-style aligned message bubbles in the conversation view

## Summary
Restyle the message bubbles in the Android structured-conversation view (`ConversationScreen.kt`'s `Bubble` composable) from the current full-width blocks to chat-app-style aligned bubbles like WhatsApp/Telegram. Assistant bubbles (labeled "Claude"/"Agent", `AssistantMessage`) align to the **left** with empty padding on their right; the user's own bubbles (`UserMessage`) align to the **right** with empty padding on their left. Bubbles no longer fill the container — they size to content up to a maximum of **~80% of the list width**, after which text wraps. The textual "You" label is dropped (right-alignment already signals the user's own messages), while assistant/agent bubbles keep their label, including the `· subagent` annotation for `isSidechain` activity. Meta items (`MetaLine`: thinking, tool_use, tool_result, question, plan) are left unchanged — full-width, as today. This is a presentation-only change confined to one Compose file; the conversation data model, transcript stream, controller, composer, and question-sheet behavior are untouched.

## Acceptance Criteria
1. Assistant/agent message bubbles are left-aligned within the conversation `LazyColumn`, with empty space between the bubble's right edge and the list's right margin.
2. The user's own message bubbles are right-aligned, with empty space between the bubble's left edge and the list's left margin.
3. A bubble sizes to its content and grows only up to ~80% of the container width; beyond that, text wraps to additional lines (no full-width bubbles).
4. The "You" label is removed from user bubbles; assistant/agent bubbles retain their label and the `· subagent` annotation when `isSidechain` is true, positioned consistent with left alignment.
5. The existing background-color distinction is preserved (user = `primaryContainer`, assistant = `SurfaceLow`) with correct text contrast in the app theme.
6. Meta items (`MetaLine`: thinking, tool_use, tool_result, question, plan) render unchanged — full-width, same styling as today.
7. Vertical spacing between consecutive messages and the list's scroll/backfill/live-append ordering behavior are unchanged.
8. The change is confined to the conversation view's rendering; no change to `ConversationModel`, `ConversationController`, `ConversationClient`, the composer, or the question sheet.
9. Very long unbroken tokens (URLs, code) wrap or otherwise stay within the bubble's max width without overflowing the container.
10. Existing conversation-view tests pass (updated only for the new layout if needed), and the app builds.

## Potential Pitfalls & Open Questions
- **Assumption** — Bubble corner shape stays a symmetric `RoundedCornerShape(12.dp)`; no Telegram-style asymmetric "tail" corner.
- **Edge case** — With "You" dropped, a user message and an immediately following assistant message are distinguished only by alignment side + background color; this is the intended chat-app behavior.

## Original Description
First, a small format change. I want the message to be like WhatsApp bubbles or Telegram. Claude messages aligned to the left and with some padding on the right of the container,, my messages aligned to the right with some padding on the left of their container.

## Clarifications
- Q: Maximum bubble width (as a fraction of the conversation list width)?
  A: ~80% (Telegram-like; ~20% gap on the opposite side).
- Q: Keep the sender label ('You' / 'Claude' / 'Agent') above each bubble?
  A: Drop 'You', keep Claude/Agent (including the '· subagent' annotation).
- Q: How should meta items (thinking, tool_use/result, question, plan) align?
  A: Left, full-width — unchanged from today.
