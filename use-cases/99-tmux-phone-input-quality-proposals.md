# Use Case 99: tmux phone-view text-input — evaluate, decide, implement

## Summary
The Android "Cool Chat" tmux/terminal phone view (`TerminalScreen.kt` + the vendored Termux `TerminalView` in `TerminalSurface.kt`) has poor text input: perceptible **lag** and **autocorrect that mangles words** (wrong replacements, garbled insertions), weighted **equally** as pains. The root causes are structural — there is **no local echo** (every keystroke round-trips over the WebSocket to the PTY via `WsTerminalSession`/`TerminalStreamController` and only appears when Claude/tmux echoes it), and the IME's autocorrect/composing region has no client-side text buffer to reconcile against, so a bufferless terminal mis-applies suggestion replacements (only partially mitigated by UC-36's conversational-keyboard toggle and composing-region flush). This use case runs **evaluate → decide → implement**: first produce a proposal that ranks candidate approaches across **both** families — in-place IME/echo fixes to the Termux view **and** a decoupled local-composer text-field — and folds in the RND memo's structured `stream-json` path (`RND-remote-view-decoupled-io-and-question-rendering.md`, Approach C) to reach an explicit **go/no-go**; then it implements the recommended winner. It must not regress UC-21/UC-23/UC-36/UC-85.

## Acceptance Criteria
1. A proposal artifact is produced first (a new design doc under `use-cases/` or an update to the RND memo) that precisely separates the **lag** symptom (no local echo, per-keystroke round-trip) from the **autocorrect-mangling** symptom (IME composing/suggestions against a bufferless terminal), citing the current code paths.
2. The proposal ranks **at least three** candidate approaches drawn from **both** families — (a) in-place fixes to the Termux `TerminalView` (e.g. disable/normalize autocorrect via InputType no-suggestions, composing-region correctness, speculative local echo) and (b) a **decoupled local composer** (a native Android `TextField` with full IME/autocorrect that sends the finalized line via `send-keys -l` + Enter) — each with mechanism, effect on lag, effect on autocorrect correctness, implementation effort, and regression risk.
3. The proposal reaches an explicit **go/no-go** decision on the RND memo's structured `stream-json` (Approach C) for the input problem specifically, with justification.
4. The proposal ends in a **recommendation** (primary approach, plus any incremental quick-win), justified against the maturity target and the equal weighting of lag vs autocorrect.
5. The **recommended winner is implemented** within this use case (not deferred to a follow-up), coexisting with the UC-36 conversational-keyboard toggle and preserving a raw-PTY passthrough path for power users.
6. After implementation, **both** symptoms are demonstrably improved: typing does not exhibit the per-keystroke lag for the composed text, and autocorrect no longer produces wrong/garbled replacements in the delivered text.
7. A verification method is defined and exercised, consistent with the project's testing approach (cf. the UC-85 deterministic gate) — covering the input path and the answer-echo invariants.
8. No regression to UC-21 (terminal emulation / agent switcher), UC-23 (IME insets), UC-36 (conversational keyboard / composing flush), or UC-85 (gate); multi-line paste, non-ASCII/emoji input, and mid-stream agent switching still work.

## Potential Pitfalls & Open Questions
- **Assumption** — A local composer materially fixes both symptoms (native text buffer for the IME + one-shot send avoids per-key round-trips) at the cost of the composed line not being raw-terminal; the raw path stays available for power users.
- **Risk** — Implementing the winner in the same use case means the analyst/challenger proposal phase and the developer implementation phase happen in one `develop` run; if the evaluation concludes the best option is large (e.g. full `stream-json`), scope may need to split — the go/no-go in AC#3 is the control point.
- **Risk** — Input changes are the most regression-prone area (UC-36 / UC-23 / UC-85); the UC-85 gate must stay green.
- **Edge case** — Multi-line paste, non-ASCII/emoji, and mid-stream target switching (agent switcher) must behave correctly under whatever input model is chosen.

## Original Description
the cool chat is a bit incomplete. So I wanted to address the main issue in the tmux phone view, the atrocious input between lag and how badly the autocorrect works in tmux by mangling words, doing the replacements wrong. I want proposals in how to address this.

## Clarifications
- Q: What is the deliverable for UC-99?
  A: Proposal + implement the winner (evaluate, recommend, then implement the chosen approach within this use case).
- Q: How much can the input model change? (solution-space constraint)
  A: Both, as ranked options — evaluate in-place Termux/IME fixes AND a decoupled local composer, and let the recommendation choose.
- Q: Which symptom should the recommendation prioritize?
  A: Both equally (lag and autocorrect-mangling weighted equally).
- Q: How should UC-99 relate to the existing RND memo (Approaches B/C)?
  A: Build on / decide it — fold in the stream-json path (Approach C) and reach an explicit go/no-go for the input problem.
