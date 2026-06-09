# Use Case 42: Conversation view — stop rendering harness-injected user lines as the user's own messages

## Summary

In the Android structured conversation view, invoking a skill correctly shows the `Skill(...)` tool-use bubble but *also* emits a second bubble carrying the skill's `SKILL.md` body, rendered **right-aligned as if the human typed it**. The root cause is server-side: `ConversationEventMapper.mapUser()` (`server/src/main/java/com/aisandbox/server/stream/service/ConversationEventMapper.java:238-258`) classifies any `user` transcript line that is not a `tool_result` as a `TurnStart` (a genuine user prompt → right-aligned via UC-39). It has no case for the `user`-role lines the Claude Code harness *injects* on the user's behalf: skill `SKILL.md` bodies, slash-command wrappers (`<command-name>`/`<command-message>`/`<command-args>`), `<local-command-stdout>` lines, and `isMeta:true` lines. The fix detects this whole class using **structural markers only** (no content-sniffing, so real prompts are never eaten). A skill body that follows an unmatched `Skill(...)` tool-use is **folded into that bubble as tap-to-expand detail**, delivered **on-demand via UC-41's `FetchDetail` round-trip** (the server correlates the injected line to the `Skill` `toolUseId`). Injected lines with **no** host tool-use bubble render as a **collapsed left-aligned system note** (e.g. `Command: /foo`) with their body as tap-to-expand detail — never as a right-aligned prompt. History/backfill renders identically to live. The change is concentrated in the server mapper and the protocol doc; the Android client reuses UC-41 rendering, adding only a non-user "system note" bubble style. This use case builds directly on UC-41 (`ToolUse.primaryText`, `ToolDetail`, `FetchDetail`, and the collapse/detail dialog) and UC-39 (bubble alignment).

## Acceptance Criteria

1. Loading a skill in the conversation view shows **exactly one** bubble for it — the left-aligned `Skill(...)` tool-use bubble — and **no** right-aligned bubble containing the `SKILL.md` body.
2. The skill's `SKILL.md` body is reachable as the **tap-to-expand detail** of that `Skill(...)` bubble, fetched on-demand via `FetchDetail` (the UC-41 mechanism), correlated by the `Skill` `toolUseId`.
3. Slash-command wrapper lines (`<command-name>`/`<command-message>`/`<command-args>`), `<local-command-stdout>` lines, and `isMeta:true` `user` lines are **never** rendered as right-aligned user prompts.
4. An injected `user` line with no corresponding tool-use bubble renders as a **collapsed, left-aligned, non-user system note**, with its body available as tap-to-expand detail.
5. Genuine user prompts (text the human actually submitted) **continue** to render right-aligned via `TurnStart` — no real prompt is suppressed or relabeled.
6. `tool_result` `user` lines continue to map to `ToolResult` exactly as before (no UC-41 regression).
7. Detection uses **structural markers only** — `isMeta:true`, the `<command-name>`/`<command-message>`/`<command-args>` and `<local-command-stdout>` wrappers, and the "skill body following an unmatched `Skill` tool-use" correlation — with **no** content-shape heuristics.
8. The behavior is identical for **live append and for backfilled history / reconnect** — a folded skill load looks the same whether streamed live or replayed.
9. `isSidechain` (subagent/teammate) injected lines fold or note under the correct `source`, not the main pane.
10. Server unit tests cover each injected-line kind → expected frame (folded skill detail / system note / unchanged real prompt / unchanged tool_result). Android instrumented tests assert that no spurious right-aligned bubble appears for a skill load and that the system-note bubble renders left-aligned.
11. `server/CONVERSATION_PROTOCOL.md` is updated to document how injected `user` lines are classified and rendered (folded detail vs. system note).

## Potential Pitfalls & Open Questions

