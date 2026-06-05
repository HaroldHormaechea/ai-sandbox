---
name: dind-selftest
description: >-
  Self-verify Docker-in-Docker (rootless) end-to-end inside this ai-sandbox
  session. Use when asked to test/confirm DinD, check that the subuid delegation
  landed, bring up the rootless daemon, or diagnose why `aisandbox-dind start`
  fails. Drives the baked `aisandbox-dind` CLI (doctor/start/selftest) and the
  UC-30 subuid bind-mount. Diagnose-only: on failure it explains the cause
  (often a stale, pre-UC-30 container) and tells you to respawn — it never edits
  /etc or source. Linear runbook — follow the steps in order.
---

# DinD self-test — ai-sandbox session

This skill confirms that **Docker-in-Docker (rootless)** actually works in *this*
session, end to end. It is the live counterpart to UC-30's unit tests — the
"operator sign-off" leg (UC-30 AC#8) turned into a runbook.

It encodes what UC-30 shipped:
- The dind capability's server-side install hook delegates a subuid/subgid range
  **by name** to the runtime session user — both `claude:100000:65536` and
  `sandbox:165536:65536` — into host files that `docker-compose.dind.yml`
  **bind-mounts** read-only over the container's `/etc/subuid` + `/etc/subgid`.
  (The container's runtime user is self-registered as the fixed name `sandbox`
  regardless of its numeric uid, so the by-name `sandbox:` line covers it.)
- `aisandbox-dind doctor` now reports the subuid range alongside `/dev/fuse`
  and `fuse-overlayfs`.

> **This skill is read-only / diagnose-only.** It runs checks and starts the
> daemon; it never writes `/etc`, never edits source, and never tries to "fix"
> a broken session in place. The only remediation it recommends is **respawn**
> (or re-run `./setup.sh` on the server) — see *Step 5*.

> **It must run inside a freshly spawned, DinD-enabled session.** A long-lived
> container created *before* UC-30 cannot be retrofitted (the bind-mount is
> applied at spawn time). If you are in such a container, Step 1 / Step 5 will
> detect it and tell you to respawn instead of mis-reporting "the fix is broken".

Run the steps in order. Stop at the first hard failure and read *Step 5*.

---

## Step 0 — Preflight (identity + capability gate)

```sh
id
echo "AI_SANDBOX_DEVTOOL_DIND=${AI_SANDBOX_DEVTOOL_DIND:-<unset>}"
```

- Note the user (commonly `claude` uid 1000 in dev mode, or an arbitrary uid
  self-named `sandbox` in server mode).
- **If `AI_SANDBOX_DEVTOOL_DIND` is not `1`**, DinD was not enabled for this
  session. **Stop** — there is nothing to verify here. Re-run `./setup.sh`
  (or `./setup.sh --reconfigure`) on the server, enable the `dind` capability,
  and spawn a fresh session.

## Step 1 — Verify the subuid/subgid delegation landed (the system-level change)

```sh
echo "--- /etc/subuid ---"; cat /etc/subuid
echo "--- /etc/subgid ---"; cat /etc/subgid
echo "--- bind-mounted? ---"; grep -E '/etc/subuid|/etc/subgid' /proc/self/mountinfo || echo "NOT bind-mounted"
```

**PASS requires both:**
1. `/etc/subuid` **and** `/etc/subgid` each contain **both** lines:
   `claude:100000:65536` **and** `sandbox:165536:65536` (disjoint ranges).
2. `/proc/self/mountinfo` shows `/etc/subuid` and `/etc/subgid` as **bind
   mounts** — this is the UC-30 mechanism. If they are present but *not*
   bind-mounted, you are reading the image's baked file, not the delegation.

**If the `sandbox:` line is missing, or the files are not bind-mounted →** this
is almost certainly a stale / pre-UC-30 container. Go to *Step 5*.

## Step 2 — `aisandbox-dind doctor`

```sh
aisandbox-dind doctor
```

**PASS requires all of:**
- `subuid range : present (...)` **and** `subgid range : present (...)`
  (if `doctor` prints **no** subuid line at all, the baked CLI predates UC-30 —
  see *Step 5*);
- `/dev/fuse : present + accessible`;
- `fuse-overlayfs : present` (resolvable on PATH).

A `MISSING` on any of these is a hard failure — read the line, then *Step 5*.

## Step 3 — `aisandbox-dind start` (leave it running)

```sh
aisandbox-dind start
```

**PASS:** the command reports the rootless daemon started and the socket
(`$XDG_RUNTIME_DIR/docker.sock`, default `/home/claude/.docker/run/docker.sock`)
appears. Re-confirm independently:

```sh
docker info --format 'Server Version: {{.ServerVersion}} | Storage: {{.Driver}}'
```

**HARD FAIL signature:** the log ends with
`failed to setup UID/GID map: ... No subuid ranges found for user <uid> ("<name>")`.
That is the exact pre-UC-30 failure — go straight to *Step 5*.

**On success, leave the daemon running.** This self-test doubles as turning DinD
on for the session. Tell the operator it is up and that they can stop it later
with `aisandbox-dind stop`.

## Step 4 — `aisandbox-dind selftest`

```sh
aisandbox-dind selftest
```

This brings up a one-service `alpine` compose project, execs `tmux -V` inside it,
asserts a version string, and tears it down.

**PASS:** it prints a tmux version and reports success.

**Offline exception:** `selftest` must pull `alpine` the first time. If the host
has **no network** (image pull impossible), this step may fail for reasons
unrelated to DinD. Only in that case may it be skipped — and then the overall
result is a **qualified pass**: report explicitly that `doctor` + `start`
passed but `selftest` was skipped due to no network. An offline skip is **never**
reported as a full pass, and a `selftest` failure on a host *with* network is a
real failure (do not write it off as "offline").

## Step 5 — Interpreting failure (and the stale-container trap)

DinD failing here usually does **not** mean UC-30 regressed. The most common
cause is that **this is an old container** that predates UC-30 and was never
respawned (re-enabling DinD / restarting the service on the server affects
*new* spawns; a running container is not retrofitted).

Run this signature check:

```sh
echo "baked CLI has subuid reporting? (expect >=1):"
grep -c 'subuid range' /usr/local/bin/aisandbox-dind
echo "subuid bind-mounted? (expect a line):"
grep -E '/etc/subuid' /proc/self/mountinfo || echo "NO"
echo "sandbox delegated? (expect a line):"
grep -E '^sandbox:' /etc/subuid || echo "NO"
```

**Stale / pre-UC-30 container** (respawn — do NOT conclude the fix is broken):
- `grep -c 'subuid range' /usr/local/bin/aisandbox-dind` is **0** (the baked CLI
  is older than UC-30), **and/or**
- `/etc/subuid` is **not** bind-mounted, **and/or**
- the `sandbox:` line is **absent**, **and** Step 3 failed with
  `No subuid ranges found`.
  → **Remediation: respawn.** On the server: ensure the deployment includes
  UC-30 (commit `fa300e8` / tag `server-v0.0.31` or later) **and that the
  session image was rebuilt** (the release ships the *management server*; the
  *session image* carrying the new `aisandbox-dind` is built locally during
  setup), then `./setup.sh` (or `--reconfigure`) with `dind` enabled and spawn a
  **fresh** session. Re-run this skill there.

**Genuinely-missing host prerequisite** (host-level, not the session):
- `doctor` shows `/dev/fuse MISSING` → the host kernel does not expose
  `/dev/fuse`; the dind compose override passes the host's `/dev/fuse` through,
  so the host must provide it. Report it; this is a host config issue.
- `doctor` shows `fuse-overlayfs MISSING` on an otherwise-current image → report
  it as an image/provisioning gap (`aisandbox-dind install` populates it).

In all cases: **report the diagnosis and the recommended action. Do not edit
`/etc`, do not modify source, do not attempt to hand-provision subuid in-session
— it is read-only there by design.**

## Result summary (what to report)

State a single verdict plus the evidence:
- **PASS** — Steps 1–4 all green (or 1–3 green + Step 4 skipped-offline = qualified
  pass). DinD is verified and the daemon is left running (`aisandbox-dind stop` to
  stop).
- **FAIL — stale/pre-UC-30 container** — cite the Step 5 signature; recommend
  respawn from a UC-30+ image.
- **FAIL — host prerequisite** — name the missing `/dev/fuse` / `fuse-overlayfs`;
  recommend the host/image fix.

Include the actual command output for the failing step so the operator can act.
