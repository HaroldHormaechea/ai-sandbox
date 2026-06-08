# Use Case 37: Android remote view — native rendering of Claude's questions via a transcript-tail sideband

## Summary
When Claude Code (running as an interactive TUI in a sandbox session) calls `AskUserQuestion` — or surfaces the plan-mode (`ExitPlanMode`) approval prompt — the Android remote view today shows it only as raw terminal text in the scrollback, answerable solely by arrow-key/number keystrokes round-tripped to the PTY. This use case adds a **structured question sideband** that keeps the terminal model fully intact and is purely additive. A **sidecar daemon inside the sandbox container** tails Claude Code's per-session transcript JSONL (`~/.claude/projects/**/*.jsonl`, bind-mounted and writable; the Stage 0 spike confirmed `AskUserQuestion` `tool_use` blocks carry the full `questions[]`/`options[]` structure and are written *before* the answer), and pushes detected prompts to the Java server via IPC. The server forwards a new structured `question` control frame over the existing `/v1/sessions/{n}/stream` WebSocket. The wire protocol is bumped to **`ai-sandbox.v2`**: updated clients negotiate v2 and receive the new frame; clients advertising only v1 are cleanly rejected (close 1003) per the existing handshake — no broken streams. The Android client renders the question and its options in a **dedicated, dismissible sheet** (not a general split-pane — UC-25 rejected that), separate from the scrollback, including a **free-text field for the "Other" option** and multi-selection for `multiSelect` questions. While a sheet is open, terminal input for that prompt is **locked** to prevent double-submit. The user's choice is sent as a structured **`answer` frame**; the **server** translates it into the correct TUI keystrokes (centralizing the fragile key-mapping). Multi-source is supported: each Claude instance — including agent-team teammates / nested subagents under `<session>/subagents/` — writes its own transcript, so the daemon watches all of them and attributes each question; a question from a UC-21 target the user is not currently viewing surfaces as a **badge on that target's switcher tile**, and tapping switches the stream and opens the sheet. When a question is answered (anywhere) or aborted, the sidecar detects the next transcript turn and the server emits a **resolved/cancel frame** so the client dismisses the sheet. Broader I/O decoupling and input-lag elimination (Approach C, `stream-json`) are explicitly **out of scope** and deferred to a future use case.

## Acceptance Criteria
1. When Claude in a session invokes `AskUserQuestion`, the Android client (negotiating `ai-sandbox.v2`) receives a structured `question` control frame carrying, per question: question text, `header`, `multiSelect` flag, and the full `options[]` (label + description) — never raw ANSI.
2. The plan-mode approval prompt (`ExitPlanMode`) is detected and delivered as a `question` frame in the same structured way.
3. Detection is driven by tailing the transcript JSONL via a **sidecar daemon inside the sandbox container**, which pushes to the server over IPC — **not** by parsing terminal/ANSI output, and not by the server reading files itself.
4. The `question` frame is delivered while Claude is still awaiting the answer (before the user responds), within a bounded latency of the call (target ≤1 s under normal load).
5. The client renders the question + options in a UI surface distinct from the terminal scrollback, with one tappable control per option; `multiSelect` questions allow multiple selections before submit; the always-present "Other" choice exposes a **free-text input field** in the sheet.
6. Submitting an answer sends a structured **`answer` frame**; the **server** maps it to the TUI option-selection keystrokes (including the free-text path), resolving the prompt exactly as a TUI answer would (verified by Claude proceeding with the chosen option / free text).
7. While a question sheet is open for a prompt, terminal input for that prompt is **locked** (no double-submit). When the feature/sheet is not active, answering in the terminal/modifier-bar still works unchanged.
8. Protocol gating: the server negotiates `ai-sandbox.v2`; a client advertising only `ai-sandbox.v1` is rejected with close code 1003 (existing behavior) and never receives `question` frames — no errors beyond the clean rejection, no broken stream.
9. Multi-source: questions from agent-team teammates / subagents (separate transcripts, incl. `<session>/subagents/agent-*.jsonl`) are detected and attributed to their source target.
10. A question from a UC-21 target the user is not currently viewing surfaces as a **badge on that target's switcher tile**; tapping it switches the stream to that target and opens the sheet.
11. Invalidation: when a question is answered (via any path) or aborted, the sidecar detects the next transcript turn and the server emits a **resolved/cancel frame**; the client dismisses the corresponding sheet/badge rather than leaving it stale.
12. No cross-session leakage: a question from session A is never delivered to a client streaming session B.
13. Robustness: the daemon handles new/rotated transcript files appearing mid-session, reads only complete JSON lines, and never crashes the session or blocks the PTY stream on a malformed line; the sidecar↔server IPC tolerates session start/stop and reconnect without leaving a stale daemon.

