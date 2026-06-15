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
| `teammate-message`| `uuid`, `isSidechain`, `source`, `teammateId`, `color`, `text` (UC-58 — an inbound teammate/subagent message delivered to a team-lead session as a `user` line) | 58 |
| `tool-use`        | `…`, `toolName`, `toolUseId`, `inputSummary` (bounded), `primaryText` | 4, 41 |
| `tool-result`     | `…`, `toolUseId`, `isError`, `summary`                             | 4  |
| `tool-detail`     | `toolUseId`, `toolName`, `input`, `result`, `isError`, `available` (UC-41 on-demand, untruncated) | 41 |
| `system-note`     | `uuid`, `isSidechain`, `source`, `label`, `detail` (UC-42 — an injected `user` line with no host bubble) | 42 |
| `question`        | `…`, `toolUseId`, `questions[]` (`question`,`header`,`multiSelect`,`options[]{label,description}`) | 10 |
| `plan-approval`   | `…`, `toolUseId`, `plan`                                           | 13 |
| `pending-question`| `promptKey`, `kind` (`questions`\|`plan`), `questions[]`, `plan`, `answerable` — UC-50 LIVE pane-delivered prompt (sets the sheet ONLY, NO inline item); UC-55 carries FULL per-tab options for a multi-question batch (`answerable=true`) | 50/55 |
| `pending-clear`   | `promptKey` — UC-50 clears a pane-delivered sheet whose key matches | 50 |
| `turn-end`        | `…`, `durationMs`, `messageCount` (the `system:turn_duration` marker) | 15 |
| `targets`         | `targets[]` (incl. `pendingActivity`/`pendingQuestion`), `selectedId` | 16, 18 |
| `target-selected` | `targetId`                                                          | 17 |
| `backfill-start`  | `source` — begins the bounded backfill window                      | 6, 22 |
| `backfill-end`    | `source` — ends it; live append follows                           | 6, 22 |
| `answer-echo`     | `questionUuid`, `questionIndex`, `selections[]`, `freeText` — **replay-profile only** (UC-85); echoes the answer just received | 85 |
| `error`           | `code`, `title`, `detail` (RFC 9457-ish), usually followed by close| —  |

`AskUserQuestion` → `question`; `ExitPlanMode` → `plan-approval`; every other
`tool_use` → `tool-use` (internal noise summarized, not dumped).

**UC-50 — live pane-delivered pending prompt (`pending-question` / `pending-clear`).**
On claude `2.1.169` the blocking assistant turn that carries the `AskUserQuestion`
/ `ExitPlanMode` `tool_use` is **buffered in memory until the answer is produced**
— it is never written to the transcript while the session blocks (verified at the
byte level: the transcript ends with a newline at the user prompt). So the UC-40
residual idle-flush has nothing to flush and the transcript-derived `question` /
`plan-approval` frames never fire while blocked — the conversation showed only a
perpetual "Working…". The fix brings the **visible-pane signal** UC-49 proved for
the sessions-list "?" into the conversation channel:

- The in-container tail helper captures the SAME `targetSpec()` pane it follows
  (so subagent panes are covered too — AC8) on EVERY poll, gates on the
  `looksLikePendingAskUserQuestion` / `looksLikePendingPlanApproval` predicates,
  parses the prompt structure with `parsePendingPrompt`, and — once the parsed
  `key` is stable across one extra poll (settle) — emits
  `__ctrl__\tpending-question\t<json>` once per new/changed prompt, and
  `__ctrl__\tpending-clear\t<promptKey>` when the chrome disappears.
- The server maps the JSON to a `pending-question` frame. It **sets the client
  sheet ONLY — it adds NO inline item**, so when claude later writes the resolved
  turn to the transcript (on a residual-writing build), the transcript path owns
  the single inline bubble and there is no double-render (AC5 dedupe). A per-turn
  `transcriptPromptThisTurn` guard suppresses the pane frame if a transcript
  `question`/`plan-approval` already fired this turn (transcript wins). On
  `2.1.169` only the pane path fires.
