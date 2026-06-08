# Use Case 37: Android remote view — structured "conversation" connection mode over the live session (tmux kept as fallback)

## Summary
Add a second way to connect to a session from the Android sessions list: **single-tap → a structured "conversation" view**, while **long-press → the existing tmux/terminal view** (kept as the raw/power fallback). Both are front-ends onto the **same live interactive `claude` session** in the same container — not a second conversation. The new mode never runs `claude -p` (that would spawn a separate brain) and does not use `--remote-control`. Instead it exploits the fact that the running interactive session writes its complete structured record to its **transcript JSONL** (`~/.claude/projects/<cwd-slug>/<session-id>.jsonl`): a per-session **sidecar daemon** tails that file and the server relays it to the client as JSON events, giving structured **output** (assistant text, `thinking`, `tool_use` incl. `AskUserQuestion`, `tool_result`, turn lifecycle). **Input** is a native **local composer** whose submitted text the server injects into the *same* tmux session via `send-keys` — eliminating the per-keystroke echo lag and autocorrect breakage of the terminal view (the structured view never renders the raw PTY, so there is no echo to fight). Questions render in a dedicated **sheet** (with a free-text field for the always-present "Other" option); the user's choice is sent as a structured **answer** that the server translates into the session's selection keystrokes. A **thinking/working spinner** is driven by the transcript turn lifecycle. The **agent-selection buttons carry over** via the existing `enumerate-targets`/`select-target` plumbing, with subagent/teammate activity distinguished by the transcript's `isSidechain` flag and `subagents/agent-*.jsonl` files. Because both modes drive one session, anything done in one is reflected in the other, and long-press→tmux is always available as the escape hatch for TUI states the structured view can't drive (slash menus, multiline, interrupts). This use case supersedes the original Approach-B "question sideband only" scope of UC-37: the sideband is generalized to the whole conversation plus a composer.

## Empirically-confirmed mechanics (do not re-derive — verified against `claude 2.1.159`)
These were validated by live spikes (recorded in `RND-remote-view-decoupled-io-and-question-rendering.md` §10–§11) and are the contract the implementation builds on:

1. **Transcript is the structured output of the live interactive session.** Path `~/.claude/projects/<cwd-slug>/<session-id>.jsonl`; one JSON line **per content block**, written when that block completes.
2. **Block-level granularity, not token-level.** A turn is written as a sequence of lines: `user` → (`assistant:thinking` → `assistant:text` → `assistant:tool_use` → `user:tool_result` → `assistant:text`)\* → `system:turn_duration`. There is **no** partial/token streaming in the transcript.
3. **Explicit turn-end marker:** `{"type":"system","subtype":"turn_duration","durationMs":…,"messageCount":…}` is written at the end of each turn — the precise signal to stop the spinner and re-enable the composer.
4. **`thinking` is a distinct line** (block has `thinking` text + `signature`) → exact "Claude is thinking" signal.
5. **`AskUserQuestion` is a normal `tool_use`** carrying the full `questions[]` (each with `question`, `header`, `multiSelect`, `options[]` of `label`+`description`), written before the answer.
6. **Per-line metadata** on every line: `sessionId`, `uuid`, `parentUuid` (message DAG), **`isSidechain`** (true for subagent/teammate messages), `cwd`, `gitBranch`, `version`, `timestamp`.
7. **Input has no structured channel into an interactive session** — the only way to drive the *same* session is to write to its PTY (tmux `send-keys`). The composer is local; only submitted text/keys are injected.
8. **Latency to first block is model time** (seconds; ~6 s observed on Opus high-effort) — the spinner window is real and well-defined (input-sent → first assistant line of the new turn).

