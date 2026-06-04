# Use Case 30: Server-side per-capability install scripts in `setup.sh` (idempotent, hard-gated) + dind subuid provisioning

## Summary
Generalize the devtools capability selector (UC-26/UC-27) so each capability can declare an **optional, idempotent server-side install hook** — a new manifest hook function (e.g. `devtool_server_install()`) backed by a `.sh` — that the **server setup wizard (`./setup.sh`) runs right after the operator selects capabilities and advances to the next step**. This is a **new third stage**, distinct from and prior to the existing host-side `devtool_spawn_env` (`spawn.sh`) and in-container `devtool_provision` (`entrypoint.sh`), and it runs in the privileged setup context where `/etc` is writable (unlike the unprivileged in-session runtime, where `devtool_provision` runs as the session user with a read-only `/etc`). If a capability's server-install hook fails, that capability is **hard-gated** (marked unavailable/errored and surfaced clearly) without aborting the rest of setup. A hook may register **post-requisite notices** (e.g. "a restart is required"); `setup.sh` aggregates these across all capabilities and prints a consolidated list **at the end of the run**. The use case then adds such a hook for the **dind** capability to fix a live failure: rootless `dockerd` aborts at userns setup with `failed to setup UID/GID map: No subuid ranges found for user 997 ("sandbox")` because `/etc/subuid`/`/etc/subgid` only delegate a range to `claude` (uid 1000), not the actual runtime session user, and `/etc` is read-only with no `sudo` in-session so it cannot be fixed at runtime. Backward compatibility is required: capabilities without the new hook behave exactly as today.

## Acceptance Criteria
1. The manifest gains an **optional new hook function** (e.g. `devtool_server_install`); a capability that defines it gets a server-side install, and one that does not behaves **byte-for-byte as today** — auto-discovered with no edits to the selector/resolver/persistence code (preserves UC-27 AC#2 "drop a directory").
2. `setup.sh`, after the capability-selection step and on advancing to the next step, runs the server-install hook of **each selected capability** as a new third stage, before any in-container `devtool_provision`.
3. **Idempotent**: re-running setup (or re-invoking a hook) is a verifiable no-op on the second pass and exits success — no duplicate `/etc/subuid` lines, no repeated mutation.
4. **Hard-gate on failure**: a failing server-install hook marks that capability unavailable/errored and surfaces a clear message; it does **not** abort setup or other capabilities' installs.
5. **Post-requisites**: a hook can register post-requisite notices (e.g. a required restart / a follow-up command); `setup.sh` collects them across all selected capabilities and prints a consolidated summary **at the end** of the setup run.
6. The **dind** server-install hook idempotently delegates a subuid/subgid range to the runtime session user in `/etc/subuid` + `/etc/subgid` (e.g. `<user>:100000:65536`) without clobbering the existing `claude` entry, covering a non-1000 uid (997 `sandbox` / arbitrary-uid case).
7. The dind hook ensures `fuse-overlayfs` is resolvable on PATH and `/dev/fuse` is accessible, or hard-gates (per AC#4) with an actionable message naming exactly what is missing.
8. After the dind hook runs (and any stated post-requisite such as a restart is applied), `aisandbox-dind doctor` reports subuid present + `fuse-overlayfs` present + `/dev/fuse` accessible, `aisandbox-dind start` brings the rootless daemon up (the socket appears), and `aisandbox-dind selftest` passes where the environment allows.
9. No regression to `java`/`android` provisioning or the selector UI; new/modified `.sh` is shellcheck-clean.

## Potential Pitfalls & Open Questions
- **Open** — which uid receives the subuid range at setup time (operator uid vs `claude` vs the actual future runtime uid). If sessions can run as an arbitrary uid unknown at setup time, the write may need to target the sandbox **image** `/etc` (baked during setup's image build) rather than only a host file — the dev-team should resolve this from live inspection of how the session container's runtime uid is determined.
- **Assumption** — `setup.sh` runs with enough privilege (operator/root) to write `/etc/subuid`/`/etc/subgid` and/or drive the sandbox image build; the delegated range is `100000:65536` and is collision-safe against `claude`'s existing entry.
- **Risk** — granting a subuid range widens the per-session trust boundary; it must stay within the documented dind opt-in stance (`the container is the trust boundary`, see the dind manifest WARNING).
- **Edge case** — `/dev/fuse` may be absent on a hardened kernel; the dind compose override (`docker-compose.dind.yml`) adds it, but the hook should still detect and message rather than fail opaquely.

## Original Description
> update the setup capabilities optiins so it can include server side installation scripts for each capability pointinh to an Sh.
>
> also create one so the dind capability sets up the serve4 properly when enabked
>
> scripts should b3 idempotent btw
>
> if a script has postrequisites kike restarting output it qt the end of the setup

## Clarifications
- Q: At what lifecycle stage should a capability's "server-side install script" run (where it has root + writable `/etc` for things like the subuid delegation)?
  A: During the setup process, after selecting capabilities and moving to the next step (i.e. inside `./setup.sh`).
- Q: How should a capability's manifest reference its server-side install script?
  A: A new hook function (mirroring the existing `devtool_spawn_env` / `devtool_provision` hooks).
- Q: How does the new server-side install script relate to the existing in-container `devtool_provision` hook?
  A: A new third stage, run before `devtool_provision`.
- Q: If a capability's server-side install script fails, what should happen?
  A: Hard-gate the capability (mark it unavailable/errored) without aborting the whole setup.
- Note (post-requisites): if a script has post-requisites such as requiring a restart, `setup.sh` should output them at the end of the setup run.