- **`answerable`** is decided server-side (never inferred client-side):
  `answerable = "plan".equals(kind) || allQuestionsHaveOptions(questions)`
  (`ConversationEventMapper#answerable`). Plan approval and a single question
  (options fully recovered from one capture) are answerable; a multi-question batch
  is answerable once **every** tab's options are recovered (see UC-55 below).
  `answerable=false` is reserved for the narrow genuinely-unrecoverable residual — a
  prompt KIND whose options cannot be derived at all — where the client shows the
  questions read-only with an "answer in tmux to continue" affordance.

**UC-55 — full multi-question recovery (eager, server-side wizard walk).** One passive
`capture-pane` of the multi-question wizard (`← ☐ Q1  ☐ Q2 … ✔ Submit →`) shows ONLY
the focused tab's options, so UC-50 delivered the batch header-only with
`answerable=false` (tmux fallback). UC-55 removes that fallback for the standard
wizard: when the pane signal delivers a header-only multi-question prompt, the server
**eagerly recovers every tab's options** before emitting the frame, so the batch
arrives `answerable=true` with the full per-tab `options[]` and the client renders the
existing paged sheet (no new frames; the `pending-question` shape is unchanged).

- **The server is the single keystroke writer.** Recovery runs in
  `ConversationFacade#recoverWizardOptions` under a **per-session pane lock** serialized
  with `injectAnswer`/`injectAnswerBatch`, so a recovery walk can never interleave with
  an answer injection on the same pane. It steps the live wizard with **navigation keys
  ONLY** (`InputInjectionService.WIZARD_NAV_KEYS` = `Right`/`Left`) — `Right` to read the
  next tab, `Left` to restore — and **never** sends a selection/commit key (`Space`,
  `Enter`, `Tab`, digits, literals), so the brief tab-flicker is provably non-corrupting:
  a user's committed answer can never be mutated by recovery (a user manually navigating
  tmux during the walk may have cursor/tab focus perturbed, but not their answer).
- **Per-tab parse.** Each stepped capture is parsed by the helper's read-only
  `--parse-pane` mode (`parseFocusedTab` = `parseFocusedPrompt` + `optionsHaveCheckbox` +
  `parseNumberedOptions`; the focused tab's header is supplied by the server, which owns
  the tab order). The walk is **bounded and always restores** focus to tab 0 — even on
  error/timeout (a `finally` step-back) — so `injectAnswerBatch`'s "wizard opens at tab 0"
  assumption holds.
- **Race with the helper poll.** The walk perturbs the focused tab while the helper's
  independent 300 ms poll runs, which can transiently change the `promptKey`. Two guards
  keep this invisible to the client: (1) a header-only `pending-question` re-emit for an
  **already-recovered key** is dropped (it never downgrades the open full-options sheet,
  and `cacheQuestion` additionally refuses any full→header-only cache downgrade so
  `deriveAnswerSpec` keeps its per-tab options); (2) for a short settle window after a
  recovery, a header-only multi-question `pending-question` for a **new** key, or a
  `pending-clear`, is dropped as a stepping transient. Recovery fires **once per settled
  promptKey**. This satisfies the core goal with no silent "Working…" and no tmux fallback
  for the ordinary wizard.

  > Keystroke walks are pinned to `InputInjectionService.PINNED_CLAUDE_VERSION`
  > (`2.1.169`); the UC-55 nav-only walk was verified against the live wizard
  > (Phase-0 spike) — `Right`/`Left` navigate tabs for reading without committing.

**UC-41 — collapsed tool bubbles + on-demand detail.** The client renders one
collapsed, type-aware bubble per tool call, merging the `tool-use` with its
paired `tool-result` (matched on `toolUseId`) into a single row. To support that:

- `tool-use` carries an extra **`primaryText`** field: the single type-aware label
  *value*, extracted server-side (decision D2). For a `Skill` call it is the skill
  name; for a `Bash` call it is the command; for any other tool it is the bounded
  input summary. The client formats the surrounding label ("Skill loaded `<name>`",
  "Command used: `<snippet>`…", "`<tool>`: `<snippet>`…") and applies its own
  ~20-char snippet budget — the server supplies only the raw value.
