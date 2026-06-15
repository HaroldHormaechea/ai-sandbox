# Use Case 80: Long chat messages render in full (no cropping/trimming)

## Summary
In the ai-sandbox Android conversation view, **long messages are currently cropped/trimmed** — their text is cut off rather than shown in full. Messages (user, assistant, and teammate bubbles) should render their **entire content** regardless of length, wrapping across as many lines as needed within the bubble, so no information is silently hidden. This applies to long plain-text messages and should be consistent across all bubble types in the chat. If any truncation exists for a deliberate reason (e.g. a collapsed tool/skill detail with an expand affordance per UC-41), that intentional collapse is out of scope — this UC targets the *unintended* cropping of ordinary message bodies.

## Acceptance Criteria
1. A long user message renders in full (all lines visible, wrapped), not cropped to a fixed number of lines or a fixed height.
2. A long assistant message renders in full, wrapped, with no truncation/ellipsis on the body.
3. A long teammate/subagent message (UC-58 bubble) renders in full as well — the un-cropping is consistent across bubble types.
4. Whatever currently causes the trim (e.g. a `maxLines`, a fixed-height/`heightIn` constraint, or an ellipsis overflow on the message `Text`) is removed for message bodies so content is height-unbounded and scrolls with the list.
5. Deliberately collapsible content (UC-41 tool/skill detail bubbles with an expand/popup affordance) is unaffected — this UC does not force-expand intentional collapses; it only removes unintended truncation of ordinary message bodies.
6. Layout remains correct with very long messages: the bubble grows vertically, the list scrolls, and there is no horizontal overflow or clipped text.
7. No regression to UC-37/40/47/49/50/58 rendering. CI gates pass: `:android:test` + `:android:lint` (server gates only if server code changes — not expected).

## Potential Pitfalls & Open Questions
- **Ambiguity (root cause)** — The analyst must locate WHAT trims the text today: a `maxLines = N` + `TextOverflow.Ellipsis` on the bubble `Text`, a `Modifier.heightIn(max = …)`/fixed height on the bubble, or a `LazyColumn` item-size constraint. The fix differs per cause; identify it precisely (likely in the `Bubble` composable in `ConversationScreen.kt`).
- **Edge case** — Extremely long single messages (thousands of lines): un-capping height must not break `LazyColumn` recycling or cause jank. Confirm the item still scrolls smoothly and isn't measured eagerly in a way that hurts UC-79 windowing.
- **Edge case** — Code blocks / preformatted / very long unbroken tokens (URLs, base64): ensure wrapping/soft-wrap doesn't cause horizontal overflow; long unbroken strings should wrap or scroll, not clip.
- **Distinction** — Do NOT remove the UC-41 intentional tool/skill collapse-with-expand. Keep that affordance; only ordinary message bodies are un-cropped here.
- **Relationship** — UC-79 (un-capped item heights change windowing/anchoring math), UC-78 (anchor uses item index, so variable heights are fine), UC-41 (collapsible tool detail — leave intact), UC-81 (copy — full text must be copyable, which assumes full text is present).

## Original Description
Chat messages shouldn't be cropped even when they are long. Now we are trimming them.

## Clarifications
- Status: **Captured during the autonomous UC-58→60 run (2026-06-15) at the user's request.** Interactive clarification loop skipped (autonomous capture); the exact truncation mechanism is left for the analyst to find in the Android `Bubble`/`ConversationScreen` code. To be implemented in this same autonomous batch; release deferred until all queued UCs (58–60 + 78–81) are merged.
