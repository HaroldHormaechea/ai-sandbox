# Use Case 31: `dind-selftest` skill (baked) — live Docker-in-Docker verification runbook

## Summary
Add a `dind-selftest` skill baked into the session image at `container-bin/skills/dind-selftest/SKILL.md` (parallel to `container-bin/skills/android-emulator-setup/`), callable inside every spawned session. It is a **linear runbook** that lets Claude self-verify Docker-in-Docker end-to-end using the existing `aisandbox-dind` CLI — encoding the live verification that UC-30 left as a manual operator sign-off (UC-30 AC#8). It walks: (a) preflight (identity + `AI_SANDBOX_DEVTOOL_DIND` gate); (b) confirm the subuid/subgid delegation landed — both `claude:100000:65536` and `sandbox:165536:65536` present in `/etc/{subuid,subgid}`, and those files are **bind-mounted** (the UC-30 mechanism) rather than the baked image file; (c) `aisandbox-dind doctor` (subuid present + `/dev/fuse` accessible + `fuse-overlayfs` present); (d) `aisandbox-dind start` (rootless daemon socket comes up, no `No subuid ranges found` error); (e) `aisandbox-dind selftest` (alpine one-service compose + `tmux -V`). On a green `start` the runbook **leaves the daemon running** (and says so, plus how to stop it). On failure it is **diagnose-only / read-only**: it interprets the cause and, crucially, recognizes the **stale / pre-UC-30 container signature** (baked `aisandbox-dind` lacking the subuid report, `/etc/subuid` missing the `sandbox` line and not bind-mounted, `start` failing identically) and advises **respawn / server re-setup** rather than concluding the fix is broken — it never mutates `/etc` or source. It is documentation/runbook only (a `SKILL.md`); no source logic changes.

## Acceptance Criteria
1. New `container-bin/skills/dind-selftest/SKILL.md` exists with valid skill frontmatter (name + description + trigger guidance), structured like `container-bin/skills/android-emulator-setup/SKILL.md`.
2. The skill lands in the image and is callable in a freshly spawned session — verify `SandboxDockerfile`'s existing `COPY container-bin/skills/ → /opt/ai-sandbox/skills/` picks up new skill directories automatically; add wiring only if skills are registered individually.
3. The runbook is linear and ordered (preflight → subuid + bind-mount → `doctor` → `start` → `selftest`) with an explicit pass/fail signal at each step.
4. The subuid step checks both `/etc/subuid` and `/etc/subgid` contain `claude:100000:65536` **and** `sandbox:165536:65536`, and verifies they are bind mounts (e.g. via `/proc/self/mountinfo`) — distinguishing the UC-30 mechanism from the baked image file.
5. The skill names the exact CLI invocations (`aisandbox-dind doctor` / `start` / `selftest` / `stop`) and the concrete success signals (socket present; no `No subuid ranges found`; `selftest` prints a tmux version).
6. The skill includes a **stale / pre-UC-30 interpretation** section: detect a baked `aisandbox-dind` lacking subuid reporting (`grep -c 'subuid range' /usr/local/bin/aisandbox-dind` == 0), `/etc/subuid` lacking the `sandbox` line / not bind-mounted, and `start` failing with the subuid error → conclude the session predates UC-30 and must be respawned (not that the fix regressed).
7. The skill states it must run inside a freshly spawned DinD-enabled session, and that it is read-only/diagnostic: on failure it advises respawn / re-running `./setup.sh` on the server, and never performs in-session `/etc` or source mutation.
8. **Post-`start` behavior:** on success the runbook leaves the rootless daemon running and explicitly reports that it is up and how to stop it (`aisandbox-dind stop`).
9. **Pass bar:** a PASS requires all four checks (subuid + bind-mount, `doctor`, `start`, `selftest`) to succeed. `selftest` may be skipped **only** when the host is provably offline (image pull impossible), in which case the result is reported as a qualified pass naming the skipped leg — an offline skip is never silently treated as full success.
10. No regression to existing baked skills; no contradictory README/docs claims about available skills.

## Potential Pitfalls & Open Questions
- **Assumption** — `SandboxDockerfile`'s `COPY container-bin/skills/ /opt/ai-sandbox/skills/` auto-includes new skill directories; verify rather than assume (vs. per-skill registration).
- **Edge case** — `selftest` needs network to pull `alpine`; on an offline host it can fail for reasons unrelated to subuid. The skill must distinguish a network/offline failure from a real DinD failure (AC#9).
- **Risk** — the skill is only truly exercisable on a fresh DinD-enabled session; in a stale container it should early-exit with the respawn guidance rather than emit confusing reds.
- **Assumption** — the skill frontmatter/format matches how baked skills are surfaced to a spawned session's Claude (mirror `android-emulator-setup` exactly).

## Original Description
> Create a `dind-selftest` skill, baked into the session image under `container-bin/skills/dind-selftest/` (parallel to the existing `container-bin/skills/android-emulator-setup/`), so it is callable inside every spawned session. The skill encodes the live end-to-end DinD verification that UC-30 left as a manual "operator sign-off" (AC#8) — i.e. a linear runbook Claude can follow to self-test Docker-in-Docker using the existing `aisandbox-dind` CLI.
>
> It should: (1) verify the system-level change landed — `/etc/subuid` and `/etc/subgid` contain both `claude:100000:65536` and `sandbox:165536:65536`, and that `/etc/subuid`/`/etc/subgid` are bind-mounted (the UC-30 mechanism) rather than the image's baked file; (2) run `aisandbox-dind doctor` and check it reports subuid present + `/dev/fuse` accessible + `fuse-overlayfs` present; (3) run `aisandbox-dind start` and confirm the rootless daemon socket comes up with no "No subuid ranges found" error; (4) run `aisandbox-dind selftest` (alpine one-service compose + `tmux -V`) and confirm it passes; (5) interpret failures and, crucially, detect and explain the "this is a stale pre-UC-30 container, not a fresh spawn" situation we just hit (baked `aisandbox-dind` lacking the subuid-range report, `/etc/subuid` missing the sandbox line and not bind-mounted, `start` failing identically) and tell the operator to respawn rather than mis-diagnosing.
>
> This is the DinD counterpart to the Android self-test skills (`android-testing`, `android-emulator-setup`).

## Clarifications
- Q: After a green `aisandbox-dind start`, should the skill leave the rootless daemon running or stop it?
  A: Leave it running (and report that it's up + how to stop it).
- Q: On a failed/stale result, diagnose-only or also attempt safe in-session remediation?
  A: Diagnose-only — read-only; identify the cause (esp. stale pre-UC-30 container) and advise respawn / server re-setup; no in-session mutation.
- Q: What counts as a PASS for the self-test?
  A: All four checks (subuid + bind-mount, doctor, start, selftest) must pass; `selftest` may be skipped only if the host is provably offline, reported as a qualified pass.
