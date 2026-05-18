# ai-sandbox

## Description

A self-contained Docker environment for running [Claude Code](https://docs.claude.com/en/docs/claude-code) as a fully autonomous agent. Claude runs inside an Alpine container with all permission prompts disabled, so it can read, write, and execute freely without interrupting you for approvals. The container is the sandbox — let Claude work, and detach/reattach to its session whenever you want.

You can run more than one of these at a time. Each session is its own Docker Compose project named `ai-sandbox-<N>`, with a tmux window named `main` inside the container. Sessions can share the host workspace and Claude config (default — fast and zero-friction) or run on their own isolated copies (opt-in via flags on `spawn.sh`).

## How to use

### First-time setup

Run the guided setup walkthrough:

```bash
./setup.sh         # Linux / macOS
.\setup.ps1        # Windows
```

It steps you through:

1. **SSH key** — copies your private key to `secrets/git-key` (or confirms it's already there). Used for git clone/push.
2. **Git identity** — sets the `user.name` / `user.email` recorded on every commit Claude makes. Detects defaults from your host `git config --global` (and the SSH key's `.pub` comment as a secondary hint), prompts to confirm or override, writes `secrets/gitconfig`. The container applies it at boot via `git config --global include.path`, so it survives `clean.sh` and image rebuilds (the file lives on the host).
3. **Container image** — builds `ai-context:latest` if needed.
4. **`gh` login (optional)** — launches a disposable container that runs `gh auth login` and writes the resulting token to `secrets/gh-token`. Skip if you don't need `gh issue` / `gh pr` etc.
5. **Claude first-run** — launches Claude in a disposable container so you can do `/login`, accept the "trust this folder" prompt, and acknowledge the bypass-permissions warning. The state lives in `claude-config/` and persists, so the long-running daemon never asks again.
6. **First session** — initializes the gitignored `./.ai-sandbox-counter` (it holds the *last issued* N, so it starts at `0` and the first spawn produces `ai-sandbox-1`), takes down any leftover legacy unnumbered `ai-sandbox` container, and brings up `ai-sandbox-1` via `./spawn.sh --non-interactive`. Idempotent — re-running setup when an `ai-sandbox-*` project already exists logs "skipping spawn" and continues.

The script is idempotent — re-run it any time to re-authenticate, rebuild, or replay the Claude first-run.

#### Upgrading an existing install

If you cloned this repo before the git-identity step shipped, your `ai-context:latest` image's `entrypoint.sh` does NOT yet apply `secrets/gitconfig`. After re-running `setup.sh` / `setup.ps1` once to capture identity, accept the rebuild prompt at step 3 — the wizard now defaults to Y when `secrets/gitconfig` is present so the new entrypoint logic lands in your image automatically.

After setup completes, attach to Claude:

```bash
./attach.sh         # or .\attach.ps1 on Windows
```

You'll drop straight into your already-authenticated session. None of `secrets/`, `claude-config/`, `claude-config-*/`, `workspace/`, `workspace-*/`, or `.ai-sandbox-counter` is tracked by git.

### Spawning additional sessions

```bash
./spawn.sh         # Linux / macOS
.\spawn.ps1        # Windows
```

Reads `./.ai-sandbox-counter` (atomically incremented inside a per-repo lock at `./.ai-sandbox-counter.lock/`), then brings up `ai-sandbox-<N>` via `docker compose -p ai-sandbox-<N> up -d` against the existing `docker-compose.yml`. The counter is **monotonic** — it never decreases, never repeats a previously issued value, and is not rolled back if `docker compose up` fails.

Flags:

| Flag | Effect |
|---|---|
| `--isolated-workspace` | Mount `./workspace-<N>/` (auto-created) instead of the shared `./workspace/`. |
| `--shared-workspace` | Mount the shared `./workspace/` (default). |
| `--isolated-claude-config` | Mount `./claude-config-<N>/` (auto-created) instead of the shared `./claude-config/`. |
| `--shared-claude-config` | Mount the shared `./claude-config/` (default). |
| `--label <value>` | Set the `com.ai-sandbox.label` container label. Surfaced by `attach.sh` and by the UC03 management REST API. |
| `--non-interactive` | Never prompt; use defaults for any flag not explicitly set. Also engaged automatically when stdin is not a TTY. |
| `-h`, `--help` | Show usage. |

The `secrets/` mount is **always** shared (and always read-only) — one SSH key + one git identity + one optional `gh` token is the whole point of the wizard.

**Shared vs. isolated trade-offs:**

- **Shared workspace** (default) — every session sees the same `./workspace/`. Two sessions can collaborate on the same project, but they race on file edits and on git operations. Use this when you're running parallel agents on the same repo and you accept the coordination risk.
- **Isolated workspace** (`--isolated-workspace`) — `./workspace-<N>/` is a fresh, empty folder. Each session clones its own copy of whatever it wants. Use this when you want sessions to be independent.
- **Shared `claude-config`** (default) — every session reuses the same Anthropic auth, the same `~/.claude/projects/` history, the same `settings.json`. Quick to spin up, but concurrent writes to that folder can clobber each other (see "Known foot-guns" below).
- **Isolated `claude-config`** (`--isolated-claude-config`) — `./claude-config-<N>/` is a fresh folder. The very first time you start an isolated-config session, you'll need to `/login` again inside it (the auth lives there).

### Attaching to a session

```bash
./attach.sh         # Linux / macOS
.\attach.ps1        # Windows
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

Inside that shell you're in `/workspace` as the `claude` user, with the SSH key already configured. Anything you clone here also appears in `./workspace/` (or `./workspace-5/`, depending on the session's mount) on the host (and vice versa).

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
| `--keep-workspace` | Don't delete `./workspace-<N>/` even if it exists. |
| `--keep-claude-config` | Don't delete `./claude-config-<N>/` even if it exists. |
| `--non-interactive` | Never prompt. With no target, exits non-zero. Also engaged automatically when stdin is not a TTY. |
| `-h`, `--help` | Show usage. |

`clean.sh` **never** touches:

- the shared `./workspace/` folder,
- the shared `./claude-config/` folder,
- the read-only `./secrets/` folder,
- the monotonic `./.ai-sandbox-counter` file,
- the `ai-context:latest` Docker image.

It removes only per-session containers, per-session named volumes, and the per-session isolated host directories (`./workspace-<N>/`, `./claude-config-<N>/`) when they exist — i.e. only when that session opted into isolation via `spawn.sh`. The counter is **never** decremented or reset by any `clean` operation; after `./clean.sh --all`, the next `./spawn.sh` issues `N = max-issued-so-far + 1`.

For a **factory reset** — wipe every container, the image, all shared host state, and copied secrets — run the following manually:

```bash
./clean.sh --all
docker rmi ai-context:latest
rm -rf workspace/* claude-config/* secrets/git-key secrets/gh-token secrets/gitconfig
# Counter is left in place by design (monotonic across the project lifetime).
# If you really want to start session numbering from 1 again, remove it:
#   rm -f .ai-sandbox-counter
```

After that, `./setup.sh` starts everything fresh (rebuilds the image — slower).

## Remote management — the UC03 mTLS server

Sitting alongside the Bash/PowerShell kit is a Java (21 LTS) Spring Boot
service that exposes the same session operations — **list / spawn / kill
/ inspect** — plus **interactive tmux attach** over WebSocket-over-TLS,
all on a single mTLS-gated port (default `12410`, bound to all
interfaces). It lives under [`server/`](server/) with its own Gradle
build and ships as two fat jars.

### Prerequisites

- Host **OpenJDK 21+** at install time.
- Same Docker engine the UC02 scripts already use.
- A dedicated POSIX user `ai-sandbox-server` in the `docker` group.

### Install

```bash
# Create the runtime user.
sudo useradd -r -s /usr/sbin/nologin -G docker ai-sandbox-server

# Unpack the release zip (jars + OAS + sample config + systemd unit).
sudo install -d -m 0755 /opt/ai-sandbox-server /opt/ai-sandbox-server/lib
sudo install -d -m 0750 -o ai-sandbox-server -g ai-sandbox-server /var/log/ai-sandbox-server
sudo unzip ai-sandbox-server-*.zip -d /opt/ai-sandbox-server

# Generate server cert + key + empty allowlist + sample config.
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar pki init

# Walk through the container's pre-flight state: SSH key for git, git
# author identity, gh PAT, Claude pre-init. Every step has a CLI flag
# so the same command can run unassisted under Ansible / cloud-init —
# add `--no-gh` and/or `--no-claude-preinit` to opt out of optional
# steps. Re-run with `--force` to refresh credentials when they expire.
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar secrets seed

# Mint a client cert, then start the service.
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar client mint alice --out /tmp/alice/
sudo install -m 0644 /opt/ai-sandbox-server/systemd/ai-sandbox-server.service \
    /etc/systemd/system/ai-sandbox-server.service
sudo systemctl daemon-reload
sudo systemctl enable --now ai-sandbox-server
```

The unit refuses to start if any of the following is wrong: server key/cert
unreadable, allowlist folder empty (refuse-to-start policy), Docker socket
unreachable, UC02 scripts missing or non-executable, audit-log directory
missing or not writable.

#### What `secrets seed` captures, where, and the at-rest security model

| Step | Output | Mode | At-rest content |
|------|--------|------|----------------|
| SSH key | `/etc/ai-sandbox-server/secrets/git-key` | 0600 | **Decrypted** copy of the operator's private key — passphrase is stripped at install time so `entrypoint.sh` can hand the key to `gh` / `git` without an interactive prompt. The operator's source key on disk is never modified. |
| Git identity | `/etc/ai-sandbox-server/secrets/gitconfig` | 0600 | Plain INI with `user.name` + `user.email`. Bind-mounted RO into containers; `entrypoint.sh` wires it via `git config --global include.path`. |
| gh PAT | `/etc/ai-sandbox-server/secrets/gh-token` | 0600 | Single-line plaintext token captured by `gh auth login --web` inside an ephemeral container, written to the bind-mounted secrets dir. Skipped with `--no-gh`. |
| Claude pre-init | `/etc/ai-sandbox-server/templates/claude-config/` | 0750 | Snapshot of `~/.claude/` after one interactive OAuth session in an ephemeral container. RO bind-mounted into every spawned session at `/etc/claude-template/`; `entrypoint.sh` copies it into `~/.claude/` once per session (gated by `.seeded`). Skipped with `--no-claude-preinit`. |

All four outputs are owned by `ai-sandbox-server:ai-sandbox-server`. The whole tree lives under `/etc/ai-sandbox-server/` (mode 0750), so non-root users cannot read it; root and the management server's runtime user can. A subsequent operator can run `claude /login` inside any spawned session to override the seeded template for that session's lifetime — respawns lose the override (sessions are ephemeral).

**Unassisted install example** (Ansible-style, all flags supplied so nothing prompts):

```bash
sudo aisandboxctl secrets seed \
    --git-key /home/operator/.ssh/id_ed25519 \
    --git-name "Alice Operator" --git-email alice@example.com \
    --gh-token-file /tmp/gh-token \
    --no-claude-preinit   # or --claude-config-source <workstation-tarball>
```

##### Alternative for non-wizard deployments

The pre-UC06 manual-drop flow still works: after `pki init`, drop `git-key`, `gitconfig`, and optionally `gh-token` into `/etc/ai-sandbox-server/secrets/` by hand (mode 0600, owned `ai-sandbox-server`). Leave `/etc/ai-sandbox-server/templates/claude-config/` empty (or absent) and sessions will skip the Claude pre-init copy. Operators on this path lose the unattended-install story `secrets seed` provides but retain full control over how secrets land on disk.

### Client lifecycle

```bash
# PKCS#12 bundle (passphrase prompted at the TTY) — default.
aisandboxctl client mint alice --out /tmp/alice/

# PEM trio instead (alice.crt + alice.key + server.crt).
aisandboxctl client mint alice --pem --out /tmp/alice/

# Revoke. In-flight connections from that cert are torn down within ≤ 1s.
aisandboxctl client revoke alice

# List currently-allowed certs.
aisandboxctl client list
```

Mint always copies the public client cert into the allowlist folder
(`/etc/ai-sandbox-server/clients/`); the server's filesystem watcher
picks the change up within 250 ms.

### Endpoints

All mTLS-gated **except** `POST /v1/enrollment` (UC04), which exists so
the Android client can bootstrap mTLS in the first place. The
enrollment endpoint is single-use-token-gated + per-IP rate-limited;
trust-surface analysis lives in [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md)
§ "Enrollment trust boundary".

| Verb   | Path                              | Notes |
|--------|-----------------------------------|-------|
| GET    | `/v1/sessions`                    | Session list (running + starting + stopped, UC04 AC37). |
| POST   | `/v1/sessions`                    | Spawn (sync, 60 s timeout). |
| GET    | `/v1/sessions/{n}`                | Session detail. |
| DELETE | `/v1/sessions/{n}[?force=true]`   | Clean a session. |
| POST   | `/v1/enrollment`                  | **mTLS-exempt.** Single-use-token bootstrap; returns a P12 bundle (UC04). |
| GET    | `/v1/healthz`                     | 200 only when Docker, scripts, TLS are healthy. |
| GET    | `/v1/clients`                     | Allowlist listing. |
| POST   | `/v1/clients`                     | Add a cert. |
| DELETE | `/v1/clients/{cnOrFingerprint}`   | Remove a cert. |
| GET    | `/v1/openapi.yaml`                | springdoc-generated OAS (also committed at `server/openapi.yaml`; CI fails on drift). |
| GET    | `/v1/swagger-ui`                  | Swagger UI (strict CSP). |
| WSS    | `/v1/sessions/{n}/stream`         | Subprotocol `ai-sandbox.v1`. Schema in [`server/STREAM_PROTOCOL.md`](server/STREAM_PROTOCOL.md). |

### Foot-guns

- Binding to all interfaces by default means a misconfigured firewall
  could expose port 12410 to the public internet. Host-level firewalling
  is recommended even though mTLS is the gate.
- Plain-PEM server private key at mode 0600 is the at-rest protection.
  Future upgrade path (passphrase-protected key + systemd
  `EnvironmentFile=`) is documented in
  [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md).
- Docker socket access (via the `docker` group) is the privilege boundary
  that mTLS protects. Anyone with a valid client cert can spawn / kill /
  stream into containers and, via container escape, root the host.
- The same foot-guns inherited from UC02 (shared workspace / claude-config
  races, git push races) apply when sessions are driven through the
  management server, exactly as they do via the local shell scripts.

### Build (developer)

```bash
./gradlew :server:build           # compile + spotlessCheck + bootJar + ctl jar
./gradlew :server:generateOpenApiDocs  # regenerate the committed OAS
./gradlew :server:releaseBundle   # build/release/ai-sandbox-server-*.zip
```

## Android client — the UC04 phone app

A native Android client (Kotlin + Jetpack Compose, Material 3 Expressive,
`minSdk = 29`, sideload-only distribution) talks to the UC03 server.
Lives under [`android/`](android/) and ships as a signed APK + AAB on
every `android-vX.Y.Z` tag. **Two users, two devices** — this is not a
public app and never will be on the Play Store; AC29 forbids any
analytics / telemetry / crash-reporter SDK.

Per-device enrollment is QR-based:

```bash
# On the server host — issue a single-use 10-minute token + show its QR.
aisandboxctl client invite alice-phone \
    --server-url https://your-host:12410 \
    --pki-dir /etc/ai-sandbox-server/pki
# The ASCII QR prints to stdout. Scan from the app's onboarding screen.

# Or emit a 512x512 PNG for sharing over a side channel:
aisandboxctl client invite alice-phone \
    --server-url https://your-host:12410 \
    --pki-dir /etc/ai-sandbox-server/pki \
    --out /tmp/alice-phone-invite.png
```

The Android client scans the QR, POSTs the token to
`POST /v1/enrollment` (the single mTLS-exempt path on the server),
imports the returned PKCS#12 bundle into the Android KeyStore as
**non-exportable**, and uses that key as the sole TLS client identity
for every subsequent call. Re-scanning a QR replaces the existing
identity (one server profile at a time per device).

See [`android/README.md`](android/README.md) for operator + developer
quickstart, [`design/android-ui/`](design/android-ui/) for the visual
specification, and [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md)
§ "Enrollment trust boundary" for the trust-surface analysis of the
mTLS-exempt path.

### Android build (developer)

```bash
./gradlew :android:lint :android:test           # JVM-only checks
./gradlew :android:assembleDebug :android:bundleDebug   # debug APK + AAB
./gradlew :android:assembleRelease :android:bundleRelease  # release artefacts (needs signing config)
```

Signing config reads the env vars `KEYSTORE_FILE` / `KEYSTORE_PASSWORD`
/ `KEY_ALIAS` / `KEY_PASSWORD`, or falls back to `~/.gradle/keystore.jks`
when those are unset and the file exists. CI (`android-release.yml`)
decodes the keystore from a base64-encoded GitHub secret to tmpfs at
build time.

### Known foot-guns

The default shared-workspace + shared-claude-config layout trades safety for ergonomics. Nothing in the code prevents the following — be aware:

- **Concurrent file edits across sessions sharing `./workspace/`.** Two sessions editing the same file race on writes; the loser is silently overwritten. Two sessions running `git checkout` on the same repo can leave the working tree in an inconsistent state. If you need this isolation, spawn with `--isolated-workspace`.
- **Concurrent writes to `claude-config/` shared state.** `~/.claude/projects/`, `~/.claude/settings.json`, and various hook-state files inside `claude-config/` are not designed to be concurrently mutated by multiple Claude processes. Settings updates from one session can clobber another's; hook state can desync. If you need isolation, spawn with `--isolated-claude-config` (you'll re-`/login` once).
- **Concurrent `git push` races against the same remote branch.** Every session uses the same `secrets/git-key`, so to your git host they all look like the same author. Two sessions pushing to the same branch will hit a non-fast-forward error on whichever one loses the race; the operator (you) has to resolve.
- **Manually `rm`ing `.ai-sandbox-counter` while sessions exist.** The counter is the source of truth for "last issued N." If you remove it, the file is recreated at `0` on the next spawn — meaning the next `./spawn.sh` will issue `N = 1` and try to bring up `ai-sandbox-1`. If `ai-sandbox-1` already exists, `docker compose up -d` is a benign no-op and you end up reattaching to the existing one (not a fresh session). To restart numbering safely, run `./clean.sh --all` first.

## How it works

Claude is launched with `--dangerously-skip-permissions`, which disables every permission prompt — file writes, bash commands, network calls, all run without asking. This is safe *only* because the container itself is the trust boundary: Claude is confined to a non-root user inside Alpine, with no access to your host beyond the explicit bind mounts (`workspace/` or `workspace-<N>/`, `claude-config/` or `claude-config-<N>/`, and the read-only `secrets/` folder).

All git operations are expected to go over SSH; no HTTPS-specific configuration (custom CA cert, credential helper) is set up. `gh` is configured to use SSH for `git_protocol`, so `gh repo clone OWNER/REPO` works the same way as a plain `git clone git@github.com:OWNER/REPO.git`. If `secrets/gh-token` is present (created via the setup walkthrough), the entrypoint also runs `gh auth login --with-token` so `gh`'s API operations work — those still go to `api.github.com` over HTTPS via the system CA bundle.

On boot, an entrypoint script copies the mounted SSH key into `~/.ssh/`, fixes its permissions (SSH refuses world-readable keys), writes an SSH config that pins the key to all hosts, then clones the bootstrap project if it isn't already there. With no command passed, it starts a [`tmux`](https://github.com/tmux/tmux) session named `main` running Claude with the project directory as its working directory, and keeps the container alive with `tail -f /dev/null`. That tmux setup is what makes the detach/reattach workflow possible: Claude is never bound to your terminal, so disconnecting your client doesn't kill it. The `attach.sh` / `attach.ps1` scripts enumerate running `ai-sandbox-*` projects via `docker compose ls --format json` (parsed with `jq` on POSIX, `ConvertFrom-Json` on PowerShell) and either auto-attach, prompt, or error based on the count.

The same entrypoint also supports a one-off mode (used by setup step 5): when given a command like `claude --dangerously-skip-permissions`, it runs the bootstrap and then `exec`'s that command instead of starting tmux. This is how the wizard pre-handles `/login`, the trust dialog, and the bypass-permissions warning — the dialogs fire in a disposable container, but Claude's state is written to the bind-mounted `claude-config/`, so the persistent sessions inherit the accepted state.

Anything Claude can reach — your workspace files, the network, the SSH key (and therefore your git account), any credentials checked into a repo you cloned in — it can also modify or exfiltrate. The autonomous mode trades safety prompts for throughput; treat the workspace folder as "the agent could see and change this."

Build-time, the image fetches three things from the network alongside Alpine `apk` packages and the npm install of `@anthropic-ai/claude-code`: the pinned `gitleaks` release tarball, the latest `rtk` release tarball (see below), and the Alpine package index. All three widen the supply-chain surface to the same degree — no checksum verification is currently done for any of them. Treat upstream compromise of those projects as in scope when you reason about what an attacker could land inside the container at build time.

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
