# Use Case 04: Android client application

## Summary

Native Android client (Kotlin + Jetpack Compose, `minSdk = 29`) for the UC03 mTLS management server, distributed as an internal-only signed APK for two users (developer + partner). Onboarding is a **multi-frame animated QR**: `aisandboxctl client mint <name> --qr` emits a sequence of QR frames carrying the PKCS#12 + server cert + base URL; the app reassembles and stores the material in the Android KeyStore. **One device, one cert**, **one server profile** at a time — re-scanning a QR offers "Replace existing identity?". The home screen lists running `ai-sandbox-N` sessions over mTLS; tap-to-open drops into a full-screen terminal backed by Termux's `TerminalView` (Apache 2.0) connected to `wss://host:12410/v1/sessions/{n}/stream` (subprotocol `ai-sandbox.v1`). Touch UX adds a sticky on-screen modifier bar (Ctrl/Alt/Esc/Tab/Fx + tmux prefix), long-press copy, paste menu, two-finger scroll, drag-to-resize-pane, and rotation-driven `resize` frames. Terminal renders **dark only** (Solarized-Dark-ish), bell triggers a brief **haptic vibration**. Tablet width (`≥600 dp`) renders split-pane (list left, terminal right); phone is single-pane. The WebSocket survives lock-screen via a `dataSync` foreground service; first stream open prompts for battery-optimization exemption; a 5-min backoff-failure cap surfaces a manual "Disconnected — tap to reconnect". Settings is minimal: identity details + Remove-this-device's-identity + battery-opt status + About. CI publishes a signed APK + AAB as GitHub Release assets on every `android-vX.Y.Z` tag, mirroring `server-release.yml`. PROJECT_BRIEF.md needs a `/revise-brief` pass **after** this UC is saved and **before** `/develop` to add Kotlin + Compose + `android/**` paths + the two new workflows.

## Acceptance Criteria

