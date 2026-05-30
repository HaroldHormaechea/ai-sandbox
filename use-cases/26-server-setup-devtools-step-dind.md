# Use Case 26: Server setup — "Select the development tools you want to install" step with rootless Docker-in-Docker as the first opt-in capability

## Summary
The server setup wizard (`setup.sh` / `setup.ps1`, plus the `.deb` post-install path from UC-19) gains a new top-level step labelled **"Select the development tools you want to install"**. The step renders as a checklist of opt-in capabilities provisioned into the per-session `ai-sandbox-N-claude-sandbox-1` containers spawned by `spawn.sh`. For v1 the checklist has exactly one entry: **Enable Docker-in-Docker (rootless, inside the session container)** — the DinD daemon runs as the non-root session user, exposes no host docker socket, and adds no host-level system dependency (no sysbox, no privileged flag). Toggling DinD on surfaces an inline trust-boundary warning at the moment of selection, because rootless DinD still expands what code inside a session can reach beyond the project's stated "container is the trust boundary" stance. The step is reachable both during first-time setup and via a `setup.sh --reconfigure` re-run (parity on `setup.ps1`); the re-run pre-fills with the current state. Configuration changes propagate to NEW sessions only — sessions running at the moment of the toggle remain unchanged (matches the existing spawn-time-configures-the-container model). The step's data model is shaped to accept future capabilities that are image-time installs (Rust toolchain, Python interpreter + venv tooling, Go, Node alt-version, etc.) under the same checklist, so adding a second capability does not require restructuring the wizard. UC-26 is a prerequisite for UC-24: UC-24's runtime diagnosis path requires Docker access from inside the session container in order to inspect the live `claude-swarm-<pid>` tmux state, and that access is exactly what enabling rootless DinD here provides.

## Acceptance Criteria
1. The setup wizard presents a new top-level step titled **"Select the development tools you want to install"** after the image-build / gh-login / claude-login stages and before the wizard's exit / summary stage. `setup.sh` and `setup.ps1` show the step at parity.
2. The step renders as a checklist of opt-in capabilities. For v1 the checklist has exactly one entry: **"Enable Docker-in-Docker (rootless; lets sessions run docker / docker compose inside their sandbox container)"**. All entries default to OFF.
3. Selecting the DinD entry triggers an inline warning before commit: a short explicit note that enabling this expands the project's trust boundary because sessions can launch docker commands, followed by a `Continue? [y/N]` confirmation. Declining the confirmation returns to the checklist with the entry un-selected.
4. The wizard supports a re-run mode (`setup.sh --reconfigure`, `setup.ps1 -Reconfigure`, or equivalent) that jumps straight to this step with the current state pre-selected. The operator can flip capabilities on / off without re-doing earlier wizard stages.
5. When DinD is enabled, freshly `spawn.sh`-ed sessions can run `docker info` and `docker compose ls` successfully from inside the sandbox container without further configuration. The daemon inside the session runs as the non-root session user; no `/var/run/docker.sock` from the host is bind-mounted.
6. When DinD is disabled (or skipped), `spawn.sh` produces sessions identical to today's behaviour — no docker binary inside, no socket mount, no rootless daemon, nothing added to the image.
7. The DinD setting is persisted on the server such that subsequent `spawn.sh` invocations honor it without re-prompting. Changes propagate to NEW sessions only — sessions running at the moment of the toggle remain unchanged; the operator can manually recycle one via the existing recreate flow if they want it retrofitted.
8. The wizard's user-facing affordance is shaped as a generic checklist of capabilities, not a single DinD-yes-or-no question, so adding a future capability (e.g. "Install Rust toolchain", "Install Python + uv") in a subsequent UC does not require restructuring the wizard step.
9. After running setup with DinD enabled, the runtime path UC-24 needs to diagnose the "all tmux windows shown" regression is operational: from a session container, `docker compose -p ai-sandbox-N exec -T claude-sandbox tmux -S /tmp/tmux-…/claude-swarm-… list-panes -a -F …` returns successfully when a Claude Code agent team is running.
10. README + the relevant onboarding docs explain the new step and the trust-boundary tradeoff. `PROJECT_BRIEF.md`'s non-goals + trust-boundary statements are reviewed and updated if the rootless-DinD addition requires nuance.