- **Edge case** — A real user prompt containing literal `<command-name>`-like text must not be misclassified; the marker matcher should require the harness's exact structural placement (whole-line/whole-content wrapper), not a substring match.
- **Edge case** — `isSidechain` lines that load skills must fold/note under the correct `source` (`subagent:<agentId>`), not the main pane.
- **Assumption** — The injected `SKILL.md` body arrives as a `user` line *after* the assistant's `Skill` tool-use, with **no** `tool_use_id` linking the two, so the server must correlate them via "most-recent unmatched `Skill` call on the same `source`." To be validated against a live transcript by the dev-team.
- **Risk** — Adding a new server→client frame (system note) touches the `ConversationServerMessage` sealed union and its serializer; the analyst/challenger should decide whether to reuse the existing `ToolUse`/`ToolDetail` shapes or add a dedicated frame. Reuse keeps the wire surface smaller; a dedicated frame is clearer to render.
- **Risk** — Over-suppression that hides a *real* prompt would be worse than the current cosmetic bug; this is why detection is constrained to structural markers (AC7) and real prompts are explicitly preserved (AC5).

## Original Description

In the Android structured conversation view, when a skill loads you correctly see the `Skill(...)` tool-use bubble, but you ALSO see a second bubble containing the skill's SKILL.md body (partial at least), rendered RIGHT-ALIGNED as if the human typed it — i.e. as the user's own message. Root cause is server-side: `ConversationEventMapper.mapUser()` (server/src/main/java/com/aisandbox/server/stream/service/ConversationEventMapper.java, ~lines 238-258) classifies every `user` transcript line that is NOT a `tool_result` as a `TurnStart` (= a real user prompt → right-aligned via UC-39 alignment). It has no case for harness-injected `user` lines. Claude Code injects the skill's SKILL.md body as a `user`-role line on a Skill invocation; the same mechanism produces slash-command wrappers (`<command-name>`/`<command-message>`/`<command-args>`), `<local-command-stdout>` lines, and `isMeta:true` lines. All currently fall through to TurnStart and mis-render as the user's own message.

Desired behavior (decided with user):
- Render decision: FOLD the injected body into the existing left-aligned `Skill(...)` tool-use bubble as its tap-to-expand DETAIL, reusing UC-41's collapse/detail infrastructure (collapsed one-line summary + on-demand detail dialog). Do not emit a separate right-aligned bubble for it.
- Scope: handle the WHOLE class of harness-injected user lines, not just Skill bodies — Skill SKILL.md bodies, slash-command wrapper lines, `<local-command-stdout>`, and `isMeta:true` lines.
- Open nuance flagged for analyst+challenger: "fold into the Skill bubble" only has a host bubble when there's a corresponding `Skill(...)` tool_use to attach to. Slash-command / isMeta / local-command-stdout lines frequently have NO corresponding tool-use bubble — those need a fallback rendering.

Relevant code already identified: server `ConversationEventMapper.mapUser` + the `TurnStart` frame in `ConversationServerMessage.java`; UC-41 already added `ToolUse.primaryText`, `ToolDetail`, `FetchDetail` and the Android collapse/detail dialog in `ConversationScreen.kt` / `ConversationController.kt` / `ConversationModel.kt`. This builds directly on UC-41.

## Clarifications

- Q: How should a skill/command-injected user line render instead of a right-aligned "your" message?
  A: Fold the injected body into the existing left-aligned `Skill(...)` tool-use bubble as its tap-to-expand detail, reusing UC-41's collapse/detail infrastructure. Do not emit a separate right-aligned bubble.
- Q: What scope should the fix cover?
  A: The whole class of harness-injected user lines — Skill `SKILL.md` bodies, slash-command wrappers, `<local-command-stdout>`, and `isMeta:true` lines.
- Q: For injected user lines that have NO host Skill bubble to fold into (slash-command wrappers, isMeta, `<local-command-stdout>`), what's the fallback?
  A: Render a collapsed left-aligned, non-user system note (e.g. `Command: /foo`) with the body as tap-to-expand detail.
- Q: How should the server detect injected (non-prompt) user lines?
  A: Structural markers only — `isMeta:true`, the `<command-*>`/`<local-command-stdout>` wrappers, and a Skill body following an unmatched `Skill` tool-use. No content-shape heuristics.
- Q: How is the folded `SKILL.md` body delivered to the client as the Skill bubble's detail?
  A: On-demand via UC-41's `FetchDetail` round-trip — the body is re-read from the transcript when the user taps to expand; the injected line is correlated to the `Skill` `toolUseId` server-side.
