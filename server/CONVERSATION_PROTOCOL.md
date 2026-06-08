# `/v1/sessions/{n}/conversation` — structured-conversation WebSocket (UC-37)

A **second front-end onto the same live interactive `claude` session** as the
binary PTY stream (`/v1/sessions/{n}/stream`). Single-tap in the Android
sessions list opens this structured view; long-press opens the tmux/terminal
view. Both drive **one** session — there is no `claude -p`, no
`--remote-control`. This channel never renders the raw PTY:

- **Output** = the server tails the live session's transcript JSONL (a per-block
  structured record Claude Code writes to disk) via the in-container helper
  `aisandbox-conversation-tail`, and relays typed JSON events to the client.
- **Input** = a local native composer; submitted text/answers are injected into
  the **same** tmux session with `send-keys` (centralized, version-pinned).

It is **separate** from the binary stream (different path, different subprotocol,
different frame vocabulary). The tmux mode is unaffected (AC24).

## Subprotocol (AC21)

| Header                   | Required value          |
|--------------------------|-------------------------|
| `Sec-WebSocket-Protocol` | `ai-sandbox.conv.v1`    |

The handler advertises `ai-sandbox.conv.v1` via `WebSocketHandler.getSubProtocols()`
(echoed in the 101). Reactor-Netty exposes no working **pre**-upgrade handshake
filter, so the version gate is applied **post-upgrade, handler-side**: a client
that did not advertise the token receives an `error`
(`unsupported_subprotocol`) frame and a WebSocket close **1003**. (The dormant
`Subprotocol`/`StreamCaps` interceptor beans on the binary stream are **not**
relied on — see RISK 7 in the proposal.)

## Frame model

All frames are **JSON text**. Binary frames are ignored. Each carries a `type`
discriminator. This is a distinct `type` namespace from the binary stream's
control frames — do not mix the two.

Output is **block-level, not token-level**: the transcript writes one line per
content block as the block completes, so frames arrive as blocks finalize. A
thinking/working spinner bridges the gap from submit to the first block.

### Server → client (`ConversationServerMessage`)

Transcript-derived frames carry `uuid` (transcript message id — used for
client-side dedupe across backfill/reconnect), `isSidechain` (true for
subagent/teammate lines), and `source` (`main` | `subagent:<agentId>`).

| `type`            | Carries                                                              | AC |
|-------------------|---------------------------------------------------------------------|----|
| `turn-start`      | `uuid`, `isSidechain`, `source`, `text` (user prompt)               | 14, 6 |
| `thinking`        | `…`, `text` (thinking block)                                        | 5  |
| `assistant-text`  | `…`, `text`                                                         | 3  |
| `tool-use`        | `…`, `toolName`, `toolUseId`, `inputSummary` (bounded)             | 4  |
| `tool-result`     | `…`, `toolUseId`, `isError`, `summary`                             | 4  |
| `question`        | `…`, `toolUseId`, `questions[]` (`question`,`header`,`multiSelect`,`options[]{label,description}`) | 10 |
| `plan-approval`   | `…`, `toolUseId`, `plan`                                           | 13 |
| `turn-end`        | `…`, `durationMs`, `messageCount` (the `system:turn_duration` marker) | 15 |
| `targets`         | `targets[]` (incl. `pendingActivity`/`pendingQuestion`), `selectedId` | 16, 18 |
| `target-selected` | `targetId`                                                          | 17 |
| `backfill-start`  | `source` — begins the bounded backfill window                      | 6, 22 |
| `backfill-end`    | `source` — ends it; live append follows                           | 6, 22 |
| `error`           | `code`, `title`, `detail` (RFC 9457-ish), usually followed by close| —  |

`AskUserQuestion` → `question`; `ExitPlanMode` → `plan-approval`; every other
`tool_use` → `tool-use` (internal noise summarized, not dumped).

The `error` frame's `code` is usually fatal-and-close (`unsupported_subprotocol`,
`not_authorized`, `tail_failed`, `inject_failed`). The one **non-fatal** code is
`no_transcript`: the helper could not resolve an active transcript within its
grace window (see *Active-transcript resolution*). The channel stays open — the
helper keeps polling and a `backfill-start` follows if the transcript later
appears — so the client should show a transient "connecting / no transcript yet"
state, not a hard failure.

