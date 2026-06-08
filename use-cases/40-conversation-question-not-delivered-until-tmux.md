# Use Case 40: Conversation view — assistant message and its `AskUserQuestion` never appear until you answer elsewhere

## Summary
In the Android structured-conversation (non-tmux) view, when the live `claude` session asks via `AskUserQuestion`, neither the question sheet nor the assistant message that carries the question ever appears — the view is stuck and the user must long-press into the tmux view to see and answer it, defeating the structured view's purpose. The assistant `text` block and the `AskUserQuestion` `tool_use` are emitted by Claude Code in the **same transcript line** (one `message.content[]` array), so both disappear together. The client mapping is correct (`ConversationEventMapper` → `[AssistantText, Question]`; `ConversationController` renders the message and raises `pendingSheet`), so the line is not reaching the client. Confirmed behavior: after answering in tmux, the missing message/question **appear retroactively** once the next turn is written — proving the line is buffered, not lost. Root cause is the in-container tail helper (`container-bin/aisandbox-conversation-tail`): `readNewLines` only emits **newline-terminated** lines, buffering any trailing partial in `residual` with no idle flush (POLL_MS=300, no timeout drain). The final line written before the session **blocks awaiting the answer** has no trailing newline yet, so it is stranded in `residual` until the next write supplies the newline. The fix delivers the blocking assistant+question line **live** (while the session is still blocked), covers the equivalent plan-mode `ExitPlanMode` prompt, and adds a bounded safety-net so a paused writer can never again strand a complete line.

## Acceptance Criteria
1. When the live session asks an `AskUserQuestion`, the question sheet appears in the structured-conversation view with **no user action in any other view**, while the session is still blocked on the answer.
2. The accompanying assistant `text` block renders **at the same time** as the question, not retroactively after answering.
3. Answering in the structured view resolves the question in the live session and the turn proceeds, with no need to open tmux at any point.
4. Holds whether the assistant message bundles a `text` block + the `AskUserQuestion` `tool_use` in the **same** transcript line, or the question is the only block.
5. The plan-mode `ExitPlanMode` approval prompt — same "assistant message that then blocks on input" mechanism — is delivered live and answerable in the structured view under the same conditions.
6. A bounded **idle/safety-net flush** in the helper emits a stranded trailing line **only** once it is a complete, parseable JSON object; it never emits a half-written line and never double-emits when the real newline later arrives (correct `offset`/`residual` accounting; survives `ConversationController` key-dedupe).
7. Subagent/teammate (`isSidechain`) questions in `subagents/agent-*.jsonl` get the same live delivery, not just the main transcript.
8. No regression to normal turns: assistant text, tool-use/result, thinking, turn-end stream and dedupe correctly (UC-37 AC3–AC6, AC22 backfill/reconnect); the change does not alter ordering or introduce duplicates.
9. A regression test reproduces the stuck state — a transcript whose final line is an unterminated assistant message carrying `AskUserQuestion`, writer paused — and verifies the line is relayed live, then that a later real newline does not double-emit it.

## Potential Pitfalls & Open Questions
- **Risk** — The idle-flush must gate on **complete JSON** (e.g. parse-attempt the residual), not on a time threshold alone; a partial write that happens to contain a balanced object prefix must not be emitted early. The offset must not advance past an emitted-from-residual line, or the eventual newline will re-emit it (rely on `ConversationController`'s key-dedupe as a second line of defense, but don't depend on it for correctness).
- **Edge case** — Distinguish "writer paused mid-line" (don't flush) from "writer finished a line but wrote no newline and is now blocking on input" (do flush). A short bounded idle (e.g. a few × POLL_MS) plus a successful JSON parse is the discriminator to validate in the spike.
- **Open question (verification, low risk)** — A live byte-level look at the `.jsonl` while a question is pending should confirm the missing trailing newline; if instead `claude` genuinely defers writing the line until the tool resolves, the fix moves from helper idle-flush to a different signal path. The "appears retroactively" evidence makes the buffering explanation the strong favorite.

## Original Description
Bugfix now. When a question is asked, the question is not shown. Actually, the whole message before the question is not shown. So I get stuck and need to get to the tmux to continue.

## Clarifications
- Q: After switching to tmux, answering, then returning to the conversation view — does the previously-missing message/question appear (retroactively) or is it gone for good?
  A: Appears retroactively (after the next turn is written) — confirms delayed/buffered delivery, not a dropped line.
- Q: During the stuck state, what does the conversation view show (spinner vs idle)?
  A: Not sure — left as an observable requirement (AC5/turn-lifecycle), to be confirmed in the live spike.
- Q: Should the fix also cover the plan-mode (ExitPlanMode) approval prompt?
  A: Yes — cover both AskUserQuestion and plan-mode approval (same blocking mechanism).
- Q: How broad should the fix be?
  A: Root-cause fix (live delivery of the blocking line) plus a bounded safety-net idle-flush so a paused writer can never strand a complete line again.
