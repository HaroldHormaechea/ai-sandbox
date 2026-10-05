---
plan_for: (free-form task)
work_branch: feat/pki-init-chowntree-symlink-fix
team: ai-sandbox
approved: 2026-10-05
---

# Implementation plan — `pki init` chownTree crash on dangling DinD symlink

## Analysis

`sudo aisandboxctl pki init --force --san IP:<addr>` crashes with
`java.nio.file.NoSuchFileException` during step 4 (directory creation + chown +
chmod), **before** the cert is ever minted (step 5), so no key is produced.

Root cause (verified against source):
- `PkiInitCommand.java:219` calls `ownership.chownTree(sessionsDir)` — a
  *recursive* chown over `/var/lib/ai-sandbox-server/sessions`.
- `Ownership.chownTree` (`Ownership.java:69`) walks with `Files.walk(root)` and
  calls `chown(each)`.
- `Ownership.chown` (`Ownership.java:62`) resolves the `PosixFileAttributeView`
  **without** `LinkOption.NOFOLLOW_LINKS`, so `setOwner(...)` **follows** symlinks.
- A dangling symlink inside a per-session rootless-DinD overlay2 layer
  (observed: `…/sessions/<session>/environment-utilities/dind/var/lib-docker/overlay2/<layer>/diff/usr/local/bin/nodejs`)
  makes `setOwner` hit a missing target → `NoSuchFileException` → propagates out
  and aborts the whole init.

Two distinct defects: (1) `chown` follows symlinks (lchown vs chown), and (2)
recursively chowning the entire `sessions/` tree to the service user is itself
wrong — it would flatten per-session rootless-DinD **subuid-mapped** files
(uid 100000+) to `ai-sandbox-server`, a latent UC-30 / UC-94 regression even on
hosts without a dangling link.

## Proposed Solution (challenger-approved, design (c) = both)

1. **`Ownership.chown`** — resolve the `PosixFileAttributeView` with
   `LinkOption.NOFOLLOW_LINKS` (lchown semantics), so the symlink **itself** is
   chowned rather than its (possibly missing) target. Fixes the follow-the-
   dangling-link crash at its source and is the correct behavior for a tree walk.

2. **`Ownership.chownTree`** — replace `Files.walk` + per-entry chown with
   `Files.walkFileTree` + **skip-and-continue**: catch per-entry failures and
   return `FileVisitResult.CONTINUE` from `visitFileFailed` (and guard the
   per-entry chown), so an entry that vanishes or can't be chowned mid-walk never
   aborts the pass. This covers BOTH the symlink-follow case and the
   walk-vs-chown TOCTOU race (a file enumerated by the walk can disappear before
   the chown runs — common inside a live DinD store). Emit **one aggregate
   warning per root** summarizing skipped entries (no per-entry log spam).

3. **`PkiInitCommand.java:219`** — change `chownTree(sessionsDir)` to
   `chown(sessionsDir)` (single, non-recursive), mirroring the line-217
   `chown(sessionsParent)`. `sessions/` only needs to *exist* at mode 0750 and be
   owned by the service user at its top level (UC-05); its per-session contents
   are managed per-session and must NOT be recursively re-owned (UC-30 subuid
   preservation; UC-94 "narrowly-scoped repair, never blanket chown"). This both
   removes the crash trigger and fixes the latent subuid-flattening regression.

4. **Test seam** — add an injectable `ownershipResolver` (a `BiFunction`) plus
   `setOwnershipResolver(...)` on `PkiInitCommand.Init`, mirroring the existing
   injection seams in that class, so a test can supply a current-user resolver
   and exercise the real chown path without root. The production code path is
   byte-identical when the seam is not overridden.

## Files Affected

**Production code** (developer — within `paths.production`):
- `server/src/main/java/com/aisandbox/server/cli/Ownership.java` — items 1 & 2.
- `server/src/main/java/com/aisandbox/server/cli/PkiInitCommand.java` — items 3 & 4.

**Test code** (QA — within `paths.test`):
- `server/src/test/java/com/aisandbox/server/cli/OwnershipTest.java` — primary
  hermetic regression: dangling-symlink `chown`/`chownTree` must not throw, and a
  vanished entry mid-walk is skipped. Root-free via self-chown to the current user.
- `server/src/test/java/com/aisandbox/server/cli/PkiInitCommandTest.java` —
  end-to-end: inject a current-user resolver, place a dangling symlink under
  `sessionsDir`, assert `pki init` now completes past the sessions chown and
  mints the cert (fails pre-fix, passes post-fix). Also assert the sessions
  *contents* are NOT recursively chowned — must use a **spy resolver recording
  chown'd paths** (owner inspection is vacuous since self-chown to the current
  user is an indistinguishable no-op).

## Risks & Considerations

- **Blast radius:** the only other `chownTree` caller is
  `ClaudePreInitStep.templateDir` (+ the enrollment/log roots in `PkiInitCommand`
  itself). The hardening (items 1–2) is strictly more robust for all of them and
  changes no success-path behavior. `chown` is used widely for single files; the
  `NOFOLLOW_LINKS` change only differs for symlink arguments, which single-file
  chowns don't target.
- **UC-30 / UC-94:** item 3 is the key correctness fix — it *stops* clobbering
  subuid-mapped session files; it does not weaken any required ownership (the
  top-level `sessions/` dir is still chowned to the service user).
- **UC-05 ACs:** `sessions/` still created at 0750 and owned by the service user
  at the top level — AC preserved.
- **profile-java-server-architecture:** layering rules (Controller→Facade→
  Service→Repository) are N/A for install-time CLI code — no conflict.
- **No root/sudo** needed for any test; all run under `./gradlew :server:test`.

## Challenger verdict

**APPROVE** (2 rounds). Verified: root cause against source; blast radius
(`chownTree` callers = `PkiInitCommand` + `ClaudePreInitStep.templateDir` only);
alignment with UC-05, UC-94 AC5/risk, UC-30 subuid preservation; profile layering
N/A for CLI code; no brief/version conflict. Non-blocking QA note carried into the
test plan (spy resolver for the "not recursively chowned" assertion).
