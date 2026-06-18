# Use Case 91: Conversation bleed + missing agent pills on session→session switch (UC-86 fix incomplete, android-v0.4.17)

## Summary
Despite UC-86 (PR #107 — the target-switch suppress guard, released in `android-v0.4.17`), switching from one session's chat to another still misbehaves on the latest app + server, and the failure is **sticky** (stays wrong until forced). Two coupled symptoms: **(1)** the second session's chat shows the **OLD** (first-viewed) session's transcript instead of its own; **(2)** if the session you switch *to* has subagents, its **agent pills do not appear** — the user must drop to tmux to reach those agents. Crucially, if the user **lands first** on a session that has agents, the pills render and correctly show each agent's messages — so the agent-level path itself works; it is the **session-switch path** that fails to recompute per-session view state. This strongly indicates the whole per-session conversation state (transcript window **and** agent-pill enumeration) is captured/keyed on first entry and not re-derived when switching to a different session. UC-91 re-opens the incomplete UC-86 fix: reproduce on the current build first (repro-first, per UC-86), find why the session-switch path doesn't re-key, and fix it so switching sessions always renders the target session's transcript and its agent pills — without regressing UC-78/79/80/81 chat rendering/scroll, UC-58/59 attribution, UC-21/60 pills, or UC-88 reconnect.

## Acceptance Criteria
1. **Repro-first:** on the current build (≥`android-v0.4.17`), demonstrate *before* any fix that switching session A→B shows A's transcript (sticky), and that switching to an agent-bearing session B shows no pills.
2. After the fix, switching session A→B shows **B's transcript only** — no carryover from A, and it does not require any manual refresh/restart.
3. After switching to a session that has subagents/team agents, **that session's agent pills appear** (no need to drop to tmux), and tapping them shows the correct agent's messages.
4. Behavior is **independent of entry order**: whether the user lands first on a no-agent session then switches to an agent session, or vice-versa, the pills and transcript always reflect the **currently-viewed** session.
5. The failure is **not sticky** post-fix: per-target state re-keys on every switch (rapid back-and-forth and away-then-back never leave stale transcript, stale pill set, or stale scroll window).
6. Switching target re-anchors the UC-79 infinite-scroll window to the correct conversation's segment.
7. A **regression test** reproduces the session→session bleed (and, where feasible at seam level, the missing-pills-on-switch case) and would have failed before the fix. Align with `ConversationAnchorInstrumentationTest.targetSwitch_*`, asserting **transcript identity and pill set**, not just scroll position.
8. No regression to single-agent sessions, chat rendering/scroll (UC-78/79/80/81), attribution/working-state (UC-58/59), pills (UC-21/60), or wedged-reconnect (UC-88).
9. CI gates pass: `:android:test` + `:android:lint` (and `:server:*` if any server/Node helper changes).

## Potential Pitfalls & Open Questions
- **Assumption** — the agent-level path works (pills correct when landed-on first); the defect is the session-switch path failing to recompute per-session state. Analyst confirms during repro.
- **Risk** — `targetSwitch_reAnchorsToNewStreamBottom` passed yet the bug shipped: it likely asserts scroll anchoring, not transcript/pill identity. The new test must assert *what's displayed*, not just scroll position.
- **Risk** — pills missing on switch but present on first-land suggests pill enumeration is derived once and cached against the wrong key; a transcript-only fix would leave the pills broken. Both must be re-keyed together.
- **Risk** — a force-reset on selection could mask races under fast switching; fix the keying/scoping, not the symptom.
- **Open question (likely settled here)** — key per-session view state by a `(session, agent)` composite vs reset-and-reload on switch. The sticky + missing-pills evidence favors a single re-keyed state handle on session switch; the analyst defines the contract so AC2–5 are testable.

## Original Description
In the Android app, when you switch from a chat of a session to another it shows the OLD CONVERSATION. It should have been fixed in a recent UC (UC-86) and released in android-v0.4.17, but it still happens — tested with the most recent server and app versions. Additional detail: it is session→session. When the second session has subagents, it does not show the pills — you have to go to tmux. But if you land first on a session with agents, it shows the pills and they do show the correct agent messages. The wrong conversation stays wrong (sticky).

## Clarifications
- Q: Which switch level still shows the OLD conversation on the latest build?
  A: Session→session. When the second session has subagents it does not show the pills (you have to go to tmux). But if you land first on a session with agents, it shows the pills and they do show the correct agent messages.
- Q: Does the stale conversation eventually correct itself, or stay wrong?
  A: Stays wrong (sticky).
- Q: Run mode — autonomous through to merge + release?
  A: Autonomous to merge + release.
