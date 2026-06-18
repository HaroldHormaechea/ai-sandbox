# Use Case 89: Jump-to-bottom scroll button with unread badge

## Summary
In the Android app's chat/conversation view, add a "jump to bottom" affordance: a small circular button containing a downward arrow that appears only when the conversation is **not anchored to the bottom** (the user has scrolled up away from the latest message). The button carries a **numeric unread badge** showing how many new messages have arrived since the user scrolled up. Tapping it smoothly scrolls to the most recent message, clears the unread count, and **re-engages auto-follow** so subsequent incoming messages keep the view pinned to the bottom. While the user is scrolled up reading earlier messages, the app must **never auto-scroll them down** when new messages arrive — auto-follow stays suppressed until they tap the button or manually scroll back to the bottom. New content (including streaming assistant messages growing token-by-token) follows the same single rule: follow if anchored at bottom, suppress if scrolled up — no special-casing for streaming. "At bottom" uses a **small tolerance** (last message mostly visible) to avoid flicker near the end. Entering or switching into a conversation always starts **anchored at the bottom** with the button hidden and auto-follow on. The change is confined to the Android Compose chat UI and its scroll-state handling; it touches no server code, enrollment, or message transport.

## Acceptance Criteria
1. When the message list is at the bottom (within the small tolerance — last message mostly visible), the jump-to-bottom button is **not** shown.
2. When the user scrolls up beyond the tolerance so the latest message is not visible, a small circular button with a downward-arrow icon appears at a consistent position (e.g. bottom-end of the list area).
3. While the button is visible, it displays a **numeric badge** of how many new messages have arrived since the user left the bottom; the count increments as further messages arrive while scrolled up.
4. Tapping the button smoothly animates the list to the last available message, clears the unread badge to zero, and hides the button once the bottom is reached.
5. After tapping the button, auto-follow is re-engaged: any new messages arriving thereafter keep the view pinned to the latest message automatically.
6. While the user is scrolled up (button visible / auto-follow suppressed), arrival of one or more new messages — including a streaming message growing in place — does **not** move the user's scroll position; they stay anchored to the message they were reading, and only the badge count updates.
7. If the user is at the bottom and new content arrives (a new message **or** an in-place streaming message growing token-by-token), the view auto-scrolls to keep the newest content visible, the button stays hidden, and no badge is shown.
8. Manually scrolling back down to the bottom (without tapping the button) re-engages auto-follow, clears the badge, and hides the button — an end state equivalent to tapping it.
9. On entering or switching into a conversation, the view starts anchored at the bottom (latest message), with the button hidden and auto-follow on, regardless of any prior scroll position.
10. The button does not obscure the message composer/input or other interactive controls, and respects safe-area/insets.
11. Behavior is covered by an instrumented/Compose UI test (or unit test over the scroll-state logic) verifying: button + badge visibility toggling, badge increment while scrolled up, suppression of auto-scroll (incl. streaming) while scrolled up, and re-engagement on tap and on manual return to bottom.

## Potential Pitfalls & Open Questions
- **Edge case** — A message arriving *while the user is actively dragging/flinging* the list: the auto-follow decision and badge update must not fight the active gesture or cause a jump.
- **Assumption** — The conversation list is a Compose `LazyColumn` driven by a `LazyListState`, and the button is a `FloatingActionButton`-style overlay within the chat screen. To be confirmed against the actual chat composable by the dev-team.
- **Risk** — Existing auto-scroll logic in the chat view may need to be *replaced or gated* by this new anchored/suppressed state machine rather than run alongside it, to avoid double-scrolling. This intersects the UC-86/UC-88 conversation-switch/view-bleed work, so the dev-team should reconcile with that code path.

## Original Description
In the android app chat view, when the user comversation view is not anchored to the bottom, show a small round icon with a down arrow inside of it that moves the scroll to the bottom and anchors the chat view to the last message available as new ones come. so the user doesnt need to continue scrolling.If the user manually scrolled up to see a message sent before never forcefully scroll them down again unless they click that buttombto avoid disrupting their read

## Clarifications
- Q: Should the jump-to-bottom button indicate unread/new messages that arrived while you were scrolled up?
  A: Show unread count — a numeric badge of how many new messages arrived while scrolled up.
- Q: When a streaming assistant message grows token-by-token at the bottom and you ARE anchored at the bottom, what should happen?
  A: Treat as normal new content — same follow rule as any new message (follow if at bottom, suppress if scrolled up); no special streaming handling.
- Q: On entering / switching into a conversation, where should the view start?
  A: Anchored at bottom — always open at the latest message with the button hidden and auto-follow on.
- Q: How precise should the 'at bottom' threshold be?
  A: Small tolerance — last item mostly visible / within ~1 item counts as bottom, to avoid flicker near the end.
