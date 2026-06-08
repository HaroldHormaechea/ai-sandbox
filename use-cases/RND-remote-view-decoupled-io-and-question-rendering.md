# R&D — Decoupled I/O + Question Rendering for the Remote Claude View

> **Status:** living feasibility memo (R&D, not a formal use case). Last updated 2026-06-08.
> **Author:** root Claude session (project-builder workspace).
> **Why this file is here:** persisted so progress survives a crash; updated periodically as the
> investigation deepens. It deliberately does **not** take a `NN-slug` use-case number — it is a
> feasibility study, not a committed use case, so it must not collide with the `define-use-case`
> ledger (`USE_CASES.md`). If/when we commit to building it, split it into numbered use cases.

---

## 1. The ask (verbatim intent)

Make the **remote view of Claude sessions** (the Android client) do three things:

- **A — Decouple inputs from outputs.** Stop treating the session as one undivided terminal surface where what you type and what Claude prints share the same scrolling grid.
- **B — Detect when Claude is asking a question**, so questions can be **rendered separately** (e.g. a distinct prompt UI with selectable options) rather than buried as plain text in the scrollback.
- **C — Improve input responsiveness** — make autocorrect work better and reduce *apparent* input lag.

---

## 2. How the remote view works today (verified)

Pipeline, end to end. Everything is **raw PTY bytes**; there is **no semantic layer** anywhere.

### Output path
```
Claude Code (interactive TUI, alternate-screen ncurses-style app)
  → tmux  → pty4j PTY (server)
  → OutputRingBuffer (256 KiB, bounded, single producer/consumer)
  → WebSocket BINARY frame  (/v1/sessions/{n}/stream, subprotocol ai-sandbox.v1)
  → StreamClient (OkHttp) → TerminalStreamController → WsTerminalSession.feed()
  → Termux TerminalEmulator (full VT100/xterm ANSI parser) → TerminalBuffer (char grid)
  → TerminalView / TerminalRenderer (Canvas paint)
```

### Input path
```
Android IME / hardware keys / ModifierBar
  → Termux TerminalView.InputConnection (commitText / composing-region handling)
  → WsTerminalSession.onStdin → TerminalStreamController.sendStdin → StreamClient.sendStdin
  → WebSocket BINARY frame
  → SessionStreamHandler.handleIncoming → TmuxBridgeService.Bridge.writeStdin
  → pty4j process stdin (write + flush) → tmux → Claude's PTY
```

### Control plane (already structured)
- **Text (JSON) frames** carry control only: `resize`, `mouse`, `close`, `enumerate-targets`,
  `select-target` (client→server) and `targets`, `target-selected`, `error` (server→client).
- A **separate** WebSocket `/v1/sessions/events` streams the live sessions list
  (`SessionEventMessage` Snapshot/Delta).

### Key facts that shape feasibility
1. **Claude runs as an interactive TUI**, launched in `entrypoint.sh` as
   `while true; do claude --dangerously-skip-permissions; … done`. **No** `--output-format`,
   **no** `stream-json`, **no** headless mode. It draws its **own** input box, slash-command
   menus, `AskUserQuestion` option lists (arrow-key selectable), spinners — all as ANSI redraws.
2. **The client is a general-purpose terminal**, not Claude-aware. It has zero understanding of
   "this is a question" vs "this is output" vs "this is my own echo."
3. **No structured Claude output is consumed anywhere** (grep-confirmed: no `stream-json`,
   `output-format`, `.jsonl`, `transcript`, `AskUserQuestion` in code).
4. **`~/.claude/` is bind-mounted and writable** → Claude Code's per-session transcript JSONL
   (`~/.claude/projects/<cwd-slug>/<session-id>.jsonl`) **exists at runtime**. This is the
   lever for robust question-detection (see §5, Approach B).
5. **The wire protocol versions cleanly**: `Sec-WebSocket-Protocol: ai-sandbox.v1`, with an
   explicit plan to negotiate `ai-sandbox.v2` and reject mismatches (close 1003). New frame
   types can be added behind a version bump or capability handshake without breaking in-flight
   sideloaded clients.

