# Android ai-sandbox terminal

_Started 2026-05-17 05:10 UTC_

---

## User

ai-sandbox Android client (UC04, planned) — a native Android app (Kotlin / Jetpack Compose, minSdk 29) that connects to a self-hosted ai-sandbox
 management server (UC03, already shipped) over a single TLS port with mutual TLS. Onboarding is a one-shot QR scan that imports a client identity (cert
 + key + server pin + URL) into the Android KeyStore. The home screen lists running ai-sandbox-N Docker sessions retrieved over an mTLS-gated REST API
(GET /v1/sessions); the user can spawn a new session, delete an existing one, or tap to open a full-screen terminal. Inside the terminal, the app holds
 a WebSocket open (wss://host:12410/v1/sessions/{n}/stream, subprotocol ai-sandbox.v1) that bridges the device to a per-client tmux session running in
the target Docker container, so each viewer gets independent sizing without disturbing the shared main session. Touch-input maps to xterm: an on-screen
 modifier bar (Ctrl/Alt/Esc/Tab/Fx + a one-tap tmux prefix), long-press selection for copy, paste menu, two-finger scroll for the terminal's
scroll-wheel, drag-to-resize-pane, and rotation triggers a JSON resize control frame. The WebSocket survives lock-screen and task-switch via a
foreground service of type dataSync, with a persistent notification showing the attached session and a disconnect action. On the server's side,
certificate revocation (which tears down active connections within ≤1s) is rendered to the user as a "re-scan a new QR" prompt. Distribution is
internal sideload for two users, with a release signing config and a tag-triggered GitHub Actions release pipeline already in place for a later Play
Closed Testing track. No analytics, no third-party SDK, no telemetry.

## Assistant

_[tool: questions_v2]_

## User

Questions answered:
- scope: QR onboarding (scan + import identity), Session list / home, New session sheet, Delete confirmation, Terminal screen (the meat), Modifier bar + tmux controls, Pane resize / split, Foreground service notification, Cert-revoked / re-scan prompt, Settings (server pin, cert info)
- fidelity: Clickable navigation between screens
- aesthetic: Material 3 expressive — Google's current default, soft shapes, dynamic color
- theme: Dark only (developer audience)
- accent: 4
- frame: Yes — Android frame with status bar / nav
- terminal_demo: A build/log stream (gradle, kubectl, etc)
- variations: Modifier bar layout (above keyboard vs floating vs collapsible), Session list density (cards vs rows vs compact), Color accent
- audience: Spec doc handoff to me-the-implementer

## Assistant

_[tool: update_todos]_

_[tool: copy_starter_component]_

_[tool: copy_starter_component]_

_[tool: read_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

Now I'll build the prototype. Given the spec-doc-handoff audience, I'll center a phone frame on a dark workbench with a screen navigator and optional spec notes rail. Mono/warm-light accent as default with the variations exposed via Tweaks.

Let me write the files:

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: read_file]_

_[tool: grep]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: show_html]_

_[tool: done]_

_[tool: eval_js]_

_[tool: eval_js]_

_[tool: list_files]_

_[tool: eval_js]_

_[tool: show_html]_

_[tool: get_webview_logs]_

_[tool: eval_js]_

_[tool: eval_js]_

_[tool: read_file]_

_[tool: sleep]_

_[tool: show_html]_

_[tool: get_webview_logs]_

_[tool: copy_files]_

_[tool: eval_js]_

_[tool: delete_file]_

_[tool: show_html]_

_[tool: get_webview_logs]_

_[tool: done]_

_[tool: update_todos]_

_[tool: fork_verifier_agent]_

Built a clickable hi-fi prototype for the ai-sandbox Android client. All 10 screens wire together, presented in a centered phone frame with M3 Expressive dark theming.

**Workbench layout**
- Left rail: screen picker (every UC04-N slot)
- Center: Pixel-shaped frame, scaled to fit
- Right rail: implementation notes per screen (toggleable for hand-off)
- Bottom-right: Tweaks panel

**Flows wired**
- QR scan → import → continue → sessions
- Sessions ↔ terminal ↔ split pane ↔ revoked dialog → re-scan loop
- Sessions → "+" sheet → optimistic `starting` row
- Sessions → row overflow → delete confirm (warns when other clients attached)
- Settings → "simulate revoke" triggers the cert-revoked path
- Notification screen renders the ongoing foreground entry with Open / Disconnect

**Tweaks exposed**
- Modifier bar: Docked / Floating / Collapsible
- Session list density: Cards / Rows / Compact
- Accent color (5 swatches; default mono-warm)
- Implementation-notes rail on/off, phone frame on/off

Caveats / next steps:
- Terminal stream is canned (gradle + kubectl log); real xterm rendering, paste, two-finger scroll, and rotation→resize-frame are descriptive in the spec rail, not interactive.
- QR scan animation uses a fake QR — replace with CameraX preview.

## User

For UC04-2a, OS image and hardware specs must not be provided, only label.

## Assistant

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: str_replace_edit]_

_[tool: done]_

New session sheet now shows just the label field, with a note that image + hardware come from the server profile. Spec-rail note and the spawn handler updated to match.

