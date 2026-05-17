# Use Case 04: Android client application

## Summary

Native Android client (Kotlin + Jetpack Compose, Material 3 Expressive, `minSdk = 29`) for the UC03 mTLS management server, distributed as an internal-only signed APK for two users (developer + partner). The UI is specified by a Claude Design handoff bundle (see `## UI / Design` below) — the implementation must reproduce the design's visual specification in Compose. Onboarding is **token-based** to match the design's UC04-1: a one-time enrollment token (10-min expiry) issued by a new `aisandboxctl client invite <name>` subcommand is encoded into a single QR with the server URL and the server cert pin; the app scans, POSTs to a new server-side `POST /v1/enrollment` endpoint (added in this UC; see § "Server-side scope additions"), receives a PKCS#12 bundle, and stores the private key in the Android KeyStore. **One device, one cert**, **one server profile** at a time. The home screen lists `ai-sandbox-N` sessions (running and stopped) over mTLS; tap-to-open drops into a full-screen terminal backed by Termux's `TerminalView` (Apache 2.0) connected to `wss://host:12410/v1/sessions/{n}/stream` (subprotocol `ai-sandbox.v1`). Touch UX adds a sticky **docked** on-screen modifier bar (Ctrl/Alt/Esc/Tab/Fx + tmux prefix; floating and collapsible are operator-selectable design variations), long-press selection menu (Copy / Paste / Select all / Send to…), two-finger scroll, drag-to-resize-pane, an in-terminal **split-pane** toggle, and rotation-driven `resize` frames. Terminal renders **dark only** per the design's M3 Expressive token set (mono-warm accent default), bell triggers a brief **haptic vibration**. Tablet width (`≥600 dp`) renders split-pane at the navigation level (list left, terminal right); phone is single-pane. The WebSocket survives lock-screen via a `dataSync` foreground service that mirrors the design's notification layout (FOREGROUND·dataSync pill, attached-session title, mono connection-metadata body, Open + Disconnect actions); first stream open prompts for battery-optimization exemption; a 5-min backoff-failure cap surfaces a manual "Disconnected — tap to reconnect". Settings is organized into Server / Client identity / WebSocket / Diagnostics sections per the design. CI publishes a signed APK + AAB as GitHub Release assets on every `android-vX.Y.Z` tag, mirroring `server-release.yml`. PROJECT_BRIEF.md needs a `/revise-brief` pass **after** this UC is saved and **before** `/develop` to add Kotlin + Compose + `android/**` paths + the two new workflows + the enrollment endpoint additions.

## UI / Design

The Android client's UI is specified by a Claude Design handoff bundle (`claude.ai/design`, project `ai-sandbox-android-ui`). The dev-team agents must obtain the bundle (URL captured in the README they'll generate) and reproduce the visual specification in Jetpack Compose. **Do NOT copy the prototype's React/HTML structure** — recreate the pixel output in Compose using the screens, components, and tokens listed below.

### Theming (Material 3 Expressive, dark only)

Color tokens — source CSS variable → suggested Compose colour-scheme slot:

| Token | Hex | Compose role |
|---|---|---|
| `--bg-workbench` | `#0a0a0c` | terminal background, sheet scrim |
| `--surface` | `#131216` | base surface |
| `--surface-low` | `#1a181d` | card surface |
| `--surface-high` | `#2a282e` | button (idle) |
| `--surface-highest` | `#34323a` | tooltip |
| `--on-surface` | `#ece6ec` | body text |
| `--on-surface-variant` | `#c8c2cc` | secondary text |
| `--on-surface-muted` | `#8c8693` | captions, mono helper text |
| `--outline` | `#7a747e` | strong borders |
| `--outline-variant` | `#3a373d` | dividers, list separators |
| `--error` | `#ffb4ab` | error text |
| `--error-container` | `#5d1a17` | error backgrounds |
| `--success` | `#8ad6a5` | connected dot, FOREGROUND pill |
| `--warning` | `#ffb784` | starting state, attached-warning |
| `--accent` | `#ece6ec` | mono-warm default accent |
| `--on-accent` | `#1f1d22` | text on accent |
| `--accent-container` | `#2a262e` | accent surface |
| `--on-accent-container` | `#efe9ef` | text on accent surface |

