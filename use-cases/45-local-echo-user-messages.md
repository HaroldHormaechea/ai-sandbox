# Use Case 45: Echo user messages locally in the conversation view

## Summary
In the Android conversation view, a message the user sends does not currently appear until the server streams back the `turn-start` frame that carries the injected text (see `ConversationController.onFrame()` and `ConversationItem.UserMessage` in `android/src/main/kotlin/com/aisandbox/android/conversation/`). Because the text is injected into tmux and only echoed after Claude's round-trip, there is a perceptible gap — and, worse, if the frame is delayed or dropped (cf. UC-40), the user's own line may be missed entirely. This use case makes the composer echo the user's message **locally and immediately** as an optimistic bubble the moment they hit send, so the conversation always reflects what they typed without waiting on the server. When the authoritative `turn-start` echo later arrives, the optimistic bubble is reconciled with the server's copy (deduplicated, not duplicated). The change is confined to the conversation view's send/render path; it must not alter the tmux fallback mode and must not change what is actually injected into the session.

## Acceptance Criteria
1. When the user submits a message in the conversation view, their message bubble appears in the transcript immediately (within one frame / before any server round-trip), right-aligned exactly as a confirmed user message would render.
2. The turn spinner still transitions to `WORKING` on submit, as it does today; the optimistic echo does not suppress or duplicate the spinner.
3. When the server's authoritative `turn-start` frame for that submission arrives, the optimistic bubble is reconciled with the server copy so the user sees exactly **one** bubble for that message — no duplicate, no flicker that removes then re-adds the line.
4. If the server echo never arrives (delayed/dropped, per UC-40), the optimistic bubble remains visible rather than vanishing; the user never loses sight of what they sent.
5. Reconciliation matches the optimistic bubble to the server frame robustly (e.g. by submission ordering and/or text), tolerating server-side text normalization differences without producing a duplicate.
6. The optimistic echo applies only to the structured conversation mode; the tmux fallback (long-press) connection is unaffected.
7. The actual text injected into the session is byte-for-byte unchanged by this feature — local echo is display-only.
8. Backfill / history replay (the `if (!backfilling)` paths) is unaffected: re-entering a conversation does not produce phantom optimistic bubbles.

## Potential Pitfalls & Open Questions
- **Edge case** — Reconciliation keying: the server may normalize/trim the injected text or wrap it, so matching the optimistic bubble purely by text equality can fail. A monotonic local submission sequence (or a client-issued correlation id, if the protocol allows) is more reliable; the protocol may not currently carry such an id.
- **Edge case** — Multiple rapid submissions before any echo returns: each must echo and later reconcile to the correct server frame in order, without cross-matching.
- **Risk** — If the submission is ultimately rejected/not injected (session error), the optimistic bubble would be left as a "sent" message that never actually ran. Whether to mark such bubbles as pending/failed vs. leave them is an open question; AC4 deliberately favors never hiding the user's text.
- **Assumption** — The optimistic `UserMessage` can reuse the existing `ConversationItem.UserMessage` model with a client-generated placeholder uuid that is later replaced/merged on echo; this assumes the render layer keys off the model rather than requiring a server-issued uuid up front.
- **Ambiguity** — Whether to show a subtle "pending/sending" affordance on the optimistic bubble until the server confirms, or render it identically to a confirmed message. The summary assumes identical rendering (no special pending state) for simplicity, but a pending tick is a reasonable alternative.

## Original Description
User messages should be echoed locally to avoid waits and missed messages in the UI.
