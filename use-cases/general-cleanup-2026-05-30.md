# General Cleanup — Design vs Android Implementation Audit (2026-05-30)

Audit of `/design/android-ui/project/` (Claude Design HTML/CSS/JSX export, dated 2026-05-17) against the live Jetpack Compose app under `android/src/main/kotlin/com/aisandbox/android/ui/`. Surface every structural mismatch, every dead/no-op affordance, and pick the right vehicle for each follow-up (a `/develop` run, a brand-new use case, or no action because an existing UC already overrides the design).

**Important framing.** The design bundle is a low-fidelity prototype produced before the terminal use cases (UC-21 emulator + agent switcher, UC-23 IME insets) were captured. Where an existing use case has refined a screen beyond what the prototype shows, the **use case wins**, the design is treated as advisory, and the audit does not chase the design's gaps on that screen.

---

## Per-screen audit

### UC04-1 Onboarding — PARTIAL

- Design: `screen-onboarding.jsx`
- Impl: `ui/screens/OnboardingScreen.kt`, `ui/components/QrScanner.kt`

**Mismatches (all on the Scanning stage):**

1. **Top chrome missing.** Design shows a top bar overlaying the camera preview with a leading close (`IcClose`) button (returns to sessions) and a trailing camera-flash (`IcFlashOn`) toggle, both styled as glassy `rgba(0,0,0,0.45)` blur pills. The live screen renders neither.
2. **Static reticle, no scan-line animation.** Design renders four white reticle corners + a vertical accent-colored scan-line sweeping over the inner 216 dp region with a 1.8 s `scanLine` keyframe + 12 dp `box-shadow` glow. The live `OnboardingScreen.kt` (~line 199–209) is a bordered square with no animation.
3. **Reticle does not transition to success-green on import.** Design swaps `border-color` of all four corners from `#fff` to `var(--success)` with a 240 ms transition when stage flips to `imported`. The live reticle stays static.

**No use-case override** — the design is the authoritative reference for onboarding chrome.

**Action:** `/develop` task — "Onboarding screen visual polish (UC04-1)".

### UC04-2 Sessions list — MATCH

Card layout, status pill, FAB, settings IconButton, filter chips all line up with `screen-sessions.jsx`. UC-18 (tappable cards) and UC-20 (swipe-to-delete) extend behavior beyond the design — both already merged. No action.

### UC04-2a New-session bottom sheet — MATCH

The "label only; image + hardware come from server profile" invariant the design notes (`screen-sessions.jsx` sheet body) is respected in the Compose sheet inside `SessionsScreen.kt` (~line 425–435). No action.

### UC04-2b Delete confirmation — MATCH

M3 AlertDialog reuses the destructive primary action shape; warning copy fires when `attached > 0` and the force toggle matches UC-20. No action.

### UC04-3 Terminal — code MATCHES UC-21, but user reports observable mismatch

- Design: `screen-terminal.jsx` (low-fi — single surface, no agent concept)
- Authoritative spec: **UC-21** (real ANSI emulator + hamburger menu + agent-team switcher) + **UC-23** (IME insets)
- Impl: `ui/screens/TerminalScreen.kt`, `ui/components/AgentSwitcherBar.kt`, `ui/components/TerminalSurface.kt`, `terminal/TerminalStreamController.kt`, `server/stream/service/SwarmEnumerationService.java`, `server/stream/service/TmuxBridgeService.java`

**Code state.** TerminalScreen renders ONE `TerminalSurface` whose underlying Termux `TerminalView` is bound to the process-scoped controller's WebSocket. `AgentSwitcherBar` is a single horizontal row above the surface; it self-hides when only `main` is present, lists `main` first, and `onSelect` re-points the existing WebSocket via a `select-target` control frame. Server-side, `SwarmEnumerationService` builds the target list from `claude-swarm-*` sockets only, and `TmuxBridgeService.start(...)` calls `select-window` + `select-pane` + `resize-pane -Z` (zoom toggle) when the target carries `window`+`pane`. By every read of the source, the code matches the user's intent.

