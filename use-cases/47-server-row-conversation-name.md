# Use Case 47: Show the Claude conversation name in each server row

## Summary
In the main sessions list, each row's status line currently shows the live tmux window title (`SessionSummary.tmuxTitle`, computed server-side via `tmux display-message ... "#W"` and normalized to `claude.exe` / `(idle)` / `(unavailable)` in `DockerEnumerationService`). That value reflects the shell's current foreground context, not a human-meaningful conversation name. This use case adds the **Claude conversation name** to that status section so a user scanning the list can tell what each session is actually about — not just whether it's running `claude.exe` or idle. There is no conversation-name field today on either the server or client, so this requires deriving/tracking the name (e.g. the Claude session/conversation title or summary for the active conversation) on the server, adding it to the `SessionSummary` payload, and rendering it in the row where `claude.exe`/`idle` is shown. When no conversation name is available (no active conversation, or session idle), the row falls back gracefully to the existing tmux-title behavior.

## Acceptance Criteria
1. When a session has an active/known Claude conversation, its row's status section displays the conversation's name/title instead of (or in addition to, per the chosen layout) the raw `claude.exe`/`idle` text.
2. The conversation name is provided by the server in the sessions payload (a new field on `SessionSummary`), not scraped or guessed client-side.
3. When no conversation name is available (idle session, no active conversation, or lookup failure), the row falls back to the current behavior (`(idle)` / tmux title / `(unavailable)`) without showing an empty or broken label.
4. The conversation name updates as it changes, consistent with the live status push (UC-32) — a scanning user sees the current name without manually refreshing.
5. Long conversation names are truncated/ellipsized to fit the row without breaking the layout or pushing out the status pill.
6. Computing the conversation name does not materially slow the `GET /v1/sessions` enumeration (it must not add a heavy per-session blocking call that degrades list latency for many sessions).

## Potential Pitfalls & Open Questions
- **Missing input** — There is no existing source of a "conversation name" in the codebase. The exact source must be defined: the Claude session title/summary, the most recent conversation transcript name, a derived first-message summary, or something surfaced over the conversation protocol (UC-37). This is the central open question.
- **Edge case** — A session may have multiple or no conversations (fresh shell, between conversations, or a non-Claude foreground command). Which conversation's name wins, and what shows when there isn't one, needs definition (AC3 covers the empty case but not the multi-conversation tiebreak).
- **Risk** — Performance of enumeration: `tmuxTitle` is already a per-session `docker compose exec`; adding another per-session exec/lookup to fetch a conversation name could multiply list latency, especially with many sessions. A cached/pushed value is preferable to a synchronous per-request scrape (relates to AC6).
- **Ambiguity** — Layout: replace the `claude.exe`/`idle` text with the name, or show both (name as primary, working/idle as a secondary indicator)? This overlaps with UC-47 (working spinner) and UC-32 status — the three should be designed to share the row's status section coherently.
- **Edge case** — Conversation names can contain arbitrary user/Claude text; rendering must handle unusual characters and RTL/emoji without layout breakage, beyond just truncation.

## Original Description
Add functionality so in the mobile app, the rows that show each server, in the section where we show claude.exe or idle, we show the Claude conversation name
