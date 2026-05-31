# UC-27 DinD — Resume / Handoff

**Status as of this checkpoint:** UC-27 core is DONE, merged, released, and installed. The
**DinD capability** on the new glibc/Debian base is *functionally proven working* but has a
final **WIP-unverified** commit that must be rebuilt + live-gated before merge.

This file is a transient handoff doc — delete it before/at the final merge.

---

## 1. Where things stand

### Done & shipped (safe — on `main` / GitHub Releases)
- **UC-27 core** merged via **PR #34** → `main` (merge commit `6ef29c3`), released as **`server-v0.0.27`**
  (both `.deb` + zip published), and **installed on this host** (`dpkg -l ai-sandbox-server` → 0.0.27,
  service active). CI was green (build, integration, real-docker-integration, real-docker-onboarding,
  release-install-smoke).
- **Verified live in-environment:** the raw-mode selector, manifest model, **Java** (PATH resolves in
  `sh -lc` + `sh -c`, JAVA_HOME set), **Android** (adb/sdkmanager/emulator/aapt2 + build-tools on PATH,
  deps-pull-java), no-capability **no-trace**, glibc base, PowerShell removal. `:server:test` green except
  31 pre-existing non-root host-artifact failures (chown-to-service-user wall; pass on root CI; proven
  identical at base commit `fcd495c~1`).
- Use-case ledger `USE_CASES.md` row 27 is currently `in-progress` (uncommitted working-tree edit). It
  should flip to `done` only after the DinD work below lands and the full gate passes.

### In progress — DinD fix branch `fix/uc-27-dind-debian-rootless-prereqs`
Commits (all pushed):
| SHA | What |
|---|---|
| `c43922d` | rootless prereqs: `uidmap` / `slirp4netns` / `fuse-overlayfs` |
| `e0aeadd` | `aisandbox-dind` trap + `--pidfile` fixes; `iproute2`; `/dev/net/tun` + `CAP_SYS_ADMIN` in dind override |
| `952c908` | `iptables`; `systempaths=unconfined`; snippet-written-up-front; ~90s readiness wait; manifest WARNING |
| `c54d70a` | **WIP / UNVERIFIED** — compose plugin install (v2.29.7) + ephemeral exec-root |

**DinD is confirmed serving on a CLEAN spawn** (verified before this checkpoint): `docker run hello-world`
prints "Hello from Docker!" in BOTH `sh -lc` and `sh -c`, daemon rootless, `docker` resolves by bare name,
`DOCKER_HOST` set — all with no manual env. The `c54d70a` WIP adds the last two robustness fixes but has
**NOT** been rebuilt or live-gated yet.

---

## 2. Why DinD needed so much (host reality)

Host: **Ubuntu 26.04, kernel 7.0.0, Docker 29.5.0, runc 1.3.5**, rootful host dockerd, x86_64, glibc.
The Alpine→Debian base flip (UC-27) dropped multiple rootless-Docker prerequisites, and this hardened
kernel needs extra grants. Full proven requirement set for rootless DinD to serve here:

- **Packages (in `SandboxDockerfile`):** `uidmap`, `slirp4netns`, `fuse-overlayfs`, `iproute2`, `iptables`.
- **Devices (in `docker-compose.dind.yml`):** `/dev/fuse`, `/dev/net/tun`.
- **Capability:** `CAP_SYS_ADMIN` (minimal delta — tested: setuid newuidmap + valid /etc/subuid +
  apparmor/seccomp unconfined + even explicit SETUID/SETGID all FAIL; only SYS_ADMIN works; full
  privileged not needed).
- **`systempaths=unconfined`** on the session container (un-masks /proc so the inner runc can mount /proc;
  fixes `error mounting "proc" ... operation not permitted`).
- **apparmor=unconfined + seccomp=unconfined** (already present; nested daemon needs clone/unshare/mount/keyctl).
- **Host sysctl:** `kernel.apparmor_restrict_unprivileged_userns=0` (the user set it; **resets on reboot** —
  re-apply with `sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0` before testing DinD).

