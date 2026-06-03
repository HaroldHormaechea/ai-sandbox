# Docker-in-Docker (rootless) limitations in the dev-team session — 2026-06-03

> Status: reference note (not a use case — no ledger row). Captures why the
> **live-container** verification leg of UC-24 (and any future UC that needs a
> real, running `ai-sandbox-*-claude-sandbox-1` container) could not be executed
> from inside the project-builder dev-team session, and what is required to
> close that gate.

## Context

UC-24 (`24-android-terminal-multi-window-leak-fix.md`) is a server-side fix to
the tmux bridge. Its acceptance criteria deliberately demand **live evidence**:

- **AC#6** — the diagnosis must be grounded in live container inspection
  (`tmux list-sessions / list-windows -a / list-panes -a` on the default and
  swarm sockets, plus observed `resize-pane -Z` behaviour), not spec reading.
- **AC#2 / AC#7** — an end-to-end smoke run on a real container with an active
  Claude Code `--team-name` swarm, demonstrating the single-pane + agent-switcher
  behaviour on the Android client.

Both legs require standing up a real session **container** (the server manages
sandbox sessions as Docker containers and bridges into them via
`docker compose exec … tmux …`). That means the dev-team session must be able to
run Docker. It cannot — for the reasons below.

## What actually blocks it

The session runs as an unprivileged user (`uid 997`, name `sandbox`) inside an
already-containerised, locked-down environment. Attempting **rootless** Docker
(`dockerd-rootless` / `aisandbox-dind`) fails at the user-namespace setup step:

1. **No subuid/subgid range for the runtime user.**
   `dockerd-rootless` aborts with:
   ```
   failed to setup UID/GID map: No subuid ranges found for user 997 ("sandbox")
   ```
   `/etc/subuid` and `/etc/subgid` only contain an entry for `claude`, not for
   `sandbox` (uid 997). Rootless Docker requires a delegated subordinate-UID
   range to build the user namespace; without it, the daemon cannot start.

2. **`/etc` is read-only and there is no `sudo`.**
   The obvious fix — add a `sandbox:100000:65536` line to `/etc/subuid` /
   `/etc/subgid` — is impossible: `/etc` is mounted read-only and the session
   has no privilege-escalation path (`sudo` is absent). So the missing range
   cannot be provisioned from within the session.

3. **`fuse-overlayfs` is missing.**
   Rootless Docker's default storage driver needs `fuse-overlayfs` (or a
   kernel that permits unprivileged overlay mounts). The cached binary is not
   present, so even past the namespace step the daemon would have no usable
   storage driver.

4. **Image build needs network pulls.**
   Building the sandbox image (`SandboxDockerfile`) pulls base layers and apk
   packages. The dev-team session's network posture is not guaranteed to allow
   those pulls, adding a second independent blocker on top of the daemon issue.

## Relationship to UC-27 (rootless DinD)

UC-27 (`27-devtools-capability-selector.md`) added a rootless Docker-in-Docker
*capability* to the **sandbox image itself** (`aisandbox-dind` helper, iptables /
`slirp4netns` / `fuse-overlayfs` prereqs, `systempaths=unconfined`). That work
makes DinD available **inside a properly provisioned sandbox container on a host
that grants the necessary kernel features and subuid delegation**. It does **not**
make DinD available in the project-builder dev-team session, which is a different,
more restricted environment that lacks the subuid delegation, writable `/etc`,
and `fuse-overlayfs` that UC-27's DinD path assumes. In short: UC-27 enables DinD
for end users of a real sandbox; it does not retroactively enable it for the
tooling session that builds and tests the project.

## Impact on UC-24 verification

The live-container legs were therefore **gated to an operator / CI run**, and the
fix was instead verified by faithful proxy:

| Verification | How | Status |
|---|---|---|
| tmux zoom/status mechanics (AC#6 invariant) | `TmuxZoomHostTmuxTest` drives the host's **real tmux 3.3a** (the same version the Debian-bookworm base ships) | done, in `:server:test` |
| Exact `prepareClientSession` argv sequence | `TmuxBridgeSessionSetupTest` with a mocked `ProcessExecutor` | done |
| Android terminal/switcher UI no-regression (UC-21 AC#1–15) | `:android:connectedDebugAndroidTest` on a booted **KVM emulator** (34 tests, 0 fail) | done |
| Real-swarm end-to-end on a live container (AC#2/#7 smoke) | needs a real running container + a live Claude Code swarm | **gated — not run** |
| Live `main` window-count capture (AC#5 trigger) | needs the default socket on a live container | **gated — not run** |

`docker compose exec … tmux …` is a pure transport wrapper over identical tmux
argv, so the host-tmux harness exercises the same code paths the bridge would hit
inside a container — but it cannot prove orchestration-level behaviour of a real
Claude Code team (window vs pane layout, today's swarm socket shape), which is why
those two rows remain open.

## What is required to close the gate

Any one of:

- **A host that delegates subuid/subgid to the runtime user** (`sandbox`, uid 997)
  — add `sandbox:100000:65536` to `/etc/subuid` and `/etc/subgid` (requires a
  writable `/etc` or a pre-seeded image), **plus** a cached `fuse-overlayfs`
  binary, **plus** network access for the image build. Then `aisandbox-dind` /
  rootless Docker can start and a real session container can be spawned.
- **A privileged or sysbox-backed runner** where the daemon can use real overlayfs
  without the rootless namespace dance.
- **A dedicated DinD CI job** (`AI_SANDBOX_DIND=1`) that runs the gated
  `SwarmBridgeIT` integration test against a real container + swarm, mirroring the
  manual "Live Verification" operator runbook from UC-21 AC#15.

Until one of those exists, treat the real-swarm end-to-end check as an **operator
sign-off step**, performed on a real ai-sandbox deployment, not as something the
dev-team session can self-certify.

## Adjacent environment notes (same session, 2026-06-03)

Not DinD-specific, but observed in the same run and worth recording for whoever
maintains the KVM dev image:

- The baked Android SDK at **`/opt/android-sdk` was entirely missing**; the cache
  SDK's `platform-tools` / `platforms` / `build-tools` / `licenses` symlinks were
  dangling (no `adb`) and the emulator's X11 native libs were absent. The emulator
  suite only ran after reinstalling those via the cache `sdkmanager` and fetching
  `libx11-6 libxcb1 libxau6 libxdmcp6 libx11-xcb1` into a userspace
  `LD_LIBRARY_PATH` prefix. Likely a KVM-image regression.
- The brief's JDK path `/usr/lib/jvm/temurin-21-jdk-amd64` did **not** exist;
  the working OpenJDK 21 was at `/workspace/environment-utilities/java/jdk`.
- `jq` was not installed; the skill-ledger scripts needed a static `jq` dropped
  onto `PATH`.
