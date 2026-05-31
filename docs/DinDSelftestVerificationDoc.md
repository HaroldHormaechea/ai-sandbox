# DinD Selftest — Operator Verification Runbook (UC-26 AC#9)

## What this runbook is

UC-26 ships rootless Docker-in-Docker (DinD) as the first opt-in dev-tool
capability. Once an operator enables it via `./setup.sh --reconfigure`
(or `aisandboxctl reconfigure`), every newly-`spawn.sh`-ed session
container boots a rootless `dockerd-rootless.sh` and exposes it on
`/run/user/<uid>/docker.sock`.

The acceptance contract (AC#9) for that path has four legs:

1. **(a)** `aisandbox-dind doctor` reports a running rootless daemon.
2. **(b)** `docker compose ls` succeeds (proves the docker CLI inside the
   session can reach the in-session daemon).
3. **(c)** `aisandbox-dind selftest` round-trip succeeds end-to-end —
   compose-up a single-service alpine project, exec `tmux -V` inside it,
   compose-down. **Load-bearing.**
4. **(d)** The selftest is idempotent and teardown-clean — a second
   selftest run leaves no leftover containers, networks, or volumes
   from the first.

Legs (a) and (b) are exercised by Java unit tests (the `ReconfigureCommand
--doctor` subcommand and the `lib.sh` env-injection tests). Legs (c)
and (d) require a real Docker daemon on the host, a real rootless
dockerd inside the session, and live network access (one-time tarball
fetch on first DinD enablement). They cannot be exercised in CI today
without sysbox-on-runner / nested-virt — so the contract for (c) and
(d) is **operator-run on a real host**, per this runbook.

## When to run this

Run before tagging a `server-vX.Y.Z` release that includes UC-26 changes
to:

- `container-bin/aisandbox-dind` (any subcommand)
- `docker-compose.dind.yml`
- `entrypoint.sh` (the `aisandbox-dind install` / `start` block)
- `setup.sh` Step 6 / `devtools-select.sh` (the "Select the development tools
  you want to install" selector)
- `lib.sh` (the `_aisb_devtool_catalog`, `inject_devtool_spawn_env`, or
  `read_enabled_devtools`/`write_enabled_devtools` helpers)
- Any code reachable from `OnboardCommand` / `ReconfigureCommand` that
  alters how the DinD ledger is written or read

Skip if a release touches none of the above — the Java-level tests cover
the wiring between the wizard and the spawn-time injection.

## Prerequisites

- A Linux host with Docker (Compose v2+) installed and the user able to
  `docker info` non-root (or `sudo` available).
- A fresh checkout of `ai-sandbox` on `main` (or the release branch
  under verification).
- Network egress to `download.docker.com` and `dl-cdn.alpinelinux.org`
  (the first selftest run fetches the rootless-docker tarball and the
  `alpine:latest` image; subsequent runs reuse the cache).
- ~500 MB of free disk under `./workspace/environment-utilities/dind/`
  for the rootless-docker tarball cache.
- **Unprivileged user namespaces must be permitted by the host kernel.**
  Rootless `dockerd` (via `rootlesskit`) creates a nested user namespace
  inside the session container. On Ubuntu 23.10+ / 24.04+ / 26.04 the
  default `kernel.apparmor_restrict_unprivileged_userns=1` AppArmor policy
  **blocks** this for unprivileged processes — and it is enforced at the
  host kernel level, so the container's `seccomp:unconfined` +
  `apparmor:unconfined` (set by `docker-compose.dind.yml`) do **not**
  lift it. Verify and, if needed, relax it on the host **before** running
  the gate:

  ```bash
  cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns   # 1 = restricted
  # If 1, EITHER (operator decision, needs root):
  sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0   # host-wide, transient
  #   …or attach an AppArmor profile that grants `userns,` to the session
  #   container. A quick smoke test that userns works at all:
  #   `unshare -Urm true`  → exit 0 means OK; "Permission denied" means blocked.
  ```

  Without this, `aisandbox-dind start` fails with
  `rootlesskit … failed to start the child: fork/exec /proc/self/exe:
  operation not permitted`. This is a **host configuration prerequisite**,
  not a defect in the DinD plumbing.

## Steps

### 1. Enable DinD in the wizard

```bash
./setup.sh --reconfigure
```

In the "Select the development tools you want to install" step (the
last numbered step before the wizard exits):

1. Type `1` to toggle the **Enable Docker-in-Docker (rootless; …)** entry.
2. Read the inline trust-boundary warning that appears.
3. Type `y` at the `Continue? [y/N]` prompt.
4. Press Enter on the empty line to commit.

The wizard prints
`Development tools persisted: dind` and
`Changes apply to NEW sessions only — existing sessions are unaffected.`

Verify the ledger:

```bash
cat .ai-sandbox-devtools
# Expected: a single line:  dind\tsession-spawn
```

### 2. Spawn a fresh session with DinD layered

```bash
./spawn.sh --non-interactive --shared-workspace --shared-claude-config
```

In the spawn output, look for:

```
  devtools      : DinD enabled (rootless dockerd will start inside the session)
```

Note the session number `N` printed in the success line (e.g.
`ai-sandbox-3 is running`).

### 3. AC#9 (a) — `aisandbox-dind doctor`

Exec into the session and run `doctor`:

```bash
docker compose -p ai-sandbox-3 exec -T claude-sandbox aisandbox-dind doctor
```

Expected:

- Exit code 0.
- Output mentions `rootless` mode, the storage driver (`fuse-overlayfs`
  or `vfs` as the fallback), and lists at least one accessible daemon.
- No `cannot connect to the daemon` errors.

### 4. AC#9 (b) — `docker info` / `docker compose ls` inside the session

```bash
docker compose -p ai-sandbox-3 exec -T claude-sandbox docker info
docker compose -p ai-sandbox-3 exec -T claude-sandbox docker compose ls
```

Both MUST exit 0. The first emits a docker-info banner; the second
prints an empty project list (no compose projects are running inside
the session yet).

### 5. AC#9 (c) — `aisandbox-dind selftest` (load-bearing)

```bash
docker compose -p ai-sandbox-3 exec -T claude-sandbox aisandbox-dind selftest
```

Expected:

- Exit code 0.
- Output indicates a one-service alpine compose project was brought
  up, `tmux -V` was exec'd inside it, the version string was printed,
  and the project was torn down.

**If this leg fails:**

- Capture stderr (re-run with `2>&1 | tee dind-selftest.log`).
- Capture `docker info` output from inside the session (step 4).
- File an issue with both logs attached. Do NOT tag the release.

### 6. AC#9 (d) — idempotency + teardown-clean

Re-run the selftest a second time:

```bash
docker compose -p ai-sandbox-3 exec -T claude-sandbox aisandbox-dind selftest
```

Expected:

- Exit code 0 again.
- Output looks the same as step 5 (no "container already exists" or
  "network in use" errors that would indicate a leak from the first
  run).

Then confirm there are zero leftover artifacts inside the session:

```bash
docker compose -p ai-sandbox-3 exec -T claude-sandbox docker ps -a --format '{{.Names}}'
# Expected: empty output

docker compose -p ai-sandbox-3 exec -T claude-sandbox docker network ls --format '{{.Name}}'
# Expected: ONLY the built-in `bridge`, `host`, `none` networks — no `aisandbox-dind-*` survivors.

docker compose -p ai-sandbox-3 exec -T claude-sandbox docker volume ls --format '{{.Name}}'
# Expected: empty (or any volumes are pre-existing operator state, not selftest leftovers).
```

If any of these list a `aisandbox-dind-*` / `selftest-*` artifact, the
teardown is leaky — file an issue.

### 6b. Stronger runtime gate — busybox file round-trip (committed script)

Beyond the `tmux -V` selftest, a committed end-to-end gate proves a
container running **inside** the in-session rootless daemon can write a
file that round-trips back out to the session container's filesystem —
a stricter AC#5/AC#7/AC#9 proof than "a daemon answers". The script
drives the **real supported path** end-to-end (real `lib.sh`
`write_enabled_devtools` ledger writer → real `./spawn.sh` →
image-baked `aisandbox-dind` → busybox `docker run`), on a throwaway
high-numbered project (`ai-sandbox-91` by default) under a temp state
root so it never collides with a live session:

```bash
server/src/test/e2e/uc26-dind-busybox-gate.sh
# exit 0 = PASS · 1 = FAIL · 77 = SKIP (prereq missing, e.g. no /dev/fuse)
```

What it asserts:

1. The ledger is written via the production writer and reports `dind`.
2. `./spawn.sh` layers `docker-compose.dind.yml` — the spawned container
   has `/dev/fuse` passed through and `AI_SANDBOX_DEVTOOL_DIND=1`
   (AC#5/#7 spawn-side wiring).
3. Inside the session, a `busybox` container in the rootless daemon does
   `printf <sentinel> > /data/probe.txt` on a bind-mounted path.
4. The session container reads `/workspace/dind-probe/probe.txt` back and
   asserts it equals the sentinel **byte-for-byte** (the round-trip).
5. A teardown trap brings the project down (`-v`) and removes the temp
   state — idempotent and re-runnable.

The script is intentionally inert to Gradle (it lives under
`server/src/test/e2e/`, which `compileTestJava` / `processTestResources`
do not consume) — it is an operator/CI-on-a-real-host artifact, run
directly with `bash`.

### 7. Disabled-session control (AC#6 sanity)

Recycle the wizard to disable DinD, spawn a control session, and prove
it's byte-identical to today's behaviour:

```bash
./setup.sh --reconfigure
# Type `1` to toggle dind OFF (no warning fires on disable). Enter to commit.
./spawn.sh --non-interactive --shared-workspace --shared-claude-config
```

Note the new session number `M`. Then:

```bash
# The control session has NO docker binary inside it.
docker compose -p ai-sandbox-M exec -T claude-sandbox sh -c 'command -v docker'
# Expected: exit non-zero, no output.

# Neither AI_SANDBOX_DEVTOOL_DIND nor the rootless daemon is present.
docker compose -p ai-sandbox-M exec -T claude-sandbox sh -c 'printenv AI_SANDBOX_DEVTOOL_DIND || echo unset'
# Expected: `unset`.
```

If the disabled session carries a docker binary or the DinD env var, the
AC#6 ("DinD disabled → sessions identical to today") promise has
regressed.

### 8. Cleanup

```bash
./clean.sh 3
./clean.sh M
```

(Replace `3` / `M` with the actual session numbers from steps 2 and 7.)

## Pass criteria

All seven steps above pass cleanly on at least one of:

- An x86_64 Linux host (the developer's primary).
- An aarch64 Linux host, if a release is the first to claim arm64
  parity for DinD.

Record the host details (kernel, docker version, distro) in the
release-prep checklist alongside the verifier's initials and the date.

## Known limitations

- This is an **operational verification**, not a CI gate. CI does not
  exercise rootless DinD inside a containerised GitHub Actions runner
  because the cost + flakiness vs. value tradeoff for a sideload-for-two
  project doesn't justify the engineering investment today.
- macOS / Windows hosts are out of scope (rootless DinD inside a
  Docker-Desktop session VM has its own quirks; the v1 trust-boundary
  story is Linux-host first).
- A future revision may move the (c) / (d) legs into a Testcontainers-
  based integration test gated on a `RUN_DIND_ITS=1` env var so the
  contract can be CI-enforced on a daily cron without slowing every PR.

## Findings — 2026-05-31 live gate run (this host)

Recorded when the busybox round-trip gate (§6b) was first executed on the
dev host (Ubuntu 26.04 LTS, Docker 29.5.0, x86_64). The gate could **not**
be marked green; two distinct blockers surfaced, captured here verbatim:

1. **Defect (pending developer fix) — `aisandbox-dind start` cannot create
   `XDG_RUNTIME_DIR` in a logind-less container.** `cmd_start` runs
   `mkdir -p /run/user/$(id -u)` as the non-root session user, but
   `/run/user/` does not exist and is root-owned (a bare container has no
   `systemd-logind` to create it), so the command fails with
   `mkdir: can't create directory '/run/user/': Permission denied` and
   `set -e` aborts **before** the daemon is launched (no socket, no
   `dockerd-rootless.log`). This is host-independent and blocks the DinD
   runtime path on every host. Suggested fix: fall back to a user-writable
   `XDG_RUNTIME_DIR` (e.g. `$HOME/.docker/run`, mode 0700) when
   `/run/user/<uid>` is not creatable, and point `DOCKER_HOST` /
   `--exec-root` at it accordingly.

2. **Host prerequisite (not a defect) — unprivileged userns blocked.** With
   the above worked around manually, `rootlesskit` then failed with
   `failed to start the child: fork/exec /proc/self/exe: operation not
   permitted`. Root cause: `kernel.apparmor_restrict_unprivileged_userns=1`
   (Ubuntu 24.04+/26.04 default). `unshare -Urm true` inside the session
   returns `/proc/self/setgroups: Permission denied`, confirming the
   kernel-level block. Relaxing it needs root (`sysctl -w …=0` or an
   AppArmor `userns,` profile); see the Prerequisites section. On this host
   passwordless sudo is unavailable, so the gate is **blocked, not failed**
   — recorded rather than skipped.
