# Use Case 60: Show spawned subagents as pills at the top of the chat screen, like team agents

## Summary
The conversation/chat screen renders **team agents as pills at the top** (the agent switcher introduced around UC-21), letting the user see and switch between the lead and its teammates. However, **spawned subagents** (agents created via the Agent/Task tool that run in their own sub-session rather than as named tmux-pane team members) **do not appear as pills**. This use case makes subagents show up as pills at the top of the chat screen **with the same characteristics as team-agent pills** — same visual treatment (label/name, per-agent colour), same placement, and the same interaction behavior (tap to focus/switch to that agent's view, live add/remove as subagents spawn and finish). The goal is parity: from the user's perspective a subagent doing work should be as visible and navigable as a formal team agent. The likely work is in the Android chat screen's agent-pill/switcher component plus whatever signal enumerates active agents for a session (the same mechanism that currently lists team agents — possibly the tmux-window/pane enumeration or a server-side agent roster); the analyst must determine how team-agent pills are sourced today and extend that source to include subagents.

## Acceptance Criteria
1. When a session has one or more active **subagents**, each appears as a **pill at the top of the chat screen**, in the same location and layout as the existing team-agent pills.
2. The subagent pills have the **same characteristics** as team-agent pills: name/label, per-agent colour, and any status affordance (e.g. working/idle) that team-agent pills carry.
3. Tapping a subagent pill behaves like tapping a team-agent pill — it focuses/switches to that subagent's view/pane (or the equivalent navigation team-agent pills provide).
4. Pills update **live** as subagents are spawned and as they finish/exit (a finished subagent's pill is removed or marked per the team-agent convention), mirroring how team-agent pills appear/disappear.
5. The lead/main agent and existing team-agent pills are unaffected — subagent pills are additive and visually consistent, not a separate or divergent UI.
6. If subagents and team agents are simultaneously present, all are shown consistently together without duplication or mislabeling.
7. No regression to the UC-21 agent-team switcher, the terminal pane behavior (UC-24/UC-33 zoom/split fixes), or single-agent sessions (which show no extra pills).
8. CI gates pass: `:android:test` + `:android:lint` (and `:server:test` + `:server:spotlessCheck` if a server-side agent roster/signal changes).

## Potential Pitfalls & Open Questions
- **Ambiguity** — How are team-agent pills sourced today? (tmux window/pane enumeration, a server agent roster, or the team config?) The analyst must find this before deciding how to surface subagents — subagents may not be tmux-pane-backed the way team members are, so the enumeration source may need extending rather than reused as-is.
- **Assumption** — "Subagent" here means an agent spawned via the Agent/Task tool that runs in its own sub-session, distinct from a named `TeamCreate` teammate. Confirm the exact distinction in the running model and that both should be represented identically (the user asked for the same characteristics).
- **Edge case** — Subagents can be short-lived (spawn, do a turn, exit). Pills must appear/disappear cleanly without flicker or stale entries; decide whether a just-finished subagent lingers briefly or is removed immediately, matching the team-agent convention.
- **Edge case** — Nested or many simultaneous subagents — ensure the pill row handles overflow the same way the team-agent switcher does (scroll/wrap), with no layout break.
- **Risk** — Switching/tap behavior: if subagents are not tmux-pane-backed, "switch to this agent" may not have a pane to focus; the analyst must define what tapping does for a subagent (e.g. show its sub-session transcript) so AC3 is well-defined rather than a dead pill.
- **Relationship** — Closely related to UC-21 (agent-team switcher), UC-24/UC-33 (terminal pane zoom/split), and conceptually to UC-58/UC-59 (faithful representation of subagent/team-agent activity in the client).

## Original Description
Subagents should, as team agents, show as pills in the top of the chat screen as team agents do, with the same characteristics.

## Clarifications
- Status: **Captured during an autonomous run — pending dispatch.** No dev-team run started yet; the interactive clarification loop was skipped (autonomous). The open items — how team-agent pills are sourced today, the subagent-vs-team-agent distinction, and what tapping a non-pane-backed subagent pill should do — are left for the analyst to resolve against the code. When dispatched it gets its own worktree + ledger row off the then-current `main`.
