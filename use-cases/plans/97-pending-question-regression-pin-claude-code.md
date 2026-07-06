---
plan_for: use-cases/97-pending-question-regression-pin-claude-code.md
work_branch: feat/uc-97-pending-question-regression-pin-claude-code
team: ai-sandbox-uc-97
approved: 2026-07-06
---

# UC-97 — Approved Implementation Plan

> Analyst↔challenger converged: **Challenger APPROVED** (v2 resolved all 4 Majors, no Criticals). All paths are inside TARGET_DIR `/workspace/ai-sandbox-uc-97-pending-question-regression-pin-claude-code`. Profiles applied: profile-java-server-architecture, profile-java-call-graph-tool.

## ⚠️ Jointly-flagged escalation — AC8 file mismatch (resolved)
AC8 says pin Claude Code "in `SandboxDockerfile` **and `ai-sandbox-updater.sh`**." Source contradicts the updater half: `ai-sandbox-updater.sh` is a parameter-free, root-owned self-updater for the **`ai-sandbox-server` `.deb` only** (`PKG=ai-sandbox-server`, `TRACK=server-v`; UC-84 security header forbids external input) — it never runs npm or touches Claude Code. Repo-wide grep confirms Claude Code is installed in **exactly one place: `SandboxDockerfile:85`**. Analyst + challenger agree: the image build is the sole version lever; the security-critical updater will NOT be cosmetically edited. AC8's intent is satisfied by pinning in `SandboxDockerfile` + **documenting** (in `PROJECT_BRIEF.md`/`README.md`) that a deployed sandbox's Claude Code version moves only via image rebuild (`docker compose build`).

═══════════════════════════════════════
# ANALYSIS

**Pipeline (verified in source).** Pending-question detection is a **live `tmux capture-pane` scraper**, not the transcript (UC-50 proved Claude buffers the blocking assistant turn in memory). `container-bin/aisandbox-conversation-tail` `streamLoop()` (~L1970-2090) matches `PENDING_QUESTION_CHROME` (~L1451) via `looksLikePendingAskUserQuestion`/`looksLikePendingPlanApproval`, then after a one-poll settle + once-per-key guard (`emittedPendingKey`) emits `__ctrl__\tpending-question\t<json>`/`pending-clear`. Server `SessionConversationHandler` pumps these: `pumpTail→dispatchTailLine→dispatchPendingQuestion→{recoverMultiQuestion,mapPendingPrompt}→ConversationEventMapper#answerable` (confirmed via call-graph tool) → `ConversationServerMessage.PendingPrompt` → client. Client `ConversationController.onFrame` `"pending-question"` (L784-796) sets `_pendingSheet`; `ConversationScreen` renders at **L255 `if(!readOnly){ pendingSheet?.let{…} }`** where **L159 `readOnly = selectedTargetId.startsWith(SUBAGENT_ID_PREFIX)`**.

**Root cause (candidate-driven; AC1 repro confirms before code) — two compounding defects, downstream of detection** (instance (c): push alert fired yet no sheet):
- **RC-1 Version drift (systemic).** `SandboxDockerfile:85` installs `@anthropic-ai/claude-code` **unpinned**. The codebase is **version-split**: `ConversationEventMapper.java:19` + its test verified against **2.1.159** (transcript shape); `InputInjectionService.PINNED_CLAUDE_VERSION="2.1.169"`, both helper `pinnedClaudeVersion` constants, and `CONVERSATION_PROTOCOL.md` on **2.1.169**. Running image ships 2.1.159 → pane-chrome/injection (2.1.169-tuned) mismatch the running TUI → intermittent detection/parse misses. This is why "every version breaks it."
- **RC-2 Warm-path re-emit gap + render-gating (per-entry-path).** Call-graph proves pending delivery is 100% tail-control-driven — **no client-request re-derive entry point**. `ConversationController.attach` (L256-259) no-ops when `connectJob.isActive`; the helper's once-per-key guard won't re-emit a still-blocked prompt on a warm socket. So if `_pendingSheet` was cleared by any transient (racing `pending-clear` from a leaked concurrent tail, `turn-start/end`/`select-target` clear), nothing re-emits until a fresh connection ("exit and re-enter to fix it"). Plus: a stale `subagent:` selection restored on warm re-entry makes `readOnly=true` → L255 gates the sheet out even when populated. `focusAnswerableTargetForDeepLink()` (L422) is wired **only** to the deep-link path (`AiSandboxApp.kt:190`), NOT normal open → exactly instance (c).

═══════════════════════════════════════
# PROPOSED SOLUTION

## Thrust A — repro-first root cause + fix (AC1-7, 11)
**A0 (HARD GATE). Live repro + 5-signal capture (AC1/AC2).** QA reproduces live on the pinned Claude Code and, at the wedge, captures: (i) `aisandbox-conversation-tail --scan-pending` for the blocked pane; (ii) pane bytes + `.jsonl` tail; (iii) server `PendingPrompt` null/populated; (iv) client `selectedTargetId`/`readOnly`; (v) foregrounded view + socket state; plus a census of the concurrent tail procs (leak vs one-per-connection). **Root cause is stated from these signals; no Thrust-A production code is written until the failing path→defect mapping is pinned.**

