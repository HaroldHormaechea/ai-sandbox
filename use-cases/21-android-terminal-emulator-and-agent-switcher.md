# Use Case 21: Android client — make the terminal screen usable (real emulator + keyboard), add a hamburger menu (delete/disconnect), and an agent-team switcher

## Summary
The Android terminal screen (UC04-3, reached by tapping a session) is a v0.1 placeholder and effectively unusable: the body (`SimpleTerminalSurface`) dumps raw UTF-8 stdout as monospace text with no ANSI/cursor/color emulation; there is no IME/soft-keyboard or hardware-keyboard input path (only the `ModifierBar` special keys), so the user can't type; the top-right hamburger (`MoreVert`) button is a no-op; and the Splitscreen button merely stacks two panes bound to the same stream. UC-21 makes the screen genuinely usable and adds controls + an agent-team switcher in one effort: **(1) Working main terminal** — vendor Termux's `TerminalView` (Apache-2.0) for real ANSI/cursor/color emulation, render the main tmux session's live output, and accept input from both the on-screen (IME) and any connected hardware keyboard, integrated with the existing `ModifierBar`. **(2) Hamburger menu** — a *Delete session* action (reusing UC-20's confirm dialog + force toggle) and a *Disconnect* action that navigates back to the list **and** tears down the stream + foreground service with no confirmation, while the **back arrow** instead keeps the session syncing. **(3) Agent-team switcher** — a horizontal row of selectable "boxes" below the title/menu bar and above the terminal, listing the running agent-team members plus the always-present main session, where tapping a box switches the streamed target. Verified live on `ai-sandbox-1-claude-sandbox-1`: agent-team members are Claude Code teammates rendered as **tmux panes** on a separate `claude-swarm-<pid>` socket (tagged with agent name/color/type/team); the main session is on the `default` socket; and the current stream protocol bridges a single tmux target, so the switcher needs new server-side enumerate/select protocol mirrored in the Android `StreamClient`. This is a large, multi-layer effort across the Android UI, the WebSocket protocol on both ends, and the server's tmux bridge.

## Acceptance Criteria

**A. Working main terminal**
1. Tapping a session opens the terminal and the main tmux session's live output renders with correct ANSI handling (cursor positioning, colors, redraw) via the integrated Termux `TerminalView` — TUIs (the Claude Code TUI, `htop`, `vim`) display legibly, not as raw escape bytes.
2. Tapping the terminal surface opens the on-screen keyboard and typed characters reach the PTY (with echo); a connected hardware keyboard also sends input.
3. The `ModifierBar` (ctrl/alt/esc/arrows/function) continues to work alongside keyboard input, emitting correct escape sequences.
4. The client sends resize frames so the server PTY matches the rendered cols/rows (no garbled wrapping).

**B. Session controls (hamburger + back/disconnect)**
5. The top-right hamburger (`MoreVert`) opens a menu containing at least **Delete session** and **Disconnect**.
6. **Delete session** reuses UC-20's confirmation (including the force toggle for attached sessions); confirming actually deletes (container torn down) and returns to the list; the session does not reappear.
7. **Disconnect** immediately returns to the sessions list **and** tears down the stream + stops the foreground service, with **no** confirmation dialog.
8. The **back arrow** returns to the list **without** disconnecting — the WebSocket/foreground service keeps the session syncing (re-entering shows continuity). This changes today's behavior, where leaving the screen always stops the service.

**C. Agent-team / subagent switcher**
9. When an agent team is running, a horizontal row of selectable "boxes" appears below the title/menu bar and above the terminal — one per agent-team member, labeled with the agent's identity (name/type + its color), styled like the `ModifierBar` segment.
10. The **main session is always present** in that row (even when not in the swarm view), so the user can always return to it.
11. Tapping a box switches the main terminal surface to that target's output and routes input to it; the selected box is visually indicated.
12. Members are discovered dynamically (the `claude-swarm-<pid>` socket and its panes are not hard-coded), and the row updates as members appear/disappear; with no team running, only the main session shows (or the row is hidden, per design).
13. The server protocol gains the capability to enumerate stream targets (main + swarm panes, with metadata) and to select which target a stream bridges (output + stdin), implemented in the tmux bridge + stream handler and mirrored in `StreamClient`.

**Cross-cutting**
14. No regression to UC-18 (tap opens terminal) or UC-20 (swipe-delete); reconnect/give-up/cert-revoked banners still work.
15. Coverage: instrumented Compose tests for keyboard input + switcher selection (via the `android-testing` skill); server tests for the enumerate/select protocol; verified live against an active agent team on `ai-sandbox-1-claude-sandbox-1`.

