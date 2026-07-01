---
plan_for: use-cases/96-multiquestion-gate-timeout.md
work_branch: feat/uc-96-multiquestion-gate-timeout
team: ai-sandbox-uc-96
approved: 2026-07-01
---

# UC-96 — Approved Implementation Plan: MultiQuestionGateTest timeout (server-side replay ordering race)

> Analyst↔challenger approved (round 2; challenger verdict: **APPROVE**, all 3 claims verified). Root cause = a replay-only server ordering race; client + server production contract are correct. Fix is a replay-gated emit-ordering change in the server handler + a regression-guard test. No assertion weakening.

## Root cause (confirmed)
`applyAnswerBatch` (`server/src/main/java/com/aisandbox/server/stream/handler/SessionConversationHandler.java:642-668`): line 652 `safe(() -> facade.injectAnswerBatch(...))` → `ConversationFacade.recordAnswerBatch` (~:385) does `gate(n).offer(SIGNAL)`, which **unparks the replay tail pump BEFORE** the two `AnswerEcho` frames are emitted (lines 656-663). The unparked pump reads `u2/a2/te1` → EOF → `finally { synchronized(outboundLock){ outbound.tryEmitComplete() } }` (~207-243) on the same outbound sink; `emit()` does `tryEmitNext(frame)` **ignoring the EmitResult** (~762), so any echo emitted after the sink completes is `FAIL_TERMINATED` and **silently dropped**. A batch must land **2** post-unpark emits (single only 1) → the batch's 2nd echo loses the window → `collector.received().size` stays `<2` → `MultiQuestionGateTest.kt:66 waitUntil(90_000)` times out. Deterministic under a consistent CI thread schedule ⇒ red on every `main` run since UC-85 introduced it. Zero client involvement; scales with echo count = the exact single-passes/multi-fails differential.

The prior client-side pin (`EchoCollector.collectLatest` cancel-on-reconnect) is **inert**: the client only swaps after a 1s reconnect backoff (`ReconnectController.schedule[0]=1000ms`, `reset()` on prior Open), and `incoming` is a never-completed `SharedFlow(replay=0, extraBufferCapacity=256)`, so a `Dispatchers.IO` collector drains both buffered echoes in µs and wins by ~1s — it cannot cause an always-red test. Withdrawn (kept only as low-probability defense-in-depth).

## Step 0 — repro-first (mandatory, AC6), decisive
1. **Emulator-free handler-level server test (primary, no Spring/mTLS/WS server needed):** follow the precedent in `server/src/test/.../SessionConversationHandlerTest.java` (constructs the handler directly, minimal WebSocketSession double, mocked `ConversationFacade.startTail` returning a fake Tail that drives the pump in-process, captures outbound frames). Add a replay-mode variant that dispatches one `answer-batch` and asserts **2 `AnswerEcho`s are emitted to the sink BEFORE `tryEmitComplete`** (an ordering invariant, not merely final count). Show it **FAILS against the current (pre-fix) order** (reproduces `<2` / echo-after-complete) and **passes after the fix** — this is the repro-first evidence at the mechanism level.
2. **(diagnostic, optional)** log the `EmitResult` of each `AnswerEcho` `tryEmitNext` to confirm `FAIL_TERMINATED` on echo #2 pre-fix.
3. **End-to-end (authoritative):** the `android-gate` CI job on the PR — red on the base, green after the fix — plus the full `android/gate.sh` (0 failures).

## Fix (leading = SERVER, replay-scoped, no assertion change)
In `SessionConversationHandler.java`, **move the echo-emit block ahead of the gate-releasing inject call** in BOTH methods (symmetry so single can't regress):
- `applyAnswerBatch`: emit the per-item `AnswerEcho`s (uses only `ab.questionUuid()` + the already-built/sorted `items` at ~647 — no dependency on `specs` at 648-651 or on `injectAnswerBatch`'s void return) **after `items` is built (~647), before `safe(() -> facade.injectAnswerBatch(...))` at 652.** Leave `evictCachedQuestion(q)` where it is.
- `applyAnswer`: symmetrically emit its single `AnswerEcho` (uses only `a.*`) before `injectAnswer`.
Both blocks are `answerEchoEnabled()`-gated (false in production) → moving a block that is entirely skipped in prod is a **ZERO production behavior change** (AC-11 safety). With the pump still parked, both echoes are enqueued into the live sink first; the pump then unparks/emits `u2/a2/te1`/completes after — the reorder **removes the race entirely** (completion can no longer precede the echo emits). Reactor `Sinks.many().unicast().onBackpressureBuffer()` is FIFO + terminal-after-data, so ordering holds.
- Add a one-line comment noting the intentional ordering (echoes now strictly precede the post-answer transcript frames; the collector correlates by questionIndex/uuid not position, and the resume assertion keys off the last frame `te1`, so this is harmless).
- **Alternative (only if the reorder is judged risky):** hold the multi fixture stream open until the echoes flush (defer the post-answer EOF via a settle marker in `fixtures/replay/multi-question.tail` / `ReplayEnvelopeReader`). Still replay-scoped, no assertion change.
- **Do NOT** bump `ConversationClient.incoming` replay (app-wide production change) or just raise the 90s `waitUntil` (neither recovers an echo dropped into a completed sink).

If Step-0 evidence unexpectedly shows a genuine harness drop instead (low probability): rework `GateHarness.EchoCollector` to a non-cancelling per-client collector (accumulate across reconnects, cancel only on `stop()`), assertions intact.

## Files Affected
**Production (server Java) — leading fix, replay-gated:** `server/src/main/java/com/aisandbox/server/stream/handler/SessionConversationHandler.java` (reorder echo-emit before gate-release in `applyAnswerBatch` + `applyAnswer`).
**Test (server):** new/extended test under `server/src/test/**` (handler-level) — reproduce `<2` pre-fix + assert **2 echoes emitted before `tryEmitComplete`** for a batch answer under replay (regression guard; ordering assertion, not count-only). Runs in `./gradlew :server:test`, no emulator.
**Fixture (only if the "hold stream open" alternative is chosen):** `fixtures/replay/multi-question.tail` (+ possibly `ReplayEnvelopeReader.java`).
**Test (Android, only if Step 0 shows a harness drop):** `android/src/androidTest/.../gate/GateHarness.kt`. `MultiQuestionGateTest.kt` — **no assertion changes.**

## Verification
- `./gradlew :server:test` green incl. the new handler ordering-guard test (mechanism; repro-first red→green).
- `android-gate` CI job on the PR green + full `android/gate.sh` 0 failures (user-visible end-to-end; the release gate). Both required per AC1/AC6.

## Risks & guardrails
- No assertion weakening (pure emit-ordering; every `MultiQuestionGateTest` check stays). Replay-scoped (`answerEchoEnabled()` false in prod → zero prod effect). Symmetric with `applyAnswer`. Guard test asserts ordering (echoes before complete), not just final count. Run android-gate to green even after the server unit test passes (server test proves the mechanism; the gate proves the user-visible fix). Scope: UC-85 gate leg + its replay-gated server path only; unrelated to UC-94/UC-95.