- `tool-detail` is the **on-demand, untruncated** payload for one tool call, sent in
  response to a client `fetch-detail` (see below). Unlike `tool-use`/`tool-result`
  (bounded to the 600-char streaming summary), it carries the FULL `input` and
  `result`, re-read from the transcript on demand and bounded only to a generous
  device-safe cap — **48 KB** (`ServerProperties.Streams.conversationDetailMaxBytes()`,
  matching `ConversationEventMapper.CONVERSATION_DETAIL_MAX_BYTES`). When the id
  cannot be resolved (scrolled out of the retained window, expired, helper
  miss/timeout) the frame is returned with **`available=false`** and empty
  `input`/`result` — the client shows a "detail unavailable" state rather than
  hanging or crashing (AC9).

**UC-42 — harness-injected `user` lines never render as the user's own message.**
Claude Code injects `user`-role transcript lines *on the user's behalf* — a skill's
`SKILL.md` body on a `Skill` invocation, slash-command wrappers, `<local-command-stdout>`
lines, `isMeta:true` system notes. These are NOT prompts the human typed, so they must
not render right-aligned. `ConversationEventMapper.mapUser` classifies every
non-`tool_result` `user` line by **structural markers only** (no content-shape
heuristics, so a real prompt is never eaten), in this exact priority order:

| # | Structural marker (read off the line root) | Outcome | Renders as |
|---|---------------------------------------------|---------|------------|
| 1 | top-level `sourceToolUseID` nonblank | **fold** — emit nothing | (the existing `Skill` `tool-use` bubble; body is its tap detail, see below) |
| 2 | `isMeta == true` | `system-note`, label `System note` | collapsed, left-aligned note |
| 3 | string content starts `<command-name>` AND contains `</command-name>` AND `<command-args>` | `system-note`, label `Command: <name>` | collapsed, left-aligned note |
| 4 | string content starts `<local-command-stdout>` | `system-note`, label `Command output` | collapsed, left-aligned note |
| 5 | string content starts with a `<teammate-message …>` opening tag (UC-58) | `teammate-message`, sender-attributed | distinct, left-aligned, NON-user bubble |
| 6 | none of the above | `turn-start` (unchanged) | right-aligned user message |

`tool_result` `user` lines are unaffected — they still map to `tool-result` before this
classifier runs. Rule 3 requires the harness's exact structural placement (whole-content
prefix + the sibling `</command-name>`/`<command-args>` tags), not a substring match, so a
genuine prompt that merely *mentions* `<command-name>` mid-text stays a `turn-start`.
`[Request interrupted by user for tool use]` lines carry no structural marker and remain
`turn-start` by design (no content-sniffing).

**UC-58 — teammate/subagent messages never render as the user's own message.** In a
team-lead session, the harness delivers inbound teammate/subagent messages to the lead as
`user`-role transcript lines whose string content is a
`<teammate-message teammate_id="…" color="…">…</teammate-message>` envelope (no `isMeta`,
no `sourceToolUseID`). Left unclassified they fall to rule 6 (`turn-start`) and render
right-aligned as the user's own message. Rule 5 reclassifies them to a `teammate-message`
frame:

- **Structural detection (AC3).** Only a line whose content (after `strip()`) *starts with*
  the `<teammate-message` opening tag, with a tag-boundary char (`>`, `/`, or whitespace)
  right after the tag name, AND that is **well-formed** (carries a `teammate_id` attribute
  and a closing `</teammate-message>`), is reclassified. A genuine user message that merely
  contains the literal text stays a `turn-start`. A malformed / half envelope (no terminating
  `>`, no `teammate_id`, or no closing tag) degrades to `turn-start` rather than dropping the
  line.
- **Envelope parsing.** The opening tag's end is found by scanning for the `>` that
  terminates it **while respecting quoted attribute values** — an attribute like
  `summary="… > …"` contains a `>` that must not be mistaken for the tag end, or markup
  would leak into the bubble. `teammate_id` → `teammateId` (the bubble label); `color` →
  `color`. The inner content runs to the LAST `</teammate-message>`.