**User-reported symptom (task #4 in the cleanup request):** *"all tmux windows are shown instead of only the main one with selector buttons at the top to be able to switch agents."*

Three plausible runtime causes:

1. **Main-target multi-window leak.** The `main` target bridges with `BridgeTarget.main()` which has `hasPane() == false`, so the `resize-pane -Z` zoom step is skipped. If Claude Code's main session now spans multiple tmux *windows* (not panes), the per-client `attach` shows the active window only, but tmux's status line + prefix key let the user flip between them — which a user could legitimately describe as "all tmux windows are shown". The Android client has no per-window switcher tile because `SwarmEnumerationService.enumerate` only enriches swarm-socket sockets, never the default socket's windows.
2. **Stale-target zoom failure.** If `select-pane`/`resize-pane -Z` silently fails on the per-client session (e.g., the pane spec is wrong after a swarm restart), the per-client view falls back to whatever tmux defaults to, which on a multi-pane window is the full split layout — matching the symptom exactly.
3. **Pane discovery scope.** `list-panes -a` on the swarm socket enumerates every pane on that socket, but `SwarmEnumerationService.discoverSwarmSockets` only filters by socket name `claude-swarm-*`. If a defunct swarm socket lingers (Claude Code's swarm orchestrator may not always unlink on exit), stale teammates appear in the switcher row long after the team has rotated.

This is a runtime-behaviour question, not a code-review question. The dev team must run against a live sandbox container with an active agent team to confirm the cause.

**No design-level mismatch to fix.** UC-21 is the authoritative spec for this screen and the code matches it. The follow-up is a diagnostic + bug fix, not "make the screen look like the prototype."

**Action:** new use case (drafted as **UC-24** in this cleanup) — "Terminal screen — investigate and fix the 'all tmux windows shown instead of just main' regression."

### UC04-3c Split / resize pane — MISMATCH (entirely absent)

- Design: `screen-terminal.jsx` (`splitMode` toggle, drag handle, two-pane layout)
- Impl: no split logic in `TerminalScreen.kt`, no Splitscreen toolbar action, no drag-handle gesture.

UC-21 deliberately did not include split (it focused on real emulator + hamburger + agent switcher); the original UC-04 design slot for split was never picked up afterward. The current terminal scaffold does not even have a place to host a second pane — `TerminalScaffoldLayout` has a single `terminal` slot.

**Trade-off.** Implementing split now means: (1) extending the WS protocol with a server-side `tmux split-window -v` instruction set, (2) teaching `TmuxBridgeService` to maintain N concurrent panes inside one per-client session and arbitrate input routing, (3) adding a Compose layout with a draggable separator, (4) propagating per-pane resize frames. This is roughly the size of UC-21 itself.

**Action:** new use case (drafted as **UC-25** in this cleanup) — "Terminal screen — split-pane support (UC04-3c)." **Marked tentative — not autonomously dispatched to `/develop` in this cleanup run** because it is a multi-layer (Android + WS protocol + server tmux bridge) effort that risks exceeding the 6-round dev-team cap on its own, and because it's a feature addition rather than a fix; user review should pick the milestone slicing before it kicks off.

### UC04-5 Foreground-service notification — N/A

The design renders a notification-shade mockup (`screen-overlays.jsx` `ScreenNotification`) to communicate the persistent-notification layout. The live app does not — and should not — render this as an in-app composable; `TerminalForegroundService` owns the actual system notification. No mismatch.

### UC04-6 Cert revoked — MATCH

`CertRevokedScreen.kt` + the M3 AlertDialog plumbed off `NetworkEvent.CertRevoked`. `ServerIdentityChangedScreen.kt` adds Pin / Hostname / HandshakeError variants the design didn't anticipate — those are operational extensions, not regressions. No action.

### UC04-7 Settings — MATCH

Four sections (Server / Identity / WebSocket / Diagnostics) match the design. The "Reset → clears KeyStore" affordance the implementation-notes rail mentions is **not** rendered in the design's `ScreenSettings`, so its absence in `SettingsScreen.kt` is not a mismatch — the spec rail is annotation, not screen content.

Minor: the design's WebSocket section shows live values (`u_42 · 124×38` for per-client tmux session id + cols×rows). The implementation prints static strings (`"auto on viewport change"`). This is a polish nit, not worth a dedicated follow-up; surface it inside UC-24's scope opportunistically.

---

## No-op / dead affordances

**None found** in the current Compose sources. Every `onClick` (across `SessionsScreen`, `OnboardingScreen`, `SettingsScreen`, `TerminalScreen`, `CertRevokedScreen`, `ServerIdentityChangedScreen`, `ModifierBar`, `AgentSwitcherBar`, `SessionAvatar`, `StatusPill`, `QrScanner`) is wired either to a parameterised callback that fires real navigation/state changes, to a ViewModel method that hits the network or controller, or to a local `mutableStateOf` that drives a dialog/menu open/close. The original cleanup request anticipated "for each no-op button, document it as a NEW USE CASE" — that subtask resolves to nothing-to-do.

The design *does* show affordances the implementation has not yet built — onboarding's close + flash buttons, terminal's split toggle. Those are **missing buttons**, not no-op buttons, and they're already captured under the per-screen mismatches above.

---

## Tentative new use cases drafted in this cleanup

| File | Title | Verdict / Trigger |
|---|---|---|
| `24-android-terminal-multi-window-leak-fix.md` | Terminal — investigate and fix the user-reported "all tmux windows shown instead of just main" regression | drafted, dispatched to `/develop` autonomously |
| `25-android-terminal-split-pane.md` | Terminal — split-pane support (UC04-3c) | **tentative**, NOT dispatched; left for user review |

(Both are appended to `USE_CASES.md` with `Status: pending` per the ledger contract — the orchestrator updates status as develop runs progress.)

---

## Planned `/develop` runs (this cleanup will autonomously dispatch)

Each `/develop` is single-track from the root session, so they run sequentially. The user authorised autonomous execution + merge + release in the cleanup request.

| Order | Target | Brief |
|---|---|---|
| 1 | **UC04-1 onboarding polish** | Implement the three onboarding chrome mismatches: top close + flash buttons (M3 glass-pill IconButtons over the camera preview, close returns to sessions, flash is a toggle wired to CameraX `Camera.cameraControl.enableTorch`), animated scan-line (vertical accent-colored line sweeping the reticle inner region, ~1.8 s alternate), reticle corner color transition to `Success` on `OnboardingViewModel` stage `Imported`. Scope: visual + camera-control only — no protocol or storage changes. Use the existing screen, do not refactor flows. |
| 2 | **UC-24 terminal multi-window investigation** | Diagnose live with an active swarm session (use the `aisandbox-emulator` + `android-testing` skills + a live `ai-sandbox-N-claude-sandbox-1` container), determine which of the three plausible causes from the audit fires, and fix. Likely areas: `SwarmEnumerationService` (does it correctly skip non-main tmux windows on the default socket?), `TmuxBridgeService` (does `resize-pane -Z` succeed for the swarm-pane path? does the main path need its own per-window switcher tile when claude main has spawned auxiliary windows?), and the per-client session lifecycle. The fix may extend the switcher to also surface windows on the main socket if Claude Code's runtime layout has shifted; resolve that with live evidence rather than spec interpretation. |

`/develop` run 3 (split pane) is **not** dispatched in this cleanup — captured as tentative UC-25 instead, per the rationale above.

---

## Release

When both develop runs land green on the Android `androidTest` suite + `:server:test`:

- Merge each result to `main` (one commit per UC the team produces; no squashing on the dev-team's side, but if multiple commits land per UC their PR will already be a single squash from the orchestrator's bundle).
- Tag the resulting state per the brief's release command convention (`git tag server-vX.Y.Z && git push --tags`) so the existing tag-triggered release workflow runs.
- Confirm the GitHub Actions release pipeline finishes via the `github-pipeline-operations` skill before considering the cleanup closed.
