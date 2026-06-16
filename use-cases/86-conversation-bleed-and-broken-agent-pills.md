# Use Case 86: Conversation/agent-pill view bleed — all windows show the same conversation; agent pills don't navigate (suspected android-v0.4.15 regression)

## Summary
Following the 2026-06-15 dev batch (UC-58/59/60 agent attribution & pill work, plus UC-78/79/80/81 chat-rendering changes, released as `android-v0.4.15`), the Android conversation view has regressed in two linked ways. **(1) View bleed:** every chat/conversation window shows the *same* conversation regardless of which session **or** which agent is actually selected — opening a different session's conversation, or switching agents within a team-lead session, displays the same transcript rather than the one belonging to the selected target. The user confirms the bleed is at **both** levels (session-to-session and agent-to-agent). **(2) Pill navigation broken:** the agent pills at the top of the chat screen (team-agent pills from UC-21 and subagent pills from UC-60, carrying the UC-58/59 attribution) no longer switch the view when tapped — tapping a pill does not focus/show that agent's conversation as it should. The leading hypothesis (recorded from a prior analysis session) is that this is coupled to the **UC-79 infinite-scroll / lazy-loading** change: the dynamic loader may not be **recovering the expected conversation segment** for the newly-selected session/agent — i.e. the windowing/paging state is shared or mis-keyed across targets, so a selection change does not re-anchor to the correct conversation's message window. The most probable regression sources are therefore UC-79 (infinite scroll segment recovery), UC-60 (subagent pills + tap-to-focus / switcher enumeration), and UC-58/59 (attribution & working-state in the UC-37 transcript-tail path). The analyst must reproduce both symptoms on `android-v0.4.15`, then bisect to confirm the culprit and whether one root cause (a mis-scoped "selected target" / conversation-window handle) drives both.

## Acceptance Criteria
1. Opening the conversation for a given session shows **that session's** transcript only — switching to a different session's conversation shows a **different**, correct transcript (no cross-session bleed).
2. Within a team-lead/multi-agent session, the conversation view shows the conversation/transcript for the **currently-selected agent**, and selecting a different agent changes the displayed content accordingly (no cross-agent bleed).
3. Tapping a **team-agent pill** focuses/switches the view to that team agent (per the UC-21 switcher behavior), updating the displayed conversation to that agent's.
4. Tapping a **subagent pill** focuses/switches to that subagent's view (per UC-60), updating the displayed content; a non-pane-backed subagent behaves per the UC-60-defined tap action.
5. Switching target (session or agent) **re-anchors the infinite-scroll window** (UC-79) to the correct conversation's segment: the initial/loaded message window belongs to the selected target, not a stale window carried over from the previously-viewed conversation.
6. The selected-target state is **scoped per session/agent** — navigating away and back, or rapidly switching between two sessions/agents, never leaves a stale or shared transcript (or a stale scroll window) displayed.
7. Speaker attribution (UC-58: teammate vs user vs assistant) and the working/idle signals (UC-59) remain correct **for the correct conversation** once the right one is displayed (no re-regression of those fixes).
8. The defect is **reproduced on the current build (`android-v0.4.15`) first** — both symptoms demonstrated — then the fix is demonstrated against the same repro; if the two symptoms share one root cause, that is stated and both are fixed together.
9. No regression to single-agent sessions (one conversation, no extra pills), to chat rendering & scrolling (UC-78/79/80/81), or to the UC-37/40/47/49/50 conversation paths.
10. CI gates pass: `:android:test` + `:android:lint`, and `:server:test` + `:server:spotlessCheck` if any server/Node helper (e.g. `aisandbox-conversation-tail`) changes.

## Potential Pitfalls & Open Questions
- **Assumption** — Coupling to UC-79 infinite scroll (segment-recovery failure on target switch) is the leading hypothesis from a prior session, **not yet confirmed**. The analyst should validate it during repro and remain open to UC-60 (pill/switcher) or a shared "selected target" handle being the actual root cause.
- **Edge case** — Repro matrix: confirm whether the bleed/dead-pill failure occurs in single-agent, team-only, subagent-only, and mixed (team + subagent) sessions. UC-60 AC6 (simultaneous team agents + subagents) is the most likely to expose mis-keying.
- **Risk** — A naive "force-refresh / reset scroll on selection" fix could mask the bleed without correcting the underlying mis-scoped windowing state, leaving stale-content races under fast switching. The fix must correct the keying/scoping (AC6), not paper over it.
- **Risk** — Over-correction could re-break UC-58/59 attribution or UC-78–81 loading/scrolling once the correct conversation is wired back in; AC7/AC9 guard this.
- **Open question** — If the root cause is confirmed in UC-79's paging state, decide whether the conversation window should be keyed by `(session, agent)` composite or reset-and-reload on every selection change; the analyst defines the contract so AC5/AC6 are testable.

## Original Description
There was another use case that may not have made it into a UC, because the session died before. It was about all the chat windows showing the same conversation, and agent pills to navigate across agents/team agents not working properly. A possible regression of the dev batch executed yesterday (2026-06-15: UC-58/59/60/78/79/80/81/82/83, released as android-v0.4.15).

## Clarifications
- Q: What is the scope of the "all windows show the same conversation" bleed?
  A: **Both** — across sessions (different sessions show the same transcript) and across agents within a session (switching agents doesn't change the view).
- Q: Are the two symptoms (view bleed + non-working agent pills) one root cause or two separate regressions?
  A: When analyzed in another session, it was said to **possibly relate to the UC-79 "infinite scroll"** — it may not be properly recovering the expected conversation segment for the selected target.
- Q: Which build first showed this?
  A: **Post-batch `android-v0.4.15`** (the release right after the 2026-06-15 batch).
- Q: Priority relative to UC-85?
  A: Save the UC, push it to `main`, then dispatch it **immediately and autonomously** — and **spawn QA to validate/reproduce the scenario before spawning the dev team** (repro-first).