### Relevant prior use cases
| UC | Title | Status | Relevance |
|----|-------|--------|-----------|
| 21 | Terminal emulator + agent switcher | done | Built the Termux view, the process-scoped controller, and **mid-stream target switching** across tmux sockets (agent-team panes). Establishes that **a single stream shows one target at a time**, and questions may originate from teammates on *other* targets. |
| 23 | IME keyboard insets | done | Solved keyboard occlusion + PTY-resize thrash. Foundation for any new bottom input UI. |
| 25 | Split-pane | **rejected** | Rejected for complexity (per-pane resize/focus/layout protocol). Cautionary precedent: a *fixed* question bar is fine; a general split-pane is not. |
| 36 | Conversational keyboard (words + autocomplete) | done | Already did the heavy lifting on sub-feature **C**: word prediction + suggestions on, autocorrect-corruption off, composing-region never echoed char-by-char (avoids double-typing), flush-before-control ordering. **Most of the cheap input wins are already taken.** |

---

## 3. The core tension (read this before judging effort)

The current model is a **faithful remote terminal**. Claude Code's interactive mode is a
**full-screen TUI** that owns its own input box and redraws it on every keystroke. That single
fact drives everything:

- **"Input lag"** is structural: you type → bytes round-trip to the PTY → Claude redraws its
  input box → the redraw streams back → *then* you see the character. There is **no local echo**.
  On mobile RTT this is the lag. UC-36 mitigated the *autocorrect/echo* problems but **cannot
  remove the round-trip** while input is fed to a live TUI.
- **"Decoupling I/O"** in the UI sense (a read-only transcript above, a composer below, chat-app
  style) **fights the TUI**: Claude already draws an input box inside the grid. Bolting a second
  composer onto a live TUI gives the user *two* competing input areas. Coherent decoupling
  really wants Claude driven in a **line/structured** manner, not as a screen we mirror.
- **"Detect questions"** from a mirrored ANSI grid means reverse-engineering Claude's
  *visual* question rendering (rounded box, numbered options) — an unstable, version-coupled
  target.

**Conclusion:** all three sub-features point at one strategic question —
**how far do we move from "mirror a raw terminal" toward "consume a structured Claude
interface"?** The honest answer is a *spectrum*, and the three sub-features sit at different
points on it. They should **not** be treated as one monolithic build.

---

## 4. Feasibility verdict (summary)

| Sub-feature | Feasible? | Best mechanism | Effort | Risk |
|---|---|---|---|---|
| **C** — input responsiveness / autocorrect | **Largely already done** (UC-36/23). Remaining *apparent-lag* win needs a local composer → only in a line/structured mode. | Incremental in TUI = small. Real fix rides on Approach C. | Low (incremental) / High (real fix) | Low / Med |
| **B** — detect + render questions | **Yes**, robustly, **without** abandoning the terminal model. | **Transcript-JSONL sideband** (Approach B) | **Medium** | **Medium** |
| **A** — decouple I/O (chat-style) | **Yes**, but only *coherently* via a structured channel. | Headless **stream-json** session mode (Approach C) | **High** | **High** |

Nothing here is infeasible. The graded risk is about **how much product surface you change**,
not about whether the technology exists.

---

## 5. The three implementation approaches (the real decision)

### Approach A — Screen-scrape the TUI ANSI  ❌ not recommended as primary
Parse the streamed escape sequences / grid to recognise Claude's question UI (the box + numbered
options, "Do you want to proceed?", etc.) and lift it into a separate widget.
- **Pros:** no container changes; works on the existing single stream.
- **Cons:** **fragile and version-coupled** — you're parsing an unstable *visual* format from a
  VT100 grid with cursor positioning and box-drawing. Breaks whenever Claude Code's TUI restyles.
  High false-positive/negative rate. A maintenance tax forever.
- **Verdict:** acceptable only as a last-resort fallback signal, never the source of truth.

