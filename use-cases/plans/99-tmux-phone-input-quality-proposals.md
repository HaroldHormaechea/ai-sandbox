---
plan_for: use-cases/99-tmux-phone-input-quality-proposals.md
work_branch: feat/uc-99-tmux-phone-input-quality-proposals
team: ai-sandbox-uc-99
approved: 2026-07-16
---

# UC-99 — tmux phone-view text-input — FINAL APPROVED PROPOSAL (B1)
Challenger-approved (round 2). Decision: **B1 — decoupled native local composer → existing PTY stdin.** stream-json Approach C = **NO-GO** for the input problem. All paths absolute under TARGET_DIR = `/workspace/ai-sandbox-uc-99-tmux-phone-input-quality-proposals`.

## Analysis — the two symptoms, separated (code-grounded, challenger-verified)

**Symptom 1 — LAG = transport/echo (no local echo).** The vendored Termux `TerminalView` renders ONLY the emulator screen buffer `mEmulator`, fed exclusively by server-echoed PTY bytes: `WsTerminalSession.feed()` → `session.appendToEmulator` (`.../terminal/WsTerminalSession.kt:78`). The IME `Editable` held by the retained `BaseInputConnection` (`terminal-view/.../TerminalView.java:345`) is never painted. On commit/finish/sendKeyEvent it runs `sendTextToTerminal`→`inputCodePoint`→`TerminalSession.write`→`onStdin` (`WsTerminalSession.kt:59`) → `controller.sendStdin` (`.../terminal/TerminalStreamController.kt:118`) → `StreamClient` binary WS → server `SessionStreamHandler` → `TmuxBridgeService.writeStdin` → pty4j. A character becomes visible ONLY when Claude/tmux redraws its input box and that redraw streams all the way back. On mobile RTT that round-trip *is* the perceived lag. UC-36 buffers the composing region locally (no char-by-char echo) but committed text still round-trips → committed-text lag persists. Structural (RND §3).

**Symptom 2 — AUTOCORRECT MANGLING = buffer-model (bufferless terminal).** The IME composes/corrects against its own `Editable`, expecting a normal editable field (setComposingText / deleteSurroundingText / re-commit). But the view CLEARS the editable on every commit (`content.clear()`, `TerminalView.java:382`) and translates `deleteSurroundingText` into a run of DEL KeyEvents fired at the PTY (`TerminalView.java:398`, note the "Samsung Auto check spelling sends leftLength>1" comment). A spell-correction's delete+recommit choreography is applied to a PTY that may have echoed different bytes, and the IME model desyncs → wrong/garbled replacements. UC-36 turned AUTO_CORRECT and CAP off (`computeInputType`, `TerminalView.java:476`) so silent auto-rewrite is gone and suggestions are tap-to-insert, but the structural IME-editing-model vs bufferless-terminal mismatch remains (worse in raw/char mode and with aggressive spell-correct keyboards).

Distinct root causes: lag = transport/echo; mangling = buffer-model. A **local composer fixes both at once** — it gives the IME a real persistent editable (autocorrect works correctly) AND shows local echo (no round-trip until the line is sent).

## Ranked Approaches + Recommendation + stream-json go/no-go

**Family (a) — in-place Termux TerminalView fixes**
- **A1 — inputType/composing normalization.** Lag: **none**. Autocorrect: marginal. Effort: low. Regression: **HIGH on UC-36** (pinned by `TerminalInputTypeTest` + `TerminalInputConnectionDeliveryTest`). Quick-win ceiling already taken; cannot fix lag → not primary.
- **A2 — speculative emulator local echo.** Would need to PREDICT Claude's full-screen TUI rendering — intractable; mispredict→flicker/garbage. Effort **high**, regression **very high** (vendored emulator, UC-21). **Reject.**

