---
plan_for: use-cases/94-spawn-root-owned-bind-sources.md
work_branch: feat/uc-94-spawn-root-owned-bind-sources
team: ai-sandbox-uc-94
approved: 2026-06-30
---

# UC-94 — Approved Implementation Plan: Fix session-spawn failure from root-owned per-session bind sources

> Analyst↔challenger approved (challenger verdict: **Approve**, round 2). Honors the three FIXED user decisions: (1) dind subuid/subgid live at server-owned `/etc/ai-sandbox-server/secrets/dind/` and the server wires `AI_SANDBOX_DIND_SUBUID/SUBGID_HOST_PATH` there; (2) repair of broken root-owned state lives in deb `postinst` + `aisandboxctl onboard` (root), never spawn.sh (non-root); (3) service uid/gid is derived, never hardcoded to 997.

## Analysis

On the install-mode management server, session spawn fails because per-session bind-mount sources under the host state root (`/var/lib/ai-sandbox-server/sessions/`) are owned `root:root` and unusable by the container, which runs as `997:0` via `AI_SANDBOX_RUN_AS_USER`. Two failure modes: (1) **DinD** — `docker-compose.dind.yml` binds `secrets/dind/subuid|subgid` as *files* over `/etc/subuid|/etc/subgid`, but they get auto-created by the Docker daemon as root *directories* → fatal `not a directory` mount error; (2) **base** — `claude-config`/`workspace` auto-created `root:root` → entrypoint dies `Permission denied` on `/home/claude/.claude` → container Exited(1). Root-owned debris under `/var/lib` is not dpkg-tracked, so it survives `apt purge`+reinstall.

Verified against the code:
- `ScriptExecutorService.composeEnv()` (`server/src/main/java/com/aisandbox/server/sessions/service/ScriptExecutorService.java:122`) exports `AI_SANDBOX_COMPOSE_FILE`/`HOST_STATE_ROOT`/`SECRETS_HOST_PATH`/`CLAUDE_TEMPLATE_HOST_PATH`/`RUN_AS_USER`, but NOT the dind paths. So `docker-compose.dind.yml:92-93`'s `${AI_SANDBOX_DIND_SUBUID_HOST_PATH:-./secrets/dind/subuid}` falls back to `./secrets/dind/` under the state-root cwd (spawn.sh cds there at line 22) → the auto-create trap.
- The spawn-time guard `_aisb_dind_ensure_subid_files` (`devtools.d/dind/manifest.sh:52`) calls `aisb_subuid_ensure_line` (`devtools.d/lib/server-install.sh:32`), whose `[ -f "$file" ] || : > "$file"` (line 37) fails on a directory and on a root-owned parent; both swallowed by `devtool_spawn_env`'s `|| true` (line 98).
- `spawn.sh:219` mkdir is prevention-only (no-op on existing root-owned dir). spawn.sh runs as the non-root service user → repair must live in postinst + onboard.
- `/etc/ai-sandbox-server/secrets/` is created+chowned to the service user by `pki init` (`PkiInitCommand.java:193,213`), re-asserted by `onboard` (`OnboardCommand.java:345-351`) and `secrets seed` (`SecretsSeedCommand.java:273-276`).
- Identity derivable everywhere (composeEnv uses secrets-dir `unix:uid`; CLIs use `Ownership.resolve(systemUserName)`; postinst chowns by name). No literal 997.
- `devtools.d/` ships as a directory copy to `host/devtools.d` (`build.gradle.kts:440`) / `opt/ai-sandbox-server/host/devtools.d` (deb, line 597). New `devtools.d/dind/*.sh` ship automatically; the deb copy is non-executable, so install-mode callers MUST invoke via `bash <path>`.

## Proposed Solution

Five coordinated parts. Production paths do repair (root) + wiring; spawn.sh's existing pre-create plus the self-healed guard do prevention (non-root). **Anti-drift is MANDATORY (Part B).**

