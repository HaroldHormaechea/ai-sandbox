# Use Case 65: Conversation menu — "Clear" action (send /clear and wipe the in-app transcript)

## Summary
The conversation view's top-bar overflow menu (`ConversationScreen.kt`, the `DropdownMenu` that currently holds only **Disconnect**) gains a new **Clear** item above Disconnect. Choosing it sends the `/clear` slash command to the session's Claude over the existing conversation WebSocket (the same `composer-input` path used for normal messages, `ConversationClient.sendComposer`), and simultaneously empties the locally rendered transcript in the Android UI (the `ConversationModel`/`ConversationController` item list) so the screen matches Claude's freshly-cleared context. After clearing, the session stays connected and usable: the composer remains enabled and the user can immediately send a new message and receive answers as normal. Unlike **Disconnect**, **Clear** does not navigate back or tear down the stream — it only resets the conversation in place.

## Acceptance Criteria
1. The conversation overflow menu shows a **Clear** item (positioned above **Disconnect**); both remain reachable.
2. Tapping **Clear** sends `/clear` to the session's Claude through the conversation channel (verifiable: Claude's context is reset — a follow-up question that depends on earlier context is answered as if the prior turns are gone).
3. Tapping **Clear** empties the in-app transcript: all prior user/assistant/tool/system items disappear from the conversation list, leaving an empty (or "context cleared") view.
4. Any pending AskUserQuestion sheet visible at the time of Clear is dismissed and does not reappear after the clear completes.
5. After Clear, the composer is enabled and the user can send a new message; the new message and Claude's response render normally in the now-empty transcript.
6. Clear does **not** navigate away from the conversation and does **not** disconnect the stream (the connection banner does not go to a disconnected/retry state as a result of Clear).
7. The menu closes after Clear is chosen.
8. QA verifies the end-to-end behaviour against a live server + emulator session (menu → Clear → transcript empty → send new message → response received).

## Potential Pitfalls & Open Questions
- **Assumption** — `/clear` is delivered via the normal `composer-input` text frame (the harness/tmux Claude interprets the slash command), not a new dedicated control frame. If the harness echoes `/clear` back as a `<command-name>` system note, the local-wipe step must run *after* (or independently of) that echo so the transcript ends empty, not showing a stray system note.
- **Edge case** — Local optimistic echo: `submitComposer` normally adds an optimistic user bubble. The Clear path must NOT leave a `/clear` user bubble behind in the wiped transcript.
- **Edge case** — Timing/races: server backfill frames (`backfill-start`/`backfill-end`) or in-flight `assistant-text` for the pre-clear turn could repopulate the transcript right after a local wipe. The wipe must be sequenced so post-clear server frames don't resurrect old content (e.g. wipe on confirmation/turn boundary, or ignore frames belonging to the pre-clear epoch).
- **Edge case** — Selected target: if a subagent/teammate target is selected in the `AgentSwitcherBar`, decide whether Clear applies to the main session only or the selected target. Resolved decision: Clear targets the **currently selected target's** pane (consistent with existing input routing); document if implementation finds /clear is only meaningful for the main session.
- **Risk** — `/clear` semantics in the embedded Claude harness must actually reset context; if the harness maps `/clear` to something else, QA must confirm real context reset, not just a visual wipe.

## Original Description
"I want a new button, in the menu inside of chat sessions that shows disconnect, that says clear. It should send the /clear command to Claude and clean the whole conversation also in the android UI. It then should be possible to continue writing and receiving answers from Claude."

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy). Decisions baked into the criteria above rather than asked interactively:
- Delivery of `/clear` reuses the existing composer-input WebSocket path (no new server frame unless the dev-team finds it necessary).
- Clear is in-place: it does not disconnect or navigate back (that distinguishes it from Disconnect).
- Local transcript is wiped in the UI in addition to resetting Claude's context.