## Potential Pitfalls & Open Questions
- **Open question** — Confirm the plan-mode `ExitPlanMode` approval prompt appears as a structured `tool_use` in the transcript JSONL. The Stage 0 spike verified this for `AskUserQuestion` only; `ExitPlanMode` needs the same check before AC#2 is guaranteed.
- **Risk** — Server-side key-map fidelity: translating an `answer` frame into TUI option-selection keystrokes (number vs arrow+enter, and the free-text "Other" path) may shift across Claude Code versions. May need a version-aware mapping or a more robust submission contract.
- **Missing input** — Concrete latency SLA for AC#4, and a live `tail -f` confirmation that the transcript line is flushed at the instant of ask (memo §10 caveat: inferred from separate-line + timestamp gap, not observed live).
- **Edge case** — Race where a question is answered/aborted between detection and sheet render (handled by the resolved/cancel frame, but ordering must be correct so a sheet never appears for an already-resolved prompt).
- **Risk** — Permission prompts are out of scope because sessions run `--dangerously-skip-permissions`; if that ever changes, permission prompts (distinct from `AskUserQuestion`) would need separate detection.
- **Edge case** — Sidecar lifecycle: ensuring exactly one daemon per session, restart on crash, and clean teardown so a dead session leaves no orphaned tailer (parallels the FGS lifecycle lessons in UC-34/35).

## Original Description
> I want the remote view of Claude sessions to decouple inputs and outputs. For outputs also identify if Claude is asking a question so it can be rendered separately. For the input it is to improve responsiveness so autocorrect works better and we get less apparent input lag.

This use case formalizes **Stage 1 / Approach B** of the R&D feasibility study in
`use-cases/RND-remote-view-decoupled-io-and-question-rendering.md` — the question-detection-and-rendering
slice, whose feasibility was confirmed by the Stage 0 spike (memo §10). The broader input/output
decoupling and input-lag elimination (Approach C, `stream-json` structured channel) are deliberately
left to a separate future use case, as recorded in the memo.

## Clarifications
- Q: Where should the question detector run, and how does it reach the server?
  A: A **sidecar daemon inside the sandbox container** that tails the transcript and pushes to the server via IPC.
- Q: How should the new `question` frame be gated for backward compatibility?
  A: **Hard bump to `ai-sandbox.v2`** — v1-only clients are cleanly rejected (close 1003).
- Q: How should a tapped answer get back to Claude?
  A: **Server-side `answer` frame** — the client sends a structured answer; the server translates it to TUI keystrokes.
- Q: When a question arrives from an agent-team target the user isn't currently viewing, what's the UX?
  A: **Badge on that target's switcher tile + tap to switch**, then open the sheet.
- Q: AskUserQuestion always offers a free-text "Other" option — how should the sheet handle it?
  A: **Free-text input field in the sheet**, submitted via the answer frame.
- Q: If a question can be answered from both the sheet and the terminal, how do we prevent double-submit?
  A: **Sheet locks terminal input** for that prompt while open.
- Q: How does a question get invalidated when it's answered elsewhere or aborted?
  A: **Server emits a resolved/cancel frame** after the sidecar detects the next transcript turn.
- Q: What "Claude is waiting" states are in scope for this use case?
  A: **AskUserQuestion and plan-mode (`ExitPlanMode`) approval.**