### Approach B — Transcript-JSONL sideband (hybrid)  ✅ recommended for sub-feature B
Keep the terminal model exactly as-is. Add an **in-container daemon** that tails
`~/.claude/projects/<cwd-slug>/<session-id>.jsonl`. When an `AskUserQuestion` tool_use (or other
"awaiting user" turn) appears, emit a **new `question` control frame** to the client with the
**structured** question text + options. Android renders it in a **separate, dismissible question
sheet/bar** (not a split-pane — learn from UC-25). The user's choice is sent back as the
appropriate **keystrokes into the existing TUI** (arrow+enter or the option number) — so the
*answer* path reuses today's input pipeline untouched.
- **Pros:** **robust** (structured data, not screen-scraping); **small blast radius** (TUI,
  emulator, UC-21/23/36 all keep working); rides the clean `v1→v2`/capability handshake; the
  hard part (detection) is data-driven.
- **Cons / unknowns to spike:** (1) ✅ **VALIDATED** (Stage 0, see §10) — `AskUserQuestion` lands
  in the transcript JSONL with full structured options, and the question line is flushed as a
  *separate* line ~12 s **before** the answer line; (2) the **Notification hook** (`Notification`
  event fires when Claude needs input/permission) may be a cheaper "a question is pending" trigger,
  though it lacks the structured options — could pair the two (still un-spiked); (3) ✅
  **VALIDATED** — **agent-team multi-source**: each independent Claude (incl. nested subagents)
  writes its **own** transcript file; the daemon globs all `*.jsonl` under `~/.claude/projects/`
  and tags each question with its source; (4) with `--dangerously-skip-permissions` there are
  **no** permission prompts to detect — only genuine `AskUserQuestion`s — which simplifies scope.
- **Verdict:** **the recommended near-term path for B.** Delivers "render questions separately"
  with the least architectural risk.

### Approach C — Headless `stream-json` session mode (structured channel)  ✅ long-term, for A + C
Run Claude with `--output-format stream-json --input-format stream-json` (or the Agent SDK) as a
**parallel "chat" session mode**, distinct from the terminal mode. Output arrives as structured
events (assistant text, `tool_use` incl. `AskUserQuestion` with options, `tool_result`); input is
a **local composer** submitted as structured user messages.
- **Pros:** **decouples I/O by construction** (A); **local composer = no per-keystroke round-trip,
  full IME/autocorrect, no lag** (C); questions are **first-class** (B) for free; tool-use and
  thinking can render richly.