**Part A — Wire dind subuid/subgid paths to the server-owned secrets dir (AC#2).** In `ScriptExecutorService.composeEnv()` (after `secretsDir` resolution ~line 142), add two UNCONDITIONAL env entries from `props.secrets().dir()`: `AI_SANDBOX_DIND_SUBUID_HOST_PATH` → `<secretsDir>/dind/subuid`, `AI_SANDBOX_DIND_SUBGID_HOST_PATH` → `<secretsDir>/dind/subgid`. Inert when dind off. Update the Javadoc env list. Blast radius verified safe: only `ScriptExecutorServiceTest` reads the map (containsEntry/containsKey, no exact-map assert); `HostScriptComposeEnvTest` asserts argv only; `SessionUidAlignmentContractTest` is a static file parser.

**Part B — REQUIRED single-source-of-truth shell helpers (anti-drift, Issue 1).** Two NEW bundled shell scripts, each the sole implementation, invoked from all call sites:
1. **`devtools.d/dind/ensure-host-subid.sh` (NEW — sole writer of subid content).** Sources `../lib/server-install.sh` + `manifest.sh` (relative to its own `BASH_SOURCE`); args `--secrets-dir <dir> --owner <user:group>`; exports `AI_SANDBOX_DIND_SUBUID_HOST_PATH=<dir>/subuid` + `_SUBGID=<dir>/subgid`; `install -d <dir>` (0700); runs the existing `_aisb_dind_ensure_subid_files` (writes BOTH files with BOTH lines `claude:100000:65536` + `sandbox:165536:65536`); chowns `<dir>` + both files to `--owner`. Install-mode path: `/opt/ai-sandbox-server/host/devtools.d/dind/ensure-host-subid.sh` (ships via the existing directory copy — no build.gradle change).
2. **`devtools.d/dind/repair-state-root.sh` (NEW — sole owner of the repair name-set).** Args `--state-root <dir> --owner <user:group>`; enumerates the EXACT per-session bind-source name-set (no `sessions/*` glob); `chown -R` matches to `--owner`; removes the legacy `<state-root>/secrets/dind` debris if present; idempotent + never fatal.

postinst (dash) invokes both via explicit `bash /opt/ai-sandbox-server/host/devtools.d/dind/<script>.sh …`. `onboard` and `secrets seed` invoke the SAME scripts via the existing `ProcessRunner`. One implementation each → postinst and Java cannot diverge. **Guard test:** `DebPostinstContractTest`-style assertion that BOTH postinst and the onboard step invoke the bundled scripts by path.

**CI note (from challenger, non-blocking):** add the two new helpers to a `test -f` PRESENCE check in `server-ci.yml` (alongside the existing `test -f .../devtools.d/dind/manifest.sh` lines), NOT the `test -x` executable-bit loop at ~337-346 — they ship non-executable and are invoked via `bash`, so a `test -x` check would fail CI.

**Part B-seed — SecretsSeed parity (Issue 3, DECISION: ADD parity).** `SecretsSeedCommand.Seed` ALSO invokes `ensure-host-subid.sh` after its `secretsDir` ensure (~line 276, where `Ownership` is already resolved), so a `pki init` + `secrets seed` (no-onboard) install satisfies AC#2 at install time. The spawn guard is defense-in-depth, not the AC#2 guarantee.

**Part C — Self-heal in `aisb_subuid_ensure_line` (AC#3).** In `devtools.d/lib/server-install.sh:37`, when `$file` exists but is NOT a regular file, remove and recreate before append:
```
if [ ! -f "$file" ]; then
    [ -e "$file" ] && { rm -rf -- "$file" || return 1; }
    : > "$file" || return 1
fi
```
Heals a wrongly-typed path in a writable (service-owned) parent; still returns non-zero when the parent is unwritable (root-owned) — root paths' job. **Required unit test (`server/src/test/e2e/uc30-server-install-unit.sh` §A) — all FOUR cases:** (i) pre-existing directory → rm+recreate, rc 0, regular file with exactly the line; (ii) non-regular non-dir (symlink and/or fifo) → rm+recreate, rc 0; (iii) idempotent no-op when the regular file already contains the line → byte-identical, rc 0, no dup; (iv) unwritable/root-owned parent → non-zero return. (Harness runs manually / operator-gate, not in server-ci.yml — QA runs it explicitly.)

**Part D — Repair stale state-root debris in BOTH postinst + onboard (AC#5), via `repair-state-root.sh`.** Scoped to the EXACT name-set under `/var/lib/ai-sandbox-server/sessions/` — never a `sessions/*` glob: `workspace`, `workspace-*`, `claude-config`, `claude-config-*`, `claude-projects-*`; remove the legacy `sessions/secrets/dind` fallback if present. Must NOT touch the UC-62 siblings `sessions/server-ssh.sock`, `server-ssh-home`, nor `docker-config`/`update-trigger`/`enrollment` (all already service-owned) — asserted untouched in the script's test. Idempotent, postinst stays `exit 0`. Re-applies on every reinstall → survives `apt purge`+reinstall.

**Part E — Prevention already in place once A lands (AC#4, AC#7).** No spawn.sh change. Non-DinD spawns never layer the override / touch new vars; fresh-state spawns hit mkdir no-ops + a correct guard → behaviorally identical (AC#7).

**OnboardCommand integration (rec a):** NOT inlined — a new step in `com.aisandbox.server.cli.secrets` (e.g. `DindStateStep`, mirroring `SshKeyStep`/`GitIdentityStep`) shelling out to the two bundled scripts via the injected `ProcessRunner` (testable seam). `OnboardCommand` runs it after the secrets-dir ensure/chown (~line 351); `SecretsSeedCommand.Seed` runs the subid half after its `secretsDir` ensure (~line 276).

## QA — MANDATORY before/after gate, DIND-ENABLED (AC#1, AC#6)

Both AC#1 clauses need before/after evidence, so the repro MUST be dind-enabled. QA authors the exact commands; the orchestrator relays the `sudo` steps to the user (no passwordless sudo here) and captures output as acceptance evidence.

**BEFORE (unfixed/installed build, broken precondition):**
1. Enable dind in the install-mode ledger (`/var/lib/ai-sandbox-server/sessions/.ai-sandbox-devtools` ← `dind` selection).
2. Manufacture the broken state: root-owned `claude-config` + `workspace` under the state root, AND a root-owned *directory* at the dind subuid bind source the build-under-test resolves (unfixed build → `<state-root>/secrets/dind/subuid`).
3. Run the spawn as the server does: `sudo -u ai-sandbox-server env AI_SANDBOX_COMPOSE_FILE=/opt/ai-sandbox-server/host/docker-compose.yml AI_SANDBOX_HOST_STATE_ROOT=/var/lib/ai-sandbox-server/sessions AI_SANDBOX_SECRETS_HOST_PATH=/etc/ai-sandbox-server/secrets AI_SANDBOX_CLAUDE_TEMPLATE_HOST_PATH=/etc/ai-sandbox-server/templates/claude-config AI_SANDBOX_RUN_AS_USER=997:0 bash /opt/ai-sandbox-server/host/spawn.sh --non-interactive --shared-workspace --shared-claude-config` (capture spawn.sh stderr directly — the server only logs exitCode).
4. Capture BOTH symptoms: the dind `error mounting "…/secrets/dind/subuid" … not a directory` AND the base `cp: cannot create '/home/claude/.claude/…': Permission denied` → container Exited(1).

**AFTER (fixed build — POC-jar swap into `/opt/ai-sandbox-server/lib/aisandboxctl.jar` + updated host scripts/bundled helpers in place):**
5. From the SAME broken starting state, run `sudo aisandboxctl onboard --force` — exercises Part D repair + Part B provisioning (server-owned `/etc/ai-sandbox-server/secrets/dind/{subuid,subgid}` with both lines).
6. Re-run the dind-enabled spawn from step 3. Assert: container reaches healthy/ready; `/etc/subuid` AND `/etc/subgid` mount as FILES (e.g. `docker compose -p ai-sandbox-<N> exec -T claude-sandbox sh -c 'test -f /etc/subuid && test -f /etc/subgid'`); uid 997 can write `/home/claude/.claude`.
7. No-regression evidence (AC#7): also capture a non-DinD spawn and a clean fresh-state spawn succeeding unchanged.

## Files Affected

**Production code (developer):**
- `server/src/main/java/com/aisandbox/server/sessions/service/ScriptExecutorService.java` — Part A.
- `devtools.d/dind/ensure-host-subid.sh` — NEW (Part B): sole writer of the subid dir+files.
- `devtools.d/dind/repair-state-root.sh` — NEW (Part B/D): sole owner of the repair name-set + legacy-debris removal.
- `devtools.d/lib/server-install.sh` — Part C self-heal.
- `server/src/main/java/com/aisandbox/server/cli/secrets/DindStateStep.java` — NEW (rec a): ProcessRunner shell-out seam.
- `server/src/main/java/com/aisandbox/server/cli/OnboardCommand.java` — invoke the step (Parts B + D).
- `server/src/main/java/com/aisandbox/server/cli/SecretsSeedCommand.java` — invoke the subid step (Part B-seed).
- `server/debian/postinst` — `bash`-invoke both bundled scripts (Parts B + D); idempotent, still `exit 0`.
- `.github/workflows/server-ci.yml` — add the two helpers to a `test -f` presence check (NOT the `test -x` loop).

**Test code (qa):**
- `server/src/test/e2e/uc30-server-install-unit.sh` — Part C self-heal (4 cases) + `repair-state-root.sh` name-set test asserting siblings (`server-ssh.sock`/`server-ssh-home`/etc.) untouched.
- `server/src/test/java/com/aisandbox/server/sessions/ScriptExecutorServiceTest.java` — assert `composeEnv()` carries both dind env vars = `<secretsDir>/dind/subuid|subgid`; 2-arg/dev-mode ctor still omits them.
- `server/src/test/java/com/aisandbox/server/release/DebPostinstContractTest.java` — postinst `bash`-invokes both bundled scripts by path (anti-drift guard); still `exit 0`.
- `server/src/test/java/com/aisandbox/server/cli/OnboardCommandTest.java` (+ a `SecretsSeedCommand` test) — the new step(s) shell out to the bundled scripts with the right `--secrets-dir`/`--state-root`/`--owner` args via the existing seams.

## Risks & Considerations
- Anti-drift enforced: one bundled script owns the subid lines, one owns the repair name-set; postinst + Java both invoke them; a contract test pins the invocations.
- deb ships the new scripts non-executable → invoke via `bash <path>`; CI presence check must be `test -f`, not `test -x`.
- Narrow repair scope: exact name-set only; UC-62 `server-ssh.sock`/`server-ssh-home` and other state dirs provably untouched (asserted).
- Self-heal `rm -rf` blast radius bounded to the exact `…/subuid`/`…/subgid` path, guarded to a non-regular existing path, returns non-zero on failure.
- Derived identity throughout — never literal 997.
- postinst must never fail (`set -e`, ends `exit 0`; guarded new commands).
- AC#7 behaviorally identical: new env keys inert without the dind override; guard/`mkdir -p` no-ops on correct state; developer runs full `./gradlew :server:test`.
- Profile (java-server-architecture): touched Java is install-time CLI + one private method; no new layering surface.
- Release out of dev-team scope: end at a merged-ready PR with before/after evidence; `server-v*` release cut afterward via the `release` skill.