## Potential Pitfalls & Open Questions
- **Risk (extensibility framing — deferred to implementation)** — V1's only capability (DinD) is a *spawn-time runtime configuration* of how `spawn.sh` constructs the container. The future capabilities the user named (Rust toolchain, Python interpreter) are likely *image-time installs* baked into the Dockerfile build. The step's underlying data model needs to handle both *image-time* and *spawn-time* knobs without forking into two parallel mechanisms. Resolve at implementation time — likely as a per-capability `apply_at: image-build | session-spawn` flag in the persisted config.
- **Assumption (deferred to implementation)** — UC-19's `.deb` postinst auto-runs the wizard on a TTY. The checklist UI must therefore work in a TTY context (`dialog`, `whiptail`, or a plain shell numbered-menu fallback). Pick the rendering toolkit during implementation; the choice must not break the cross-platform parity requirement (AC#1).
- **Risk (rootless-DinD performance / quirks)** — Rootless Docker has overlayfs-nesting limitations and is slower than rootful for image pulls + large builds. Verify the DinD-inside-session flow works for the actual UC-24 diagnostic operations (which are read-mostly: `docker compose exec` + `tmux list-panes`), not just for `docker info`. If a real Claude-Code-swarm interaction stresses rootless's limits, surface that during implementation rather than after merge.

## Original Description
> Ok. For that then we need a new use case before that: The server SET-UP must allow to choose if to enable docker-in-docker as a setup option. This will be in a new "development environment setup" setup step, which for now will include only this, but in the future will include more stuff (e.g. add Rust build environment, add Python environment, etc).

## Clarifications
- Q: Which Docker-in-Docker implementation strategy should v1 use?
  A: Rootless DinD inside the session container — best fit for the project's "container is the trust boundary" stance; no host docker socket, no sysbox dependency.