### Client → server (`ConversationClientMessage`)

| `type`              | Carries                                                   | AC |
|---------------------|-----------------------------------------------------------|----|
| `composer-input`    | `text` (may contain newlines)                            | 8, 9 |
| `answer`            | `questionUuid`, `questionIndex`, `selections[]` (option indices), `freeText` | 11 |
| `select-target`     | `targetId`                                                | 17 |
| `interrupt`         | — (ESC into the session)                                  | —  |
| `enumerate-targets` | —                                                         | 16, 18 |
| `close`             | `reason`                                                  | —  |

## Backfill, reconnect, restart (AC6 / AC20 / AC22)

- On open and on every target switch the server emits `backfill-start`, replays
  a **bounded** recent window of the transcript (default 200 lines,
  `ServerProperties.Streams.conversationBackfillLines()`), then `backfill-end`
  and live-appends. The client renders the existing history on open, then
  appends; **`uuid` dedupe** prevents a backfill that overlaps already-seen
  lines from double-rendering.
- The entrypoint runs `claude` in a `while true` restart loop, so each restart
  yields a **new** `<session-id>.jsonl` (and a **new** `claude` PID). The helper
  re-anchors on a **pane→claude PID change** (see *Active-transcript resolution*),
  emits `rebaseline`, and re-backfills the new file. The client treats
  `rebaseline` as a soft reset of the live tail (history already shown stays).
  Once anchored, the helper follows that one file **stably** (it does not re-pick
  "newest in dir" each tick), so the tail never hops onto a concurrent teammate
  or foreign-session transcript in the shared slug dir.
- Network drop: the client reconnects, re-attaches to the **same** session, and
  the backfill window covers any missed lines.

## Active-transcript resolution (the load-bearing part)

`~/.claude` is bind-mounted and **shared** by default across sessions with an
identical cwd-slug, so the host cannot disambiguate session N's transcript by
path, and the slug dir holds transcripts from many teams/sessions side by side.

> **Why not the open fd?** An earlier version resolved the transcript by reading
> the live `claude` PID's open fds for an open `*.jsonl`. That premise is
> **empirically false** on the shipping `claude` build: `claude`
> opens→appends→closes the transcript per write (it watches via inotify) and
> holds **zero** `.jsonl` fds open between writes. fd-scanning therefore returned
> null on essentially every poll, the helper emitted nothing while the channel
> stayed open, and the client showed an optimistic spinner with no user echo, no
> replies, and a spinner that never cleared — the single root cause behind the
> three UC-37 conversation-mode bugs.

The helper resolves it robustly **in-container by process identity + cwd-slug**:

```
target tmux pane → #{pane_pid}
  → walk /proc/<pid>/task/<pid>/children to the `claude` descendant (claudePid)
  → /proc/<claudePid>/cwd      ⇒ cwd-slug ⇒ ~/.claude/projects/<slug>/
  → /proc/<claudePid>/cmdline  ⇒ identity (--agent-name / --team-name / --parent-session-id)
```

The cwd-slug is the cwd with every non-alphanumeric char replaced by `-`
(`/workspace/p` → `-workspace-p`). Then, depending on the pane's identity:

- **Teammate / subagent pane** (`--agent-name` present): anchor to the top-level
  `<slug>/<sid>.jsonl` whose **in-file `(agentName, teamName)` matches the cmdline
  tuple**, newest mtime. (`--agent-id` of the form `analyst@team` is *not* used —
  it never matches the transcript's hex stem.)
- **Main / orchestrator pane** (`--agent-name` absent), two tiers:
  1. *Team active (preferred, AC23-exact):* any teammate process sharing this
     cwd-slug carries `--parent-session-id` = **the orchestrator's transcript
     stem** → anchor main to `<parent-session-id>.jsonl` exactly.
  2. *No team (fallback):* the **`agentName`-absent** `<slug>/<sid>.jsonl` with the
     newest mtime (the live session is the one actively appended), re-anchored on a
     main-pane `claude` PID change (the entrypoint restart loop).

Identity-anchoring is **mandatory for AC23**: because the slug dir is shared, a
naive newest-mtime pick could route a *foreign* session's conversation to the
client. If resolution yields nothing after a bounded grace (~8 s — covering a
`claude` restart settling), the helper emits `__ctrl__\tno-transcript` (→ a
non-fatal `no_transcript` error frame) rather than hanging silently, so the
condition is observable and testable end-to-end.

Subagent/teammate activity (AC17) is read from
`<projects>/<slug>/<session-id>/subagents/agent-*.jsonl`, globbed each tick, and
tagged `source: subagent:<agentId>` (complementing the per-line `isSidechain`).

## Input injection — keystroke mapping (centralized, version-pinned)

All mapping lives in `InputInjectionService`, pinned to Claude Code
**2.1.159** (`InputInjectionService.PINNED_CLAUDE_VERSION`). A version bump is a
single-file change (RISK 3). Only well-defined cases are mapped; everything else
relies on long-press→tmux (AC24).

| Action            | Keystrokes (`tmux send-keys`)                                              |
|-------------------|---------------------------------------------------------------------------|
| Submit prompt     | `-l` each text segment; `C-j` (LF) between segments → newline **without** submit (AC9 multiline); final `Enter` (CR) submits (AC8) |
| Answer single     | reset cursor to top (`Up`×N); `Down`×k to the chosen index; `Enter`       |
| Answer multi      | reset to top; walk options toggling `Space` on selected; `Enter`          |
| Free-text "Other" | select Other; type the free text (`-l`); `Enter` — **most fragile path**  |
| Interrupt         | `Escape`                                                                   |

### Fragile edges (documented per the proposal RISKs)

- **Multiline** relies on the TUI treating **LF (`C-j`) as newline-insert** and
  **CR (`Enter`) as submit** on the pinned build. If a future build diverges,
  multiline degrades; single-line submit is solid.
- **Answer selection** assumes the option cursor starts at the top and that
  `Down`/`Space`/`Enter` navigate the `AskUserQuestion` list. Verified against
  the pinned build only — re-verify on a bump.
- **`ExitPlanMode` (AC13)** — confirmed only `AskUserQuestion` is a transcript
  `tool_use`. If a live check shows `ExitPlanMode` is **not** emitted as a
  transcript `tool_use`, the `plan-approval` frame is simply never produced and
  AC13 degrades to long-press→tmux (RISK 1). The mapper handles it generically;
  no broken sheet results.
- Arbitrary TUI sub-modes (slash menus, `@`-mention pickers) are **not** mapped
  — long-press→tmux is the full-fidelity escape hatch (RISK 2, AC24).

`--dangerously-skip-permissions` means there are **no** interactive permission
prompts to drive (RND §7) — only genuine `AskUserQuestion`/plan approvals.

## Multi-target switcher (AC16 / AC17 / AC18)

`enumerate-targets` → `targets`, reusing the binary stream's `TargetInfo` shape
plus the additive `pendingActivity` / `pendingQuestion` flags. The flags are
computed only on this channel (a bounded `--scan-pending` transcript scan of
each **non-selected** target, capped per enumerate); the binary `/stream`
enumerator leaves them `false`. `select-target` switches the tailed + injected
target on the same WebSocket (generation-token tail swap, mirroring the binary
stream's re-bridge). A non-selected target with `pendingQuestion` is badged;
tapping it switches and opens the pending sheet.

## Close-code matrix

| Code | When                                                        |
|------|-------------------------------------------------------------|
| 1000 | Client `close` frame, or normal end-of-stream.              |
| 1003 | Unsupported / absent `ai-sandbox.conv.v1` subprotocol.      |
| 1008 | Policy violation (no identity, or authorize rejected).      |
| 1009 | Text frame too big.                                         |
| 1011 | Server-side error (transcript tail failed, etc.).           |
| 4401 | Cert revoked (shared with the binary stream's revoke path). |

## Same-session integrity (AC23)

The structured view and a concurrent tmux view of the same session stay
consistent: input from either reflects in both (one tmux session); a session's
events never reach a client viewing another session (per-`n` scoping +
mTLS identity, reusing the binary stream's authorize path).
