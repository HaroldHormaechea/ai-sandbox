# ai-sandbox

## Description

**ai-sandbox spawns sandboxed, free-running [Claude Code](https://docs.claude.com/en/docs/claude-code) sessions in disposable Linux containers to autonomously develop applications.** Each session runs Claude as a fully autonomous agent inside a Linux (Debian/glibc) container launched with `--dangerously-skip-permissions`, so it can read, write, and execute freely — no approval prompts — while the container itself is the trust boundary. Let Claude work, and detach/reattach to its session whenever you want.


You can run more than one of these at a time. Each session is its own Docker Compose project named `ai-sandbox-<N>`, with a tmux window named `main` inside the container. Whether a session shares the host workspace and Claude config (fast and zero-friction) or runs on its own isolated copies is a **per-session property chosen at creation time** through the management server's API or the Android app.

## The three tiers

ai-sandbox is three cooperating layers:

- **Management server** (`server/`) — a Java 21 / Spring Boot service that exposes session operations (list / create / delete / inspect) plus interactive tmux attach over a single mTLS-gated port. It is the **operational front door**: you install the `.deb`, onboard, and drive sessions from it (directly via its REST/WebSocket API, or through the Android app). Quick-start [below](#management-server-quick-start); full install + operation in [`server/README.md`](server/README.md).
- **Container + orchestration layer** — the `ai-context:latest` image plus the Docker Compose context and internal orchestration helpers that actually spawn, attach to, and tear down the disposable `ai-sandbox-<N>` sessions on a Linux host. This is the **internal engine the server drives**, not something you operate by hand.
- **Android client** (`android/`) — an optional native Kotlin/Compose phone app that talks to the management server over mTLS (sideload-only, two devices, no telemetry). Quick-start [below](#android-client-quick-start); full build + enroll detail in [`android/README.md`](android/README.md).

## Getting started

You operate ai-sandbox through the **management server**. The full procedure —
prerequisites, the `onboard` wizard, the at-rest security model, client
lifecycle, endpoints, foot-guns, and upgrades — lives in
[`server/README.md`](server/README.md); the [quick-start below](#management-server-quick-start)
is the condensed version. In outline:

1. **Install + onboard the server.** Install the `.deb` on a Linux host and run
   `sudo aisandboxctl onboard` — one wizard that provisions the PKI, captures
   the SSH key + git identity (+ optional `gh` token), and snapshots Claude's
   first-run state so spawned sessions start past the login/trust prompts.
2. **Authorize a client.** Mint an operator cert (`aisandboxctl client mint`)
   or issue a QR invite for the Android app (`aisandboxctl client invite`).
3. **Operate sessions.** Create, list, attach to, and delete `ai-sandbox-<N>`
   sessions from the **Android app** ([`android/README.md`](android/README.md))
   or directly against the server's **REST/WebSocket API** (the full endpoint
   table is in [`server/README.md`](server/README.md#api-surface)). Attaching
   streams the session's `main` tmux window over WebSocket-over-TLS; detaching
   leaves Claude running so you can reattach later.

Whether a session uses a **shared** or **isolated** workspace / Claude config
is chosen per session when you create it (a flag on the create-session request
/ a toggle in the app) — see [Known foot-guns](#known-foot-guns) for the
trade-off. Deleting a session is `DELETE /v1/sessions/{n}` (or the app's delete
action); it tears down the container and any isolated per-session directories.

**Updating the images.** Update the server itself by reinstalling the newer
`.deb` (`sudo apt install ./ai-sandbox-server_<version>_amd64.deb`). The
session container image (`ai-context:latest`) is built lazily and refreshed by
onboarding — re-run `sudo aisandboxctl onboard --force` to rebuild it (e.g.
after changing development-tool selections with `sudo aisandboxctl reconfigure`).

### Testing Android apps inside the sandbox

Android is one of the opt-in **development tools** (see [Development tools](#development-tools) below) — enable **Android SDK** in the selector (`sudo aisandboxctl reconfigure`) and recreate the session. It is **amd64-only** and **depends on Java** (the selector auto-selects Java for you). UC-27 changed how this works:

- **No build-time bake.** The Android SDK is no longer baked into `ai-context:latest`. Every session now runs on a **single glibc (Debian) base** (`node:20-bookworm-slim`), and when the Android capability is enabled the toolchain is **provisioned eagerly at spawn** by `aisandbox-android` into the persisted `/workspace/environment-utilities/android/sdk` (cmdline-tools + platform-tools + `platforms;android-36` + `build-tools;36.0.0` + the x86_64 system image + emulator). The glibc base is what lets the emulator's QEMU binary load (under the old Alpine/musl base it died with `posix_fallocate64: symbol not found` — a glibc symbol `gcompat` doesn't export).
- **PATH is wired automatically.** With Android enabled, a freshly spawned session resolves `adb`, `sdkmanager`, `emulator`, and the `build-tools/<ver>` binaries (e.g. `aapt2`) by bare name in **both** login (`sh -lc`) and non-login (`sh -c`) shells, with `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `JAVA_HOME` set.

The full CI build lane works inside the session:

```bash
./gradlew :android:lint :android:test :android:assembleDebug :android:bundleDebug
```

Because the SDK is provisioned at spawn, this lane runs offline after the first spawn; Gradle's Maven/AGP/Compose dependencies still resolve over the network on a cold build (and cache afterward).

#### Running instrumented tests (the emulator)

Instrumented tests (`:android:connectedAndroidTest`) need a running emulator,
which needs hardware **KVM**. The x86_64 system image + emulator are provisioned
with the rest of the SDK at spawn into `/workspace/environment-utilities/android/`
(persisted via the workspace bind mount, so the **shared** workspace downloads
them once and reuses them across container restarts, rebuilds, and re-spawns).
The AVD is created on first `start`. From inside a session, drive it with the
bundled helper:

```bash
aisandbox-emulator doctor    # check toolchain + KVM; verify aapt2/adb actually load
aisandbox-emulator start     # lazily install system image, create AVD, boot headless
./gradlew :android:connectedAndroidTest
aisandbox-emulator stop
```

**Host KVM prerequisite.** The orchestration layer automatically passes
`--device /dev/kvm` into the session when the **Android capability is enabled**
in the ledger **and** the host exposes `/dev/kvm` (the android manifest's spawn
hook layers `docker-compose.kvm.yml`). It also detects the host's `kvm` group
GID and adds it as a supplementary group on the container (`group_add`), so the
in-container user can actually *open* `/dev/kvm` — passing the device alone is
not enough, because the device node is group-owned and the runtime user would
otherwise hit `EACCES`. Verify on the host first:

```bash
ls -l /dev/kvm                                   # must exist; note its group
{ [ -r /dev/kvm ] && [ -w /dev/kvm ]; } && echo "readable+writable"
getent group kvm                                 # the GID passed through to the session
```

If `/dev/kvm` is missing, load the modules (`modprobe kvm kvm_intel` or
`kvm_amd`) and — if the host is itself a VM — enable **nested virtualization**.
Without KVM the build + JVM-test lane above still works; only the emulator is
affected — `aisandbox-emulator start` reports that acceleration is unavailable
and refuses to launch (pass `--no-accel` to force very slow software emulation).

**Limitations.** Android testing is **amd64-only** today (arm64 is a documented
follow-up — x86_64 system images won't boot on arm64; the selector shows the
Android row disabled on non-amd64 hosts). The emulator needs a Linux host that
exposes `/dev/kvm`; the build + JVM-test lane does not.

**Per-session cache caveat.** The system image + AVD cache lives under the
session's `/workspace`. Sessions created with an **isolated** workspace
therefore have their own `environment-utilities/` and re-download the ~1.5 GB
system image on their first emulator use. Sessions sharing one workspace all
reuse the single cache. This is the accepted trade-off of caching under the
workspace bind mount.

### Development tools

**"Select the development tools you want to install"** is a **pure-shell raw-mode cursor checkbox selector** of opt-in capabilities the sandbox provisions into the per-session containers. One capability per line prefixed `[X]`/`[ ]`, with a highlighted cursor: use **↑/↓ (or the mouse wheel)** to move, **Space** to toggle, **Enter** to commit, **q/Esc** to cancel. It is pure shell (`read` + ANSI) — no `whiptail`/`dialog` dependency — so the `.deb` TTY auto-onboard path reaches the identical picker. Selections persist on the host (`<id>\t<apply_at>` per line, byte-stable), and propagate to **NEW sessions only** — sessions running at the moment of the toggle are left untouched (recreate one — `DELETE` then create via the API/app — to retrofit it).

The selector is reachable two ways:

- During onboarding (`sudo aisandboxctl onboard`), after the Claude pre-init step.
- Any time after via `sudo aisandboxctl reconfigure`. The reconfigure path renders only the selector with the current selection pre-filled — no other wizard step runs.

**Manifest-driven, version-bearing.** Capabilities are auto-discovered shell manifests at `devtools.d/<id>/manifest.sh` — adding one is dropping a directory, no selector/resolver edits. Each manifest's label embeds the **exact version** it will install (sourced from the same constants the install uses, so the label and the install can't drift), e.g. *"Java 21 (Temurin JDK 21.0.5+11)"* and *"Android SDK — platform-tools / build-tools 36.0.0 / android-36 (x86_64 emulator)"*.

**Dependencies.** Selecting a capability auto-selects its transitive dependencies (marked in the list); deselecting a capability that another selected one depends on prompts for confirmation and cascade-deselects on `y`. The committed selection is never internally inconsistent.

**Eager-at-spawn provisioning + PATH.** Toolchains are installed **at spawn** (not baked into the image), into the persisted `/workspace/environment-utilities/<id>/` cache, before the session is handed over — so the session is ready immediately and pays no first-use install delay. When Java/Android are enabled, their binaries resolve by bare name in **both** login (`sh -lc`) and non-login (`sh -c`) shells, with `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` set. The orchestration layer waits for an in-container readiness marker before reporting the session ready, and attach tolerates the provisioning window.

#### Capabilities

The catalog ships exactly three capabilities — all **default OFF** on a fresh install:

| id | what it installs |
|---|---|
| `dind` | rootless Docker-in-Docker daemon |
| `java` | Temurin JDK 21 (standalone) |
| `android` | Android SDK (cmdline-tools, platform-tools, build-tools, platform, x86_64 system image, emulator). **Depends on `java`; amd64-only** — shown disabled on other arches. See [Testing Android apps](#testing-android-apps-inside-the-sandbox). |

**`dind` — Docker-in-Docker (rootless)**

Lets code running inside a session start its own `docker` / `docker compose` commands without touching the host's Docker socket.

- **What you get.** When you enable DinD and respawn a session, `entrypoint.sh` calls `aisandbox-dind install` (one-time download of the rootless Docker static tarball from `download.docker.com` into `/workspace/environment-utilities/dind/`) and then `aisandbox-dind start` (boots a rootless `dockerd-rootless.sh` daemon in the background, exports `DOCKER_HOST=unix:///run/user/<uid>/docker.sock`, and persists that to `~/.profile`-sourced state so a fresh `tmux` window inherits it). `docker info`, `docker compose ls`, `docker run`, etc. then work inside the session — including the runtime path UC-24's diagnostic operations need.
- **Server-side setup stage (subuid delegation).** When DinD is selected, `setup.sh` runs a **server-side install stage** right after the capability selector (a new third stage, before any in-container provisioning) that idempotently delegates a `subuid`/`subgid` range to the session's runtime user in host files bind-mounted read-only over the session's `/etc/subuid` + `/etc/subgid`. This fixes rootless `dockerd` aborting with *"No subuid ranges found for user … (sandbox)"* on server-spawned sessions that run as an arbitrary uid (which the entrypoint registers under the fixed name `sandbox`) — `/etc` is read-only in-session with no `sudo`, so the delegation must be prepared at setup. The stage is idempotent (re-running setup is a no-op) and **hard-gates**: if it can't satisfy a prerequisite (e.g. host `/dev/fuse` is absent) it marks DinD unavailable, prints an actionable reason, and continues the rest of setup without aborting. It registers a **respawn** post-requisite that `setup.sh` prints in a consolidated "Post-setup steps" list at the end of the run; existing sessions keep their old mapping until respawned.
- **Verifying.** Inside the session, `aisandbox-dind doctor` prints the daemon + storage-driver + `/dev/fuse` + **`subuid`/`subgid` range** status. `aisandbox-dind selftest` brings up a one-service alpine container, runs `tmux -V` inside it, asserts the version string, and tears it back down. From the host, `sudo aisandboxctl reconfigure --doctor` (`--session <N>` to target one) execs the doctor command into each enumerated session.
- **Trust-boundary tradeoff (deliberate, opt-in).** The rootless daemon runs as the **non-root session user** with **no host-socket bind**, so it does **not** widen the host trust boundary — but it **does** widen what code inside a session can reach: the session can now launch and inspect containers, and the rootless-Docker bring-up requires `/dev/fuse` plus `apparmor:unconfined` + `seccomp:unconfined` on the session container. Project policy is "the container is the trust boundary"; enabling DinD is a deliberate, opt-in expansion of that boundary. The wizard surfaces the warning at the moment of selection, before commit.
- **UC-26 delivers an in-session rootless daemon; it does NOT deliver host-daemon visibility from inside the session.** `/var/run/docker.sock` is **never** mounted in. If you need to drive the host's Docker from a Claude session today, do it from the host, not from inside a sandbox.
- **First-use network.** The static rootless-Docker tarball is fetched from `download.docker.com` on first DinD-enabled start. The build + JVM-test lane (`./gradlew :android:lint`, `:android:test`, etc.) and the rest of the session never need network for this.
- **Isolated-workspace caveat.** `/workspace/environment-utilities/dind/` lives under the session's workspace bind mount. Sessions created with an **isolated** workspace have their own `environment-utilities/dind/` cache and re-download on first DinD-enabled use. Sessions sharing one workspace all reuse the single cache. Same trade-off as the UC-22 Android emulator cache.
- **When DinD is disabled (or skipped).** Spawned sessions are byte/behaviour-identical to today: no rootless daemon, no `/dev/fuse` device, no `docker-compose.dind.yml` override applied.

## Management server (quick-start)

The management server is an optional Java (21 LTS) Spring Boot service that exposes session operations — **list / create / delete / inspect** — plus **interactive tmux attach** over WebSocket-over-TLS, all on a single mTLS-gated port (default `12410`, bound to all interfaces). It lives under [`server/`](server/) with its own Gradle build and ships as a `.deb` package on every `server-v*` release.

Minimal install on a Linux host — **the full procedure, prerequisites, the `onboard` wizard, the at-rest security model, session-uid alignment, client lifecycle, endpoints, foot-guns, upgrade notes, and the developer build all live in [`server/README.md`](server/README.md)**:

```bash
# Download the latest server-v* .deb.
TAG="$(curl -fsSL https://api.github.com/repos/HaroldHormaechea/ai-sandbox/releases \
    | grep -oE '"tag_name":\s*"server-v[^"]+"' | head -1 | cut -d'"' -f4)"
VER="${TAG#server-v}"
curl -fsSL -o /tmp/ai-sandbox-server.deb \
    "https://github.com/HaroldHormaechea/ai-sandbox/releases/download/${TAG}/ai-sandbox-server_${VER}_amd64.deb"

# Install (apt resolves the package Depends). The post-install hook creates the
# runtime user + directory tree and offers to run onboarding from a terminal.
sudo apt install /tmp/ai-sandbox-server.deb

# Onboard (PKI + runtime user + secrets + Claude pre-init), authorize a client,
# then enable the unit.
sudo aisandboxctl onboard
sudo aisandboxctl client mint alice --pem --out /tmp/alice/
sudo systemctl enable --now ai-sandbox-server
```

The service starts on an empty allowlist but refuses every request (401) until a client cert is authorized — mint one as above, or enroll a device later with `aisandboxctl client invite`. Trust-surface analysis of the single mTLS-exempt path (`POST /v1/enrollment`) lives in [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) § "Enrollment trust boundary". See [`server/README.md`](server/README.md) for everything else.

## Android client (quick-start)

An optional native Android client (Kotlin + Jetpack Compose, Material 3 Expressive, `minSdk = 29`) drives the management server from a phone over mTLS. It lives under [`android/`](android/) and ships as a signed APK + AAB on every `android-vX.Y.Z` tag. Distribution is **sideload-only** — **two users, two devices**, never the Play Store; AC29 forbids any analytics / telemetry / crash-reporter SDK.

Build → sideload → enroll, in brief — **full build, signing, architecture, and foot-guns are in [`android/README.md`](android/README.md)**:

```bash
# 1. Build a signed release APK from the repo root (debug builds can't be sideloaded
#    over a partner's existing install — see android/README.md "Signing").
./gradlew :android:assembleRelease

# 2. Sideload it onto the device.
adb install -r android/build/outputs/apk/release/*.apk

# 3. On the server host, issue a single-use invite QR for the device.
sudo -u ai-sandbox-server aisandboxctl client invite alice-phone \
    --server-url https://your-host:12410 \
    --pki-dir /etc/ai-sandbox-server/pki
```

Scan the QR from the app's onboarding screen: it redeems the token via the server's mTLS-exempt `POST /v1/enrollment`, imports the returned PKCS#12 bundle into the Android KeyStore as **non-exportable**, and uses that key as its sole TLS client identity thereafter. Re-scanning replaces the identity (one server profile per device). See [`android/README.md`](android/README.md) for the full operator + developer guide, [`design/android-ui/`](design/android-ui/) for the visual spec, and [`server/README.md`](server/README.md#enroll-a-device--aisandboxctl-client-invite) for the `client invite` flags.

### Two ways to connect to a session (UC-37)

From the sessions list, **single-tap** a row to open the **structured conversation view** and **long-press** to open the **tmux/terminal view** — both drive the *same* live `claude` session in that container (there is no separate `claude -p` conversation, and `--remote-control` is not used):

- **Conversation view (single-tap)** — a chat-style front-end. Output is rendered from the running session's transcript as structured items (assistant text, thinking, tool use/results); questions (`AskUserQuestion`) and plan approvals render in a dedicated sheet with selectable options and a free-text "Other"; a local composer with full IME/autocorrect submits without per-keystroke lag (no raw PTY is rendered); a spinner reflects the turn lifecycle; the agent switcher carries over. The protocol is documented in [`server/CONVERSATION_PROTOCOL.md`](server/CONVERSATION_PROTOCOL.md).
- **Terminal view (long-press)** — the full-fidelity tmux terminal. Text input defaults to a **decoupled composer** (a native text field with full IME/autocorrect and local echo that sends the finished line to the PTY in one shot, so there's no per-keystroke lag and no autocorrect mangling); a per-view toggle flips to **raw passthrough** — the historical per-keystroke Termux input — for interactive TUIs that need live keystrokes (slash menus, arbitrary sub-modes). The on-screen modifier bar (Ctrl/Esc/arrows/Tab/…) is available in both modes.

Anything done in one view is reflected in the other, since both attach to the one tmux session.

## Known foot-guns

The default shared-workspace + shared-claude-config layout trades safety for ergonomics. Nothing in the code prevents the following — be aware:

- **Concurrent file edits across sessions sharing the workspace.** Two sessions editing the same file in a shared workspace race on writes; the loser is silently overwritten. Two sessions running `git checkout` on the same repo can leave the working tree in an inconsistent state. If you need this isolation, create the session with an **isolated workspace** (a flag on the create-session request / a toggle in the Android app).
- **Concurrent writes to shared `claude-config` state.** `~/.claude/settings.json` and various hook-state files in a shared Claude config are not designed to be concurrently mutated by multiple Claude processes. Settings updates from one session can clobber another's; hook state can desync. If you need isolation, create the session with an **isolated Claude config** (you'll re-`/login` once inside it). (Conversation transcripts under `~/.claude/projects/` are the exception — they are **always** isolated per session via a dedicated `claude-projects-<N>/` bind mount, even under a shared Claude config, so one session's chat never bleeds into another's view.)
- **Concurrent `git push` races against the same remote branch.** Every session uses the same `git-key`, so to your git host they all look like the same author. Two sessions pushing to the same branch will hit a non-fast-forward error on whichever one loses the race; the operator (you) has to resolve.

## How it works

Claude is launched with `--dangerously-skip-permissions`, which disables every permission prompt — file writes, bash commands, network calls, all run without asking. This is safe *only* because the container itself is the trust boundary: Claude is confined to a non-root user inside the Linux (Debian/glibc) container, with no access to your host beyond the explicit bind mounts (`workspace/` or `workspace-<N>/`, `claude-config/` or `claude-config-<N>/`, the always-per-session `claude-projects-<N>/` transcript store, and the read-only `secrets/` folder).

All git operations are expected to go over SSH; no HTTPS-specific configuration (custom CA cert, credential helper) is set up. `gh` is configured to use SSH for `git_protocol`, so `gh repo clone OWNER/REPO` works the same way as a plain `git clone git@github.com:OWNER/REPO.git`. If `secrets/gh-token` is present (captured during onboarding), the entrypoint also runs `gh auth login --with-token` so `gh`'s API operations work — those still go to `api.github.com` over HTTPS via the system CA bundle.

On boot, an entrypoint script copies the mounted SSH key into `~/.ssh/`, fixes its permissions (SSH refuses world-readable keys), writes an SSH config that pins the key to all hosts, then clones the bootstrap project if it isn't already there. With no command passed, it starts a [`tmux`](https://github.com/tmux/tmux) session named `main` running Claude with the project directory as its working directory, and keeps the container alive with `tail -f /dev/null`. That tmux setup is what makes the detach/reattach workflow possible: Claude is never bound to your terminal, so disconnecting your client doesn't kill it. The orchestration layer enumerates running `ai-sandbox-*` projects via `docker compose ls --format json` (parsed with `jq`) so the server can list them and attach to the right one's `main` window.

The same entrypoint also supports a one-off mode (used by onboarding's Claude pre-init step): when given a command like `claude --dangerously-skip-permissions`, it runs the bootstrap and then `exec`'s that command instead of starting tmux. This is how onboarding pre-handles `/login`, the trust dialog, and the bypass-permissions warning — the dialogs fire in a disposable container, but Claude's state is captured into the pre-init template, so the persistent sessions inherit the accepted state.

Anything Claude can reach — your workspace files, the network, the SSH key (and therefore your git account), any credentials checked into a repo you cloned in — it can also modify or exfiltrate. The autonomous mode trades safety prompts for throughput; treat the workspace folder as "the agent could see and change this."

Build-time, the image fetches three things from the network alongside Debian `apt` packages (plus the GitHub CLI apt repo for `gh`) and the npm install of `@anthropic-ai/claude-code` (pinned — see below): the pinned `gitleaks` release tarball, the latest `rtk` release tarball (see below), and the apt package indexes. All three widen the supply-chain surface to the same degree — no checksum verification is currently done for any of them. Treat upstream compromise of those projects as in scope when you reason about what an attacker could land inside the container at build time.

### Claude Code version pin

`@anthropic-ai/claude-code` is **pinned** to a specific, gate-verified version (currently **`2.1.169`**) via the `CLAUDE_CODE_VERSION` build arg in `SandboxDockerfile`. It used to install rolling-latest, but ai-sandbox scrapes Claude Code's terminal pane and transcript to surface pending `AskUserQuestion` prompts in the Android app, and an unpinned upgrade would silently drift that TUI/transcript shape out from under the scraper — breaking question delivery on every release that changed the shape (UC-50, UC-97).

**The only lever that changes a sandbox's Claude Code version is an image rebuild** (`docker compose build`, e.g. via `aisandboxctl onboard --rebuild-image` or a server `.deb` upgrade that rebuilds the image). The management-server self-updater (`ai-sandbox-updater.sh`) updates the `ai-sandbox-server` `.deb` **only** — it never runs npm and never touches Claude Code. So an already-running sandbox keeps its pinned version until you rebuild the image; there is no in-place Claude Code auto-update, by design.

Bumping the pin is a deliberate change gated by the UC-85 functional gate: pick the newest version that passes the gate's pending-question leg, update the build arg (and the matching in-repo references), recapture the pane-signal fixtures, and re-green the gate before shipping. Details in `server/CONVERSATION_PROTOCOL.md` § "Claude Code version pin & bump policy".

### Token compression (RTK)

The image bundles [`rtk` (Rust Token Killer)](https://github.com/rtk-ai/rtk), a CLI proxy that compresses Bash output before it reaches the LLM, reducing token spend on noisy commands. RTK is installed at `/usr/local/bin/rtk` from the [latest GitHub release](https://github.com/rtk-ai/rtk/releases/latest) at image build time (rolling-latest — unlike `@anthropic-ai/claude-code`, which is now pinned, and unlike the pinned-version `gitleaks` pattern; RTK has no TUI-shape contract with the scraper, so rolling it is safe). The resolved version is echoed during `docker compose build` so you can see what you got. Upstream is licensed under Apache-2.0 (per the repo's `LICENSE`); both Apache-2.0 and MIT are compatible with ai-sandbox's MIT redistribution.

`entrypoint.sh` runs `rtk init -g` on every container start (idempotently, after the `claude-config/` bind mount is in place), which wires RTK's Bash hook into `~/.claude/settings.json` so Claude's Bash tool calls are transparently rewritten to `rtk <cmd>`. To see how many tokens RTK has saved, run:

```bash
docker compose -p ai-sandbox-1 exec claude-sandbox rtk gain
```

**Important limitation — built-in tools bypass RTK.** Claude Code's built-in `Read`, `Grep`, and `Glob` tools do **not** route through Bash, so they bypass the RTK hook and bypass token compression. The entrypoint appends a directive to `~/.claude/CLAUDE.md` asking Claude to prefer `cat`, `rg`/`grep`, and `find` instead. That directive is a preference, not an enforcement — the agent can still call the built-ins. If you see surprisingly high token usage on file-heavy work, this is the likely cause.

### Secret-leak protection

The image installs [`gitleaks`](https://github.com/gitleaks/gitleaks) and configures a system-wide git pre-commit hook (`git config --system core.hooksPath /etc/git-hooks`) that scans staged changes for credentials before each commit. Any commit inside any cloned project (including ones Claude makes autonomously) gets scanned — if a key, token, or other secret is detected, the commit is aborted with a redacted preview of the match. This applies to every session: the hook fires inside every `ai-sandbox-<N>` container, regardless of whether the workspace is shared or isolated.

To allowlist false positives, drop a `.gitleaks.toml` in the repo root following the [gitleaks config format](https://github.com/gitleaks/gitleaks#configuration). To bypass for a single commit (use sparingly), `git commit --no-verify`.

**Contributing to this repo:** the same scan also runs on the host side via the [pre-commit](https://pre-commit.com/) framework — see `.pre-commit-config.yaml`. After cloning, run once:

```bash
pip install pre-commit
pre-commit install
```

`pre-commit` auto-fetches the pinned gitleaks version into its own cache, so you don't need to install gitleaks separately on the host.