1. App targets `minSdk = 29` (Android 10), latest stable `compileSdk` + `targetSdk`, Kotlin + Jetpack Compose.
2. Layouts: phone (single-pane, list ↔ terminal navigation); tablet (split-pane at `≥600 dp` — list left, terminal right).
3. **QR onboarding (multi-frame)** — camera reads a sequence of QR frames emitted by `aisandboxctl client mint <name> --qr` and reassembles a base64 payload containing the PKCS#12 + `server.crt` + base URL.
4. Import confirmation screen shows: server URL, server cert SHA-256 fingerprint, client cert CN, client cert fingerprint. User must tap **Trust** to commit.
5. The Android KeyStore (hardware-backed when supported) holds the imported client private key; the app uses it as the sole TLS client identity.
6. **One server profile at a time.** Re-scanning a QR while a profile already exists shows a "Replace existing identity?" confirmation; on confirm, old KeyStore entries are deleted before importing new ones.
7. All network calls use mTLS via the imported client cert. Server cert is **pinned** to the SHA-256 fingerprint from the QR — any other server cert results in a hard refusal screen ("Server identity changed — re-scan a QR").
8. Sessions list shows, per session: N, label, uptime, attached-client count. Pull-to-refresh polls `GET /v1/sessions`; auto-refresh after a successful spawn or delete.
9. **Spawn** — floating action button → form (`label`, `workspace_mode`, `claude_config_mode`) → `POST /v1/sessions`. New row appears on 201.
10. **Delete** — long-press row → confirm sheet (with optional `force=true` toggle) → `DELETE /v1/sessions/{n}`.
11. **Open** — tap row → full-screen terminal.
12. Terminal rendered by Termux's `TerminalView` (Apache 2.0); ANSI / xterm 256-color + true-color, scrollback, tmux status line.
13. **Theme** — dark only, fixed Solarized-Dark-ish palette regardless of system theme.
14. **Bell** — terminal BEL (`0x07`) triggers a brief 150 ms haptic vibration. No sound, no flash.
15. **On-screen modifier bar** — toolbar above the soft keyboard with Ctrl, Alt, Esc, Tab, F1–F12, plus one-tap tmux prefix (`Ctrl-B`). Modifiers are sticky-one-shot (tap arms; auto-clears after the next character).
16. **Copy** — long-press starts selection; highlights and copies to Android clipboard. Works in tmux copy-mode.
17. **Paste** — paste menu inserts clipboard contents into the WebSocket binary channel unchanged.
18. **Resize** — on rotation, fold, soft-keyboard show/hide, or split-pane change, app sends `{"type":"resize", "cols":…, "rows":…}` as JSON text frame within 200 ms.
19. **Mouse gestures** — drag-to-resize-pane, long-press-as-right-click, two-finger drag → mouse-wheel. Emitted as JSON `{"type":"mouse", …}` frames per `STREAM_PROTOCOL.md`. Coordinates map to terminal cells.
20. The WebSocket survives backgrounding, screen lock, and task-switch via a foreground service of type `dataSync` (Android 14+ aware), started on stream open, stopped on disconnect.
21. Foreground notification shows attached session's `N` + label and a **Disconnect** action.
22. **Battery-optimization prompt** — on the **first** stream open after install, a one-time dialog explains the trade-off and tap-throughs to the system settings screen. If declined, foreground service still runs; README documents that the OS may kill it on long idles.
23. **Reconnect on drop** — exponential backoff (1 s, 2 s, 4 s, 8 s, capped at 30 s) with a "Reconnecting…" toolbar indicator.
24. **5-minute cap** — after 5 min of failed retries, app stops, dismisses the foreground notification, and surfaces "Disconnected — tap to reconnect" in the toolbar. User taps to restart.
25. **Server-side revocation** — when the server tears down the WebSocket (≤1 s per server's AC13), the app returns to the import screen with "Your access was revoked — scan a new QR".
26. **Settings (minimal)** — single screen exposing: server URL, server SHA-256 fingerprint, client CN + fingerprint, current battery-opt exemption status (with re-prompt button), an "About" block (app version + git commit short SHA), and a destructive **Remove this device's identity** action that clears KeyStore entries and returns to import.
27. App is signed: `release` signing config fed by env vars / `~/.gradle/keystore.jks`, documented in operator README. Debug builds use the debug keystore.
28. The app ships **no** analytics, telemetry, Crashlytics, or any third-party SDK that phones home. Only outbound HTTPS is to the configured ai-sandbox server.
29. CI workflow `.github/workflows/android-ci.yml` runs lint + unit tests + AAB build on every PR touching `android/**` and on every merge to `main`.
30. Release workflow `.github/workflows/android-release.yml` is triggered by `android-vX.Y.Z` tags. Emits signed APK + AAB as GitHub Release assets.
31. `aisandboxctl client mint <name> --qr` adds a new flag on the existing CLI subcommand (server-side change, CLI module only). It renders the PKCS#12 already produced today as a multi-frame QR sequence to stdout when output is a TTY, or as a PNG sequence in the output directory when it isn't.
32. **Brief revision is operator scope and out of dev-team scope.** This UC's `/develop` run **requires** that `PROJECT_BRIEF.md`'s frontmatter already lists Kotlin under `stack.languages`, Compose under `stack.frameworks`, the `android/**` glob under `paths.production`, and that the prose Deployment section mentions the two new GitHub Actions workflows. Operator must run `/revise-brief` after saving this use case and before `/develop`.

## Potential Pitfalls & Open Questions

- **Risk** — **Signing-key backup discipline**: the GitHub Actions signing-key secret needs an offline backup. Operator README must call this out and document the recovery path (re-key + new install required for the partner's phone).
- **Edge case** — **Foldables / Chromebooks**: out of scope. The `≥600 dp` breakpoint will Just Work in tablet mode on most foldables but it is not a verified target.
- **Edge case** — **Multi-frame QR capture robustness**: phone-camera capture can drop a frame if the user moves; CLI should loop frames until the user signals success, and the app should keep the camera open until it has all distinct frames. Implementation detail — worth a unit test on the reassembly path.

## Original Description

- Who uses the app: Me, my partner
- Cert + key onboarding: QR would be great.
- I'll provide a Claude design document for this, as ong as you provide me a brief about what this appw ould do so I can paste i there
- Terminal experience: full-screen tty, on-screen keyboard, copy/paste
- On background/lock/app-switch, keep it open
- Min sdk version API level 29 (Android 10). Target form would be phone + tablet (e.g. I have a Redmi Note 9 Pro)
- Distribution would be internal-only for now: for me and her. But I do expect to later sign it or something.

## Clarifications

- Q: How should the QR onboarding flow handle the size problem? A PKCS#12 + server cert + URL usually exceeds a single QR's data ceiling (~2.9 KB).
  A: Multi-frame animated QR carries the whole p12 — `aisandboxctl client mint <name> --qr` emits a sequence of QR frames; the phone reads them all and reassembles. No new server endpoints.

- Q: Which terminal-rendering library should the app use?
  A: Termux's `TerminalView` (Apache 2.0) — battle-tested native Kotlin/Java widget; handles 256-color + xterm mouse + scrollback; ~1 MB on the APK.

- Q: Tablet UX layout?
  A: Split-pane (list left, terminal right) at `≥600 dp` width.

- Q: Distribution / signing for the internal sideload?
  A: Signed APK + AAB on GitHub Release; `android-release.yml` emits both on every `android-vX.Y.Z` tag. Signing key in a GitHub Actions secret.

- Q: Can the app be enrolled to more than one ai-sandbox server?
  A: One server at a time. Re-scanning a QR overwrites the existing identity (with a "Replace?" confirmation).

- Q: Cert-per-device naming convention?
  A: One cert per device (`alice-phone`, `alice-tablet`, …). Hardware-keystore-bound private keys can't really be exported; per-device is the only practical model.

- Q: Behavior after the 5-minute reconnect cap is hit?
  A: Stop retrying, dismiss the foreground notification, and surface "Disconnected — tap to reconnect" in the toolbar. Manual reconnect.

- Q: How should the app handle Android battery optimization for the foreground service?
  A: Prompt once on first stream open + document the trade-off in the README. Tap-through to system settings. If declined, foreground service still runs; OS may kill it on long idles.

- Q: Terminal theme / color scheme?
  A: Dark only — fixed Solarized-Dark-ish palette regardless of system theme.

- Q: Terminal bell (ASCII BEL, 0x07) handling?
  A: Vibrate briefly (150 ms haptic). No sound, no flash. Setting to disable in app settings.

- Q: Settings screen scope?
  A: Minimal — identity info (server URL + fingerprints + CN), "Remove this device's identity" destructive action, battery-opt exemption status, About block (version + commit SHA). No theme picker, no modifier-bar customization.

- Q: When does the brief get revised to add Kotlin + Compose + android-* CI to `PROJECT_BRIEF.md`?
  A: Operator runs `/revise-brief` after this UC is saved and before `/develop`. Same pattern as UC03. Predictable orchestration boundary.
