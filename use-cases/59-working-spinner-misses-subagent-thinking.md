# Use Case 59: Sessions-list "working" spinner may not trigger when a team agent / subagent is the one thinking

## Summary
The per-row **"working" spinner** on the sessions list (added in UC-48 to distinguish genuinely idle sessions from actively-working ones) **may fail to show as "working" when the active work is being performed by a team agent or a spawned subagent** rather than the lead/main session. In other words, a session whose lead has delegated to a teammate/subagent — and is therefore legitimately busy — can read as **idle** (no spinner), because the working-state derivation appears to look only at the lead session's own turn-state / transcript tail and does not account for a subagent or team agent currently thinking. This is a suspected-but-unconfirmed defect ("MAY not trigger") that the analyst must reproduce and confirm against a live session that has spawned a team/subagent. The likely locus is the working-state derivation in the UC-37 structured-conversation transcript-tail helper (`container-bin/aisandbox-conversation-tail`, `deriveWorking`) and/or the server turn-state signal feeding the UC-48 spinner — whichever decides "working vs idle" needs to also treat active team-agent / subagent turns as "working". The fix must make the spinner reflect real activity regardless of whether the lead or a delegate is doing the work, without reintroducing the UC-48 false-"working"-while-blocked-on-a-question regression.

## Acceptance Criteria
1. When a session's lead has spawned a team/subagent and that **teammate or subagent is actively working** (thinking / running a turn), the session row shows the **"working" spinner** — it does not read as idle.
2. When the lead itself is working, the spinner still shows (UC-48 behavior unchanged).
3. When the session is genuinely idle — neither the lead nor any teammate/subagent is working (e.g. all are idle, or blocked on a pending question) — the spinner does **not** show, preserving the UC-48 / UC-48-pending-question behavior (no false "working" while blocked awaiting an answer).
4. The working state transitions correctly back to idle once the teammate/subagent turn completes and nothing else is working.
5. The behavior is verified against a **live** session that has spawned a team and/or a subagent — reproduce the miss on the current build first (confirm the suspected defect is real), then demonstrate the fix.
6. No regression to UC-48 (per-row spinner), UC-48 pending-question gap, UC-49 ("?" awaiting-answer indicator), or UC-32 (live status push).
7. CI gates pass: relevant `:server:test` / helper tests (and `:android:test` + `:android:lint` if the client-side spinner logic changes), plus `:server:spotlessCheck` if server Java changes.

## Potential Pitfalls & Open Questions
- **Ambiguity (suspected, unconfirmed)** — The report is "MAY not trigger." The analyst must first confirm the defect is real and isolate WHERE "working" is decided: the transcript-tail `deriveWorking` (which reads the lead transcript), the server live turn-state signal (UC-49), or the Android row rendering. The fix site depends on which signal is blind to delegate activity.
- **Assumption** — A team agent / subagent runs its turns in a separate tmux pane / sub-session whose activity is not reflected in the lead session's transcript tail, so `deriveWorking` over the lead transcript can't see it. Confirm whether the existing UC-49 pane signal (which already surfaces pending questions from the tmux pane) can also surface "a delegate is working".
- **Edge case** — Distinguish "a teammate is working" from "the lead is blocked waiting on a teammate" — both should arguably read as "working" (the session is making progress), but verify against the intended UC-48 semantics rather than guessing.
- **Risk** — Re-introducing the UC-48 pending-question regression: a session blocked on an AskUserQuestion must NOT read as "working" just because a delegate exists. Keep the blocked-on-question case idle/"?" per UC-48/UC-49.
- **Relationship** — Builds directly on UC-48 (spinner), UC-49 (turn-state / pending-question), UC-37 (transcript-tail helper). May share root mechanism with UC-58 (lead transcript does not capture teammate/subagent activity faithfully).

## Original Description
I think the 'thinking' spinner in the server list MAY not trigger when it is a team agent or subagent thinking.

## Clarifications
- Status: **Captured during an autonomous run — pending dispatch.** No dev-team run started yet; the interactive clarification loop was skipped (autonomous). The most material open item — confirming the defect is real and locating the working-state decision (transcript-tail `deriveWorking` vs server turn-state vs client) — is left for the analyst to resolve against a live team/subagent session. When dispatched it gets its own worktree + ledger row off the then-current `main`.
