# Use Case 41: Collapse tool-use / skill bubbles to a one-line summary, tap to expand full command + output

## Summary
In the structured-conversation view, render tool and skill activity as compact, type-aware **one-line bubbles** instead of the current 600-char `MetaLine` dump, and merge each `tool_use` with its paired `tool_result` (by `toolUseId`) into a **single** collapsed row. A skill invocation shows "Skill loaded `<name>`"; a shell command shows "Command used: `<~20-char snippet>`…"; any other tool shows "`<tool>`: `<~20-char snippet>`…". Tapping the row opens a **popup/dialog** that shows the full, untruncated tool input (e.g. the complete command) **and** its full output. Because the server currently truncates everything to 600 chars (`bound(...)`), the dialog gets its content via an **on-demand fetch** keyed by `toolUseId`/`uuid` (the server re-reads the transcript line and returns the full input + result), keeping the live stream and backfill lean. This touches the conversation renderer (`ConversationScreen.kt`: `MetaLine` → a collapsible merged tool/skill row + a detail dialog), the controller/model (merge tool-use+result, hold the metadata needed to fetch detail), and the server (a new fetch endpoint or control frame that returns untruncated input+output by id, plus `Skill`/`Bash` type awareness). Assistant text, thinking, question, and plan rendering are unaffected.

## Acceptance Criteria
1. A `Skill` tool invocation renders as a single-line bubble "Skill loaded `<skill name>`" (name parsed from the tool input), not a raw input dump.
2. A shell/`Bash` command renders as a single-line bubble "Command used: `<snippet>`…" where the snippet is ~20 characters of the command plus an ellipsis when truncated.
3. Any other tool renders as a single-line bubble "`<tool>`: `<snippet>`…" with the same ~20-char budget.
4. Each tool call is **one** collapsed row: the `tool_use` and its `tool_result` (matched by `toolUseId`) are merged into a single bubble, not two separate rows.
5. Tapping the bubble opens a popup/dialog showing the **full, untruncated** tool input and the full output, fetched **on demand** by `toolUseId`/`uuid` from the server.
6. The fetch returns content beyond the 600-char streaming summary limit; the dialog is scrollable with selectable text and a sane max height for large outputs.
7. An error result is visually distinguished in both the collapsed row and the dialog (existing `isError`).
8. A tool whose result hasn't arrived yet shows the collapsed row and an "awaiting result" state; the merged row/dialog updates when the `tool_result` arrives.
9. The on-demand fetch degrades cleanly: a missing/expired/unresolvable id shows a clear "detail unavailable" state in the dialog rather than hanging or crashing.
10. Collapsed bubbles preserve `isSidechain`/subagent attribution, and ordering/dedupe (UC-37 AC4/AC6, AC22) is unchanged.
11. Assistant text, thinking, question, and plan rendering are unchanged.

## Potential Pitfalls & Open Questions
- **Edge case** — On-demand fetch must resolve the full content by `toolUseId`/`uuid` against the (possibly rotated/restarted) transcript; reuse the helper's resolution logic. A tool whose line has scrolled out of the retained window may be unfetchable → drives AC9's "detail unavailable".
- **Assumption** — Skill loads appear as a `Skill` tool_use with the skill name in its input; to be confirmed against this project's real transcripts (could surface differently). The "Command used" label assumes the `Bash` tool; map both, fall back to the generic "`<tool>`:" form for the rest.
- **Risk** — Merging tool_use + tool_result into one row changes the item model/dedupe; ensure the merge keys on `toolUseId` and survives backfill where a pair is split across the backfill boundary (result present, use absent or vice versa).
- **Edge case** — Very large outputs on fetch: the endpoint should itself bound to a generous cap (e.g. stream/paginate or cap at a few hundred KB) so the dialog can't OOM the device.

## Original Description
I want the "tool use" and skill loaded bubbles to be a summary: Skill loaded X; Command used: ... ( maybe 10 characters plus maybe ellipsis). Clicking in it should open a new pop up with the full command and output.

## Clarifications
- Q: How should the full command + output reach the popup, given the server currently truncates to 600 chars?
  A: On-demand fetch by toolUseId/uuid when the popup opens (server re-reads the transcript); keep the live stream lean.
- Q: Should the two existing rows (tool-use + tool-result) merge into ONE collapsed bubble?
  A: Yes — merge into a single row per tool call; the result folds into the popup.
- Q: Snippet length for the collapsed command/tool label?
  A: ~20 chars + ellipsis (the originally-suggested ~10 was deemed likely too short to identify the command).
- Q: Which item kinds get this collapsed-summary + popup treatment?
  A: Both Skill loads and Tool use (tool results fold into the merged popup). Thinking blocks are unchanged.