Fonts: **Roboto Flex** (sans, primary UI) and **JetBrains Mono** (mono, all session IDs, fingerprints, connection metadata, terminal output, and any code-shaped string). System fallbacks `Roboto`, `system-ui`, `ui-monospace`.

### Screen inventory

| # | Screen | Purpose |
|---|---|---|
| **UC04-1** | QR Onboarding | Full-bleed camera viewfinder with reticle + scan-line; three sequenced stages (`scan` → `imported` → `ready`); identity-imported state shows server host, cert pin, cert CN/expiry, KeyStore badge. |
| **UC04-2** | Sessions list | Large M3 top bar with server URL + mTLS subtitle; filter chips (All / Running / Stopped); session rows in one of three densities (cards default, rows, compact); extended FAB "New session". |
| **UC04-2a** | New session sheet | Bottom M3 sheet; **label-only** input with a help line *"Image and hardware specs are fixed by the server profile — not selectable from the client."*; Cancel + Spawn actions. |
| **UC04-2b** | Delete confirm | M3 dialog with delete icon, "Delete ai-sandbox-N?" title; warning line when other clients are attached; Cancel + Delete (error tone). |
| **UC04-3** | Terminal | Compact mono top bar with session name + status dot + connection metadata `wss · subproto ai-sandbox.v1 · <cols>×<rows> · tmux <stream-id>`; back button; split-pane toggle; more-overflow. Terminal pane below. Modifier bar at bottom (docked default). Long-press → floating selection menu (Copy / Paste / Select all / Send to…). |
| **UC04-4** | Foreground notification | System-shade ongoing notification with accent square avatar + "ai-sandbox" app name + "now" timestamp + green `FOREGROUND · dataSync` pill, title `Attached to ai-sandbox-<N>`, body `wss://<host>:<port> · <cols>×<rows> · idle <s>s`, actions Open + Disconnect (error tone). |
| **UC04-5** | Settings | Sectioned list — Server (URL + pin with copy button), Client identity (cert card with KeyStore-non-exportable badge, ISSUED/EXPIRES/SERIAL/KEY grid, full fingerprint), WebSocket (subprotocol / ping interval / per-client tmux stream-id), Diagnostics (Simulate cert revoke for testing the AC25 path). Footer: `ai-sandbox-android <version> · minSdk 29 · sideload · no telemetry · no SDK`. |
| **UC04-7** | Cert-revoked dialog | M3 dialog with red-shield error icon, title "Identity revoked", body referencing `aisandboxctl client invite` and the re-scan flow; Later + Scan new QR actions. |

### Components

The design uses an M3 Expressive component family. The dev-team must implement the listed slots — naming the Compose equivalents is the developer's choice, but the visual behavior is fixed:

- **M3TopBar** (regular + large variant; large used on sessions list)
- **M3IconButton** (default 40 dp, optional 36 dp size)
- **M3Chip** (filter chips with leading dot)
- **M3FAB** (extended variant; large mono-warm default)
- **M3Sheet** (bottom sheet for the new-session form)
- **M3Dialog** (delete confirm, cert-revoked, with icon + title + body + actions)
- **M3Button** (text / filled / error variants)
- **StatusPill** (running green, starting amber outline, stopped gray)
- **Avatar** (rounded square with zero-padded session N; large variant on cards)

### Iconography

Lucide-style monoline icons, 20–24 dp. The design references at minimum: `shield`, `settings`, `more`, `arrow-back`, `dot`, `check`, `qr`, `close`, `flash-on`, `delete`, `lock`, `copy`, `refresh`, `circle-dot`, `warning`, `dock`, `add`, `terminal`, `split`, `keyboard`. Use `androidx.compose.material.icons.outlined.*` or an equivalent lucide-for-compose port; do not commission custom icons.

