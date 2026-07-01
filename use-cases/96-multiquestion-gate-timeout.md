# Use Case 96: Fix the failing UC-85 gate leg — MultiQuestionGateTest times out (unblock the pre-release gate)

## Summary
The deterministic pre-release gate (UC-85, `android/gate.sh` / the `android-gate` CI job) has one consistently-failing leg: `MultiQuestionGateTest.multiQuestion_eachQuestionMapsToItsOwnAnswerFrame_andConversationResumes` (`android/src/androidTest/kotlin/com/aisandbox/android/gate/MultiQuestionGateTest.kt:66`). It drives the real paged `QuestionSheet` (answer Q1 → Next → answer Q2 → Submit) against the `N_MULTI_QUESTION` synthetic replay session and then `waitUntil(90_000) { collector.received().size >= 2 }` — expecting one `answer-echo` frame per question, correlated by `questionIndex`. Fewer than 2 echoes arrive within 90s, so the wait times out (`ComposeTimeoutException`). The test was introduced by UC-85 (commit `304601e`, its only commit) and has failed on every `main` run since 2026-06-18 — i.e. it has effectively never passed in CI, so the gate has been red on `main` independent of any other work. Because the `release` skill makes this gate a mandatory pre-release step, the gate must be genuinely green before a release can be cut. This use case is to determine the true cause (a real bug in the multi-question paged-submit/answer-echo client path, versus an incorrect assertion / fixture / timing in the gate test) and fix it so the leg passes deterministically, WITHOUT weakening what the gate verifies (each question maps to its own echo frame with the correct `questionIndex` and selections, and the conversation resumes).

## Acceptance Criteria
1. `MultiQuestionGateTest.multiQuestion_eachQuestionMapsToItsOwnAnswerFrame_andConversationResumes` passes deterministically in the `android-gate` CI job (green on the PR), and the full UC-85 gate (`android/gate.sh`) passes (all its instrumented tests, 0 failures).
2. The root cause is identified and stated explicitly: whether the defect was in the client (paged `QuestionSheet` submit path / `submitAnswer`/`submitAnswerBatch` / answer-echo correlation), the replay fixture (`N_MULTI_QUESTION` / `EchoCollector`), or the test itself (assertion/timing) — with evidence.
3. The fix preserves the gate's intent: each of the ≥2 questions still maps to its OWN `answer-echo` frame with the correct `questionIndex` and the exact selections, both echoes share the question `uuid`, and the conversation resumes (pending sheet clears, `turnPhase` returns to `IDLE`). The assertions are not loosened to mask a real defect.
4. If the cause is a client bug, the production fix is in the Android client (Kotlin/Compose) and covered so it can't silently regress; if the cause is the test/fixture, the corrected test still fails against the buggy behavior it is meant to catch (it is not turned into a no-op).
5. No regression to the other gate legs (single-question / pane-signal / etc.) or to the multi-question UX in the app.
6. Repro-first: the failure is reproduced (gate red on the current base) and the fix is shown to flip it green (gate leg passing) — via the `android-gate` CI job on the PR (authoritative), and/or locally via the `android-testing` skill on the host emulator.

## Potential Pitfalls & Open Questions
- **Ambiguity** — is `<2 echoes` because Q1's answer is never submitted on "Next" (paged flow only submits the batch at the end), or because both answers collapse into a single batch echo, or because the replay fixture doesn't replay two echo frames? The analyst must trace the actual `QuestionSheet` paged-submit wiring (`onSubmit` vs `onSubmitBatch`) and the replay fixture to decide.
- **Assumption** — the CI emulator environment is healthy for the other gate legs (only this leg fails), so this is a leg-specific correctness issue, not an emulator-wide failure. Confirm the other legs pass in the same run.
- **Risk** — do NOT "fix" it by weakening the assertion or bumping the timeout without understanding why <2 echoes arrive; a longer timeout won't help if the second echo is never produced.
- **Edge case** — `q2Multi` (Q2 multi-select) path: the test taps option 1 (+ option 0 if multi) then Submit; ensure the fix handles both single- and multi-select Q2.
- **Verification cost** — verifying requires the Android instrumented gate (CI `android-gate`, ~6 min/run; or the local emulator via the `android-testing` skill, which needs `/dev/kvm`). Prefer CI as the authoritative signal.
- **Scope** — this is the UC-85 gate leg + whatever client/fixture code it exercises. It is unrelated to UC-94/UC-95 (server/shell). Do not fold in unrelated Android changes.

## Original Description
Discovered while trying to cut the server-v0.0.55 release for UC-94 + UC-95: the `release` skill's mandatory pre-release UC-85 gate (`android-gate`) is red. Investigation showed a single failing leg — `MultiQuestionGateTest` — timing out at `MultiQuestionGateTest.kt:66` (`waitUntil(90_000) { collector.received().size >= 2 }`, `ComposeTimeoutException: Condition still not satisfied after 90000 ms`). The same test fails identically on `main` back to `b289102` (UC-93, 2026-06-18); its only commit is `304601e` (UC-85, PR #106), so it appears to have never passed in CI. UC-94/UC-95 touch zero Android code and do not cause it. This UC exists to make the mandatory gate genuinely green so the release can proceed.

## Clarifications
- Q: Is this part of UC-94/UC-95?
  A: No — those are server/shell fixes. This is a pre-existing Android gate-test failure introduced by UC-85, surfaced as the release-gate blocker. The user chose to fix it (rather than waive the gate or hold the release) before cutting the release.
- Q: How is the fix verified given it needs an Android emulator?
  A: Authoritatively via the `android-gate` CI job on the PR (must go green); optionally locally via the `android-testing` skill on the host emulator (`/dev/kvm` present).
- Q: Fix the client or the test?
  A: To be determined by investigation (AC#2) — fix whichever is actually wrong, without weakening what the gate verifies (AC#3/#4).
