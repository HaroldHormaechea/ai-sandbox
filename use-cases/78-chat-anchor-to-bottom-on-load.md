# Use Case 78: Chat anchors to the bottom on load (no top→bottom scroll jump)

## Summary
When the user opens a conversation/chat screen in the ai-sandbox Android client, the message list currently renders starting at the **top** and then visibly **scrolls down to the bottom**, producing an ugly animated jump on every open. Since a chat is read most-recent-last, the screen should **open already anchored at the bottom** (the latest message), with no visible scroll-from-top animation. The fix should make the initial scroll position land at the bottom on first composition/load — ideally without a perceptible jump or flash of the top content — for both freshly opened conversations and conversations re-entered from elsewhere (sessions list, deep link, notification tap). It must preserve normal scrolling behavior afterward (the user can freely scroll up to read history) and must keep the existing "stick to bottom as new messages arrive" behavior intact.

## Acceptance Criteria
1. Opening a conversation lands the scroll position at the **bottom** (latest message visible) on first render, with no visible top→bottom scroll animation or flash of the top of the list.
2. The anchor-to-bottom applies regardless of entry path: sessions-list tap, deep link, and notification tap all open at the bottom.
3. After load, the user can scroll up freely to read earlier messages; the initial anchoring does not trap the view at the bottom.
4. The existing auto-stick-to-bottom-on-new-message behavior (new incoming messages keep the view pinned to the latest when already at/near the bottom) is preserved — no regression.
5. Empty or single-message conversations render correctly (no crash, no spurious scroll).
6. Works for both a cold open (transcript loaded fresh) and a conversation whose messages stream in shortly after open (the view should settle at the bottom once initial content is present, not bounce).
7. CI gates pass: `:android:test` + `:android:lint` (and `:server:test` + `:server:spotlessCheck` only if any server code changes — unlikely; this is expected to be Android-only).

## Potential Pitfalls & Open Questions
- **Ambiguity** — Is the list a `LazyColumn`? The analyst should determine the current scroll mechanism (e.g. `rememberLazyListState`, an initial `scrollToItem`, or `reverseLayout`) and decide the least-jumpy approach: an `initialFirstVisibleItemIndex` at the last item, a `reverseLayout = true`, or a pre-layout `scrollToItem(lastIndex)` before first frame.
- **Edge case** — Messages that arrive asynchronously after the first frame: if the list is empty at first composition and fills in milliseconds later, a naive `scrollToItem` at composition may target index 0. Anchor must re-evaluate once initial content is present, without fighting the user if they have already scrolled.
- **Edge case** — Variable-height items (tool/skill bubbles, long messages, the UC-80 un-cropped messages) mean the last item's offset isn't known until measured; ensure the anchor uses item index (not pixel offset) so it's correct regardless of item heights.
- **Risk** — Over-anchoring: forcibly snapping to bottom on every recomposition would prevent the user from scrolling up. The anchor must be a one-time initial positioning, not a persistent pin (the persistent pin is the separate, existing new-message stick behavior).
- **Relationship** — Interacts with UC-79 (infinite scroll / dynamic loading of older messages — anchoring to bottom must still work when only a window of recent messages is initially loaded) and UC-80 (un-cropped long messages change item heights). Coordinate the anchor logic with whatever windowing UC-79 introduces.

## Original Description
When accessing a chat it begins in the top and scrolls to the bottom which is ugly. Anchor to the bottom on load.

## Clarifications
- Status: **Captured during the autonomous UC-58→60 run (2026-06-15) at the user's request.** Split from the chat-scroll feedback into its own UC (separate from UC-79 infinite scroll) per the user's explicit choice. Interactive clarification loop skipped (autonomous capture); the scroll-mechanism specifics are left for the analyst to resolve against the Android `ConversationScreen` code. To be implemented in this same autonomous batch; release is deferred until all queued UCs (58–60 + 78–81) are merged.
