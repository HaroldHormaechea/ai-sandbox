# Use Case 25: Android terminal — split-pane support (UC04-3c) **[TENTATIVE — pending user review before dispatch]**

## Summary
The Claude Design prototype (`design/android-ui/project/screen-terminal.jsx`) sketched a split-pane mode (UC04-3c) — a Splitscreen toolbar toggle, a vertical drag-handle between two stacked terminal viewports, and an "active pane" outline. The live `TerminalScreen.kt` (post-UC-21, post-UC-23) has no split toggle, no second viewport, and `TerminalScaffoldLayout` has one `terminal` slot. UC-21 deliberately deferred split to keep the milestone bounded; UC-23 then layered IME-aware geometry on the single-slot layout. Implementing split now is roughly a UC-21-sized effort across three layers: (1) protocol — extend the existing WebSocket control-frame vocabulary with `split` / `close-split` / `focus-split` and a per-pane `resize` so the server can drive `tmux split-window -v` on the per-client session and route input to whichever pane is focused; (2) server — teach `TmuxBridgeService` to maintain N concurrent panes inside the same per-client tmux session, manage focus, and stream their distinct outputs as discriminated frames; (3) Android — add a Compose layout that hosts up to two `TerminalView` instances stacked vertically with a draggable separator, route input to the focused pane (visualised by an accent-colored outline per the design), and propagate per-pane resize. This is also a *feature addition* relative to the user's current pain (which is about the existing terminal not behaving as advertised), so it is captured here for user review before any `/develop` run is scheduled.

## Acceptance Criteria

1. The terminal toolbar exposes a Splitscreen action that toggles between single-pane and two-pane vertical layout. The action's enabled state reflects server-side support (so older servers gracefully hide the toggle).
2. Entering split mode issues a server control frame that causes `TmuxBridgeService` to run `tmux split-window -v -t <client-session>` on the per-client session; the new pane appears below the existing one and renders its own live output in a second `TerminalView`.
3. A draggable horizontal separator between the two viewports adjusts pane heights with debounced resize control frames on release (matching the design's `splitMode` interaction). Long-press snaps to 50/50 or closes the focused pane.
4. The currently focused pane is outlined in the accent color. Tapping either pane focuses it; keyboard / `ModifierBar` input routes only to the focused pane.
5. Resize frames are per-pane (cols/rows + pane id), so each pane's PTY matches its rendered geometry and lock-screen rotation produces correct geometry on both panes after the rotation control frame.
6. Closing a pane (long-press handle, or hamburger menu) returns the layout to single-pane mode and tears down the corresponding tmux pane on the per-client session.
7. UC-23 IME-aware geometry continues to hold: the bottom pane (or single pane after collapse) sits above the keyboard with the `ModifierBar` docked, and the no-PTY-resize guarantee on IME toggle (UC-23 AC#2) extends to whichever pane is focused.
8. Coverage: server tests for the new control frames; Compose instrumented tests for the focus + drag-resize + close interactions; a live verification round on the emulator demonstrates a real two-pane bash + `top` split.

## Potential Pitfalls & Open Questions
- **Tension with UC-21 agent switcher.** The switcher already lets a single surface re-point between agent panes. Should split mode allow each split-half to bind to a different switcher selection (independent agent surfaces), or stay on the same target? The design's `splitMode` is silent on this; resolve before implementation.
- **WebSocket protocol versioning.** UC-21 added `targets` / `select-target` frames; this UC adds `split` / `focus-split` / per-pane `resize` / `close-split`. Bump the subprotocol from `ai-sandbox.v1` to `.v2`, or keep backward compat with capability advertisement on the initial handshake? The latter is friendlier for the in-flight sideload distribution.
- **Tmux pane lifecycle.** `tmux split-window` on the per-client session must not affect the upstream `main` / `claude-swarm` session's pane count (per-client sessions are linked targets, but splits would be local). Verify on a live container before assuming.
- **Layout under IME.** With two panes stacked vertically and the keyboard up, both panes are squeezed. UC-23's resting-height pin prevents resize-thrash for the focused pane, but the unfocused pane also needs the same treatment or it'll resize on every IME toggle.
- **Termux TerminalView count.** Two `AndroidView`-hosted `TerminalView` instances doubles emulator memory and input-connection complexity. Confirm Termux supports multiple instances cleanly (their reference flow is single-pane).
- **User-priority question.** The user's actual reported pain (cleanup task #4) is the existing terminal behaviour, not the absence of split. Implementing split before fixing UC-24 risks shipping more surface area on a still-broken foundation.

## Status
**Tentative — not dispatched to `/develop` in the 2026-05-30 cleanup run.** The cleanup-run orchestrator deliberately surfaces this UC for user review before scheduling it, because (a) UC-24 (the actual user-reported regression) should land first, (b) split is a feature addition to a screen the user is still unhappy with, and (c) the multi-layer scope is comparable to UC-21 itself and benefits from explicit milestone slicing.

## Notes
- Audit source: [general-cleanup-2026-05-30.md](general-cleanup-2026-05-30.md) §UC04-3c.
- Design reference: `design/android-ui/project/screen-terminal.jsx` `splitMode` branch + spec-rail notes under `SPEC_NOTES.split`.
- Implementation notes from the design's right-rail (`splitMode handling + drag handle` block) are advisory; the implementation should follow UC-21's protocol style rather than the prototype's local-state toggle.