**Sysbox was attempted and reverted** — Sysbox CE 0.6.7's runc fork rejects Docker 29.5's `time`
namespace (`namespace {"time" ""} does not exist`); officially unsupported on 26.04/kernel-7.0. Fully
uninstalled; host restored (no `/etc/docker/daemon.json`, default runc, fusermount3 AppArmor rule removed).

**Security posture (documented in the dind manifest WARNING):** enabling DinD grants the *session*
`CAP_SYS_ADMIN` + `/dev/net/tun` + `systempaths=unconfined` + unconfined apparmor/seccomp — a deliberate,
opt-in trust-boundary widening. The **host stays protected**: no host docker-socket bind, no published
ports, non-root (uid 1000) + userns-mapped session.

---

## 3. Remaining work (in order)

1. **Re-apply the host sysctl if the box rebooted:** `sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0`.
2. **Rebuild + run the full DinD gate** on the WIP (`c54d70a`):
   - `docker compose build claude-sandbox` (slow — run detached).
   - `export AI_SANDBOX_DEV_WORKSPACE_ROOT=/home/potato-server/ai-sandbox-ws`
   - **Clean-workspace spawn:** wipe `/home/potato-server/ai-sandbox-ws/workspace-*`, seed `.ai-sandbox-devtools`
     with `dind\tsession-spawn`, reset `.ai-sandbox-counter` to 0, `./spawn.sh --non-interactive
     --isolated-workspace --isolated-claude-config --label dind-gate`.
   - **Verify (tmux-window child, NOT `docker exec` — exec bypasses the entrypoint env):** in both `sh -lc`
     and `sh -c`, `docker` resolves bare, `docker info` rootless, **`docker run --rm hello-world`** prints
     "Hello from Docker!", **`docker compose version` + `docker compose ls`** work (the c54d70a compose fix).
   - **Re-spawn test (the c54d70a exec-root fix):** spawn a SECOND time into the SAME cached workspace
     (don't wipe) → daemon must still serve (previously failed with `containerd is still running pid=… →
     timeout`).
3. **If the gate is green:** commit anything outstanding, open a PR for `fix/uc-27-dind-debian-rootless-prereqs`
   → `main` (gh is authed as HaroldHormaechea), wait for CI, **merge** (user pre-authorized merging),
   then cut **`server-v0.0.28`** (`git tag server-v0.0.28 && git push origin server-v0.0.28` → the
   `server-release.yml` workflow builds + publishes), reinstall (`sudo dpkg -i` the new .deb — sudo needs a
   password, hand off to the user), and re-verify DinD on the installed release.
4. **If the gate fails:** the WIP commit message + this doc describe the intended behavior; fix forward in
   `aisandbox-dind` / `devtools.d/lib/versions.sh`. Likely watch-items in `c54d70a`: the compose URL var
   name (script uses `DOCKER_COMPOSE_VERSION`; versions.sh defines `AISB_DOCKER_COMPOSE_VERSION` — confirm
   they're wired), and that the ephemeral exec-root path is actually ephemeral per container.
5. **Flip `USE_CASES.md` row 27 → `done`** (date) once everything passes, commit on `main`.

---

## 4. Operational notes for whoever resumes

- **Run inside tmux** so SSH drops don't kill it: `tmux new -s claude` then launch claude; detach `Ctrl-b d`,
  reattach `tmux attach -t claude`. (The prior session was a raw foreground SSH process and was lost on
  disconnect — only pushed commits survived, which is why this checkpoint exists.)
- **sudo requires an interactive password** on this host — any `dpkg -i` / sysctl / install step must be
  handed to the user (e.g. `! sudo …` in-session).
- **Don't run two `docker compose build` / spawns concurrently** (orchestrator + agent collided before).
  One driver owns the host gate.
- **Dev-team orchestration must run from the ROOT session** (the `develop` skill); spawned agents can't spawn
  agents. Team name is `ai-sandbox`.
- The workspace is the project-builder tooling at `/home/potato-server/project-builder`; the TARGET project
  is `/home/potato-server/ai-sandbox`.