**A2 (PRIMARY build — minimal). Render-gating refocus.** Generalize `focusAnswerableTargetForDeepLink()` (L422) so its caller is extended from only `AiSandboxApp.kt:190` to the **normal warm-open path** (`ConversationViewModel.attach`/screen entry): a stale `subagent:` selection is corrected to `main` on every entry. **L255/L236 `readOnly` suppression is left intact** (no UC-90/UC-60 regression). This is the smallest change that explains instance (c).

**Contingent legs (build ONLY if A0 signals show them firing):**
- **A1 `resync-pending` re-derive** — *if* signals show a warm-socket cleared sheet with no re-render on a path refocus doesn't cover. Design: add `ResyncPending` subtype to `ConversationClientMessage.java` (`"resync-pending"`) + `ConversationClient.sendResyncPending()`; handler re-derives the current pane pending-state via **facade** (`ConversationFacade`→`TranscriptTailService`, profile-conformant handler→facade→service — the handler already routes via `facade.startTail` L737) and re-emits `pending-question`(full answerable payload)/`pending-clear` regardless of `connectJob` state; client sends it on warm attach. Requires extending the helper `--scan-pending` one-shot (currently classifier-only) to emit the full `{kind,questions,plan,key}` payload. Must respect existing `RECOVERY_SUPPRESS_MS`/once-per-key semantics.
- **A3 tail-leak teardown** — *if* A0's proc census shows the four `--pane 0` tails are an actual leak emitting racing `pending-clear`. Ensure server closes tail + deregisters from `ActiveConnectionRegistry` on WS close; verify `startConnectLoop` (L1240-1248) old-tail teardown.

**A4 Regression test at the detected layer (AC11).** Whichever layer A0 fingers: server `SessionConversationHandlerTest` (resync re-emits populated answerable `PendingPrompt`, fail-before/pass-after), and/or client `ConversationControllerTest` (MockWebServer, resync repopulates `_pendingSheet`), and/or a ViewModel/screen test (answerable sheet renders despite stale subagent selection). **Guard test:** a genuine subagent read-only view (non-injectable, no answerable sheet) STILL suppresses composer + sheet (no-UC-90-regress anchor).