- **Cons:** **big new product surface.** It is a *different way of driving Claude*, not a view on
  the existing tmux TUI — **you cannot retroactively get JSON events from the already-running
  interactive session.** You'd be running a second Claude. Loses arbitrary-TUI / tmux
  multiplexing / the agent-team pane switcher for *that* mode. Must **re-implement permission
  handling** (the SDK `canUseTool`/permission-prompt-tool path replaces the TUI's prompts).
  Backward-compat + protocol work. Largest test burden.
- **Verdict:** the right **long-term** home for true I/O decoupling and lag elimination, but a
  strategic bet to be made **after** B proves the question-rendering UX is worth it.

> **Important architectural constraint (load-bearing):** the existing tmux session *is* the
> interactive TUI. A structured channel (Approach C) means running Claude **a second way**, not
> extracting structure from the live TUI. Approach B (transcript tail) is the only way to get
> structured **output** from the *same* session the user is already driving.

---

## 6. Recommended staged path

- **Stage 0 — Spike (½–1 day, throwaway):** in a live container, drive a real `AskUserQuestion`
  and inspect the session transcript JSONL: confirm the question + options are present, structured,
  and written in time. Repeat with an **agent-team** run to see multi-file behaviour. Also probe
  the **Notification hook** payload. *Output: go/no-go on Approach B.*
- **Stage 1 — Question rendering via sideband (Approach B):** in-container transcript-tail daemon
  → new `question`/`answer` frames behind `ai-sandbox.v2` (or a capability bit) → Android question
  sheet that renders options and injects the chosen answer as keystrokes. Delivers **B**. Keeps
  everything else intact. *This is the recommended first build.*
- **Stage 2 — Decide on the structured channel (Approach C):** only after Stage 1 validates the
  UX. If we want true I/O decoupling + lag elimination, build the `stream-json` chat mode as a
  *second* session mode, with permission handling and tool rendering. Delivers **A + C**.
- **Sub-feature C in the meantime:** UC-36/23 already cover the cheap wins. Do **not** over-invest
  in TUI local-echo (risky against redraws); the real lag fix arrives with Stage 2.

---

## 7. Open questions / blockers to resolve

1. ✅ **RESOLVED (Stage 0)** — `AskUserQuestion` appears in JSONL with options fully structured,
   as a separate line written ~12 s before the answer. Schema + timing confirmed. (See §10.)
   *Residual:* strict file-flush-at-ask-instant wants a live `tail -f` confirmation, but the
   separate-line + timestamp-gap evidence makes this very likely.
2. ⚠️ **Partially resolved** — multi-source is mechanically solved (each Claude writes its own
   transcript; subagents nest under `<session>/subagents/`). The remaining *product* question is
   **routing/UX**: how to surface a teammate's question when the client is viewing a different
   UC-21 target. (Ties into UC-21 target model — UX decision, not a technical blocker.)
3. **Answer injection fidelity** — mapping a tapped option back to the exact TUI keystrokes
   (number vs arrow+enter) across Claude Code versions. Fragile-ish; needs a contract or the
   Stage-2 structured channel to be fully robust.
4. **Protocol versioning** — `v2` bump vs capability advertisement at handshake; backward-compat
   for sideloaded clients in flight.
5. **Question-sheet UX under IME** — interaction with UC-23 insets; ensure it doesn't recreate the
   UC-25 split-pane complexity.
6. **Scope of "question"** — `--dangerously-skip-permissions` means no permission prompts; confirm
   we only need `AskUserQuestion` (+ maybe plan-mode/trust), not the full prompt taxonomy.

---

## 8. Evidence index (file refs)

**Server / transport**
- `server/STREAM_PROTOCOL.md` — wire format; `ai-sandbox.v1`, v2 negotiation, close codes.
- `server/.../stream/handler/SessionStreamHandler.java` — pump + binary/text frame handling.
- `server/.../stream/service/TmuxBridgeService.java` — pty4j PTY bridge, `writeStdin`, target switch.
- `server/.../stream/service/OutputRingBuffer.java` — 256 KiB bounded buffer.
- `server/.../stream/dto/ControlMessage.java`, `StreamServerMessage.java` — current frame vocab (no `question` type yet).

**Android client**
- `android/.../net/StreamClient.kt` — WebSocket; `sendStdin` (binary), control frames.
- `android/.../terminal/TerminalStreamController.kt` — process-scoped owner, output pump, target state.
- `android/.../terminal/WsTerminalSession.kt` — WS→emulator adapter; `feed()`, `onStdin`.
- `android/.../ui/components/TerminalSurface.kt` — Termux view host; `shouldEnforceCharBasedInput()` (UC-36 toggle).
- `android/.../ui/components/ModifierBar.kt` — control-key escape encoding.
- `android/.../ui/screens/TerminalScreen.kt` — flush-before-control ordering.

**Vendored terminal (Termux)**
- `terminal-emulator/.../TerminalEmulator.java` — VT100/xterm parser + state machine.
- `terminal-emulator/.../TerminalBuffer.java` — char grid.
- `terminal-view/.../TerminalView.java` — `InputConnection` (commitText/composing), `computeInputType()`.
- `terminal-view/.../TerminalRenderer.java` — Canvas paint.

**Runtime / Claude invocation**
- `entrypoint.sh` — `claude --dangerously-skip-permissions` loop; `~/.claude` seeding (bind-mounted, writable).

**Prior use cases**
- `use-cases/21-…agent-switcher.md`, `23-…keyboard-insets.md`, `25-…split-pane.md` (rejected),
  `36-…conversational-keyboard-words-autocomplete.md`.

---

## 10. Stage 0 spike results (2026-06-08) — Approach B confirmed feasible

Run against **real Claude Code transcripts on this host** (`~/.claude/projects/<slug>/*.jsonl`),
including this very session's `AskUserQuestion` call. No container needed — the transcript schema
is a property of Claude Code itself, which is the same binary the sandbox image installs.

**What was verified (all ✅):**

1. **Structured question payload.** The `AskUserQuestion` `tool_use` block is written verbatim to
   the transcript with the **complete** structure: `input.questions[]`, each with `question`,
   `header`, `multiSelect`, and `options[]` (`label` + `description`). This is *exactly* the data
   Android needs to render a native question UI — no screen-scraping, no ANSI parsing.
2. **Timing.** The assistant line carrying the question (`06:35:43.089Z`) is a **separate** JSONL
   line, written ~12 s **before** the user's answer line (`06:35:54.970Z`). A tailing daemon sees
   the question the instant Claude asks it, well ahead of the answer.
3. **Append-per-line / tail-friendly.** Each transcript line is independently valid JSON (verified
   by `jq` over `tail`). Trivially consumable by a `tail -f`-style watcher — no partial-line risk
   if you read complete lines.
4. **Multi-source (agent-team).** Independent Claude instances each write their own transcript;
   **nested subagents** write under `<session-id>/subagents/agent-<id>.jsonl` and carry their own
   structured `tool_use` entries. A daemon globbing `~/.claude/projects/**/*.jsonl` captures
   questions from every source and can tag each with its origin.
5. **Greppable keys.** `"name":"AskUserQuestion"`, `"header":…`, `"multiSelect":…`, `options[]`
   are stable JSON keys — a detector can key on `tool_use.name == "AskUserQuestion"`.

**Caveats / not-yet-tested:**
- The `AskUserQuestion` **answer** is not stored as a `tool_result` keyed by the tool-use id; it
  surfaces as a subsequent user message. Immaterial for Approach B (answers go back via keystroke
  injection), but means the transcript is the **question** source, not the answer round-trip.
- **Strict flush-at-ask-instant** (vs. buffered) was inferred from the separate-line + 12 s gap,
  not from a live `tail -f` during an active prompt. Very likely correct; cheap to confirm later.
- The **Notification hook** path was not exercised — remains an optional cheaper "pending" signal.
- Verified on the **host**, not inside a running sandbox container. The transcript format is
  Claude-Code-intrinsic so it carries over; the container-only checks already passed structurally
  (`~/.claude` bind-mounted + writable, §2).

**Bottom line:** Approach B (transcript-JSONL sideband) is **confirmed feasible**. The hardest
unknown — getting structured question data without screen-scraping a TUI — is solved by data that
Claude Code already writes to disk. Sub-feature **B** is a green light to build (Stage 1).

## 9. Changelog
- **2026-06-08 (committed)** — Stage 1 / Approach B promoted to a formal use case:
  **UC-37** (`use-cases/37-android-claude-question-sideband.md`, status `pending`). Decisions
  locked: sidecar daemon detector, `ai-sandbox.v2` hard bump, server-side `answer` frame,
  badge+tap multi-target UX, free-text "Other" field, sheet-locks-terminal, server resolved/cancel
  invalidation, scope = `AskUserQuestion` + `ExitPlanMode`. Approach C (decoupled I/O + lag) remains
  a future, separate use case.
- **2026-06-08 (spike)** — Ran Stage 0 against real Claude Code transcripts. Confirmed structured
  `AskUserQuestion` payload, ask-before-answer timing, append-per-line tailability, and
  per-source (incl. subagent) transcripts. Approach B feasibility = **confirmed**. Updated §5/§6/§7
  unknowns; added §10.
- **2026-06-08** — Initial memo. Mapped current I/O pipeline (3 parallel explorations), grounded
  Claude invocation / protocol versioning / transcript availability. Established the core tension,
  three-approach spectrum, staged recommendation, open questions.
