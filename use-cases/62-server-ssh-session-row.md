# Use Case 62: SSH-into-server host-shell session row

## Summary
Add a dedicated **terminal/"shell" icon button** to the Android sessions-list top app bar, positioned **immediately to the left of the gear Settings icon** (a square console glyph with a prompt inside, e.g. Material's `Icons.Outlined.Terminal`). Tapping it creates a **singleton** special session rendered as a row **pinned to the top** of the session list and badged **"SERVER SSH SESSION"**. Server-side the session is modeled by adding a `type` field (`claude` | `server-ssh`) to the session DTO/record and assigning the SSH session a **reserved id** (a sentinel outside the real `ai-sandbox-<N>` Docker numbering, e.g. `0` or negative) so it reuses the existing `GET`/`DELETE /v1/sessions/{n}` endpoints and the `…/{n}/stream` terminal WebSocket. Instead of a Docker container, it opens a **tmux running a login shell** (`$SHELL`, as the server's own user, in the server's working dir) on the management-server host — available whenever the app is connected. The server tracks this host-tmux outside Docker enumeration and **re-lists the pinned row on every list call until it is explicitly removed**, so it survives terminal detach, WebSocket disconnect, and app restart, and is visible to any device that lists sessions. The row's options menu offers **only Remove**, and swipe-to-dismiss runs the same Remove flow. The host tmux is destroyed **only** on explicit Remove — and on Remove it **must** be killed. Tapping the icon again while the session already exists focuses/opens the existing row rather than creating a second.

## Acceptance Criteria
1. The sessions-list top app bar shows a **terminal/shell icon button immediately to the left of the gear Settings icon**; both buttons are tappable and the Settings icon continues to work unchanged.
2. Tapping the shell icon when no `server-ssh` session exists creates one; tapping it again while one already exists does **not** create a second — at most one SERVER SSH SESSION ever exists (singleton), and the second tap focuses/opens the existing row.
3. The `server-ssh` row renders **at the top** of the session list, above every Claude row, regardless of the normal `n`-ascending sort.
4. The row displays a visible **"SERVER SSH SESSION"** tag/badge distinguishing it from Claude rows.
5. Opening the row attaches the terminal to a **tmux running a login shell on the management-server host** (server's own user, server working dir), not inside any Claude container; commands run there affect the host, not a sandbox container.
6. The session is exposed via the existing API: it carries `type == server-ssh` and a reserved id, appears in `GET /v1/sessions`, streams over `…/{id}/stream`, and is removed via `DELETE /v1/sessions/{id}`.
7. The server **re-lists the pinned row on every `GET /v1/sessions`** as long as the host tmux exists — it survives terminal detach, WebSocket disconnect, and app restart, and appears for any device that lists sessions.
8. The row's overflow/options menu offers **only "Remove"** (no Stop/Start/Pause/Unpause), matching the Claude rows' menu styling.
9. **Swipe-to-dismiss** on the row triggers the same Remove flow as the menu's Remove.
10. Detaching from the terminal, backgrounding the app, disconnecting, or restarting the app does **not** destroy the host tmux; reconnecting reaches the same shell with its session state intact.
11. Choosing **Remove** (menu or swipe) destroys the host tmux on the server; afterward the row is absent from `GET /v1/sessions` and the tmux no longer exists on the host.
12. Normal Claude sessions' enumeration, ordering, lifecycle actions (stop/start/pause/unpause), and streaming are unaffected by the presence or removal of the `server-ssh` session.
13. Singleton creation is enforced **server-side** (not only in the client): two near-simultaneous create requests yield exactly one host tmux and one row.

## Potential Pitfalls & Open Questions
- **Assumption** — the management server runs on the host (systemd) with shell access to that host; "the server itself" = that host, and the tmux runs as the server process's user in its working dir. If the server is itself containerized, "host login shell" means the server container's shell.
- **Assumption** — the shell glyph uses a stock Material terminal/console icon (`Icons.Outlined.Terminal` or the nearest available in the project's icon set); the exact asset is the developer's choice as long as it reads as a shell prompt.
- **Edge case** — the reserved id must not collide with real Docker session numbering (Claude sessions enumerate as `ai-sandbox-<N>`); pick a sentinel outside that range and ensure list, sort/pin, stream, and delete all handle it.
- **Edge case** — the stream handler currently does `docker compose -p ai-sandbox-<N> exec … tmux`; it must branch on `type == server-ssh` to attach the host tmux directly instead of going through a container.
- **Edge case** — singleton enforcement under concurrency must be a server-side guard, not just a client-side check.
- **Risk** — a host shell reachable from the app intentionally crosses the container trust boundary that is the project's core value proposition; this is an accepted operator convenience (always-on, per decision), but worth a note in the brief/security posture.

## Original Description
In the main session list view, I want that the main top right menu shows a new option above settings that says "ssh into server". It should spawn a new row in the session list, PINNED TO THE TOP OF THAT LIST, tagged SERVER SSH SESSION. It should in the server open a tmux to the server itself not to any sessions. Only when the client removed that session from the android UI should the tmux be destroyed (and indeed it must be in this case). The row for this session should show the options menu also as the claude session ones but in this case only offer the option to remove. Sliding must also work for this purpose.

(Later clarification — placement changed from an overflow-menu item to a dedicated icon button: "Actually put it in an icon next to the gear settings one. Left of it. Make it an icon of a 'shell' of some sort (typical square thing with the prompt inside).")

## Clarifications
- Q: How should the SSH session be represented server-side so it flows through the existing list/stream/remove plumbing?
  A: Reserved id + a `type` field (`claude` | `server-ssh`) on the session DTO/record; reuse the existing `GET`/`DELETE /v1/sessions/{n}` endpoints and the `{n}/stream` WebSocket. Server tracks the one host-tmux outside Docker enumeration.
- Q: Can there be more than one SERVER SSH SESSION row at a time?
  A: Singleton — at most one; tapping again focuses/opens the existing row instead of creating a second.
- Q: Should the pinned SSH row persist on the server (survive app restart / show on other devices), or be ephemeral?
  A: Persist until removed — the server re-lists the pinned row on every `GET /v1/sessions` until explicitly Removed.
- Q: What should the host tmux actually run, and how exposed should it be?
  A: A host login shell (`$SHELL`, server's own user, server working dir) on the host where the Spring Boot server runs, available whenever the app is connected (always on — it's the operator's own machine).
- Q: Placement of the entry point?
  A: A dedicated icon button in the top app bar, immediately to the left of the gear Settings icon, using a "shell" glyph (a square console icon with a prompt inside).
