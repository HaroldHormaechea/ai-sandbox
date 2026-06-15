---
name: release
description: >-
  Cut a release for the ai-sandbox project (multi-track: android-vX.Y.Z and/or
  server-vX.Y.Z). Use when asked to release, cut a release, tag a version, or
  ship the merged use cases. A MANDATORY pre-release functional gate runs first:
  the Android client is functionally tested against a LIVE server, exercising a
  single AskUserQuestion AND a multi-question flow, before any tag is pushed.
  Linear runbook — do not skip the gate.
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
> they do NOT replace the live functional gate. A regression in the
> question/answer paths (UC-43/44/55/57/75) or the conversation view
> (UC-37/40/41/47/49/50/58/78/79/80/81) only shows up against a real server with
> a real on-device session. Never push a release tag without a green Phase 1.

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

## Phase 1 — MANDATORY pre-release functional gate (live server + on-device)

Stand up the full live stack and functionally drive the Android client. Use the
**`android-testing`** skill (Phases 1–4: boot the KVM AVD → build/start the
management server → enroll the device over mTLS via the **UC-83 QR-from-file
route** — render the invite as a QR PNG and enroll through the production
`QrImageDecoder` → `onQrPayload` path, no camera — → instrumented tests). Then,
beyond the instrumented suite, **functionally exercise the question/answer paths
against the live server**:

1. **Single `AskUserQuestion`** — drive a session whose Claude raises ONE
   question; confirm the app surfaces it (pending-question indicator + push
   notification per UC-69/76), the user can answer in-app (UC-55), and the
   selected option is the one actually sent (UC-57) — not first-visible — for
   single-select, multi-select, and the "Other" free-text path (UC-75 must not
   hang on the spinner).
2. **Multi-question flow** — drive a session that raises a multi-question
   `AskUserQuestion` sheet (UC-43); confirm each question maps to its own answer
   frame and the conversation resumes cleanly afterwards.
3. **Conversation-view sanity** (regression surface from this cycle) — confirm
   teammate/subagent messages render as distinct non-user bubbles (UC-58),
   the chat opens anchored at the bottom (UC-78), scrolling up lazily loads older
   messages without a jump (UC-79), long messages aren't cropped (UC-80), and
   long-press copy works (UC-81). A booted-emulator screenshot of a real session
   transcript, eyeballed, is the evidence.

**Gate outcome:** all three must pass on a real device against a live server.
Pull screenshots and actually look at them. If any fails, STOP — it is a
release blocker; fix it (a new use case / dev-team run) before tagging. If the
full PKI/enrollment server genuinely cannot be stood up in the current
environment, do NOT silently proceed: report the blocker, fall back to the
in-app answer-path instrumented tests as partial assurance, and get explicit
human sign-off on the residual risk before pushing a tag.

Known environment noise to ignore (not gate failures): the "Quickstep isn't
responding" launcher ANR overlay on emulator screenshots; the 7 pre-existing
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
