---
plan_for: "(free-form task) — fix 3 bugs in project-selector (UC-98) / tmux-input (UC-99) feature"
work_branch: feat/fix-project-select-tmux-input
team: ai-sandbox
approved: 2026-07-16
---

FINAL APPROVED IMPLEMENTATION PROPOSAL — ai-sandbox project-selector (UC-98) / tmux-input (UC-99) three-bug fix. Analyst↔challenger agreed (challenger approved round 2). Prose only, no code snippets.

============================================================
### Analysis
============================================================
Three related bugs in the recently-added project-selector / tmux-input feature, spanning UC-98 (spawn-workspace-project-selector) and UC-99 (tmux-phone-input-quality-proposals). All three were traced end-to-end against source.

Key finding: the UC-98 spawn-selector chain — Android `NewSessionSheet` → `SessionsViewModel.spawn(label, projectId)` → `SpawnRequest.workspaceProject` → `ApiMappers.toSpawnCommand` → `SessionController` → `SessionFacade.spawn` → `scheduleWorkspaceProjectInjection` → `runWorkspaceProjectInjection` → `SpawnPromptInjector.inject` (impl `ConversationFacade.inject`) → `InputInjectionService.injectComposer` — is fully and correctly wired, and `injectComposer` DOES type the literal (`tmux send-keys -l`) AND press `Enter`. Collaborators are wired via `@Autowired(required=false)` setters. So Bug 1 is NOT a missing selection or a missing Enter; it is a submit-timing race (detailed below). Bugs 2 & 3 are two coupled halves of the UC-99 terminal (tmux phone-view) decoupled composer, which delivers input over the raw-PTY stdin path (`TerminalStreamController.sendStdin(encodeComposerLine(text))`), distinct from the UC-37 conversation composer's server-side `send-keys` path.

Bug 1 root cause: `runWorkspaceProjectInjection` injects the moment `SessionReadinessService.awaitReady` observes the container marker `/tmp/aisandbox-ready`. That marker is written by `entrypoint.sh` synchronously right after `tmux new-session -d` (verified: `entrypoint.sh` ~L286-293 `touch $READY_MARKER`), and `tmux new-session -d` returns on session *creation*, not on Claude's TUI becoming interactive. `injectComposer` then fires the literal and the `Enter` back-to-back into a still-booting Claude: the text lands in the input box (staged) but the `Enter` is consumed/lost before the prompt is ready to submit → prompt staged, never submitted. This exactly matches the reported symptom, and explains why the conversation composer (same `injectComposer`) is unaffected — by the time a user types in an attached session Claude's TUI has long been ready; only the spawn path fires pre-prompt.

Bug 2 root cause: `Composer` (`android/.../ui/components/Composer.kt`) always initialises `var text by rememberSaveable { mutableStateOf("") }`; the terminal composer (`TerminalScreen`, `onSubmit = viewModel::submitComposerLine`) never seeds from whatever is already at Claude's input line (e.g. text typed earlier via the raw Termux passthrough view UC-99 keeps for power users).

Bug 3 root cause: `TerminalViewModel.submitComposerLine` → `sendStdin(encodeComposerLine(text))`, where `encodeComposerLine` (`android/.../terminal/TerminalComposer.kt`) = `UTF-8(text) + CR(0x0D)`. This types the buffer onto whatever is already at Claude's input line then submits, so a non-empty pending line yields `pending + buffer` submitted as one message — the append bug.

============================================================
### Proposed Solution
============================================================