### Operator-side variations (Tweaks panel)

The design exposes three operator-selectable variations. v0.1 ships the recommended default for each; the alternatives may be implemented behind a build-time configuration if low-cost but are not required:

- **Modifier bar layout** — **docked** (default) / floating / collapsible
- **Session list density** — **cards** (default) / rows / compact
- **Accent swatch** — **mono-warm** `#ece6ec` (default); design includes 4 alternates (operator decides at scaffold time, not user-visible)

## Server-side scope additions (in scope of this UC's `/develop` run)

UC04's enrollment flow requires two server-side additions to UC03's already-shipped surface. Both land in this UC's `/develop` run, on top of the already-merged `server/` module:

1. **`POST /v1/enrollment`** (REST endpoint, mTLS-exempt by design — the whole point is bootstrapping mTLS — but rate-limited per-IP and gated by a one-time token):
   - Request body: `{"token": "<opaque-token>"}` (≤256 bytes; rejected with `413` over).
   - Response 201: `application/octet-stream` of the PKCS#12 bundle for the issued client cert.
   - Response 401: `application/problem+json` `enrollment_token_invalid` if the token is unknown, expired, or already redeemed.
   - Response 429: per-IP rate-limited (1 redemption per 60 s by default; configurable).
   - Token lifetime: 10 min default; configurable. Tokens are single-use — redemption deletes the token immediately.
   - The endpoint is **the only** anonymous path; all other endpoints remain mTLS-gated. THREAT_MODEL.md gets a new section covering this trust-boundary widening.
