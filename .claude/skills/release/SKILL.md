---
name: release
description: >-
  Cut a release for the ai-sandbox project (multi-track: android-vX.Y.Z and/or
  server-vX.Y.Z). Use when asked to release, cut a release, tag a version, or
  ship the merged use cases. A MANDATORY pre-release functional gate runs first:
  the UC-85 deterministic, LLM-free gate (the `replay`-profile server + the
  on-device instrumented Compose suite driven by stable testTag) must pass —
  locally via android/gate.sh or as the android-gate CI job — before any tag is
  pushed. Linear runbook — do not skip the gate.
---

# Release — ai-sandbox

Cuts a release for one or both tracks and writes honest, diff-derived notes.
Releases are **multi-track by tag prefix**:

- **`android-vX.Y.Z`** — scope `android/`. Built + published by
  `.github/workflows/android-release.yml` (signed APK + AAB).
- **`server-vX.Y.Z`** — scope `server/` **and** `container-bin/` (the in-container
  helper `aisandbox-conversation-tail` rides the server track). Built + published
  by `.github/workflows/server-release.yml` (operator zip + `.deb`).

Cut a track only if merged work since its last tag actually touched that track's
scope. A change touching both scopes releases both tracks.

> **Hard rule — the functional gate (Phase 1) is a prerequisite for every
> release.** Per-use-case unit/instrumented tests already passed at merge time;
> they do NOT replace the functional gate. A regression in the question/answer
> paths (UC-43/44/55/57/75) or the conversation view
> (UC-37/40/41/47/49/50/58/78/79/80/81) only shows up when the real Compose stack
> drives the real mTLS WebSocket end-to-end. As of UC-85 that gate is
> **deterministic and LLM-free**: the real server runs under the `replay` Spring
> profile (committed protocol fixtures instead of a live Claude session) and an
> on-device instrumented Compose suite drives every gated behaviour by stable
> `testTag` and asserts it programmatically — no `adb input tap` coordinates, no
> screenshot eyeballing. Never push a release tag without a green Phase 1.

---

## Phase 0 — Scope the release

1. Find the last tag per track:
   `git fetch origin --tags`, then
   `git tag --sort=-creatordate | grep -E '^android-v'` and `… '^server-v'`.
2. List what merged since each track's last tag, scoped to that track:
   - android: `git log <last-android-tag>..origin/main --oneline -- android/`
   - server: `git log <last-server-tag>..origin/main --oneline -- server/ container-bin/`
