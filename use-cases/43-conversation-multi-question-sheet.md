# Use Case 43: Conversation view — multi-question AskUserQuestion halts and shows only one question

## Summary

When Claude issues an `AskUserQuestion` carrying **multiple questions** (the kind navigated with arrow keys in the TUI), the Android conversation view (a) **halts** — the question sheet is not surfaced promptly, repeating the UC-40 "not delivered until you answer in tmux" symptom — and (b) after the user answers everything in tmux, the view shows **only one** question sheet, never the full set. The data is intact on the wire: `ConversationEventMapper.parseQuestions` (`server/src/main/java/com/aisandbox/server/stream/service/ConversationEventMapper.java:262-282`) maps the `AskUserQuestion` `tool_use` to a single `Question` frame carrying the entire `questions[]` list, and the Android client preserves that list into `PendingSheet.Questions`. The defect is in rendering: `QuestionSheet.QuestionBody` (`android/src/main/kotlin/com/aisandbox/android/ui/components/QuestionSheet.kt`) hardcodes `sheet.questions.firstOrNull()` and submits with `questionIndex = 0`, so only one question of the batch is ever presented or answerable (its own doc comment assumes the TUI "resolves them one at a time" and a follow-up frame would arrive per question — which does not happen for a single multi-question `AskUserQuestion`). The halt is a delivery/flush problem in the same area UC-40 addressed, exposed here for the multi-question case. This use case covers **both** defects: surface a multi-question ask promptly (no tmux fallback) **and** present all N questions **paged one-at-a-time** (Back/Next with a progress indicator), submitting each with its correct `questionIndex`, fully resolving the batch in-app. If the ask is resolved externally mid-flight (answered in tmux, transcript advances), the in-app sheet **dismisses cleanly** rather than lingering. This builds on UC-37 (the question sheet) and UC-40 (question-delivery flush).

## Acceptance Criteria

1. An `AskUserQuestion` with N>1 questions surfaces the question sheet **promptly/live**, with no requirement to answer in tmux first (no halt).
2. The sheet presents **all N questions** via a **paged, one-at-a-time** UI — a progress indicator (e.g. "2 of 4") and Back/Next navigation — so every question in the batch is reachable and answerable in-app.
3. Each question's answer is submitted with its **correct `questionIndex`** (not always 0); per-question `multiSelect`/single-select and the always-present "Other" free-text option work for every question.
4. Completing all N questions in-app **resolves the entire `AskUserQuestion`** and advances the turn (spinner clears via `TurnEnd` / next turn) with no tmux interaction.
5. A single-question `AskUserQuestion` continues to work exactly as today (no regression).
6. Plan-approval (`ExitPlanMode`) sheets continue to work as today (no regression).
7. If the ask is resolved/aborted externally (answered in tmux, or the transcript advances past it), the in-app sheet **dismisses cleanly** instead of lingering on a stale question (AC12 behavior preserved for the multi-question case).
8. Behavior is consistent for live append and for backfill/reconnect.
9. Android instrumented tests cover a multi-question ask (all questions presentable via paging, each submits the right index, batch fully resolves, external-resolution dismissal) plus a single-question regression test.

## Potential Pitfalls & Open Questions

- **Missing input** — Answer-protocol semantics: does the existing `sendAnswer(questionUuid, questionIndex, …)` resolve **one** question or the whole tool? Claude Code's `AskUserQuestion` returns once with all answers, so the client likely must collect all N then submit (or the server aggregates per-index answers before returning the tool result). Must be verified by the analyst/challenger against a live transcript and the server answer handler — it determines whether the fix is client-only or a coordinated client+server change.
- **Assumption** — The halt shares a root cause with UC-40 (flush timing); to be confirmed it is not a distinct multi-question-specific path.
- **Edge case** — Paged navigation must not let the user submit the batch until all required questions are answered; the dev-team should specify the paging state machine (what Back does after a per-question submit, whether answers are buffered locally until the final submit, etc.).
- **Risk** — A server/handler that also assumes a single question would make this a coordinated client+server change rather than client-only; the existing `submitAnswer(questionUuid, questionIndex, …)` signature already carries an index, which suggests per-index answers were anticipated but never exercised for N>1.

## Original Description

When claude prompts for multiple questions at the same time (that you are supposed to scroll with arrow keys), the ui halts (as with the older bug we had which didn't show questions), and after I answer all of them via tmux it shows the LAST question pop-up only.

## Clarifications

- Q: How should the multi-question sheet present N questions in-app?
  A: Paged — one question at a time with Back/Next and a progress indicator (e.g. "2 of 4"), mirroring the TUI arrow-key flow.
- Q: What scope should UC-43 cover?
  A: Both — fix the delivery halt (sheet appears live) AND the multi-question rendering/submit, fully removing the tmux fallback for multi-question asks.
- Q: If you answer some questions in tmux while the app sheet is open (partial external resolution), what should the app do?
  A: Dismiss the in-app sheet entirely once the tool resolves externally (transcript advances) — don't try to merge; just let the turn proceed (consistent with AC12).