## Connection-mode selection (sessions list)
- **AC1.** Single-tapping a session row opens the **structured conversation** view; **long-pressing** the same row opens the **tmux/terminal** view (today's behavior). Both target the same session number `n`.
- **AC2.** The choice is discoverable (e.g. long-press affordance/hint) and does not regress the existing swipe-to-delete (UC-20) or live-status (UC-32) behavior of the row.

## Structured output rendering
- **AC3.** Assistant **text** blocks render as conversation messages as their transcript lines arrive (block-by-block), in order, without ANSI parsing.
- **AC4.** **`tool_use`** activity is rendered as a readable, collapsible item (tool name + key inputs) and paired with its **`tool_result`** (success/error). Internal tool noise is summarized, not dumped raw.
- **AC5.** **`thinking`** blocks are surfaced distinctly (at minimum a "thinking" state; optionally the reasoning text, collapsed).
- **AC6.** Rendering is driven by the transcript line stream relayed from the server; the view shows the existing conversation history on open (backfill from the current transcript) and then live-appends.

## Local-composer input
- **AC7.** Input is a native text field with full IME/autocorrect/prediction and **no per-keystroke round-trip or echo lag** (the raw PTY is never rendered in this mode).
- **AC8.** Submitting the composer sends the message to the server, which injects it into the **same** tmux session (`send-keys` + submit) so the live `claude` receives it as a normal turn; the resulting assistant turn appears via the transcript stream.
- **AC9.** Multiline input (the composer supports newlines) is delivered to the session correctly (mapped to the session's multiline-submit convention), and a normal submit sends + clears the composer.

## Questions & answers
- **AC10.** When an `AskUserQuestion` `tool_use` appears, the view renders a **question sheet** distinct from the conversation: one control per option, multi-select where `multiSelect` is true, and a **free-text field for the "Other" option**.
- **AC11.** Submitting an answer sends a structured **answer** to the server, which translates it into the session's selection input (option keystrokes / free-text + submit) so the `AskUserQuestion` resolves exactly as if answered in the TUI (verified by the session proceeding with the chosen option).
- **AC12.** While a question sheet is pending, the normal composer is locked for that turn (no competing/double submit); the sheet is dismissed/invalidated when the question is resolved or the turn aborts (detected from the transcript advancing past it).
- **AC13.** The **plan-mode (`ExitPlanMode`) approval** prompt is detected and rendered/answered through the same mechanism. *(Open: confirm `ExitPlanMode` is a transcript `tool_use` — see Pitfalls.)*

## Thinking spinner & turn lifecycle
- **AC14.** After the user submits input, a **working/thinking spinner** shows from submit until the turn's first assistant line; it reflects the `thinking` state when a thinking block is active.
- **AC15.** The spinner clears and the composer re-enables on the turn's **`system:turn_duration`** marker (or equivalent end-of-turn detection), not on a fixed timeout.

## Agent switcher
- **AC16.** The existing **agent-selection buttons** are present in the structured view, populated by the same `enumerate-targets`/`targets`/`select-target` protocol used by the terminal view.
- **AC17.** Selecting a target renders **that target's** conversation (its transcript) and routes the composer's input to **that target's** session; main-agent vs subagent/teammate activity is distinguished using `isSidechain` and the `subagents/agent-*.jsonl` files.
- **AC18.** A question or notable activity from a non-selected target surfaces as a **badge** on that target's button; tapping switches to it and opens any pending sheet.

## Protocol / server
- **AC19.** A new JSON-only structured channel (e.g. a `/v1/sessions/{n}/conversation` WebSocket, or a versioned message set on the existing stream) carries: server→client conversation events (text/thinking/tool/question/turn-lifecycle/target frames) and client→server frames (composer input, structured answer, target select, interrupt). It is **separate** from the binary PTY stream used by tmux mode.
- **AC20.** A per-session **sidecar daemon** in the container tails the **active** transcript and feeds the server; the server relays to clients and performs input injection. The daemon handles the entrypoint's restart loop creating a **new** transcript file per `claude` (re)start, reads only complete JSON lines, tolerates rotation, and never blocks or crashes the session on a malformed line.
- **AC21.** The channel is version-gated so older clients that don't understand it are cleanly rejected/ignored (no broken connection), consistent with the existing handshake conventions.

## Reconnect / lifecycle / same-session integrity
- **AC22.** On reconnect (network drop, app background/foreground), the structured view re-attaches to the **same** session and transcript and backfills any missed lines (no lost/duplicated messages).
- **AC23.** The structured view and a concurrent tmux view of the same session stay consistent: input from either appears in both; no cross-session leakage (a session's events never reach a client viewing another session).
- **AC24.** Long-press→tmux remains a full-fidelity fallback for states the structured view cannot drive (arbitrary TUI sub-modes, raw output), with no regression to the terminal mode.

## Potential Pitfalls & Open Questions
- **Open question** — Confirm `ExitPlanMode` (plan-mode approval) appears as a transcript `tool_use` like `AskUserQuestion`. Spikes verified `AskUserQuestion` only.
- **Risk** — **Input injection into a TUI is the fragile edge.** Plain prompts and question answers are solid; slash-command menus, `@`-mention pickers, multiline conventions, and ESC/interrupt are TUI-state-dependent. Map the well-defined cases; rely on long-press→tmux for the rest. An **interrupt** (ESC) control frame → `send-keys Escape` is desirable; verify it cancels a turn cleanly.
- **Risk** — Selection-keystroke mapping for answers (number vs arrow+enter, free-text path) may vary across Claude Code versions; centralize it server-side and pin/version it.
- **Ambiguity** — Detector placement: the chosen **sidecar daemon** vs the server tailing the bind-mounted transcript directly (simpler, no extra process). Revisit now that scope is the whole stream, not just questions.
- **Edge case** — Locating the **active** transcript: the entrypoint runs `claude` in a `while true` loop, so each restart yields a new `<session-id>.jsonl`. The daemon must follow the newest/active file and re-baseline on restart, and the server must map session `n` → container cwd-slug → current transcript.
- **Edge case** — Turns containing multiple tool round-trips produce several `assistant`/`tool_result` lines before `turn_duration`; spinner/turn-complete logic must key on `turn_duration`, not the first assistant text.
- **Limitation (accepted)** — Output is **block-level, not token-streaming**; messages appear as blocks finalize. The spinner bridges the gap; token-level would require scraping the PTY, which defeats the mode's purpose.
- **Risk** — Permissions: sessions run `--dangerously-skip-permissions`, so there are no interactive permission prompts to handle (the CLI has no `--permission-prompt-tool` in 2.1.159; denials, if any, surface in a `result`/transcript). If bypass is ever disabled, permission handling needs a separate design.
- **Edge case** — Backfill volume: a long-running session's transcript can be large; opening the structured view should backfill a bounded recent window, not the entire file, and note truncation.
- **Edge case** — Subagent transcript routing: confirm whether teammate activity lands as `isSidechain` lines in the main file, in `subagents/agent-*.jsonl`, or both, so the switcher reads the right source per target.

## Original Description
> [User, across the design conversation] I want the remote view of Claude sessions to decouple inputs and outputs — to have our own UI and handle a lot of random stuff better (autocorrect, input lag, questions). Preserve the tmux connection style as a fallback: long-pressing a row connects via tmux, single-click connects via the new mechanism. The new mechanism should be done completely — input, output, questions, answers, keep the agent-selection buttons, and even a spinner when Claude is thinking — so the implementation can be designed complete instead of piecemeal. It must drive the **same** live session as the terminal (a separate `claude -p` conversation is unacceptable), and it must not use `--remote-control`.

This supersedes the earlier Approach-B-only scope of UC-37. Full design rationale, the three-approach analysis, and the spike evidence live in `use-cases/RND-remote-view-decoupled-io-and-question-rendering.md`.

## Clarifications
- Q: One structured conversation or a separate `claude -p` process? A: **Same live session** — no `-p` (it would be a second, separate conversation). The structured view is a front-end over the running interactive session's transcript (output) + tmux `send-keys` (input).
- Q: Use `--remote-control`? A: **No** — explicitly excluded.
- Q: How do the two modes coexist from the sessions list? A: **Single-tap → structured mode; long-press → tmux** (fallback).
- Q: Scope of the new mode? A: **Complete** — input (local composer), output (structured rendering), questions + answers, agent-selection buttons, and a thinking spinner.
- Q: Detector placement (from earlier round)? A: **Sidecar daemon** in the container tailing the transcript (to revisit vs server-side bind-mount tail now that scope is the full stream).
- Q: Answer round-trip? A: **Server-side translation** of a structured answer into the session's selection keystrokes.
- Q: Multi-target question UX? A: **Badge on the target's button + tap to switch**, then open the sheet.
- Q: "Other"/free-text? A: **Free-text field in the sheet.**
- Q: Prevent double-submit while a question is pending? A: **Lock the composer** for that turn while the sheet is open.
- Q: Question invalidation? A: Detected from the **transcript advancing** past the question (resolved/aborted); also covered by the turn lifecycle.
- Q: Spinner signal? A: Transcript **turn lifecycle** — spinner from submit to first assistant line / `thinking`, cleared on `system:turn_duration`.
