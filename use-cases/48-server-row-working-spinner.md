# Use Case 48: Per-row working spinner to spot idle sessions at a glance

## Summary
The sessions list gives no at-a-glance signal of whether Claude is actively working in a given session. The container `state` (`running`/`starting`/…) only reflects lifecycle, and the only activity hint is the tmux title heuristic (`claude.exe` vs `(idle)`). The conversation view tracks real activity via `TurnPhase` (`IDLE`/`WORKING`/`THINKING`, driven by `turn-start`/`thinking`/`turn-end` frames in `ConversationController`), but that signal lives only inside an open conversation, not on the list. This use case adds a **spinner / working indicator to each server row** that animates when Claude is actively working in that session and is absent when the session is genuinely idle — so a user scanning the list can immediately distinguish busy sessions from truly idle ones. This requires a session-level "working" signal exposed by the server in the sessions payload (since the list has no per-session `TurnPhase` today), the client rendering an animated indicator in the row's status area when that signal is active, and the indicator staying in sync with the live status push (UC-32).

## Acceptance Criteria
1. When Claude is actively working in a session, that session's row shows an animated spinner/working indicator in its status area.
2. When the session is idle (no active turn / Claude waiting), the row shows no spinner — making genuinely idle sessions visually distinct at a glance.
3. The working/idle signal is provided by the server in the sessions payload (a per-session field), not inferred solely from the tmux-title string client-side.
4. The indicator updates live via the existing status push (UC-32) so transitions between working and idle appear without a manual refresh, with reasonable latency.
5. The spinner is consistent with the app's existing working affordances (e.g. the conversation view's `SpinnerRow`/`TurnPhase` treatment) rather than introducing a clashing animation style.
6. The working indicator coexists cleanly in the row's status section with the lifecycle `StatusPill`, the tmux title, and (UC-46) the conversation name, without layout breakage.
7. A stopped/paused/terminating session never shows the working spinner regardless of its last-known activity.

## Potential Pitfalls & Open Questions
- **Missing input** — No session-level "working" signal exists today; `TurnPhase` is conversation-view-only. The server must derive a per-session activity state. Source options: reuse the conversation protocol's turn lifecycle server-side, infer from tmux title (`claude.exe`/foreground command) — less reliable, or track an explicit "turn in progress" flag. The chosen source is the central open question and overlaps UC-46's name source.
- **Edge case** — Flicker/debounce: rapid turn-start/turn-end cycles (or a brief idle between thinking and tool use) could make the spinner strobe. The signal likely needs debouncing/hysteresis so short gaps don't flash the indicator off.
- **Risk** — Polling cost: deriving activity per session synchronously in `GET /v1/sessions` would add latency (same concern as UC-46); a pushed/cached signal tied to UC-32's mechanism is preferable.
- **Edge case** — A session can be "working" with no app-side conversation open at all (the user is driving it from tmux/desktop). The activity signal must reflect actual session activity, not just whether an app conversation stream is attached.
- **Ambiguity** — Whether "working" should also cover `THINKING` distinctly (e.g. a different label/animation) as the conversation view does, or collapse both into a single spinner on the list. The summary assumes a single binary working/idle indicator for the list.
- **Risk** — Battery/perf on the client: many simultaneously animating spinners in a long list should use a shared/efficient animation (as `StatusPill`'s pulse does) to avoid jank.

## Original Description
Add functionality so in the mobile app, the rows that show each server, add a spinner when Claude is working so you can see really idling sessions at a glance