## Potential Pitfalls & Open Questions
- **Open question (main vs swarm)** — with a team active, the `default` (main) socket may have *no server running* (observed) while the orchestrator + teammates live on the `claude-swarm` socket. Define what the "main session" box streams in that state, and how the orchestrator is distinguished from teammates. Resolve on the live docker during analysis.
- **Risk (upstream-owned layout)** — the swarm is Claude Code's own agent-teams feature; the socket naming (`claude-swarm-<pid>`), the pane-not-window layout, and the metadata source (pane title vs process argv `--agent-name/--agent-type/--agent-color/--team-name`) are upstream-owned and version-volatile. Prefer robust discovery (scan `tmux -L` sockets, read pane metadata) behind a server-side adapter over hard-coded assumptions.
- **Risk (model mismatch)** — the user's "different tmux session windows" is actually "panes in one window on a separate server"; the switcher enumerates panes + the main session, not windows.
- **Risk (protocol/scope)** — switch target mid-stream (a `select-target` control frame; server re-bridges one WS) vs multiple concurrent streams. The former is lighter on-device; decide explicitly.
- **Risk (Termux vendoring)** — `TerminalView` is an Apache-2.0 source module (no Maven artifact); vendoring + `AndroidView` + input-connection wiring is a sizable one-time integration with attribution to honor.
- **Risk (foreground-service lifecycle)** — "back keeps syncing" means the service must persist after leaving the screen (today `DisposableEffect.onDispose` always stops it); ensure disconnect/delete and process death still tear it down so it can't leak.
- **Dependency** — the hamburger Delete reuses UC-20's confirm dialog/force semantics; UC-20 should land first (or its dialog be extracted for reuse).
- **Size risk** — one large multi-layer UC (emulator integration + UI + bidirectional protocol + server bridge) may exceed a single develop run's 6-round cap; consider milestone checkpoints and revisit splitting if the team stalls.

## Live Verification (ai-sandbox-1-claude-sandbox-1, 2026-05-23)
Inspected a running session container to ground the subagent model:
- `docker exec … ls /tmp/tmux-997/` showed two sockets: `default` and `claude-swarm-15713`.
- `tmux -S /tmp/tmux-997/claude-swarm-15713 list-windows -a` → session `claude-swarm`, one window `swarm-view`, **2 panes**.
- `list-panes` → `claude-swarm:0.0 cmd=claude.exe title=✳ general-purpose` and `claude-swarm:0.1 cmd=claude.exe title=✳ general-purpose`.
- Process argv revealed Claude Code agent-team teammates: `claude.exe --agent-id ping@pingpong-functest --agent-name ping --team-name pingpong-functest --agent-color blue --parent-session-id … --agent-type general-purpose --model claude-opus-4-7` (and a `pong`/green peer).
- The `default` socket had **no server running** while the swarm was active.

Implication: subagents = Claude Code agent-team members rendered as **tmux panes** (titled/typed/colored) on a separate `claude-swarm-<pid>` tmux server; the switcher enumerates those panes + the always-present main session, and the server's `TmuxBridgeService` (today a single target, control frames `resize`/`mouse`/`close`/`error`) must learn to target a chosen socket/session/window/pane.

## Original Description
> I need to review the window after clicking on a session to attach to it. Currently I do see some buttons but it seems not raelly functional. The only button that wroks is the "back arrow" to go back to the list, and there is a split panel that.. splits something. But e.g. I don't see any terminal output, nor can open the keyboard or anything.
> I need to improve this. First, we should see in the main window the MAIN TERMINAL (main tmux session) and be able to write on it using the on-screen or any connected keyboard.
> Then, we must be able to have an option in the top-right hamburguer menu to delete the session (similar to the silde-delete in the list)
> In that hamburguer menu we should also have an option to disconnect from it (e.g. back click shouldn't disconnect but keep it syncing; while disconnect should do both - go to the list and disconnect - no confirmation dialog).
> When there are multiple subagents / agent team members enabled in different tmux session windows, we must have a list of them in the TOP of the working window - below the row where the title / menus are, but above the terminal itself. Clicking on different items in this list (they should show as "boxes", sort of like the bottom tmux/ctrl/alt/esc... segment below) would switch the main screen from the main session to a different subagent/team agent. In this list, the main session must ALWAYS be included when shown, so you actually have a way back to it.

## Clarifications
- Q: This bundles three distinct pieces (working terminal; hamburger delete/disconnect; subagent switcher). Split into multiple use cases, or keep as one?
  A: Keep as one comprehensive use case.
- Q: How should the "main terminal" render + accept input (placeholder dumps raw UTF-8, no keyboard)?
  A: Vendor Termux's `TerminalView` for full ANSI/cursor/color emulation + proper IME keyboard input.
- Q: The stream bridges a single tmux target with no window concept; do subagents already run in separate tmux windows?
  A: Verify on the live docker — verified (see Live Verification): subagents are Claude Code agent-team teammates rendered as tmux **panes** on a separate `claude-swarm-<pid>` socket, not windows.