**Family (b) — decoupled local composer**
- **B1 — native Compose composer docked in the terminal screen; submit finalized line to the EXISTING PTY stdin (one-shot +CR).** ⟵ **WINNER.** Reuses shipped UC-37 `Composer` (real OutlinedTextField: full IME/autocorrect/prediction, multiline, Send). Holds the line locally → correct autocorrect + local echo + zero per-keystroke round-trip. On Send, transmit via existing `TerminalViewModel.sendStdin(...)` + CR — one stdin write, like the paste path (`onPasteTextFromClipboard`→`session.write`, `WsTerminalSession.kt:126`). Lag: **eliminated** for composed text. Autocorrect: **fixed**. Effort: low–med (NO server/emulator/protocol change). Regression: **low** (additive). Raw fidelity preserved.
- **B2 — composer → server `send-keys -l`** (reuse `InputInjectionService.injectComposer`). Same effect but needs a NEW control-frame + server handler + protocol bump → version-coupling. Strictly more surface than B1 for no benefit — stdin already reaches the same PTY. Not chosen; kept as precedent.

**stream-json Approach C — GO/NO-GO for the INPUT problem: NO-GO.** (RND §5-C/§11/§12.) Approach C = running Claude a *second way* (`--output-format stream-json`), a separate front-end, NOT a fix to the tmux view. Its input win (local composer, no round-trip) already ships via UC-37's composer and is exactly what B1 brings to the terminal view — without a second Claude, without re-implementing permissions, without losing tmux multiplexing / the agent switcher. Adopt C's kernel (local composer + finalized-line submit) via B1; reject C's packaging (headless second session).

**Recommendation:** **B1.** Fixes lag and autocorrect **equally** (mandated weighting), lowest blast radius, maximal reuse, no server/emulator/protocol change, coexists with raw Termux passthrough + the UC-36 toggle. Appropriate for **mvp**.

