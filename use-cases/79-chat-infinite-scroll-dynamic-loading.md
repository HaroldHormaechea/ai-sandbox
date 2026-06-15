# Use Case 79: Chat infinite scroll with dynamic (lazy) loading of older messages

## Summary
The conversation/chat screen currently loads its message history in a way that does not scale gracefully to long transcripts (the whole list is materialized, or a fixed slice is shown). This use case adds **infinite scroll with dynamic loading**: the chat initially renders a bounded window of the **most recent** messages and **lazily loads older messages as the user scrolls up**, fetching the next older page on demand (with a loading affordance) until the start of the transcript is reached. The goal is smooth scrolling and bounded memory/initial-render cost on long conversations, while preserving correct ordering, the UC-78 anchor-to-bottom-on-load behavior, and the live append of new incoming messages at the bottom.

## Acceptance Criteria
1. On open, only a bounded window of the most recent messages is loaded/rendered (not the entire transcript), and the view is anchored at the bottom (consistent with UC-78).
2. Scrolling up toward the top of the loaded window triggers loading of the **next older page** of messages, which is prepended without losing the user's current scroll position (no jump/teleport when older content is inserted).
3. A loading indicator (or equivalent affordance) is shown while an older page is being fetched/parsed; it disappears when the page is ready.
4. Paging continues until the **beginning of the transcript** is reached, after which no further load is attempted and (optionally) a subtle "start of conversation" boundary is acceptable but not required.
5. New incoming messages still append live at the bottom and the stick-to-bottom behavior is preserved when the user is at/near the bottom.
6. Message ordering, de-duplication, and identity (stable keys) are correct across paging — no duplicated, reordered, or dropped messages at page boundaries.
7. Performance: scrolling a long conversation stays smooth; initial render cost and retained item count are bounded rather than O(full transcript).
8. No regression to UC-37/40/47/49/50 conversation rendering, UC-58 teammate bubbles, UC-65 clear, or UC-78 anchoring. CI gates pass: `:android:test` + `:android:lint` (and `:server:test` + `:server:spotlessCheck` if the server transcript-tail/pagination surface changes).

## Potential Pitfalls & Open Questions
- **Ambiguity (source of pages)** — Where do older messages come from? The analyst must determine whether the client holds the full transcript already (so paging is purely a render-windowing concern) or whether older messages must be fetched from the server / transcript-tail helper (a data-loading concern needing a pagination API). The fix locus differs sharply between the two; resolve before proposing.
- **Scroll-anchor preservation** — Prepending older items to a `LazyColumn` can shift the viewport. Use a strategy that preserves the anchored item (e.g. key-stable items + `LazyListState` anchoring, or `reverseLayout`) so loading a page up doesn't visually teleport the user.
- **Edge case** — Rapid scroll-up past multiple pages: debounce/queue page loads so a fast fling doesn't fire many overlapping fetches or skip pages.
- **Edge case** — Live new messages arriving while the user is scrolled up reading history: must not force-scroll them down, and must not corrupt the loaded window.
- **Risk (scope coupling)** — This is the larger sibling of UC-78; keep UC-78 (pure anchor-on-load) independently shippable. If UC-78 already merged, build on its anchoring rather than reimplementing it.
- **Open question** — Page size and trigger threshold (how many messages per page; how close to the top before prefetch) — pick sensible defaults; tune against a long real transcript.
- **Relationship** — UC-78 (anchor on load), UC-80 (un-cropped long messages affect item heights and thus windowing), UC-37 (transcript-tail helper) if server-side paging is needed.

## Original Description
Another fix is to have an infinite scroll of the chat with dynamic loading.

## Clarifications
- Status: **Captured during the autonomous UC-58→60 run (2026-06-15) at the user's request.** Deliberately kept as a **separate** UC from UC-78 (anchor-to-bottom) per the user's explicit choice, since infinite scroll is a larger feature with its own ACs and performance risks. Interactive clarification loop skipped (autonomous capture); the page-source question (client-side windowing vs server pagination) is the key item for the analyst to resolve. To be implemented in this same autonomous batch; release deferred until all queued UCs (58–60 + 78–81) are merged.
