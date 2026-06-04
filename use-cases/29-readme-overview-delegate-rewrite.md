# Use Case 29: Rewrite the three READMEs into a purpose-first overview with delegated installation

## Summary
The repository's three READMEs (`README.md`, `server/README.md`, `android/README.md`) are rewritten from scratch into a coherent, accurate, purpose-first hierarchy. The root `README.md` leads with what the project *is* — a system that spawns sandboxed, free-running (`--dangerously-skip-permissions`) Claude Code sessions in disposable Linux containers to autonomously develop applications — then concisely describes the three tiers (the container + orchestration layer, the Java/Spring Boot mTLS management server, and the Android client). For each installable tier the root keeps a **minimal copy-paste quick-start**, but the **exhaustive step-by-step detail moves down into the module READMEs**, which become the authoritative, self-sufficient source for installing and operating their component. `server/README.md` and `android/README.md` are likewise rewritten to be complete on their own and internally consistent. The work is documentation-only and confined to these three files; no code, scripts, or other docs change. Crucially, the rewrite is **migrate-then-trim, not drop**: any unique operator step that exists only in the current root must land in the appropriate module README before the root is trimmed, verified by diffing against the pre-change root. All commands, paths, and version tokens must match the current repo and `PROJECT_BRIEF.md` (Linux-only; `setup.sh`/`spawn.sh`/`attach.sh`/`clean.sh`; `aisandboxctl`; tag streams `server-vX.Y.Z` / `android-vX.Y.Z`; no PowerShell/`.ps1` references).

## Acceptance Criteria
1. The root `README.md` opens with a purpose/description section stating plainly that ai-sandbox spawns sandboxed, free-running autonomous Claude Code sessions in disposable Linux containers to develop applications.
2. The root README contains a concise overview describing all three tiers — (a) container + orchestration layer, (b) management server, (c) Android client — each in a short paragraph.
3. For each installable tier, the root README provides a **minimal copy-paste quick-start** only, and links to the module README (relative link) for the full procedure; the exhaustive steps are not duplicated in the root.
4. The current root sections `## Remote management — the UC03 mTLS server` and `## Android client — the UC04 phone app` are reduced to overview + quick-start + pointer; their detailed content is migrated into `server/README.md` / `android/README.md` first so nothing is lost.
5. `server/README.md` is self-sufficient: a reader can install and operate the server from it alone (prerequisites, download/unpack, onboarding, client authorization, systemd, upgrade, client lifecycle, endpoints, foot-guns, build).
6. `android/README.md` is self-sufficient: a reader can build/sideload and enroll the app from it alone.
7. Genuinely root-level content that has no module home (e.g. `Workspace location`, RTK token compression, secret-leak protection, "How it works", Android-testing-inside-the-sandbox) is retained in the root, not deleted.
8. Every command, filename, path, and version token cited across all three READMEs is accurate against the current repo and consistent with `PROJECT_BRIEF.md` — including no stale PowerShell/`.ps1` references, correct `setup.sh --reconfigure`, and correct tag streams.
9. All cross-document links and in-document anchors resolve (root ↔ module READMEs); no broken links.
10. A migrate-no-loss check passes: diffing the rewritten READMEs against the pre-change root confirms every unique operator step was relocated, not dropped.
11. The change is confined to the three README files; no source code, scripts, build files, or non-README docs are modified.

## Potential Pitfalls & Open Questions
- **Edge case** — "Quick-start" boundary per tier is a judgment call: the developer/analyst must decide how much is the minimal copy-paste set (likely: server = download + `pki init` + authorize + start; Android = build/sideload + scan QR) vs. what moves down. Left to the dev-team, bounded by AC3.
- **Risk** — Migrate-no-loss is the main hazard of a full rewrite; AC4/AC10 mitigate via diff-against-pre-change, and QA owns this check.
- **Assumption** — Runs through `develop` (analyst/challenger/developer/qa) as documentation-only; QA verifies links + command accuracy + no-loss diff rather than running a build.
- **Assumption** — Root audience = newcomer evaluating/adopting; server README = Linux operator; Android README = app builder/sideloader. Tone/depth differ accordingly.

## Original Description
I need to make each readme meaningful. The root one should contain sections about the project purpose, a system to spawn sandboxed, free running Claude sessions to develop applications. Proceed to describe server and client. Then point to each ones readmes to see installation instructions

## Clarifications
- Q: How aggressively should the root README delegate installation to the module READMEs?
  A: Overview + quick-start, full steps below — the root keeps a minimal copy-paste quick-start per tier but moves the exhaustive detail into the module READMEs.
- Q: Which READMEs should this use case actually touch?
  A: Full rewrite of all three (`README.md`, `server/README.md`, `android/README.md`).
- Q: Should the other docs (`docs/`, `NEXT-STEPS.md`, `STREAM_PROTOCOL.md`) be in scope too?
  A: Only the three READMEs.
- Q: How should the dev-team verify a docs-only change?
  A: Accuracy + migrate-no-loss check — verify links/commands/paths/versions are accurate, plus diff against the pre-change root to confirm no unique operator step was dropped, only relocated.