- Q: How should the DinD trust-boundary tradeoff be surfaced to the operator?
  A: Inline warning in the wizard step itself at the moment of selection, plus README + PROJECT_BRIEF docs (AC#3 + AC#10).
- Q: When should the new step be reachable?
  A: Both first-time setup and re-runs of `setup.sh` / `setup.ps1` via a `--reconfigure` flag (AC#4).
- Q: What should happen to sessions that were already running when the operator toggles DinD on?
  A: Only new sessions get DinD; existing untouched (AC#7).
- Q: Wizard step label — keep the user's original phrase or shorten?
  A: Use **"Select the development tools you want to install"** verbatim as the step title (the user's chosen phrasing, refining their initial "Development environment setup" label).

## Implementation Progress (2026-05-31, in-flight)

Work branch: `feat/uc-26-server-setup-devtools-step-dind` (pushed; no PR opened yet — Completion phase did not run).

### Authoritative design rulings made during the run

- **AC#9 — permissive read** (team-lead ruling, not user-confirmed). AC#9's literal text suggests the in-session daemon visiting the host's `ai-sandbox-N` project; that is architecturally incompatible with the use case's own Clarifications (no host-socket bind-mount, no sysbox). The run was unblocked by adopting the permissive read: "the in-session rootless daemon is *capable* of `docker compose … exec -T … tmux …` shaped operations against ITS OWN child containers." Verification surface pinned as: (a) `aisandbox-dind doctor` reports a running rootless daemon; (b) `docker compose ls` runs without error; (c) `aisandbox-dind selftest` (alpine compose-up → `tmux -V` exec → compose-down) succeeds end-to-end — **load-bearing AC#9 fixture**; (d) selftest is idempotent + teardown-clean. **Open question for the user**: confirm this ruling, or pivot UC-26 to a host-daemon-tunnel design (which would scrap the rootless-only architecture and add a new mTLS-tunneled daemon-proxy surface to the management server).
- **Wizard step placement**: inserted as Step 6 of 7, **before** the first-session spawn (which became Step 7) so the wizard's own first spawn inherits any DinD opt-in. Step ordering: 1 SSH → 2 git identity → 3 image (incl. UC22 toolchain) → 4 gh login → 5 Claude /login → 6 devtools → 7 first session.
- **`OnboardCommand` default**: devtools step **runs** when `--no-devtools` is absent (matches `.deb` auto-onboard expectation). The 6 existing `OnboardCommandTest` cases were updated by QA to pass `--no-devtools` as back-compat housekeeping; new behaviour is covered by `DevToolsStepTest` + `ReconfigureCommandTest`.

### Phase 1 — Analysis & Challenge: **DONE**

Analyst + challenger peer loop converged in 2 rounds; final proposal approved. Notable design decisions:
- `.ai-sandbox-devtools` ledger format `<id>     <apply_at>` with `apply_at: image-build | session-spawn` as the **AC#8 extensibility seam**.
- `inject_devtool_spawn_env` in `lib.sh` / `lib.ps1` appends `docker-compose.dind.yml` to the existing `AI_SANDBOX_EXTRA_COMPOSE_FILES` chain (additive with `docker-compose.kvm.yml` from UC22).
- Image-baked `container-bin/aisandbox-dind` helper is dormant unless `AI_SANDBOX_DEVTOOL_DIND=1`; on first DinD-enabled start it lazy-installs the rootless-Docker static tarball from `download.docker.com` into `/workspace/environment-utilities/dind/` (follows the `aisandbox-emulator` precedent).
- Java install-time CLI: `ReconfigureCommand` (new), `DevToolsStep` + `DevToolsConfig` under `cli/secrets/` (new), `OnboardCommand` + `AisandboxctlCommand` modified. UC06 §AC25 install-time-CLI exemption covers all new `cli/**` code — no `profile-java-server-architecture` conflict.

### Phase 2 — Implementation: **DONE** (developer's task closed)

Commits on the work branch:
- `4970edd` — `feat(server,setup): UC-26 — devtools selection step + rootless DinD plumbing` (17 files, +1606 / -20). All shell-side primitives + Docker plumbing + Java install-time CLI.
- `09814ba` — `docs(server,setup): UC-26 — README + brief updates for devtools step` (2 files, +31 / -1). README "Optional development tools" subsection + PROJECT_BRIEF.md trust-boundary / components / data-flow / `build.commands.reconfigure` additions.

PROJECT_BRIEF.md was updated in-flight: frontmatter `build.commands.reconfigure: "./setup.sh --reconfigure"` added; new bullets under `components`, `data_flow_narrative`, and `trust_boundaries`. `non_goals` reviewed; no change required (AC#10).

`./gradlew :server:compileJava` and `./gradlew :server:spotlessCheck` clean at the time of handoff.

### Phase 3 — Testing: **IN-FLIGHT** (QA Round 1 complete; fix-back Round 2 underway)

**QA committed at `432079d`** — `test(server): UC-26 — test suite for devtools step + DinD plumbing` (9 files, +1776 / -8):

- NEW `server/src/test/java/com/aisandbox/server/cli/secrets/DevToolsConfigTest.java` — 12 cases
- NEW `server/src/test/java/com/aisandbox/server/cli/secrets/DevToolsStepTest.java` — 13 cases
- NEW `server/src/test/java/com/aisandbox/server/cli/ReconfigureCommandTest.java` — 10 cases
- MODIFIED `server/src/test/java/com/aisandbox/server/cli/OnboardCommandTest.java` (6 cases get `--no-devtools` for back-compat; +3 new UC-26 routing cases: SKIPPED, DEFERRED, full TTY end-to-end ledger persistence)
- MODIFIED `server/src/test/java/com/aisandbox/server/sessions/HostScriptComposeEnvTest.java` (+3 cells: DinD alone; DinD layered on KVM; empty ledger as AC#6 control)
- MODIFIED `server/src/test/java/com/aisandbox/server/release/ReleaseBundleTest.java` (asserts `host/docker-compose.dind.yml` 0644 + `host/container-bin/aisandbox-dind` 0755 in the release zip)
- MODIFIED `server/src/test/java/com/aisandbox/server/release/DebPackageTest.java` (same for the .deb at `/opt/ai-sandbox-server/host/`)
- MODIFIED `server/src/test/java/com/aisandbox/server/release/DebPostinstContractTest.java` (asserts `sudo aisandboxctl reconfigure` reminder appears ≥3× and names Docker-in-Docker)
- NEW `docs/DinDSelftestVerificationDoc.md` — **operator runbook for AC#9 (a/b/c/d)**, the manual-verification artifact CI can't run.

`./gradlew :server:compileTestJava` clean at checkpoint #1. Coverage table maps every AC#1–10 to at least one test (or the operator-runbook step for AC#9).

**QA local-host run results** (all NEW tests that can run locally pass):
- DevToolsConfigTest: 12/12 ✅
- DevToolsStepTest: 13/13 ✅
- ReconfigureCommandTest: 10/10 ✅
- HostScriptComposeEnvTest's 3 new cells: 3/3 ✅
- DebPostinstContractTest's new case: 1/1 ✅
- OnboardCommandTest: 12/14 fails — **pre-existing host artifact** (memory: `ai-sandbox-host-dev-constraints`); the 3 new UC-26 cases inherit the same `Ownership.resolve` / chown EPERM behaviour locally. CI passes the same harness shape today.

### Phase 3 fix-back Round 1 (in flight)

**Finding #1 — Critical: release zip + .deb don't ship `docker-compose.dind.yml`.** `server/build.gradle.kts` is missing the `from()` line in `releaseBundle` (~line 392) and the `hostData` entry in `prepDebStaging` (~line 524). Without these, every bundled-install path (zip + `.deb`) silently fails AC#5 + AC#7: `spawn.sh`'s `inject_devtool_spawn_env` (lib.sh:416) emits a `DinD enabled but $dind_override missing` warning and the spawned container has no `/dev/fuse`, `apparmor:unconfined`, or `seccomp:unconfined` → `aisandbox-dind start` cannot bring up the rootless daemon. Failing tests: `ReleaseBundleTest.uc26_dind_override_and_helper_ship_with_correct_modes` + `DebPackageTest.deb_ships_uc26_dind_override_and_helper_with_correct_modes`. Container-bin/aisandbox-dind IS shipped via the recursive `container-bin/` clause; only the compose override is missing. **Status**: relayed to developer for fix-back Round 1.

**Finding #2 — Minor: `ReconfigureCommand` lacks root-check parity with `OnboardCommand`.** No root probe before writing the ledger and no `setRootCheck` test seam. A non-root operator running `aisandboxctl reconfigure` will fail at `Files.write` with EACCES (ledger lives under `/var/lib/ai-sandbox-server/sessions/`) — surfaces as an NIO exception rather than the friendly "must run as root (use sudo)" message. **Status**: relayed to developer as discretionary (defer-to-follow-up acceptable; if added, QA writes the matching test in the same round).

### What still has to happen for UC-26 to close

1. Developer fix-back Round 1 lands the `build.gradle.kts` changes (and optionally the `ReconfigureCommand` root-check).
2. QA re-runs `./gradlew :server:test --tests "*ReleaseBundleTest*" --tests "*DebPackageTest*"` against a fresh `./gradlew :server:releaseBundle :server:debPackage` → both turn green.
3. Operator runs `docs/DinDSelftestVerificationDoc.md` end-to-end on a real host to confirm AC#9(a–d) on rootless DinD. Cannot be automated by CI.
4. PowerShell mirror parity (AC#1) exercised on a Windows host. `pwsh` is not on this Linux host; mirrors were verified by inspection only.
5. Orchestrator's Completion phase: final commit (if any uncommitted work), final push, `gh pr create` against `main`, and ledger status flip to `done`.

The 6-round fix-back cap applies to Round 1+. Currently at Round 1 of 6.

### Open scope question

`docs/DinDSelftestVerificationDoc.md` was authored by QA. It lives under `docs/` which is inside the brief's `paths.production` glob — but it is operator documentation (a runbook), not production source. Default chosen at run-time: QA owns it. Resume run may revisit if the human prefers developer ownership.

### Ledger state

`USE_CASES.md` row 26 is `in-progress` (set 2026-05-30). The orchestrator did NOT flip it to `done` because Phase 3 fix-back is not closed.

### Commits on the work branch (chronological)

| SHA | Subject |
|---|---|
| `4970edd` | feat(server,setup): UC-26 — devtools selection step + rootless DinD plumbing |
| `09814ba` | docs(server,setup): UC-26 — README + brief updates for devtools step |
| `432079d` | test(server): UC-26 — test suite for devtools step + DinD plumbing |
| `30e0dc4` | docs(use-cases): UC-26 — record in-flight implementation progress for resume |
| _(pending)_ | fix(server): UC-26 — bundle `docker-compose.dind.yml` in release zip + .deb |

### How to resume

From the root Claude Code session:

1. `git -C /home/potato-server/ai-sandbox fetch origin && git -C /home/potato-server/ai-sandbox checkout feat/uc-26-server-setup-devtools-step-dind`
2. Invoke `/develop` with `target_dir=/home/potato-server/ai-sandbox use_case=use-cases/26-server-setup-devtools-step-dind.md` and tell the orchestrator the run is a Phase 3 fix-back continuation (skip Phase 1 + 2; QA's last summary is at fix-back Round 1; developer is mid-fix on `build.gradle.kts`).
3. If the developer's fix-back commit is already on the branch, direct QA to re-run the failing tests against a fresh `releaseBundle` + `debPackage`. If not, re-spawn developer with finding #1 + (optionally) #2 from the QA Test Summary above.

**Memory cross-link**: `dev-team-dont-rebase-published-uc-branch` — when continuing an in-progress UC, do NOT rebase the work branch onto main; just add commits on top.