## Thrust B — pin Claude Code (AC8, AC9) — pin chosen EMPIRICALLY
1. Developer/QA establish live: is 2.1.169 still installable from npm; the current newest published version; which versions pass the **augmented UC-85 gate** (Thrust C).
2. **Pin = newest version that passes the gate** (AC8 verbatim), rationale recorded in `PROJECT_BRIEF.md`/`CONVERSATION_PROTOCOL.md`.
3. **`SandboxDockerfile:85`** → `ARG CLAUDE_CODE_VERSION=<X>` (mirroring `ARG GITLEAKS_VERSION=8.21.0` at L27) + `npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}`; update pinning-policy comment (~L104-106).
4. **Reconcile the version split to single X:** update the `ConversationEventMapper` `2.1.159` note, `InputInjectionService.PINNED_CLAUDE_VERSION`, both helper `pinnedClaudeVersion` constants, and `CONVERSATION_PROTOCOL.md` all to X; QA re-verifies transcript-shape + pane-chrome + injection **all against X**.
5. **`PROJECT_BRIEF.md`** frontmatter `stack.versions.claude_code: "<X>"` (replace `"latest-at-build"`) + prose policy/deliberate-bump process; **document the image-build lever** (per the AC8 escalation) in brief/README. No updater edit.
6. **⚠️ Reconciliation may be MORE than a constant bump (challenger's non-blocking note):** if X ≠ 2.1.169, the pane chrome regexes and injection keystroke walk were TUNED to 2.1.169's TUI — a different TUI shape may need real retuning. The plan is explicit: **a red C2 on X means "retune chrome/injection for X, or fall back to the next-newest gate-passing version" — NOT "edit the constant and move on."**

## Thrust C — drift guard (AC10) — additive to UC-85 (replay gate untouched)
- **C1 (deterministic, always-run):** capture raw `tmux capture-pane` bytes for pending single-select, multi-question wizard, ExitPlanMode from the **pinned X**; commit under `fixtures/`. Add pure Node tests in `container-bin/aisandbox-conversation-tail.test.js` asserting `looksLikePendingAskUserQuestion`/`looksLikePendingPlanApproval`==true + `parsePendingPrompt` yields the expected answerable payload. LLM-free, CI-runnable, would have caught this drift.
- **C2 (live, pre-release):** add a stage to `android/gate.sh` + wire `.github/workflows/android-gate.yml` that launches the **pinned X**, raises a real single + multi `AskUserQuestion`, asserts helper detects (`--scan-pending`→`pending-question`) AND end-to-end in-view sheet render on-device (across all three layers). Gated on Claude availability; required in the release gate.
- **AC9↔AC10 bump discipline:** a deliberate pin bump = edit Dockerfile ARG + recapture C1 fixtures + green C2 leg → drift turns the gate RED before release.

═══════════════════════════════════════
# FILES AFFECTED

**Production code (developer)**
- `SandboxDockerfile` — `ARG CLAUDE_CODE_VERSION` + pinned install + policy comment.
- `PROJECT_BRIEF.md` — frontmatter `stack.versions.claude_code`, prose policy, image-build-lever doc.
- `README.md` — image-build-lever doc (AC8 escalation).
- `server/CONVERSATION_PROTOCOL.md` — version refs→X + pin/bump doc.
- `server/src/main/java/com/aisandbox/server/stream/service/ConversationEventMapper.java` — version note→X.
- `server/src/main/java/com/aisandbox/server/stream/service/InputInjectionService.java` — `PINNED_CLAUDE_VERSION`→X.
- `container-bin/aisandbox-conversation-tail` — `pinnedClaudeVersion`→X; (contingent) extend `--scan-pending` to full payload.
- *(contingent on A0)* `server/.../stream/dto/ConversationClientMessage.java` (ResyncPending), `.../stream/handler/SessionConversationHandler.java` (resync handler + tail teardown), `.../stream/facade/ConversationFacade.java` (re-derive facade method), `.../stream/service/TranscriptTailService.java` (full-payload one-shot), `android/.../net/ConversationClient.kt` (`sendResyncPending()`).
- `android/.../conversation/ConversationController.kt` — generalize refocus (+ contingent resync-on-warm-attach).
- `android/.../ui/screens/ConversationViewModel.kt` (+ `ConversationScreen.kt` if entry wiring) — trigger refocus on normal open.
- `android/gate.sh`, `.github/workflows/android-gate.yml` — C2 live leg.

**Test code (QA)**
- `server/src/test/java/com/aisandbox/server/release/SandboxDockerfileContractTest.java` — assert `ARG CLAUDE_CODE_VERSION=<X>` + install line uses `@…@${CLAUDE_CODE_VERSION}` (deterministic AC8/AC9 anchor).
- `server/src/test/java/.../stream/handler/SessionConversationHandlerTest.java` — (contingent) resync re-emit fail-before/pass-after.
- `android/src/test/kotlin/.../conversation/ConversationControllerTest.kt` — (contingent) resync repopulates sheet.
- `android/src/test/kotlin/.../ui/screens/ConversationUiStateTest.kt` (+ instrumented `PaneSignalPendingQuestionGateTest.kt`/`AskUserQuestionGateTest.kt`) — answerable sheet renders despite stale subagent selection; genuine-subagent-suppression guard; C2 live leg.
- `container-bin/aisandbox-conversation-tail.test.js` — C1 pinned pane-shape fixture tests.
- `fixtures/pane-signal/*` — new committed drift fixtures (captured against X).

**Explicitly NOT changed:** `ai-sandbox-updater.sh` (AC8 escalation); existing UC-85 replay fixtures/tests (C1/C2 additive).

═══════════════════════════════════════
# RISKS & CONSIDERATIONS
- **AC1 is load-bearing** — RC-1/RC-2 strongly evidenced, but the failing-path→defect mapping (foreground/background, socket state, first-vs-later question, single vs multi) is pinned by the live repro before locking contingent legs.
- **Version-ordering / installability (AC8)** — the 2.1.159/2.1.169 split is *layer divergence*, not a stale-image regression; must confirm X is installable from npm and reconcile all four version references to X. If X≠2.1.169, budget for chrome/injection retuning (see B6).
- **6-round-cap** — three thrusts; contingent-leg gating keeps Thrust A minimal. If the repro forces the full resync+teardown build AND retuning, this may pressure the cap — flag early if so; a viable split is A(primary)+B first, C + contingent legs as fast-follow.
- **Profile conformance** — re-derive routed Controller/Handler→Facade→Service (no shortcut); `@Transactional` untouched (no DB); no entity/DTO boundary changes.
- **No regression to UC-40/50/55/57/69/75/76/85/90/93/96** — refocus leaves `readOnly` suppression intact; C1/C2 additive.
- **Call-graph caveat** — blast-radius verified on the Java server only; Kotlin client + Node helper read directly (out of tool scope).

═══════════════════════════════════════
# CHALLENGER FINAL VERDICT — APPROVE (v2, round 2)
First round raised 4 Majors (no Critical); all resolved in v2. Root cause verified against source; fix shape endorsed (A2 refocus primary; A1/A3 contingent on AC1 signals; B pin in SandboxDockerfile only + documented image-build lever; C1 deterministic fixture + C2 live gate leg). AC8 updater-clause met by documentation, not a script edit (jointly flagged). Pin value resolved empirically = newest gate-passing version, with all four in-repo version references reconciled to it and QA re-verifying all three layers. Verification must be by the LIVE UC-85 functional gate. No regressions to UC-40/50/55/57/69/75/76/85/90/93/96.
