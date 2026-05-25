# Use Case 23: Android terminal — on-screen keyboard occludes the input (IME insets)

## Summary
On the UC-21 terminal screen, opening the on-screen (IME) keyboard does not adjust the layout, so the keyboard overlaps the bottom of the Termux `TerminalView` and hides the active input line and cursor — the user can't see what they type. The activity declares `windowSoftInputMode="adjustResize"`, but the `TerminalView` is an `AndroidView` hosted in a Compose hierarchy that does not consume the IME window insets, so the bottom of the terminal stays behind the keyboard. UC-23 makes the terminal screen react to `WindowInsets.ime`: rather than resizing the PTY, the terminal **keeps its current dimensions and scrolls/offsets the rendered view** so the cursor and most recent output remain visible above the keyboard; the **`ModifierBar` docks directly above the IME** so its keys stay reachable while typing; and the **`AgentSwitcherBar` collapses to a compact strip** to reclaim vertical space while the keyboard is open. Dismissing the keyboard restores the normal layout and resumes following live output at the bottom. Portrait is the target; landscape polish is deferred. The change is confined to the Android terminal screen's layout/inset handling — no PTY-resize, server, or protocol changes.

## Acceptance Criteria
1. When the IME opens, the active input line and cursor remain visible above the keyboard (not occluded) — achieved by scrolling/offsetting the terminal view, **not** by resizing the PTY.
2. PTY cols/rows are **not** changed when the keyboard opens or closes (no resize-frame churn on IME toggle); UC-21's resize behavior on real geometry changes (rotation, font/size changes) is unaffected.
3. The `ModifierBar` docks directly above the keyboard while the IME is shown, staying visible and tappable, and continues to emit correct escape sequences (no regression to UC-21 AC#3).
4. The `AgentSwitcherBar` collapses to a compact strip while the keyboard is open and restores to its normal row when the keyboard closes.
5. Typed characters continue to reach the PTY with echo while the keyboard is shown (no regression to UC-21 AC#2).
6. Dismissing the keyboard restores the full terminal view and the normal `ModifierBar` / `AgentSwitcherBar` layout, and the view resumes following live output at the bottom.
7. No regression to terminal rendering, the hamburger menu, back-keeps-syncing, or switcher selection when toggling the keyboard (portrait).
8. An instrumented Compose test on the emulator (via the `aisandbox-emulator` helper) asserts that, with the IME shown, the input/cursor region is not occluded (its bottom sits above the IME inset) and the `ModifierBar` is positioned above the keyboard.

## Potential Pitfalls & Open Questions
- **Assumption** — Hardware keyboard: the IME stays hidden when a hardware keyboard is attached, so no scroll/dock is needed; UC-23 must not break that path.
- **Edge case** — Full-screen TUI (Claude Code TUI, `htop`, `vim`): scrolling to the cursor may reveal blank area below it; acceptable as long as the cursor / active region is visible above the keyboard.
- **Risk** — Edge-to-edge inset handling (`WindowCompat.setDecorFitsSystemWindows(window, false)`) can shift status/navigation-bar insets and affect other screens; scope the change strictly to the terminal screen — no regression to the sessions list or enrollment screens.
- **Deferred** — Landscape with the keyboard up leaves very little height; out of scope for UC-23 and tracked as a possible follow-up.

## Original Description
> When opening the on-screen keyboard, the window isn't resized, so I am unable to see what I'm typing.

## Clarifications
- Q: When the keyboard opens, how should the terminal itself adjust?
  A: Keep the terminal size and scroll to the cursor (no PTY resize on IME toggle); the view pans so the cursor/active line stays visible above the keyboard.
- Q: Where should the `ModifierBar` (ctrl/alt/esc/arrows) sit when the keyboard is open?
  A: Docked directly above the keyboard (like Termux's extra-keys row), so modifier keys stay reachable while typing.
- Q: What should the agent-switcher row do while the keyboard is open?
  A: Collapse to a compact strip to reclaim vertical space; restore to the normal row when the keyboard closes.
- Q: How should landscape (very little height with the keyboard up) behave?
  A: Defer landscape — focus on portrait for UC-23; landscape polish is out of scope.