--- Bug 1 (SERVER, stream-domain only) — reliable auto-submit with exactly-once ---
Add a spawn-scoped inject variant in `InputInjectionService` (do NOT modify the shared `injectComposer` — the conversation path is verified-good). `ConversationFacade.inject` (the `SpawnPromptInjector` port impl) calls the new variant instead of `injectComposer`. The variant enforces exactly-once across every branch:
1. Gate before typing — poll `tmux capture-pane -p` until Claude's input prompt box is present (TUI interactive). On gate timeout → log + audit `prompt-not-ready`, inject NOTHING (no type, no Enter → 0 submits).
2. Type the literal (reuse the existing literal/multiline type helper).
3. Confirm before Enter — poll `capture-pane` until the just-typed literal appears in the pane. This is a SUBSTRING-PRESENCE check, NOT a structural TUI parse, so it does not duplicate the Node box-parser (`aisandbox-conversation-tail`) or add pin-drift surface.
4. Confirm success → send EXACTLY ONE `Enter` (the sole submit).
5. Confirm timeout → log + audit `type-not-confirmed`, do NOT send Enter (never a blind Enter; graceful degrade consistent with AC10's fail-to-no-project philosophy).
Both timeout branches send ZERO Enter; a submit happens only after positive confirmation → at most one submit, never a blind submit. All `capture-pane` calls run through `InputInjectionService`'s existing `ProcessExecutor` (stream domain), and the `stream→sessions` inversion via `SpawnPromptInjector` is preserved — no new package cycle. `SessionReadinessService`/`SessionFacade` are unchanged (the container-marker wait remains a cheap pre-filter; the prompt-ready gate lives in the new variant).

--- Bug 2 (ANDROID, client-side only) — pre-populate composer from the pending pane line ---
No server involvement, no new endpoint, no layering risk. The vendored Termux terminal is an in-tree Gradle module (`terminal-emulator/`); `TerminalEmulator` exposes `getScreen()` (L347), `getCursorRow()` (L424), `getCursorCol()` (L428), and `getSelectedText(x1,y1,x2,y2)` (L2570). `TerminalStreamController` continuously pumps WS stdout into that emulator (`WsTerminalSession.feed`) regardless of which input surface has focus, so the client already holds the live pane buffer.
- Add a pure Kotlin helper in the ANDROID module at `android/src/main/kotlin/com/aisandbox/android/terminal/PendingInputReader.kt` that reads the pending input line ANCHORED ON THE CURSOR: Claude's TUI parks the cursor inside the input box, so `getCursorRow()` gives the input row directly. Read that row's text (via `getSelectedText`/row read), strip the box decoration (leading `│` / `> ` prompt marker, trailing border + spaces), and return the pending text — or return `""` when the cursor row is not a recognizable input line (safe fallback: never a wrong prefill). This is a single-row, cursor-anchored extraction, NOT a whole-screen box scan.
- Wire: `TerminalStreamController.readPendingInputLine()` (reads its emulator) → surfaced by `TerminalViewModel` → `TerminalScreen` passes it as `Composer(initialText = …)` when the composer opens / gains focus.
- `Composer` gains an optional parameter `initialText: String = ""`; the default keeps the UC-37 conversation composer byte-identical. Seed the composer's internal `text` state from `initialText`.
- The helper lives in the `android` module (NOT the vendored `terminal-emulator`) so its unit test stays in `paths.test` and the vendored code is untouched.

--- Bug 3 (ANDROID) — send replaces + submits the full buffer, not append ---
In `encodeComposerLine` (`android/.../terminal/TerminalComposer.kt`), prepend a kill-line control byte `Ctrl-U (0x15)` so the delivered sequence is `Ctrl-U + UTF-8(buffer) + CR`. This clears the pane's current input line before typing the buffer, so the composed buffer REPLACES the pending line and commits as one message. On an empty line, `0x15` is a harmless no-op (composer-only common case). Define `0x15` as a named constant beside `COMPOSER_SUBMIT_CR` with a centralized Claude-TUI-2.1.169 pin comment.
- QA MUST live-verify on Claude TUI 2.1.169 that `0x15` actually clears the Ink-based input line. If `0x15` is a no-op on 2.1.169, use the named fallback: delete the prefill-length number of chars (Backspace × prefill-length, length known from Bug 2's prefill) then type buffer + CR; or `Ctrl-A (0x01) + Ctrl-K (0x0B)` if supported.
- Coupled with Bug 2, the composer becomes an editable mirror of the pending line: prefill shows the pending text, send replaces + submits it — no duplication.

============================================================
### Files Affected
============================================================
Classified per PROJECT_BRIEF frontmatter — `paths.production` = ["**","!workspace/**","!secrets/**","!claude-config/**","!.git/**"]; `paths.test` = ["server/src/test/**","android/src/test/**","android/src/androidTest/**"].

Production code (developer):
- server/src/main/java/com/aisandbox/server/stream/service/InputInjectionService.java — Bug 1: new spawn-scoped gate→type→confirm→single-Enter variant (substring confirm; capture-pane via existing ProcessExecutor).
- server/src/main/java/com/aisandbox/server/stream/facade/ConversationFacade.java — Bug 1: `inject` (SpawnPromptInjector impl) calls the new variant.
- android/src/main/kotlin/com/aisandbox/android/terminal/PendingInputReader.kt — Bug 2: NEW cursor-anchored pending-input extraction helper.
- android/src/main/kotlin/com/aisandbox/android/terminal/TerminalStreamController.kt — Bug 2: expose `readPendingInputLine()`.
- android/src/main/kotlin/com/aisandbox/android/ui/screens/TerminalViewModel.kt — Bug 2: surface the pending line to the screen.
- android/src/main/kotlin/com/aisandbox/android/ui/screens/TerminalScreen.kt — Bug 2: fetch on composer open/focus, pass as `Composer(initialText=…)`.
- android/src/main/kotlin/com/aisandbox/android/ui/components/Composer.kt — Bug 2: add `initialText: String = ""` (default keeps conversation composer byte-identical).
- android/src/main/kotlin/com/aisandbox/android/terminal/TerminalComposer.kt — Bug 3: `encodeComposerLine` Ctrl-U (0x15) prefix + pinned constant.
- (No server changes for Bug 2; SessionFacade.java and SessionReadinessService.java unchanged.)

Test code (qa):
- server/src/test/java/com/aisandbox/server/stream/service/InputInjectionServiceTest.java — Bug 1: assert gate → type → confirm → single Enter on the happy path, and ZERO Enter on BOTH the gate-timeout and confirm-timeout branches (exactly-once).
- server/src/test/java/com/aisandbox/server/stream/facade/ConversationFacadeSpawnInjectTest.java — Bug 1: update to the new variant (inject + submit, server-actor audit).
- server/src/test/java/com/aisandbox/server/sessions/facade/SessionFacadeWorkspaceInjectTest.java — Bug 1: update for the new choreography.
- android/src/test/kotlin/.../terminal/PendingInputReaderTest.kt — Bug 2: cursor-row extraction, box-decoration strip, empty fallback for a non-input cursor row.
- android/src/test/kotlin/.../terminal/TerminalComposer*(encoder) test — Bug 3: assert Ctrl-U prefix (+ CR trailer) in `encodeComposerLine`.
- android/src/androidTest/kotlin/.../ — Compose/gate coverage: composer prefill (Bug 2) and replace-on-send (Bug 3), verified together; the UC-85 deterministic gate must stay green.

============================================================
### Risks & Considerations
============================================================
- Claude TUI 2.1.169 version-pin (TOP RISK): Bug 2 = single-row cursor-anchored decoration strip (client); Bug 1 = substring-presence check (no structural parse); Bug 3 = 0x15 line-kill (needs live verify). Centralize the pin so a version bump is one edit. Per UC-97 the Claude version is pinned to 2.1.169; a TUI change can break any of these.
- Bug 1 exactly-once (AC4): guaranteed — at most one Enter, only after positive confirmation; all failure branches (gate timeout, confirm timeout) send zero Enter. Never re-send Enter blindly.
- Bug 2 fallback-empty: if the cursor row is not a recognizable input line, prefill with "" — never a wrong prefill.
- Bug 3 non-blocking notes (challenger, for dev/QA): (a) the backspace-by-prefill-length FALLBACK assumes the pane line still holds exactly the prefilled chars at send time; if the user edits the raw passthrough view after the composer prefilled, the counts drift and backspace would over/under-delete. The Ctrl-U primary has no such issue (kills the whole line-to-start regardless of length), so this only matters if 0x15 turns out to be a no-op — comment it where the fallback lives, and only use the fallback then. (b) The pane line can change between prefill-read and send; this is fine because Ctrl-U clears whatever is actually there on send — do NOT add any send-time logic that assumes the prefilled text still matches the pane.
- Regression: `Composer.initialText=""` keeps the UC-37 conversation composer byte-identical; the vendored `terminal-emulator` module is NOT edited; Bugs 2 & 3 are coupled — implement and verify them together. UC-99's input area is the most regression-prone (UC-21 terminal emulation, UC-23 IME insets, UC-36 conversational keyboard/composing flush, UC-85 gate); keep the UC-85 deterministic gate green and preserve the raw-PTY passthrough path.
- Profiles: `profile-java-server-architecture` — Bug 1 stays within the stream domain and preserves the `SpawnPromptInjector` port-inversion (contract in `sessions`, impl in `stream`), so no new package cycle is created (`LayeringTest.no_cycles_between_top_level_feature_packages` stays green); Bug 2 introduces no server edge at all. No PROJECT_BRIEF conflicts. `profile-java-call-graph-tool` was not provisioned for this run — investigation used direct source reading.

Challenger verdict: **Approve** (round 2 of 6). Proceed to implementation.
