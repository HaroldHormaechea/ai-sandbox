# Use Case 49: Sessions-list question-mark indicator for sessions awaiting an answer

## Summary
Each row in the Android sessions list shows a **question-mark badge** (in the trailing status area, next to the `StatusPill`) when that session's Claude is blocked on an `AskUserQuestion` prompt awaiting an answer — a "needs your input" affordance, distinct from the UC-48 working spinner (busy) and plain idle. The pending-question signal is sourced from the **server's live turn-state**: the conversation handler/stream, which already delivers the question live (UC-40), tracks a per-session "awaiting answer" flag exposed as a new field on the sessions REST list **and** the UC-32 push feed. It is explicitly **not** derived from the transcript tail or the tmux title — the UC-48 live verification proved a pending `AskUserQuestion` is absent from the transcript JSONL while awaiting an answer, so the transcript-tail derivation cannot see it. While a question is pending, the row shows the "?" badge and **suppresses** the UC-48 working spinner (the session is waiting, not working); accordingly UC-48's working signal is revised so a pending question is **not** reported as working, and the row never shows both spinner and "?". The badge clears when the question is answered or cancelled and the session resumes or goes idle. It coexists with the UC-46 pill/menu and UC-47 conversation name without layout breakage, never shows for stopped/paused/terminating sessions, and works for both single- and multi-question prompts.

## Acceptance Criteria
1. When a session's Claude is awaiting an answer to an `AskUserQuestion` (single or multi-question), that session's row shows a question-mark badge in the trailing status area near the `StatusPill`.
2. The badge clears once the question is answered or cancelled (the session resumes or goes idle), live.
3. The pending-question signal is server-provided via a new field on the sessions REST list **and** the UC-32 push feed, derived from the server's live turn-state (the conversation handler/stream's awaiting-answer tracking) — NOT from the transcript tail or the tmux title.
4. The signal is correct even when **no app conversation stream is attached** to the session — a question posed while the user isn't viewing the conversation still flags the row.
5. While a question is pending, the row shows the "?" badge and does **not** show the UC-48 working spinner (mutually exclusive). UC-48's working signal is revised so a pending question is not reported as `working`.
6. Updates appear and clear **live via the UC-32 push** within reasonable latency, with no manual refresh.
7. The badge coexists with the UC-46 status pill/menu and the UC-47 conversation name without breaking the row layout.
8. The badge is never shown for `stopped`, `paused`, or `terminating` sessions.
9. Works for both single-question and multi-question `AskUserQuestion` prompts — a multi-question sheet is a single pending state until fully submitted.

## Potential Pitfalls & Open Questions
- **Edge case** — defining "awaiting answer" precisely in the conversation handler: set when the question frame is emitted, clear when the answer/cancel is injected. Server restart or a stream reconnect mid-question must re-derive the flag correctly rather than leaving it stuck.
- **Edge case** — `ExitPlanMode` and other blocking user-input prompts: UC-40 delivers both `AskUserQuestion` **and** `ExitPlanMode` live. This UC scopes the badge to `AskUserQuestion` per the description; whether `ExitPlanMode` (or any blocking prompt) should also raise the badge is a possible extension to confirm.
- **Risk** — coupling to UC-48: suppressing the working spinner during a pending question means the working derivation must also know the pending state. Since the pending signal now comes from the live server turn-state (not the transcript), the server should be the single source that drives both "working" and "awaiting answer" so they stay mutually consistent.
- **Assumption** — visual is a "?" badge near the pill; exact glyph/color/placement left to implementation, with the constraint that it is clearly distinguishable from the spinner and the pill.

## Original Description
when a question prompt is shown the list of sessions should show in that session a questionmark. That's a new UC, define it after this test

## Clarifications
- Q: Where should the "question pending" signal come from (the UC-48 live finding proved the transcript tail can't see a pending question)?
  A: Server live turn-state — the conversation handler/stream tracks an "awaiting answer" flag and exposes it on the sessions API + push feed (reliable even when no app stream is attached).
- Q: How should the question-mark relate to the UC-48 working spinner on the row?
  A: Replace the spinner with "?" while a question is pending (the session is waiting, not working), and revise UC-48 so a pending question reads not-working/awaiting — the row never shows both.
- Q: Visual treatment of the indicator?
  A: A "?" badge in the trailing status area, next to the StatusPill (consistent with the spinner's placement).