- **Nested-JSON inner content (AC6).** When the inner content is itself a JSON envelope
  (e.g. `{"type":"idle_notification",…}`), a short readable label is derived from it
  (`[<type>] <summary/text/message/content>`), so raw JSON never leaks into the bubble.
  Plain inner text is rendered as-is.
- **Client render (AC1/AC2).** The Android client renders a distinct, left-aligned,
  NON-user bubble labelled with the sender's name and tinted by `color`. Genuine user
  (right-aligned) and assistant turns are unchanged; single-agent sessions never carry the
  envelope, so they see no behaviour change (AC7).
- **Tail-helper companion (AC5).** The UC-37 `aisandbox-conversation-tail` mirror classifier
  `isNonPromptUserLine` also treats a teammate-message envelope as non-prompt, so a
  team-lead conversation is never *named* after a teammate message and turn-state derivation
  ignores teammate lines.
- **Not in the OpenAPI surface.** `ConversationServerMessage` frames are WebSocket frames,
  not REST bodies — `server/openapi.yaml` documents only REST endpoints — so the new
  `teammate-message` frame requires no OAS regeneration.

- **Rule-1 Skill-host assumption.** A top-level `sourceToolUseID` is the id of the
  `Skill` `tool_use` whose `SKILL.md` body this line carries; the `Skill` bubble already
  exists, so the body is delivered as that bubble's **on-demand `tool-detail`** — the
  helper's `fetch-detail` scan matches a line whose top-level `sourceToolUseID` equals the
  requested `toolUseId` (in addition to the `tool_use`/`tool_result` block matches), and
  the server renders that folded body with the full 48 KB renderer, preferring it over the
  tiny "Launching skill…" `tool_result`. This assumes the `Skill` host bubble is present
  (empirically always true). If it ever weren't, the fold degrades to "detail unreachable"
  on tap — never a spurious right-aligned prompt, which is strictly better than the
  pre-UC-42 behaviour of dumping the body as the user's own message.
- **`system-note` carries `detail` inline.** Unlike `tool-detail` (fetched on demand),
  the `system-note` body is small and host-less, so its full body (byte-bounded to the
  same 48 KB cap) rides **inline** in the frame; the client expands it on tap with no
  `fetch-detail` round-trip. **Backfill tradeoff:** because the body is inline, a
  `system-note` is replayed verbatim on every backfill/reconnect (a folded skill body, by
  contrast, lives only in the on-demand detail and is never replayed). `uuid` dedupe keeps
  a backfill that overlaps already-seen notes from double-rendering, so live append and
  backfilled history render identically (AC8).
- `isSidechain` injected lines fold/note under their own `source` (`subagent:<agentId>`),
  exactly like every other transcript-derived frame (AC9).

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
| `answer-batch`      | `questionUuid`, `answers[]` (`questionIndex`, `selections[]`, `freeText`) — one multi-question `AskUserQuestion` (UC-43) | 43 |
| `select-target`     | `targetId`                                                | 17 |
| `interrupt`         | — (ESC into the session)                                  | —  |
| `enumerate-targets` | —                                                         | 16, 18 |
| `fetch-detail`      | `toolUseId`, `uuid` — request the full input + result for one tool call | 41 |
| `close`             | `reason`                                                  | —  |

### On-demand tool detail (`fetch-detail` → `tool-detail`, UC-41 AC5/AC6/AC9)