2. **`aisandboxctl client invite <name>`** (new CLI subcommand):
   - Generates an unguessable enrollment token (≥256 bits of entropy, hex-encoded), stores it in a token store under the server's PKI directory (`/etc/ai-sandbox-server/enrollment/`, mode 0700, owned by `ai-sandbox-server`), and prints the token's QR-ready JSON payload `{u, t, exp, pin}` to stdout — as a single QR (the payload is ~200 bytes, well within a v3 QR's capacity).
   - The actual client cert is minted **on redemption** (not on invite issue), so an unredeemed/expired token leaves no cert behind.

These two changes mean UC04's `/develop` run touches both `server/` and the new `android/` module. The dev-team's analyst must plan accordingly.

The previously-clarified **`aisandboxctl client mint <name> --qr`** flag (multi-frame QR) is **no longer needed** — it is replaced by `aisandboxctl client invite`. Drop AC31 from the older draft (now reflected in this revision).

## Acceptance Criteria

### Android client

1. App targets `minSdk = 29` (Android 10), latest stable `compileSdk` + `targetSdk`, Kotlin + Jetpack Compose, Material 3 (Expressive baseline).
2. Layouts: phone (single-pane, list ↔ terminal navigation); tablet (split-pane at `≥600 dp` — sessions list permanently on the left, terminal on the right).
3. **QR onboarding (UC04-1)** — full-bleed camera screen reads a single QR carrying `{u, t, exp, pin}`. The reticle + scan-line animation matches the design. After capture, the app POSTs `{"token": t}` to `<u>/v1/enrollment`, pinning the server cert against the SHA-256 fingerprint embedded in `pin` *before* the request is sent.
4. Import confirmation panel shows server URL, server cert SHA-256 fingerprint, client cert CN + expiry, and a "stored in Android KeyStore · non-exportable" badge. User taps **Continue** to proceed to the sessions list.
5. Android KeyStore (hardware-backed when supported) holds the imported client private key; the app uses it as the sole TLS client identity. The PKCS#12 is destroyed after the key has been imported.
6. **One server profile at a time.** Re-scanning a QR while a profile already exists shows a "Replace existing identity?" confirmation; on confirm, old KeyStore entries are deleted before importing new ones.
7. All non-enrollment network calls use mTLS via the imported client cert. Server cert is **pinned** to the SHA-256 fingerprint received during enrollment — any other server cert results in a hard refusal screen ("Server identity changed — re-scan a QR").
8. **Sessions list (UC04-2)** — large top bar showing `ai-sandbox` and the connection subtitle `<host>:<port> · mTLS` with a green status dot. Filter chips above the list: **All · <n>** (selected by default), **Running · <m>**, **Stopped**. List rows render in the **cards** density by default; alternate densities are operator-selectable design variations.
9. **Spawn (UC04-2a)** — extended FAB "New session" → bottom M3 sheet → **label-only** input (placeholder `e.g. release-build, scratch`) → `POST /v1/sessions {"label": "..."}`. Image and hardware specs are not selectable from the client — server profile defaults apply. A new row appears optimistically with `starting` status and a 2-px warning-amber outline on the avatar; transitions to `running` on the API 201.
10. **Delete (UC04-2b)** — long-press a row → confirm dialog with the M3 delete-icon, "Delete ai-sandbox-N?" headline, body explaining the container and tmux session are destroyed on the server; when `attached > 0` the dialog shows an amber warning line *"<n> client(s) currently attached and will be disconnected."*. Confirm sends `DELETE /v1/sessions/{n}`, with an opt-in toggle in the sheet to send `force=true` for sessions with active streams.
11. **Open** — tap a row → full-screen terminal view (UC04-3).
12. The terminal is rendered by Termux's `TerminalView` (Apache 2.0) wrapping the WebSocket binary stream. Handles ANSI / xterm 256-color + true-color, scrollback, and the tmux status line. Top bar shows: back button, mono session name, status dot (success-green), mono metadata `wss · subproto ai-sandbox.v1 · <cols>×<rows> · tmux <stream-id>`, split-pane toggle, more-overflow.
13. **Theme** — dark only, fixed per the M3 Expressive token table above, mono-warm accent default.
14. **Bell** — terminal BEL (`0x07`) triggers a brief 150 ms haptic vibration. No sound, no flash.
15. **Modifier bar (docked default)** — toolbar above the system soft keyboard with the tmux prefix tile (`⌘ tmux`, accent-container background, becomes accent-on-accent when armed), Ctrl, Alt, Esc, Tab, ↑/↓/←/→, and a Fn-row toggle that reveals F1–F12. Modifiers are sticky-one-shot (tap arms; auto-clears after the next character or after 1.8 s if tmux-prefix not consumed). Floating and collapsible variants per the design are recognized as design variations; v0.1 ships only the docked variant.
16. **Long-press selection menu** — long-press in the terminal opens a small horizontal menu near the press point with Copy / Paste / Select all / Send to…. **Send to…** routes through `Intent.ACTION_SEND` so the user can share the selection to any installed app.
17. **Paste** — Paste in the selection menu inserts clipboard contents into the WebSocket binary channel unchanged.
18. **Resize** — on rotation, fold, soft-keyboard show/hide, split-pane toggle, or any pane-size change, the app sends `{"type":"resize", "cols":…, "rows":…}` as a JSON text frame within 200 ms.
19. **Mouse gestures** — drag-to-resize-pane, long-press-as-right-click (in addition to opening the selection menu when no terminal mouse-mode is active), two-finger drag → mouse-wheel. Emitted as JSON `{"type":"mouse", …}` frames per `STREAM_PROTOCOL.md`. Coordinates map to terminal cells.
20. **Split-pane mode** — the terminal toolbar's split icon toggles a stacked vertical split. Both panes share the same WebSocket and tmux session id but each renders an independently-resizable tmux pane (`tmux split-window -v` server-side via control frames). A draggable horizontal divider (8 dp, with a 32-dp drag handle indicator) sits between them. Split state is local to the device and is not persisted across app restart.
21. The WebSocket survives backgrounding, screen lock, and task-switch via a foreground service of type `dataSync` (Android 14+ aware), started on stream open, stopped on disconnect.
22. **Foreground notification (UC04-4)** matches the design: small accent square avatar with "S", "ai-sandbox" app name, "now" timestamp, a `FOREGROUND · dataSync` pill in success-green, title `Attached to ai-sandbox-<N>`, body `wss://<host>:<port> · <cols>×<rows> · idle <s>s` (idle counter updates every 5 s while attached, freezes during reconnect), actions **Open** (accent text) and **Disconnect** (error text).
23. **Battery-optimization prompt** — on the **first** stream open after install, a one-time dialog explains the trade-off and tap-throughs to the system settings screen. If declined, foreground service still runs; README documents the OS may kill it on long idles.
24. **Reconnect on drop** — exponential backoff (1 s, 2 s, 4 s, 8 s, capped at 30 s) with a "Reconnecting…" toolbar indicator and a paused idle counter in the foreground notification.
25. **5-minute cap** — after 5 min of failed retries, app stops, dismisses the foreground notification, and surfaces "Disconnected — tap to reconnect" in the toolbar. User taps to restart.
26. **Server-side revocation (UC04-7)** — when the server tears down the WebSocket (≤1 s per server's AC13), the app surfaces the M3 dialog with red-shield icon, title "Identity revoked", body explaining the device's cert was revoked, and a primary action **Scan new QR** that returns to UC04-1. The dialog dismisses to the import screen, not back to the sessions list — there is no functional state left.
27. **Settings screen (UC04-5)** is organized into four sections:
    - **Server** — URL row (with green "connected" status pill); pin row with `sha256/…` fingerprint and a copy-to-clipboard button.
    - **Client identity** — cert card with KeyStore-non-exportable badge, KEY/SERIAL/ISSUED/EXPIRES grid, full SHA-256 fingerprint.
    - **WebSocket** — subprotocol (`ai-sandbox.v1`), ping interval (`30 s · auto-reconnect on lock`), per-client tmux stream id and current `<cols>×<rows>`.
    - **Diagnostics** — destructive **Simulate cert revoke** action that locally invokes the AC26 cert-revoked path (no server call), for testing the re-scan flow.
    - Footer block: `ai-sandbox-android <version> · minSdk <29> · sideload · no telemetry · no SDK`.
28. App is signed: `release` signing config fed by env vars / `~/.gradle/keystore.jks`, documented in operator README. Debug builds use the debug keystore.
29. The app ships **no** analytics, telemetry, Crashlytics, or any third-party SDK that phones home. Only outbound HTTPS is to the configured ai-sandbox server.
30. CI workflow `.github/workflows/android-ci.yml` runs lint + unit tests + AAB build on every PR touching `android/**` and on every merge to `main`.
31. Release workflow `.github/workflows/android-release.yml` is triggered by `android-vX.Y.Z` tags. Emits signed APK + AAB as GitHub Release assets.

### Server-side (within UC04 scope; on top of UC03)

32. **`aisandboxctl client invite <name>`** — new CLI subcommand. Generates a ≥256-bit hex token, stores `{token, name, exp}` in `/etc/ai-sandbox-server/enrollment/<token-prefix>.json` (mode 0600, owned by `ai-sandbox-server`), and prints to stdout a single QR encoding the JSON `{u, t, exp, pin}` (server URL, token, ISO-8601 expiry, server cert SHA-256 pin). When stdout is not a TTY, writes the QR as a PNG to the directory passed via `--out`. Default token lifetime: 10 min, configurable via `--ttl <duration>`.
33. **`POST /v1/enrollment`** — new server endpoint (Spring Boot, on the same single TLS port; **TLS-required but mTLS-EXEMPT** — the only such path). Request: `application/json {"token": "..."}`, ≤256 bytes. Validates token (exists, not expired, not redeemed), atomically deletes the token file (single-use), mints a fresh client cert (BouncyCastle, RSA 2048 to match UC03's policy), writes the public cert into the allowlist folder (so `AllowlistWatcher` picks it up; new client can mTLS immediately on next call), and returns 201 `application/octet-stream` containing the `<name>.p12` bundle (passphrase = empty for transport; the bundle is consumed in-memory by the client and never written to durable storage).
34. **Per-IP rate limit on `/v1/enrollment`** — 1 redemption per 60 s per source IP (configurable). Trip returns 429 + Problem-Details `enrollment_rate_limited`.
35. **`POST /v1/enrollment` error codes** — `enrollment_token_invalid` (401), `enrollment_token_expired` (401), `enrollment_token_redeemed` (401), `enrollment_rate_limited` (429), `payload_too_large` (413). All in RFC 9457 form.
36. **THREAT_MODEL.md update** — extends `docs/THREAT_MODEL.md` (shipped with UC03) with a new section covering the enrollment trust boundary: the single mTLS-exempt path, single-use token semantics, rate limiting, the operator's responsibility to keep `/etc/ai-sandbox-server/enrollment/` tight, and the explicit non-goal of using the endpoint for re-keying existing clients (revocation flow uses the existing allowlist `DELETE` and forces re-invite).
37. **Server-side enumeration includes stopped containers** — `DockerEnumerationService` (UC03) is extended to also list `ai-sandbox-N` projects whose container is in `exited` / `created` state, not just `running`. The `GET /v1/sessions` response carries a `status: "running" | "starting" | "stopped"` field per session; existing fields preserved.

### Project / brief / repo

38. **Module layout** — new Gradle subproject `<TARGET_DIR>/android/` rooted at the existing top-level Gradle build. Shares the wrapper with the `server/` module.
39. App is signed: `release` signing config fed by env vars / `~/.gradle/keystore.jks`, documented in operator README. Debug builds use the debug keystore. (Repeats AC28 — kept here so the project-scope view is self-contained.)
40. **Brief revision is operator scope.** This UC's `/develop` run **requires** that `PROJECT_BRIEF.md`'s frontmatter already lists Kotlin under `stack.languages`, `jetpack-compose` under `stack.frameworks`, `android/**` declared in `paths.production` (or implicitly covered by the existing `**` glob), and that the prose Deployment section mentions both `android-ci.yml` and `android-release.yml`. Operator must run `/revise-brief` after saving this use case and before `/develop`.

## Resolved during clarification

- **Enrollment shape** — **token-based** with a one-time enrollment token in a single QR + a new `POST /v1/enrollment` server endpoint + an `aisandboxctl client invite` CLI subcommand. Replaces the earlier multi-frame-QR-carries-p12 choice. Decided by design pivot in UC04-1.
- **Terminal renderer** — Termux's `TerminalView` (Apache 2.0).
- **Tablet UX** — split-pane on `≥600 dp` width (sessions-level navigation, not the in-terminal split-pane mode which is AC20).
- **Distribution** — signed APK + AAB on GitHub Release via tag-triggered workflow.
- **Server multi-tenancy** — one server profile at a time; replace-on-rescan.
- **Cert naming** — one cert per device.
- **Reconnect-cap UX** — 5-min cap → manual "Disconnected — tap to reconnect".
- **Battery optimization** — one-time prompt on first stream open + system-settings deep-link.
- **Theme** — dark only, fixed M3 Expressive token set per the design bundle, mono-warm accent default.
- **Bell** — vibrate (150 ms).
- **Spawn form scope** — label-only. Image and hardware specs are fixed by the server profile.
- **Sessions list filter** — All / Running / Stopped chips above the list.
- **Modifier bar default** — docked; floating + collapsible recognized as operator design variations not required for v0.1.
- **Split-pane mode** — in-terminal split via split-icon toggle, stacked vertical, draggable divider; not persisted.
- **Selection menu** — Copy / Paste / Select all / Send to… (Send-to via Android share intent).
- **Brief revision timing** — operator runs `/revise-brief` after this UC is saved and before `/develop`.

## Potential Pitfalls & Open Questions

- **Risk** — **Enrollment endpoint widens the trust surface**. `POST /v1/enrollment` is the only non-mTLS path. Rate limiting + single-use tokens + 10-min expiry + token-store-on-disk hygiene are mitigations; THREAT_MODEL.md must capture the residual risk explicitly (and a future revision should consider deriving enrollment authority from a separate operator-only mTLS-gated bootstrapping channel, but that adds operator burden today).
- **Risk** — **Signing-key backup discipline**. The GitHub Actions signing-key secret needs an offline backup. Operator README must call this out and document the recovery path (re-key + new install required for the partner's phone).
- **Risk** — **AC37 (enumeration shows stopped containers) changes a UC03 contract.** UC03's `DockerEnumerationService` filters to running today. Extending it to include stopped is a semantic change; existing API consumers expecting "list = running" may break. UC03 has only the Android client as a consumer today, so safe — but flag in the dev-team's implementation notes.
- **Edge case** — **Foldables / Chromebooks**: out of scope. The `≥600 dp` breakpoint will Just Work in tablet mode on most foldables but it is not a verified target.
- **Edge case** — **Token-store concurrency**. Two simultaneous redemptions of the same token (race) must both not succeed. The token-file atomic-delete must be the serialization point (`Files.deleteIfExists` is atomic on POSIX); guard with a `synchronized` block on the enrollment service.
- **Edge case** — **Camera-permission flow**. Android 13+ requires the runtime camera permission; the onboarding screen needs to handle a denial gracefully (a fallback "enter token manually" affordance may be required if denial blocks the QR scan; v0.1 may simply refuse with a clear message and stop).
- **Assumption** — **PKCS#12 transport passphrase is empty.** The bundle is delivered over the same TLS connection that authenticated the token, never written to disk on the client. THREAT_MODEL.md should document this.

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
  A (round 1): Multi-frame animated QR carries the whole p12.
  A (overridden by design pass): **Token-based** — single QR carries an enrollment token + server URL + pin; app POSTs to `POST /v1/enrollment` to fetch the bundle. The design's UC04-1 is the source of truth.

- Q: Which terminal-rendering library should the app use?
  A: Termux's `TerminalView` (Apache 2.0).

- Q: Tablet UX layout?
  A: Split-pane (list left, terminal right) at `≥600 dp` width.

- Q: Distribution / signing for the internal sideload?
  A: Signed APK + AAB on GitHub Release; `android-release.yml` emits both on every `android-vX.Y.Z` tag.

- Q: Can the app be enrolled to more than one ai-sandbox server?
  A: One server at a time. Re-scanning a QR overwrites the existing identity.

- Q: Cert-per-device naming convention?
  A: One cert per device (`alice-phone`, `alice-tablet`, …).

- Q: Behavior after the 5-minute reconnect cap is hit?
  A: Stop retrying, dismiss the foreground notification, and surface "Disconnected — tap to reconnect".

- Q: How should the app handle Android battery optimization for the foreground service?
  A: Prompt once on first stream open + document the trade-off in the README.

- Q: Terminal theme / color scheme?
  A: Dark only — design's M3 Expressive token table is authoritative.

- Q: Terminal bell handling?
  A: Vibrate briefly (150 ms haptic).

- Q: Settings screen scope?
  A (round 1): Minimal.
  A (refined by design pass): Four sections — Server, Client identity, WebSocket, Diagnostics — matching the design's UC04-5.

- Q: When does the brief get revised to add Kotlin + Compose + android-* CI to PROJECT_BRIEF.md?
  A: Operator runs `/revise-brief` after this UC is saved and before `/develop`.

- Q: The design implies token-based enrollment instead of the multi-frame QR you previously chose. Which model do we ship?
  A: Token-based (design pass) — *"It is in UC04-1 in the design."*