## Proposed Solution (winner B1)
Add a native Compose composer as the DEFAULT terminal input surface, with a **primary input-mode control** toggling to raw Termux passthrough (today's behaviour, for interactive-TUI/power use). The composer submits the finalized line to the existing PTY stdin with a trailing CR (`0x0D`). In composer mode the Termux view relinquishes IME focus (a tap on output must not summon the laggy/mangling Termux IME); in raw mode it regains focus and behaves exactly as today. The `ModifierBar` stays available in both modes. The mode is persisted like UC-36 persists its toggle. Encoding is a pure, shared function so tests exercise the real byte path.

## Files Affected

### Production code (paths.production; all absolute)
1. `android/src/main/kotlin/com/aisandbox/android/ui/testtags/GateTestTags.kt` — ADD `object TerminalComposerTestTags { const val INPUT="terminal_composer_input"; const val SEND="terminal_composer_send"; const val MODE_TOGGLE="terminal_input_mode_toggle" }`.
2. `android/src/main/kotlin/com/aisandbox/android/ui/components/Composer.kt` — parametrize the two testTags via optional params defaulting to the current `ComposerTestTags` values, so the same component serves conversation (UC-37, unchanged) + terminal.
3. `android/src/main/kotlin/com/aisandbox/android/ui/components/TerminalSurface.kt` — ADD `inputEnabled: Boolean` (true=raw, false=composer): gate `isFocusable`/`isFocusableInTouchMode`/`requestFocus`; suppress `AiSandboxTerminalViewClient.onSingleTapUp` `showSoftInput` when disabled; `clearFocus()` (→composer) / `requestFocus()`+showSoftInput (→raw) on flip in `update`. Termux input *path* unchanged — only its *activation* is gated. (Closes AC#6 focus hole.)
4. `android/src/main/kotlin/com/aisandbox/android/ui/screens/TerminalScreen.kt` — add input-mode state (composer default ⇄ raw); dock composer + `ModifierBar` in ONE Column inside a SINGLE `windowInsetsPadding(imeInsets)` Box (no double inset); pass `inputEnabled = (mode == raw)` to `TerminalSurface`; wire `Composer(onSubmit = viewModel::submitComposerLine)`; add the MODE_TOGGLE control. MUST NOT touch the `requiredHeight` viewport seam (AC#2 no-PTY-resize invariant).
5. `android/src/main/kotlin/com/aisandbox/android/ui/screens/TerminalViewModel.kt` — ADD `fun submitComposerLine(text: String)` → `controller.sendStdin(encodeComposerLine(text))`; blank → no-op.
6. `android/src/main/kotlin/com/aisandbox/android/terminal/` — ADD pure `fun encodeComposerLine(text: String): ByteArray` (UTF-8 bytes + trailing CR `0x0D`), shared by the ViewModel AND both tests (no test-local copy).
7. `android/src/main/kotlin/com/aisandbox/android/terminal/KeyboardSettingsStore.kt` — ADD boolean pref `terminal_composer_enabled` (default true); UC-36's `conversational` key semantics untouched (scoped to the raw path only).

### Test code (paths.test)
- `android/src/test/kotlin/com/aisandbox/android/terminal/TerminalComposerSubmitTest.kt` — NEW JVM/Robolectric: `encodeComposerLine` contract — ASCII (`grep foo`→`grep foo\r`), UTF-8/emoji preserved, multiline, trailing CR, blank→empty/no-op.
- `android/src/androidTest/kotlin/com/aisandbox/android/gate/TerminalComposerGateTest.kt` — **NEW MANDATORY on-device instrumented Compose test, in the `com.aisandbox.android.gate` package so `gate.sh` runs it** (verified `android/gate.sh:242` runs `am instrument -w -e package com.aisandbox.android.gate`; self-contained `createComposeRule`, no `GateHarness`/enrollment → runs unconditionally, only raises `GATE_MIN_TESTS`). Real `Composer` (with `TerminalComposerTestTags`) + real `encodeComposerLine` into a captured byte sink: (a) type → held locally, sink EMPTY pre-send (lag fix / local echo); (b) tap Send by testTag → exact finalized bytes + CR in sink, field clears (un-mangled delivery via the real encoder); (c) render composer + `ModifierBar` + `TerminalSurface(inputEnabled=false)` together → all displayed AND Termux view NOT focused (coexistence + AC#6 focus-gate); (d) flip MODE_TOGGLE → `TerminalSurface(inputEnabled=true)` regains focus (raw preserved). Stable testTags only.
- QA: assert the full `com.aisandbox.android.gate` suite stays green (UC-85 no-regression); no existing fixtures/tags/`GateHarness`/`ConversationTestTags` touched.

## Risks & Considerations
- **UC-36:** raw input path unchanged; only its activation focus-gated → UC-36 byte-delivery gate + `TerminalInputTypeTest`/`TerminalInputConnectionDeliveryTest` stay green.
- **UC-23:** single `windowInsetsPadding` cluster + untouched `requiredHeight` seam → no PTY resize on IME toggle; UC-23 inset test stays green. The composer legitimately reduces the guarded `restingViewportHeight` — correct, not a bug.
- **Interactive-TUI caveat:** one-shot line submit (+CR) is right for shell/Claude prompt entry, NOT per-key TUI control (vim/less/menus) → that's why raw passthrough stays and `ModifierBar` is in both modes. Documented.
- **Two-toggle UX:** terminal-screen input-mode control = PRIMARY selector (composer ⇄ raw); UC-36's global setting scoped to raw only. Documented.
- **Profiles:** touches NO Java (Kotlin/additive) → no call-graph query; no server-layering impact.
- **maturity=mvp:** B1 correctly sized; stream-json (C) over-build for the input problem.

## Challenger verdict
APPROVED (round 2). Winner B1 verified against code; AC#6 focus hole closed via TerminalSurface `inputEnabled`; mandatory on-device test in `com.aisandbox.android.gate` so the UC-85 gate runs it; `encodeComposerLine` shared so the byte+CR test proves the real path; single windowInsetsPadding cluster preserving requiredHeight (UC-23); UC-36 setting scoped to raw. All 8 ACs addressed; no UC-21/23/36/85 regression; mvp-sized; profiles satisfied.