When the user taps a collapsed tool bubble, the client sends `fetch-detail`
(`toolUseId`, plus the originating `tool_use` line's `uuid` for scoping). This is
**not** injected into the tmux session — it is a server-local read. The server
resolves the current target's transcript, runs the helper one-shot
(`aisandbox-conversation-tail --fetch-detail --tool-use-id <id>`), which scans the
main + subagent transcript files for the `tool_use` (id match → full input) and the
`tool_result` (`tool_use_id` match → full result + `is_error`), and prints the
matched raw `<source>\t<raw>` envelope lines. The server renders them untruncated
(48 KB cap) into a `tool-detail` frame. On a miss the helper instead prints a
single `__ctrl__\tdetail-not-found` control line; the server maps that (and any
non-zero exit / timeout / exception) to a `tool-detail` frame with
`available=false` (AC9). The fetch is audited as `conversation_fetch_detail`
(`ok` / `miss`).

### Multi-question answers (`answer-batch`, UC-43 AC2/AC3/AC4)

A single `AskUserQuestion` may carry **N>1 questions** (in the TUI these are the
arrow-key/tab-navigated questions). The server maps the whole ask to ONE
`question` frame whose `questions[]` holds all N (unchanged from UC-37). The
client renders them **paged one-at-a-time** (a "X of N" indicator + Back/Next),
buffers each question's answer locally, and — once all are answered — submits ONE
`answer-batch` frame:

```json
{ "type": "answer-batch", "questionUuid": "<toolUseId|uuid>",
  "answers": [ { "questionIndex": 0, "selections": [1], "freeText": "" },
               { "questionIndex": 1, "selections": [0, 2], "freeText": "" },
               { "questionIndex": 2, "selections": [], "freeText": "custom" } ] }
```

The single-question case is unchanged — it still uses the `answer` frame; only
N>1 uses `answer-batch`. A custom "Other" free-text answer is injected for
**both** single-select and multiSelect questions (UC-44 — the prior UC-43
limitation, where a multiSelect question's "Other" text was toggled-but-never-typed
and silently dropped, is fixed; see the multiSelect "Other" bullet below).
Server-side the handler resolves the single cached
`Question`, sorts `answers[]` by `questionIndex`, derives each question's
option-count / "Other" index from `questions[questionIndex]`, injects the whole
sheet as ONE scheduled keystroke sequence, and **evicts the cached question only
after the last item is injected** (the one cache entry covers all N). The single
and batch paths share one per-question keystroke helper (`InputInjectionService`)
so they cannot drift on a Claude TUI version bump.

**Verified TUI keystroke model (multi-question wizard).** The runtime interaction
model is unknowable from source, so it was driven live against Claude Code
**2.1.169** (the pinned build — `InputInjectionService.PINNED_CLAUDE_VERSION`)
through real `AskUserQuestion` asks, confirming each answer verbatim in the
session-transcript `tool_result`:

- The sheet is a **tabbed wizard** (`← ☐ Q1  ☐ Q2 … ✔ Submit →`), ONE question
  per screen. It opens at the top of Q1, and the option cursor **auto-resets to
  the top** of each question when the wizard advances — so the batch path does
  NOT (and must not) issue a per-question `Up`×N reset. Within a question,
  `Up`/`Down` **wrap around** the option ring (real options + "Type something";
  "Chat about this" sits outside the ring), so a blind `Up`×N reset is not
  deterministic in the wizard. The "Type something" row is **always the last**
  option index (`otherIndex == optionCount`).
- **single-select** question: `Down`×k to the option, then **`Enter`** — which
  selects it AND advances to the next tab. A **single-question** single-select
  ask has **no** "Submit" tab and submits directly on that `Enter` (no review).
- **multiSelect** question (no "Other"): walk the options toggling **`Space`** in
  place (the cursor stays put), then **`Tab`** to advance — `Enter` on a
  multiSelect option only toggles it, it does NOT advance. (Tab advances directly
  here because the cursor ends on a real option.)
- **single-select free-text "Other"**: walk to the "Type something" row, **type
  the text inline**, then **`Enter`**. Pressing `Enter` on an EMPTY row **declines
  the whole ask**, so the text is always typed before the `Enter`.
- **multiSelect free-text "Other"** (UC-44): at the "Type something" row **type
  the literal**, then **`Enter`** to COMMIT it as a custom option (the checkmark
  shown while typing is only a non-committed PREVIEW — typing then navigating away
  silently DROPS the text, which was the UC-43 bug), then **`Space`** to SELECT
  the committed option. The cursor now sits on that last row, where a single key
  only FOCUSES the in-pane Next/Submit button (unlike a real option, from which
  `Tab` advances), so **`Tab`** focuses it and **`Enter`** activates it to advance.
- After the last question advances, the wizard lands on the **Submit** tab
  ("Review your answers"); a final **`Enter`** submits the whole batch. A
  **single-question multiSelect** ask also has this Submit/review tab, so its
  `injectAnswer` path emits the extra trailing `Enter` itself (the batch path gets
  it from `injectAnswerBatch`'s trailing `Enter`).

The batch is audited as `conversation_answer` with a `batch` = question-count tag.

## Backfill, reconnect, restart (AC6 / AC20 / AC22)

- On open and on every target switch the server emits `backfill-start`, replays
  a **bounded** recent window of the transcript (default 200 lines,
  `ServerProperties.Streams.conversationBackfillLines()`), then `backfill-end`
  and live-appends. The client renders the existing history on open, then
  appends; **`uuid` dedupe** prevents a backfill that overlaps already-seen
  lines from double-rendering.
- The entrypoint runs `claude` in a `while true` restart loop, generating a fresh
  `--session-id <uuid>` each iteration, so each restart yields a **new**
  `<session-id>.jsonl` (and a **new** `claude` PID). The helper
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
  → /proc/<claudePid>/cmdline  ⇒ identity (--agent-name / --team-name / --parent-session-id / --session-id)
```

The cwd-slug is the cwd with every non-alphanumeric char replaced by `-`
(`/workspace/p` → `-workspace-p`). Then, depending on the pane's identity:

- **Teammate / subagent pane** (`--agent-name` present): anchor to the top-level
  `<slug>/<sid>.jsonl` whose **in-file `(agentName, teamName)` matches the cmdline
  tuple**, newest mtime. (`--agent-id` of the form `analyst@team` is *not* used —
  it never matches the transcript's hex stem.)
- **Main / orchestrator pane** (`--agent-name` absent):
  0. *sessionId-exact (preferred, current entrypoint — UC-37 session-bleed fix):*
     the entrypoint restart loop launches `claude --session-id <uuid>`, so the
     main pane's cmdline carries **its own** session id. Anchor main to
     `<session-id>.jsonl` **exactly and ONLY** — never fall through to
     newest-mtime. Until that file exists (a brand-new session before its first
     transcript write), resolution returns null and the helper surfaces a
     transient `no_transcript` state, so a freshly-started session can **never**
     adopt a *foreign* session's transcript from the shared slug dir.
  1. *Team active (old entrypoint, no `--session-id`):* any teammate process
     sharing this cwd-slug carries `--parent-session-id` = **the orchestrator's
     transcript stem** → anchor main to `<parent-session-id>.jsonl` exactly.
  2. *No team (old-entrypoint fallback):* the **`agentName`-absent**
     `<slug>/<sid>.jsonl` with the newest mtime (the live session is the one
     actively appended), re-anchored on a main-pane `claude` PID change.

  Tiers 1–2 are retained only for backward compatibility with a pre-fix
  entrypoint image whose `claude` launch carries no `--session-id`; with the
  current entrypoint, tier 0 always wins.

Identity-anchoring is **mandatory for AC23**: because the slug dir is shared, a
naive newest-mtime pick could route a *foreign* session's conversation to the
client — which is exactly the UC-37 bleed the sessionId-exact tier closes. If
resolution yields nothing after a bounded grace (~8 s — covering a `claude`
restart settling, or a new session before its first transcript write), the
helper emits `__ctrl__\tno-transcript` (→ a non-fatal `no_transcript` error
frame) rather than hanging silently, so the condition is observable and testable
end-to-end.

Subagent/teammate activity (AC17) is read from
`<projects>/<slug>/<session-id>/subagents/agent-*.jsonl`, globbed each tick, and
tagged `source: subagent:<agentId>` (complementing the per-line `isSidechain`).

## Input injection — keystroke mapping (centralized, version-pinned)

All mapping lives in `InputInjectionService`, pinned to Claude Code
**2.1.169** (`InputInjectionService.PINNED_CLAUDE_VERSION`). A version bump is a
single-file change (RISK 3). Only well-defined cases are mapped; everything else
relies on long-press→tmux (AC24).

| Action            | Keystrokes (`tmux send-keys`)                                              |
|-------------------|---------------------------------------------------------------------------|
| Submit prompt     | `-l` each text segment; `C-j` (LF) between segments → newline **without** submit (AC9 multiline); final `Enter` (CR) submits (AC8) |
| Answer single (single-select) | reset cursor to top (`Up`×N); `Down`×k to the chosen index; `Enter` (submits directly — N=1 single-select has no Submit tab) |
| Answer single (multiSelect)   | reset to top; walk toggling `Space` on selected; `Tab`+`Enter` to the in-pane Submit, then `Enter` to confirm review |
| Free-text "Other" (single-select) | `Down`×k to the "Type something" row; type the free text (`-l`); `Enter` — **most fragile path** |
| Free-text "Other" (multiSelect, UC-44) | at "Type something" type (`-l`); `Enter` (commit); `Space` (select); `Tab`+`Enter` (activate in-pane Submit); `Enter` (confirm review) |
| Interrupt         | `Escape`                                                                   |
| Batch single-select Qᵢ | `Down`×k to the option; `Enter` (selects **and** advances to the next tab) — no per-question reset (auto-reset) |
| Batch multiSelect Qᵢ (no "Other") | walk toggling `Space` on selected; `Tab` (advances; cursor on a real option, so `Tab` jumps to the next tab) |
| Batch free-text Qᵢ — single-select | `Down`×k to "Type something"; type inline (`-l`); `Enter` (advances) |
| Batch free-text Qᵢ — multiSelect (UC-44) | walk toggling `Space`; at "Type something" type (`-l`); `Enter` (commit); `Space` (select); `Tab`+`Enter` (focus + activate in-pane Next/Submit → advance) |
| Batch submit       | after the last question advances → "Submit"/"Review" tab; final `Enter` submits all |

### Deterministic functional gate — the `replay` profile (UC-85)

The mandatory pre-release functional gate runs the **real** server under a new Spring
profile, `replay`, whose ONLY behavioural changes are:

- **Transcript source.** Instead of spawning the in-container
  `aisandbox-conversation-tail` helper, the tail is fed by a committed fixture file — a
  recorded `<source>\t<raw-json>` / `__ctrl__\t<kind>…` envelope stream under
  `fixtures/replay/` (the same bytes the helper would have emitted). The pump →
  `ConversationEventMapper` → WS-emit path is unchanged. A fixture may carry one
  replay-only directive, `__replay__\tawait-answer`, which is consumed by the replay
  reader (never forwarded to the pump): the tail parks there until the device's answer is
  recorded, then replays the recorded post-answer frames — so question→answer→resume
  ordering is faithful and deterministic.
- **Synthetic sessions.** A scenario per fixture is exposed as a `running` session in
  `GET /v1/sessions` (no Docker), so the device has a card to open and `authorizeOpen`
  passes. Defined by `fixtures/replay/manifest.json` (`schemaVersion` checked at boot
  against `ReplayFixtureValidator.SCHEMA_VERSION` for drift; per-scenario
  `{n, target, title, fixture}`).
- **Answer echo (`answer-echo`).** With no live tmux session to inject into, an inbound
  `answer` / `answer-batch` is **recorded** (releasing the await-answer gate) and skipped
  for injection — mirroring the UC-60 read-only no-op-inject branch — and the server emits
  an `answer-echo` frame carrying exactly what it received (`questionUuid`,
  `questionIndex`, `selections[]`, `freeText`; one per batch item, in tab order). The
  on-device gate asserts this frame to prove the **selected** option is the one
  transmitted (UC-57) and that each question of a multi-question sheet maps to its own
  answer (UC-43).

Everything else — mTLS, UC-83 enrollment, the WebSocket, the mapper, the whole answer /
conversation protocol — stays the production path. The `answer-echo` frame is emitted
**only** under `replay`; outside it the `ReplayAnswerSink` bean is absent
(`enabled()=false`), no echo is produced, and a boot-time guard aborts startup if the
profile is ever active next to a production marker (AC-11). See
`.claude/skills/android-testing/SKILL.md` for the gate runbook and the fixture
re-capture procedure.

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
