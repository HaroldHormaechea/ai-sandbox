# Use Case 44: Conversation view — multi-question "Other" free-text answers are dropped or break the wizard

## Summary

UC-43 added the paged multi-question `AskUserQuestion` sheet to the Android non-tmux conversation view, with a per-question always-present "Other" free-text field. When the user answers one or more questions of a multi-question batch using "Other", the server's keystroke-injection path (`InputInjectionService.selectQuestionAnswer`, driving the live Claude Code TUI wizard via `tmux send-keys`) handles the free-text case incorrectly, so the user observes "something odd" they cannot describe — the failure manifests downstream in the live TUI wizard, not in the Android UI, which shows a normal submit. A QA investigation isolated two source-proven root causes and several runtime-unknowable consequences. **(1) multiSelect + "Other":** in `selectQuestionAnswer` the `if (multiSelect)` branch is checked before `else if (freeTextChosen)`, so for a multiSelect question the "Other" row is toggled (`Space`) but the literal text is **never typed** — the user's custom answer silently vanishes, and because the code's own javadoc warns that pressing `Enter` on an empty "Type something" row **declines the entire ask**, the whole multi-question prompt can be aborted. **(2) single-select "Other" on a non-last question:** the `freeTextChosen` branch ends with a hardcoded `Enter` (ignoring the batch's `commitKey`); this was only ever tested for a batch of one (where `Enter` legitimately doubles as the final submit), so for a non-last free-text question it may submit the batch early (dropping trailing questions) or desync the wizard (later questions' keystrokes land on the wrong tab). The same `multiSelect`-shadows-free-text root cause also exists in the single-question `injectAnswer` path (UC-37). The fix is a coordinated client+server change whose correct keystroke model **must be live-verified against the running Claude Code TUI** (the project's emulator/server hard gate) before it is committed, then captured in `server/CONVERSATION_PROTOCOL.md`.

## Acceptance Criteria

1. In a multi-question ask, a **single-select** question's "Other" free text typed on a **non-last** question is delivered to Claude **verbatim**, and every subsequent question in the batch is still answered correctly (verified against the live wizard, not only a mocked executor).
2. In a multi-question ask, a **multiSelect** question's "Other" free text is **typed into the wizard and appears in Claude's received answer** — it is no longer silently dropped.
3. A multi-question ask that uses "Other" on one or more questions **never declines/aborts** the ask: Claude receives a complete set of answers and the turn advances normally (spinner clears via `TurnEnd`).
3a. **Stuck-popup invariant (reported live symptom):** the in-app question sheet must **never linger** once the underlying ask is resolved — including the failure path where an "Other" answer caused the ask to be declined/aborted server-side and the conversation has already moved forward. If the transcript advances past the ask (turn proceeds / `TurnEnd` / next assistant block) the sheet **dismisses cleanly** (reinforces UC-43 AC7/AC12 for the multi-question + "Other" case). After the fix, the correct answers flow through so the ask resolves normally and the sheet closes on submit; the dismissal-on-external-resolution path is the safety net and must be verified.
4. The number of answers Claude receives equals the number of questions in the batch; no early submit drops trailing questions when an earlier question used "Other".
5. Answers land on the **correct question** (wizard tab order matches `questionIndex`) even when a mid-batch question uses "Other".
6. A **single-question** ask (the UC-37 `injectAnswer` path) whose only question is multiSelect with an "Other" answer also delivers the free text verbatim (the same root cause is fixed in both paths).
7. Regression: a single-select "Other" as the **last/only** question of a batch still works exactly as today; plain (non-Other) single-select, multiSelect, and plan-approval sheets are unaffected.
8. The verified live keystroke model for the free-text advance and for multiSelect "Other" injection is documented in `server/CONVERSATION_PROTOCOL.md` against the verified Claude Code version, and the prior "documented limitation" note (multiSelect Other not typed) is removed/replaced.
9. Tests are updated to close the gaps that let this ship: a non-last free-text batch question, a multiSelect+free-text batch question (positive assertion replacing the `…_documented_limitation` test), the `SessionConversationHandler` batch derivation for those cases, and an Android instrumented test that exercises an "Other" answer within a multi-question batch.

## Potential Pitfalls & Open Questions

- **Risk (runtime-unknowable)** — The live consequence of the single-select non-last free-text `Enter` (early submit vs. tab desync vs. clean advance) and the exact keystrokes that inject text into a multiSelect "Other" row are **not knowable from source** (per the code's own comments). They must be discovered on a real Claude Code TUI during the dev-team's hard gate before the fix is finalized; the keystroke model may differ from the current assumption (e.g. `Enter`-to-commit-field then `Tab`-to-advance).
- **Edge case** — Multiple "Other" answers across several questions in one batch (the user's "for some of them"): the fix must hold when 2+ questions each use free text, in any tab position.
- **Edge case** — An "Other" row selected/toggled with **blank** text: the client should not submit a blank "Other" as a selection, and the server must never emit an `Enter` on an empty "Type something" row (which declines the ask).
- **Assumption** — The fix is preferred over a UI workaround (e.g. hiding the free-text field for multiSelect questions): UC-43's intent was to fully remove the tmux fallback for multi-question asks, so the free-text path should be made to work, not disabled. If live verification proves multiSelect-"Other" injection is genuinely infeasible on the pinned TUI, fall back to surfacing the limitation in the UI rather than silently dropping (must be flagged to the user, not shipped silently).
- **Risk** — Client/server contract drift: the client (`QuestionSheet.kt` `PagedQuestionBody`) adds the Other option index to `selections` for every "Other"; the server derivation (`deriveAnswerSpec`) and injection must stay consistent with whatever selection semantics the fix adopts.

## Original Description

NON TMUX Chat bug. Something odd happened when in a multi-question prompt I wrote in the "other" answer for some of them. I can't describe the behaviour.

Additional symptom reported by the user mid-investigation: "I have a scenario where the ask popup has got stuck despite the conversation moving forward." This corroborates the QA finding that an "Other" answer can cause the server to decline/abort the ask (the turn proceeds in Claude) while the Android sheet is left lingering with no clean dismissal — captured as AC 3a. **The fix must be live-tested**, not only covered by mocked-executor unit tests.

(Investigated by a QA scoping pass before this use case was written — see the two root causes and failure modes captured in the Summary.)

## Clarifications

Resolved autonomously from the QA scoping investigation (the user delegated autonomous execution):

- Q: What scope should the fix cover — only the obvious silent drop, or both failure modes?
  A: Both — the multiSelect "Other" silent text drop AND the single-select non-last "Other" mid-batch advance, plus the matching single-question (`injectAnswer`) path.
- Q: Is the fix client-only, server-only, or coordinated?
  A: Coordinated client+server, with the heavy lift in `InputInjectionService`; the correct keystroke model is live-verified first (project hard gate).
- Q: Fix the free-text path, or work around it by disabling "Other" for multiSelect?
  A: Fix it (consistent with UC-43's goal of removing the tmux fallback). Only fall back to a surfaced UI limitation if live verification proves injection infeasible — never ship a silent drop.
