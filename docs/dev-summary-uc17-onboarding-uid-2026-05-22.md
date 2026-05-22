# Development Summary — UC-17: out-of-box onboarding + uid-aligned session permissions

**Date:** 2026-05-22
**Use case:** [use-cases/17-server-onboarding-uid-alignment.md](../use-cases/17-server-onboarding-uid-alignment.md)
**Branch:** `feat/uc-17-server-onboarding-uid-alignment` → merged to `main` as `3488dc2` (PR [#24](https://github.com/HaroldHormaechea/ai-sandbox/pull/24), squash)
**Release:** `server-v0.0.19` (`ai-sandbox-server-0.0.19.zip`, `ai-sandbox-server_0.0.19_amd64.deb`)
**Method:** dev-team workflow (analyst → challenger → developer → QA), autonomous run.

## Why
After the session create/delete fix (UC bugfix earlier the same day), spawned session containers began crashing on a real host: they run as the image `claude` uid (1000) while the server-managed tree under `/etc/ai-sandbox-server/**` and `/var/lib/ai-sandbox-server/sessions` is owned by the host-assigned `ai-sandbox-server` uid (or root, when Docker auto-creates a missing bind-mount source). So a container could neither write its mounted `~/.claude` (entrypoint died → `Exited 1`) nor read the 0600 `git-key` (clone → `Permission denied (publickey)`). The base/operator container only worked because the operator's own uid happened to be 1000. The fix also wires up out-of-box onboarding so a fresh install is usable without manual secret-copying.

## What shipped

### 1. uid alignment — run the session container as the server-owned uid
The operator's chosen approach (run-as-server-uid via compose `user:`, not pin-uid+chown), implemented via the OpenShift arbitrary-uid recipe:
- `docker-compose.yml`: `user: "${AI_SANDBOX_RUN_AS_USER:-claude}"` (dev default byte-identical).
- `ScriptExecutorService.composeEnv` injects `AI_SANDBOX_RUN_AS_USER=<secrets-dir-owner-uid>:0` in install mode; dev mode omits; an unresolvable uid logs a loud warning and omits (never a silent 1000 default).
- `SandboxDockerfile`: `ENV HOME`, `chgrp 0`+`g=u` on `$HOME` (writes), `/etc/passwd` group-writable.
- `entrypoint.sh`: idempotently self-registers a `sandbox` passwd row for an unknown uid **before any ssh/git** — the half that fixes `getpwuid`. (The challenger caught that without this, OpenSSH hard-exits 255 and the clone fails *while the container still boots Up* — a false-green that the original proposal and the CI probe would have missed.)
- `spawn.sh`: pre-creates shared bind dirs too, so Docker never auto-creates them root-owned.

### 2. out-of-box onboarding
- New re-runnable `aisandboxctl onboard` wizard: per-component presence check, gathers only what's missing unless `--force`, reuses `pki init`/`secrets seed` machinery, builds the image lazily (`--no-image-build` fail-fast), defers cleanly under no-TTY, auto-skips the interactive Claude OAuth headless.
- debconf integration (`server/debian/{templates,config}` + `postinst`): runs the wizard non-interactively on opt-in, shreds the gh-token temp file and clears it from the debconf db, always defers safely and `exit 0`. `control` depends on `debconf`; jdeb verified to pack `config`(+x)+`templates`.

### Scope
Fresh installs + newly-spawned sessions only — **no migration** (experimental, by design).

## Tests
- **Local JVM (green): `:server:test` = 386 tests, 0 failures** — `OnboardCommandTest`, `ScriptExecutorServiceTest` (uid derivation/omit+warn/dev-omit), `SessionUidAlignmentContractTest` (Dockerfile/entrypoint/compose static guards), `DebPackageTest` (pure-JDK control-archive parser), `DebPostinstContractTest`.
- **CI-Docker (runtime acceptance, executed in CI):** the `real-docker-onboarding` lane **passed** — a session actually booted as uid `4242:0`, self-registered its passwd row, wrote `~/.claude`, `ssh -G github.com` exited 0, and read the 0600 git-key. Plus the `release-install-smoke` `.deb` checks (`config`+`templates` shipped; noninteractive `dpkg` doesn't hang) and the untouched pre-existing `integration`/`real-docker-integration` lanes.

### Honest verification limit
CI proves boot-as-injected-uid, `~/.claude` writes, passwd self-registration, ssh-client init, and git-key readability. It does **not** do a live authenticated clone to a real remote, nor the systemd/debconf-on-real-apt path — those remain operator-verified-only.

## CI / merge / release record
| Stage | Outcome |
|---|---|
| PR #24 first CI run | `build` **failed** — spotless formatting violation in `OnboardCommandTest.java` (local `:server:test` was green but didn't run `:server:spotlessCheck`, which CI runs first). Docker lanes skipped (depend on `build`). |
| Fix-back (1 round) | `ba1a872` — `spotlessApply`; re-validated with the exact CI command (spotlessCheck + test + generateOpenApiDocs, incl. OAS no-drift). |
| PR #24 second CI run | **All 5 jobs green** — build, integration, real-docker-integration, **real-docker-onboarding**, release-install-smoke. |
| Merge | Squash → `main` `3488dc2`. |
| Release | `server-v0.0.19` — `.zip` + `_amd64.deb` published. |

## Manual cleanup for an already-broken (pre-UC-17) install
1. `sudo aisandboxctl onboard --force`
2. `sudo chown -R ai-sandbox-server:ai-sandbox-server /etc/ai-sandbox-server /var/lib/ai-sandbox-server`
3. `sudo rm -rf /var/lib/ai-sandbox-server/sessions/{workspace-*,claude-config-*}`
4. Rebuild/re-pull `ai-context:latest` so it carries the UC-17 Dockerfile changes
5. Recreate already-spawned sessions (DELETE+POST `/v1/sessions`, or `clean.sh`+`spawn.sh`) to pick up the new compose `user:`

## java-class-call-scanning usefulness
Low reach for this UC. The change is mostly bash / Dockerfile / debconf / jdeb / CI — invisible to the bytecode-static, server-Java-only tool. Its one useful contribution was a `--print-hierarchy` on `ScriptExecutorService#composeEnv` confirming the env-injection blast radius (callers spawn/clean → facade → controller; covered by existing tests). The daemon `--project` mode remains broken in v0.2.0; only the one-shot `--compiled` surface works. Consistent with the prior run's verdict.
