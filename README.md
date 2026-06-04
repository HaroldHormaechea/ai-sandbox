# ai-sandbox

## Description

**ai-sandbox spawns sandboxed, free-running [Claude Code](https://docs.claude.com/en/docs/claude-code) sessions in disposable Linux containers to autonomously develop applications.** Each session runs Claude as a fully autonomous agent inside a Linux (Debian/glibc) container launched with `--dangerously-skip-permissions`, so it can read, write, and execute freely — no approval prompts — while the container itself is the trust boundary. Let Claude work, and detach/reattach to its session whenever you want.


You can run more than one of these at a time. Each session is its own Docker Compose project named `ai-sandbox-<N>`, with a tmux window named `main` inside the container. Sessions can share the host workspace and Claude config (default — fast and zero-friction) or run on their own isolated copies (opt-in via flags on `spawn.sh`).

> **Where the workspace lives.** In developer mode the host-side workspace and Claude config live **outside this repo** by default — under `$XDG_STATE_HOME/ai-sandbox` (i.e. `~/.local/state/ai-sandbox`). This is deliberate: keeping the workspace out of the working tree makes it structurally impossible for a stray `cp -a . workspace` to recurse into a freshly-created `workspace/` and fill your disk. See [Workspace location](#workspace-location) for the override and migration details.

## The three tiers

ai-sandbox is three cooperating layers. The container + orchestration layer is the core and is documented in full below; the management server and Android client are optional and each owns its own authoritative README.

- **Container + orchestration layer** — the Bash kit (`setup.sh` / `spawn.sh` / `attach.sh` / `clean.sh`) plus the `ai-context:latest` image and `docker-compose.yml` that spawn, attach to, and tear down disposable `ai-sandbox-<N>` sessions on a Linux host. This is the tier you install first; its full operation is documented in [How to use](#how-to-use) below.
- **Management server** (`server/`) — an optional Java 21 / Spring Boot service that exposes the same session operations (list / spawn / kill / inspect) plus interactive tmux attach over a single mTLS-gated port, so you can drive sessions from a remote workstation. Quick-start [below](#management-server-quick-start); full install + operation in [`server/README.md`](server/README.md).
- **Android client** (`android/`) — an optional native Kotlin/Compose phone app that talks to the management server over mTLS (sideload-only, two devices, no telemetry). Quick-start [below](#android-client-quick-start); full build + enroll detail in [`android/README.md`](android/README.md).

## How to use

### First-time setup

Run the guided setup walkthrough:

```bash
./setup.sh         # Linux (the project is Linux-only)
```

It steps you through:

1. **SSH key** — copies your private key to `secrets/git-key` (or confirms it's already there). Used for git clone/push.
2. **Git identity** — sets the `user.name` / `user.email` recorded on every commit Claude makes. Detects defaults from your host `git config --global` (and the SSH key's `.pub` comment as a secondary hint), prompts to confirm or override, writes `secrets/gitconfig`. The container applies it at boot via `git config --global include.path`, so it survives `clean.sh` and image rebuilds (the file lives on the host).
3. **Container image** — builds the `ai-context:latest` image if needed. Optional toolchains (Java, the Android SDK, Docker-in-Docker) are selected separately under [Development tools](#development-tools) and **provisioned at spawn, not baked into the image**; see also [Testing Android apps inside the sandbox](#testing-android-apps-inside-the-sandbox).
4. **`gh` login (optional)** — launches a disposable container that runs `gh auth login` and writes the resulting token to `secrets/gh-token`. Skip if you don't need `gh issue` / `gh pr` etc.
5. **Claude first-run** — launches Claude in a disposable container so you can do `/login`, accept the "trust this folder" prompt, and acknowledge the bypass-permissions warning. The state lives in the resolved `<root>/claude-config/` (see [Workspace location](#workspace-location)) and persists, so the long-running daemon never asks again. Before this step the wizard resolves `<root>` and, if it finds a populated in-repo workspace from before relocation, prompts once to migrate or keep it (the answer is persisted to `./.ai-sandbox-workspace-root`).
6. **First session** — initializes the gitignored `./.ai-sandbox-counter` (it holds the *last issued* N, so it starts at `0` and the first spawn produces `ai-sandbox-1`), takes down any leftover legacy unnumbered `ai-sandbox` container, and brings up `ai-sandbox-1` via `./spawn.sh --non-interactive`. Because `<root>` was already persisted in step 5, this non-interactive spawn never refuses. Idempotent — re-running setup when an `ai-sandbox-*` project already exists logs "skipping spawn" and continues.

The script is idempotent — re-run it any time to re-authenticate, rebuild, or replay the Claude first-run.

#### Upgrading an existing install

If you cloned this repo before the git-identity step shipped, your `ai-context:latest` image's `entrypoint.sh` does NOT yet apply `secrets/gitconfig`. After re-running `setup.sh` once to capture identity, accept the rebuild prompt at step 3 — the wizard now defaults to Y when `secrets/gitconfig` is present so the new entrypoint logic lands in your image automatically.

After setup completes, attach to Claude:

```bash
./attach.sh
```

You'll drop straight into your already-authenticated session. None of your runtime state is tracked by git: `secrets/` stays in the repo (gitignored apart from its `.gitkeep`), and the workspace + Claude config live entirely outside the repo by default (see [Workspace location](#workspace-location)). The in-repo `workspace/`, `workspace-*/`, `claude-config/`, `claude-config-*/`, and `.ai-sandbox-counter` paths remain gitignored for backward compatibility and for operators who deliberately opt back in to keeping the workspace in-tree.

### Testing Android apps inside the sandbox

Android is one of the opt-in **development tools** (see [Development tools](#development-tools) below) — enable **Android SDK** in the selector (`./setup.sh` Step 6 or `./setup.sh --reconfigure`) and respawn. It is **amd64-only** and **depends on Java** (the selector auto-selects Java for you). UC-27 changed how this works:

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

**Host KVM prerequisite.** `spawn.sh` automatically passes `--device /dev/kvm`
into the session when the **Android capability is enabled** in the ledger **and**
the host exposes `/dev/kvm` (the android manifest's spawn hook layers
`docker-compose.kvm.yml`). It also detects the host's `kvm` group GID and adds it as a
supplementary group on the container (`group_add`), so the in-container user can
actually *open* `/dev/kvm` — passing the device alone is not enough, because the
device node is group-owned and the runtime user would otherwise hit `EACCES`.
This works for both developer-mode and management-server-spawned sessions.
Verify on the host first:

```bash
ls -l /dev/kvm                                   # must exist; note its group
{ [ -r /dev/kvm ] && [ -w /dev/kvm ]; } && echo "readable+writable"
getent group kvm                                 # the GID spawn.sh passes through
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
session's `/workspace`. Sessions given an **isolated** workspace
(`spawn.sh --isolated-workspace`, or a management-server-assigned per-session
workspace path) therefore have their own `environment-utilities/` and
re-download the ~1.5 GB system image on their first emulator use. Sessions on
the shared `/workspace` (the default) all reuse the one cache. This is the
accepted trade-off of caching under the workspace bind mount.

### Development tools

`setup.sh` Step 6 — **"Select the development tools you want to install"** — is a **pure-shell raw-mode cursor checkbox selector** of opt-in capabilities the sandbox provisions into the per-session containers spawned by `spawn.sh`. One capability per line prefixed `[X]`/`[ ]`, with a highlighted cursor: use **↑/↓ (or the mouse wheel)** to move, **Space** to toggle, **Enter** to commit, **q/Esc** to cancel. It is pure shell (`read` + ANSI) — no `whiptail`/`dialog` dependency — so the `.deb` TTY auto-onboard path reaches the identical picker. Selections persist on the host in the gitignored `./.ai-sandbox-devtools` file (`<id>\t<apply_at>` per line, byte-stable), and propagate to **NEW sessions only** — sessions running at the moment of the toggle are left untouched (recycle one via `./clean.sh <N>` + `./spawn.sh` to retrofit it).

The selector is reachable two ways:

- During first-time setup, after the Claude pre-init step and before the first session is spawned.
- Any time after via `./setup.sh --reconfigure`. The reconfigure path renders only the selector with the current selection pre-filled — no other wizard step runs. (The Java install-time CLI exposes the same picker as `sudo aisandboxctl reconfigure`, which shells out to the very same selector.)

**Manifest-driven, version-bearing.** Capabilities are auto-discovered shell manifests at `devtools.d/<id>/manifest.sh` — adding one is dropping a directory, no selector/resolver edits. Each manifest's label embeds the **exact version** it will install (sourced from the same constants the install uses, so the label and the install can't drift), e.g. *"Java 21 (Temurin JDK 21.0.5+11)"* and *"Android SDK — platform-tools / build-tools 36.0.0 / android-36 (x86_64 emulator)"*.

**Dependencies.** Selecting a capability auto-selects its transitive dependencies (marked in the list); deselecting a capability that another selected one depends on prompts for confirmation and cascade-deselects on `y`. The committed selection is never internally inconsistent.

**Eager-at-spawn provisioning + PATH.** Toolchains are installed **at spawn** (not baked into the image), into the persisted `/workspace/environment-utilities/<id>/` cache, before the session is handed over — so the session is ready immediately and pays no first-use install delay. When Java/Android are enabled, their binaries resolve by bare name in **both** login (`sh -lc`) and non-login (`sh -c`) shells, with `JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` set. `spawn.sh` waits for an in-container readiness marker before reporting the session ready, and `attach.sh` tolerates the provisioning window.

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
- **Verifying.** Inside the session, `aisandbox-dind doctor` prints the daemon + storage-driver + `/dev/fuse` status. `aisandbox-dind selftest` brings up a one-service alpine container, runs `tmux -V` inside it, asserts the version string, and tears it back down. From the host, `sudo aisandboxctl reconfigure --doctor` (`--session <N>` to target one) execs the doctor command into each enumerated session.
- **Trust-boundary tradeoff (deliberate, opt-in).** The rootless daemon runs as the **non-root session user** with **no host-socket bind**, so it does **not** widen the host trust boundary — but it **does** widen what code inside a session can reach: the session can now launch and inspect containers, and the rootless-Docker bring-up requires `/dev/fuse` plus `apparmor:unconfined` + `seccomp:unconfined` on the session container. Project policy is "the container is the trust boundary"; enabling DinD is a deliberate, opt-in expansion of that boundary. The wizard surfaces the warning at the moment of selection, before commit.
- **UC-26 delivers an in-session rootless daemon; it does NOT deliver host-daemon visibility from inside the session.** `/var/run/docker.sock` is **never** mounted in. If you need to drive the host's Docker from a Claude session today, do it from the host, not from inside a sandbox.
- **First-use network.** The static rootless-Docker tarball is fetched from `download.docker.com` on first DinD-enabled start. The build + JVM-test lane (`./gradlew :android:lint`, `:android:test`, etc.) and the rest of the session never need network for this.
- **Isolated-workspace caveat.** `/workspace/environment-utilities/dind/` lives under the session's workspace bind mount. Sessions given an **isolated** workspace (`spawn.sh --isolated-workspace`, or a management-server-assigned per-session workspace path) have their own `environment-utilities/dind/` cache and re-download on first DinD-enabled use. Sessions on the shared `/workspace` (the default) all reuse the one cache. Same trade-off as the UC-22 Android emulator cache.
- **When DinD is disabled (or skipped).** Spawned sessions are byte/behaviour-identical to today: no rootless daemon, no `/dev/fuse` device, no `docker-compose.dind.yml` override applied.

### Workspace location

In developer mode, the host-side workspace and Claude config live **outside this repo by default**. The base directory (`<root>` below) is resolved with this precedence:

1. **Server pin (highest).** When the management server drives a spawn it sets `AI_SANDBOX_WORKSPACE_HOST_PATH` (the per-session bind source) or `AI_SANDBOX_HOST_STATE_ROOT` (the install-mode state root, e.g. `/var/lib/ai-sandbox-server/sessions`). Either one short-circuits everything below — the server owns the path and developer-mode relocation is not consulted. `AI_SANDBOX_WORKSPACE_HOST_PATH` names *one session's* bind-mount source directly; `AI_SANDBOX_DEV_WORKSPACE_ROOT` (below) names the developer-mode *base* under which `workspace/`, `workspace-<N>/`, and `claude-config-<N>/` are created — they operate at different levels and never both apply.
2. **Explicit operator override.** Set `AI_SANDBOX_DEV_WORKSPACE_ROOT=<dir>` and it is used verbatim as `<root>`. Use `AI_SANDBOX_DEV_WORKSPACE_ROOT=.` to deliberately keep the workspace inside the repo (the recorded in-repo opt-in — `spawn.sh` will warn but proceed).
3. **Persisted choice.** The first interactive `setup.sh` writes the chosen absolute `<root>` to the gitignored `./.ai-sandbox-workspace-root` (per-machine, mirroring `.ai-sandbox-counter`). `spawn.sh` and `clean.sh` both read this frozen value so they always agree on where sessions live.
4. **First-run default.** `$XDG_STATE_HOME/ai-sandbox` (i.e. `~/.local/state/ai-sandbox`).

So the shared workspace is `<root>/workspace`, an isolated session's is `<root>/workspace-<N>`, and per-session Claude configs are `<root>/claude-config-<N>`.

**Migration.** The first time you run `setup.sh` (or `spawn.sh` interactively) in a repo that still has a populated in-repo `./workspace` or any `./workspace-*/`, you are prompted once:

- **migrate** (default) — the directories are `mv`d to the new `<root>`, and `<root>` is persisted. A cross-filesystem `mv` (the state dir is often on a different mount than the repo) becomes a copy+delete and can be slow on a large tree; the script prints a progress line so it doesn't look hung.
- **keep** — the repo root is persisted as `<root>` (the in-repo opt-in), and nothing moves.

The prompt is shown **once** and the answer is frozen in `./.ai-sandbox-workspace-root`. A **non-interactive** `spawn.sh` that finds an unconfigured repo with a populated in-repo workspace **refuses to spawn** rather than silently migrating a multi-GB / live-git tree or silently keeping the unsafe path — it tells you to run `setup.sh` interactively or set `AI_SANDBOX_DEV_WORKSPACE_ROOT`. A fresh checkout (only `workspace/.gitkeep` present) is not "populated", so it takes the safe default with no prompt.

**Recursion guard.** Independently of where `<root>` lands, `spawn.sh` refuses to start if the resolved workspace **is**, **contains**, or **is an ancestor of** this repo's root — the exact shape that lets `cp -a . workspace` recurse and fill the disk. If the workspace is a strict descendant *inside* the repo (the deliberate `=.` opt-in), it warns instead of failing.

**Getting project source into a session.** A session's `/workspace` is host state, **not** a checkout of this repo. Bring code in the way you would into any container: `git clone` a repo from inside the session, `git archive | tar -x` a snapshot, or add your own bind mount. **Never** populate the workspace with a working-tree copy of this repo (`cp -a .` / `rsync .` from the repo root) — that is exactly the self-copy the relocation and recursion guard exist to prevent.

### Spawning additional sessions

```bash
./spawn.sh
```

Reads `./.ai-sandbox-counter` (atomically incremented inside a per-repo lock at `./.ai-sandbox-counter.lock/`), then brings up `ai-sandbox-<N>` via `docker compose -p ai-sandbox-<N> up -d` against the existing `docker-compose.yml`. The counter is **monotonic** — it never decreases, never repeats a previously issued value, and is not rolled back if `docker compose up` fails.

Flags:

| Flag | Effect |
|---|---|
| `--isolated-workspace` | Mount `<root>/workspace-<N>/` (auto-created) instead of the shared `<root>/workspace/`. |
| `--shared-workspace` | Mount the shared `<root>/workspace/` (default). |
| `--isolated-claude-config` | Mount `<root>/claude-config-<N>/` (auto-created) instead of the shared `<root>/claude-config/`. |
| `--shared-claude-config` | Mount the shared `<root>/claude-config/` (default). |
| `--label <value>` | Set the `com.ai-sandbox.label` container label. Surfaced by `attach.sh` and by the UC03 management REST API. |
| `--non-interactive` | Never prompt; use defaults for any flag not explicitly set. Also engaged automatically when stdin is not a TTY. |
| `-h`, `--help` | Show usage. |

The `secrets/` mount is **always** shared (and always read-only) — one SSH key + one git identity + one optional `gh` token is the whole point of the wizard.

**Shared vs. isolated trade-offs:**

- **Shared workspace** (default) — every session sees the same `<root>/workspace/` (see [Workspace location](#workspace-location) for `<root>`). Two sessions can collaborate on the same project, but they race on file edits and on git operations. Use this when you're running parallel agents on the same repo and you accept the coordination risk.
- **Isolated workspace** (`--isolated-workspace`) — `<root>/workspace-<N>/` is a fresh, empty folder. Each session clones its own copy of whatever it wants. Use this when you want sessions to be independent.
- **Shared `claude-config`** (default) — every session reuses the same Anthropic auth, the same `~/.claude/projects/` history, the same `settings.json`. Quick to spin up, but concurrent writes to that folder can clobber each other (see "Known foot-guns" below).
- **Isolated `claude-config`** (`--isolated-claude-config`) — `<root>/claude-config-<N>/` is a fresh folder. The very first time you start an isolated-config session, you'll need to `/login` again inside it (the auth lives there).

### Attaching to a session

```bash
./attach.sh
```

Behavior depends on how many `ai-sandbox-*` Compose projects are **running**:

- **None** — exits non-zero with a pointer at `./spawn.sh`.
- **Exactly one** — attaches directly, no prompt.
- **Multiple** — prints a numbered list like

  ```
  [1] ai-sandbox-3   refactoring auth   label=prod
  [2] ai-sandbox-5   (idle)
  [3] ai-sandbox-7   (unavailable)
  ```

  and prompts for a number (or `q` to quit). The third column is the current tmux window title of the `main` session inside that container; `(idle)` means the window is unnamed or showing a default shell, `(unavailable)` means the tmux probe failed (the container is up but not yet ready, or it crashed). `label=<value>` appears only when the session was started with `spawn.sh --label <value>`.

Pass `--session <N>` to attach to a specific session directly (required when stdin is not a TTY and more than one session is running):

```bash
./attach.sh --session 5
```

Detach with `Ctrl+B`, then `D`. Claude keeps running in the background — reattach any time with the same command and you'll see exactly where it left off.

### Opening a separate shell (e.g. to clone more repos)

To run shell commands inside a session without disturbing Claude's tmux window:

```bash
docker compose -p ai-sandbox-5 exec claude-sandbox sh
```

Inside that shell you're in `/workspace` as the `claude` user, with the SSH key already configured. Anything you clone here also appears in `<root>/workspace/` (or `<root>/workspace-5/`, depending on the session's mount) on the host — where `<root>` is the resolved workspace base (see [Workspace location](#workspace-location)) — and vice versa.

### Resetting (clean per-session, or factory-reset)

For per-session cleanup:

```bash
./clean.sh 5             # bring down ai-sandbox-5, remove its containers + volumes
./clean.sh --all         # same, for every ai-sandbox-* project (running + stopped)
./clean.sh               # interactive: lists running sessions, prompts for one or 'all'
```

Flags:

| Flag | Effect |
|---|---|
| `--session <N>` | Same as the positional `<N>` argument. |
| `--all` | Clean every `ai-sandbox-*` Compose project (mutually exclusive with `<N>`). |
| `--keep-workspace` | Don't delete `<root>/workspace-<N>/` even if it exists. |
| `--keep-claude-config` | Don't delete `<root>/claude-config-<N>/` even if it exists. |
| `--non-interactive` | Never prompt. With no target, exits non-zero. Also engaged automatically when stdin is not a TTY. |
| `-h`, `--help` | Show usage. |

`clean.sh` **never** touches:

- the shared `<root>/workspace/` folder,
- the shared `<root>/claude-config/` folder,
- the read-only `./secrets/` folder,
- the monotonic `./.ai-sandbox-counter` file,
- the persisted `./.ai-sandbox-workspace-root` (where `<root>` is recorded),
- the `ai-context:latest` Docker image.

`clean.sh` resolves the per-session directories under the same `<root>` that `spawn.sh` used (read from `./.ai-sandbox-workspace-root`, or the server's state root when management-server-driven — see [Workspace location](#workspace-location)). It removes only per-session containers, per-session named volumes, and the per-session isolated host directories (`<root>/workspace-<N>/`, `<root>/claude-config-<N>/`) when they exist — i.e. only when that session opted into isolation via `spawn.sh`. The counter is **never** decremented or reset by any `clean` operation; after `./clean.sh --all`, the next `./spawn.sh` issues `N = max-issued-so-far + 1`.

For a **factory reset** — wipe every container, the image, all shared host state, and copied secrets — run the following manually (`<root>` is the value in `./.ai-sandbox-workspace-root`, defaulting to `~/.local/state/ai-sandbox`):

```bash
./clean.sh --all
docker rmi ai-context:latest
rm -rf "$(cat .ai-sandbox-workspace-root 2>/dev/null || echo ~/.local/state/ai-sandbox)"/{workspace,workspace-*,claude-config,claude-config-*}
rm -f secrets/git-key secrets/gh-token secrets/gitconfig
# Counter is left in place by design (monotonic across the project lifetime).
# If you really want to start session numbering from 1 again, remove it:
#   rm -f .ai-sandbox-counter
# To also forget the persisted workspace location (re-prompts on next setup):
#   rm -f .ai-sandbox-workspace-root
```

After that, `./setup.sh` starts everything fresh (rebuilds the image — slower).

## Management server (quick-start)

Alongside the Bash kit is an optional Java (21 LTS) Spring Boot service that exposes the same session operations — **list / spawn / kill / inspect** — plus **interactive tmux attach** over WebSocket-over-TLS, all on a single mTLS-gated port (default `12410`, bound to all interfaces). It lives under [`server/`](server/) with its own Gradle build and ships as a self-contained release zip.

Minimal install on a Linux host — **the full procedure, prerequisites, the `onboard` wizard, the at-rest security model, session-uid alignment, client lifecycle, endpoints, foot-guns, upgrade notes, and the developer build all live in [`server/README.md`](server/README.md)**:

```bash
# Download the latest server-v* release zip and unpack it.
TAG="$(curl -fsSL https://api.github.com/repos/HaroldHormaechea/ai-sandbox/releases \
    | grep -oE '"tag_name":\s*"server-v[^"]+"' | head -1 | cut -d'"' -f4)"
VER="${TAG#server-v}"
curl -fsSL -o /tmp/ai-sandbox-server.zip \
    "https://github.com/HaroldHormaechea/ai-sandbox/releases/download/${TAG}/ai-sandbox-server-${VER}.zip"
sudo install -d /opt/ai-sandbox-server
sudo unzip /tmp/ai-sandbox-server.zip -d /opt/ai-sandbox-server
sudo ln -s /opt/ai-sandbox-server/bin/aisandboxctl /usr/local/bin/aisandboxctl

# Provision PKI + runtime user, seed container pre-flight state, authorize a client.
sudo aisandboxctl pki init
sudo aisandboxctl secrets seed        # or `aisandboxctl onboard` for the all-in-one wizard
sudo aisandboxctl client mint alice --out /tmp/alice/

# Install + start the systemd unit.
sudo install -m 0644 /opt/ai-sandbox-server/systemd/ai-sandbox-server.service \
    /etc/systemd/system/ai-sandbox-server.service
sudo systemctl daemon-reload
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

## Known foot-guns

The default shared-workspace + shared-claude-config layout trades safety for ergonomics. Nothing in the code prevents the following — be aware:

- **Concurrent file edits across sessions sharing the workspace.** Two sessions editing the same file in the shared `<root>/workspace/` race on writes; the loser is silently overwritten. Two sessions running `git checkout` on the same repo can leave the working tree in an inconsistent state. If you need this isolation, spawn with `--isolated-workspace`.
- **Concurrent writes to `claude-config/` shared state.** `~/.claude/projects/`, `~/.claude/settings.json`, and various hook-state files inside `claude-config/` are not designed to be concurrently mutated by multiple Claude processes. Settings updates from one session can clobber another's; hook state can desync. If you need isolation, spawn with `--isolated-claude-config` (you'll re-`/login` once).
- **Concurrent `git push` races against the same remote branch.** Every session uses the same `secrets/git-key`, so to your git host they all look like the same author. Two sessions pushing to the same branch will hit a non-fast-forward error on whichever one loses the race; the operator (you) has to resolve.
- **Manually `rm`ing `.ai-sandbox-counter` while sessions exist.** The counter is the source of truth for "last issued N." If you remove it, the file is recreated at `0` on the next spawn — meaning the next `./spawn.sh` will issue `N = 1` and try to bring up `ai-sandbox-1`. If `ai-sandbox-1` already exists, `docker compose up -d` is a benign no-op and you end up reattaching to the existing one (not a fresh session). To restart numbering safely, run `./clean.sh --all` first.

## How it works

Claude is launched with `--dangerously-skip-permissions`, which disables every permission prompt — file writes, bash commands, network calls, all run without asking. This is safe *only* because the container itself is the trust boundary: Claude is confined to a non-root user inside the Linux (Debian/glibc) container, with no access to your host beyond the explicit bind mounts (`workspace/` or `workspace-<N>/`, `claude-config/` or `claude-config-<N>/`, and the read-only `secrets/` folder).

All git operations are expected to go over SSH; no HTTPS-specific configuration (custom CA cert, credential helper) is set up. `gh` is configured to use SSH for `git_protocol`, so `gh repo clone OWNER/REPO` works the same way as a plain `git clone git@github.com:OWNER/REPO.git`. If `secrets/gh-token` is present (created via the setup walkthrough), the entrypoint also runs `gh auth login --with-token` so `gh`'s API operations work — those still go to `api.github.com` over HTTPS via the system CA bundle.

On boot, an entrypoint script copies the mounted SSH key into `~/.ssh/`, fixes its permissions (SSH refuses world-readable keys), writes an SSH config that pins the key to all hosts, then clones the bootstrap project if it isn't already there. With no command passed, it starts a [`tmux`](https://github.com/tmux/tmux) session named `main` running Claude with the project directory as its working directory, and keeps the container alive with `tail -f /dev/null`. That tmux setup is what makes the detach/reattach workflow possible: Claude is never bound to your terminal, so disconnecting your client doesn't kill it. The `attach.sh` script enumerates running `ai-sandbox-*` projects via `docker compose ls --format json` (parsed with `jq`) and either auto-attaches, prompts, or errors based on the count.

The same entrypoint also supports a one-off mode (used by setup step 5): when given a command like `claude --dangerously-skip-permissions`, it runs the bootstrap and then `exec`'s that command instead of starting tmux. This is how the wizard pre-handles `/login`, the trust dialog, and the bypass-permissions warning — the dialogs fire in a disposable container, but Claude's state is written to the bind-mounted `claude-config/`, so the persistent sessions inherit the accepted state.

Anything Claude can reach — your workspace files, the network, the SSH key (and therefore your git account), any credentials checked into a repo you cloned in — it can also modify or exfiltrate. The autonomous mode trades safety prompts for throughput; treat the workspace folder as "the agent could see and change this."

Build-time, the image fetches three things from the network alongside Debian `apt` packages (plus the GitHub CLI apt repo for `gh`) and the npm install of `@anthropic-ai/claude-code`: the pinned `gitleaks` release tarball, the latest `rtk` release tarball (see below), and the apt package indexes. All three widen the supply-chain surface to the same degree — no checksum verification is currently done for any of them. Treat upstream compromise of those projects as in scope when you reason about what an attacker could land inside the container at build time.

### Token compression (RTK)

The image bundles [`rtk` (Rust Token Killer)](https://github.com/rtk-ai/rtk), a CLI proxy that compresses Bash output before it reaches the LLM, reducing token spend on noisy commands. RTK is installed at `/usr/local/bin/rtk` from the [latest GitHub release](https://github.com/rtk-ai/rtk/releases/latest) at image build time (rolling-latest, matching the `@anthropic-ai/claude-code` pinning policy — not the pinned-version `gitleaks` pattern). The resolved version is echoed during `docker compose build` so you can see what you got. Upstream is licensed under Apache-2.0 (per the repo's `LICENSE`); both Apache-2.0 and MIT are compatible with ai-sandbox's MIT redistribution.

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
