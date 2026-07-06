# Pane-signal drift fixtures (UC-97 C1)

Real `tmux capture-pane -p` output captured from a **live Claude Code 2.1.169**
session (the pinned version — `InputInjectionService.PINNED_CLAUDE_VERSION` /
`SandboxDockerfile` `ARG CLAUDE_CODE_VERSION`), blocked on a live, awaiting-answer
prompt. These are the higher-fidelity counterpart to the hand-authored pane
constants in `container-bin/aisandbox-conversation-tail.test.js`: because they are
**verbatim bytes from the real pinned TUI**, the assertions that read them
(`aisandbox-conversation-tail.test.js`) turn **red** if a future Claude Code
version restyles the pending-question / plan-approval chrome out from under
`PENDING_QUESTION_CHROME` / `PLAN_APPROVAL_CHROME` — i.e. they would have caught
the UC-50/UC-97 drift (AC10). LLM-free and CI-runnable.

| File | Prompt shape | Expected detection |
|------|--------------|--------------------|
| `single-question.2.1.169.pane.txt` | single-select `AskUserQuestion` (Red/Blue) | `looksLikePendingAskUserQuestion` = **true** (affordance `Type something`/`Chat about this` + `❯`) |
| `multi-question.2.1.169.pane.txt` | multi-question `AskUserQuestion` wizard (Favorite color / Favorite size) | `looksLikePendingAskUserQuestion` = **true** (affordance + wizard tab strip `☐ … ✔ Submit`) |
| `exitplanmode.2.1.169.pane.txt` | `ExitPlanMode` plan-approval prompt | `looksLikePendingPlanApproval` = **true**; `looksLikePendingAskUserQuestion` = **false** (plan approval is explicitly excluded) |

## Capture method

Captured live (UC-97 A0 repro): a real ai-sandbox container running Claude Code
2.1.169 in a `tmux` session named `main`, authenticated with the operator OAuth
token, driven to raise each prompt, then `tmux capture-pane -p -t main` at the
wedge. Verified at capture time:

- single / multi → the streaming `aisandbox-conversation-tail --pane 0` helper
  emitted `__ctrl__\tpending-question\t<json>` with the answerable payload;
- `--scan-pending` (transcript classifier) reported `pending-activity` at the same
  wedge — confirming the documented UC-48/49 transcript-blindness (a blocking
  `AskUserQuestion` is not persisted to the transcript while Claude waits), which is
  why detection must read the **pane**, not the transcript.

## Refresh (on a deliberate pin bump — AC9)

When `ARG CLAUDE_CODE_VERSION` is bumped, re-capture these three panes from the new
version and re-run `aisandbox-conversation-tail.test.js`. If the chrome moved, the
predicates (and possibly `PENDING_QUESTION_CHROME` / `PLAN_APPROVAL_CHROME`) must be
retuned for the new build before the bump ships — the fixtures make that drift loud.