3. Decide which track(s) to cut and the next patch version of each (bump the
   patch: `android-v0.4.14 → android-v0.4.15`, `server-v0.0.50 → server-v0.0.51`;
   bump minor/major only on the user's instruction).
4. Map the merged commits to their use cases (the `UC-NN` in commit subjects /
   the ledger) — you will need this for Phase 3 notes.

## Phase 1 — MANDATORY pre-release functional gate (UC-85 deterministic, LLM-free)

Run the **deterministic gate**. It is one command and needs NO live Claude
session, NO LLM in the loop, NO blind `adb input tap`, and NO screenshot
eyeballing — every gated behaviour is raised from committed protocol fixtures and
asserted programmatically by an on-device instrumented Compose suite driven by
stable `testTag`. The mechanics (record/replay, the `replay` Spring profile, the
fixture-refresh procedure) live in the **`android-testing`** skill; this phase
just invokes the gate and reads its pass/fail.

**Run it one of two equivalent ways:**

- **Locally (KVM spawn):**
  ```bash
  JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk ./android/gate.sh
  ```
  It builds the server bootJar + APKs, stands up the REAL mTLS server under
  `--spring.profiles.active=replay`, boots the AVD (animations off — AC-10),
  enrolls via the UC-83 QR-from-file route, runs the instrumented gate suite, and
  tears down. Exit 0 = gate passed.
- **In CI:** confirm the **`android-gate`** workflow is green on the commit being
  released (it runs the same flow emulator-in-CI; the `/dev/kvm` precondition is
  proven by the `android-gate-smoke` job).

**What the gate asserts (all programmatic — no eyeballing):**

1. **Single `AskUserQuestion`** — single-select, multi-select, and the "Other"
   free-text path (UC-55/75; the spinner must clear), and that the **selected**
   option is the one transmitted (UC-57) — verified by the server's `answer-echo`
   frame, not the UI state.
2. **Multi-question sheet** (UC-43) — each question maps to its own answer frame
   (one `answer-echo` per tab) and the conversation resumes cleanly.
3. **Conversation-view invariants** — teammate/subagent bubbles render as distinct
   non-user messages (UC-58), anchor-to-bottom on load (UC-78), uncropped long
   messages (UC-80), and long-press copy (UC-81), all asserted by `testTag`.

**Gate outcome:** `android/gate.sh` exit 0 (or a green `android-gate` CI run) is
the pass. Any failure is a **release blocker** — fix it (a new use case /
dev-team run) before tagging; do NOT push a tag on a red or skipped gate.

**Scope limitation to honour (UC-85):** lazy scroll-up paging *older than the
backfilled window* (UC-79 `load-older`) is NOT exercised under `replay` — the
server's older-page fetch uses the docker helper, which is absent in a replay
run. The gate asserts anchor-to-bottom (UC-78) and in-window scroll only. If a
release touches the `load-older`/paging path specifically, cover it with a
targeted check (or a live spot-check) in addition to the gate.

**Demoted (no longer the gate):** the old LLM-driven drive — piloting the Compose
UI with blind `adb input tap` coordinates against a live Claude session and
eyeballing pulled screenshots — is REMOVED as the gate (it was non-deterministic
and chronically blocked by launcher/SystemUI ANRs, e.g. the server-v0.0.52
cycle). A manual screenshot spot-check is OPTIONAL extra assurance only; it never
substitutes for, nor blocks on, the deterministic gate.

Known environment noise to ignore (not gate failures): the 7 pre-existing
`HostScriptComposeEnvTest` failures when `:server:test` runs inside a git
worktree (a `user.dir`-parent path assumption — unrelated to release scope).

## Phase 2 — Tag and push (triggers the build + publish)

Only after Phase 1 is green. For each track being released, from a clean
`origin/main`:

```
git fetch origin && git checkout main && git merge --ff-only origin/main
git tag server-vX.Y.Z      # and/or android-vX.Y.Z
git push origin server-vX.Y.Z
```

Pushing the tag triggers the matching `*-release.yml` workflow, which builds the
artifacts and publishes the GitHub release (with `generate_release_notes: true`
auto-notes as a placeholder). Wait for the workflow to finish green
(`gh run list --workflow server-release.yml`); if the build fails, the release
is incomplete — diagnose before proceeding. Do NOT hand-build artifacts.

## Phase 3 — Write the real release notes (overwrite the auto-notes)

The auto-generated GitHub notes are a placeholder. Replace them with proper,
diff-derived notes using the workspace **`generate-release`** skill — **never**
by copying a previous release's body. Run it **once per track**, scoped to that
track's paths, against the diff since the previous tag of the same track. It
produces:

- **New features** — one bullet per feature, ≤ 200 words, each linking the use
  case(s) it came from.
- **Bugfixes** — one bullet per fix, ≤ 50 words, each linking its use case / PR.

`generate-release` derives everything from the commit/PR/use-case diff since the
previous track tag; it writes only the release **description** (the tag + build
are already done by Phase 2).

## Phase 4 — Verify + report

- Confirm both releases exist with artifacts attached
  (`gh release view server-vX.Y.Z`) and the notes are the diff-derived ones, not
  the placeholder.
- Report to the user: the tags cut, the release URLs, the use cases each release
  covers, and the Phase 1 functional-gate result (with the screenshot evidence).

## Cadence

Default cadence for autonomous runs is a release after the queued use cases are
delivered (this matches how the dev-team batches land). The user may ask for a
different cadence (e.g. "release every N merged UCs"); honor that.
