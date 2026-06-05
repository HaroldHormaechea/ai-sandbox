# ai-sandbox management server (UC03)

mTLS-gated REST + WebSocket API for the ai-sandbox UC02 multi-session kit.
It exposes the same session operations the host scripts do — **list / spawn /
kill / inspect** — plus **interactive tmux attach** over WebSocket-over-TLS,
all on a single mTLS-gated port (default `12410`, bound to all interfaces).
Lives in this `server/` subdirectory; build is fully independent of the
Bash + Docker orchestration scripts at the repo root, and it ships as two fat
jars (the Spring Boot server + the `aisandboxctl` CLI).

This README is **self-sufficient**: a Linux operator can install, onboard,
authorize clients, run, upgrade, and operate the server from this file alone.
The repo-root [`README.md`](../README.md) keeps only a minimal quick-start and
points here for the full procedure.

## Audience

Operators running the ai-sandbox kit on a Linux host who want to drive
session lifecycle and attach to live tmux sessions from a remote workstation.

## Prerequisites

- A **Docker engine + Compose v2 plugin** on the host — the server drives
  Docker to spawn sessions. `docker.io` is pulled in as a package dependency;
  add `docker-compose-plugin` too if it isn't already present.
- Everything else the server needs at runtime — **OpenJDK 21** (JRE),
  `openssh-client`, and `debconf` — is declared in the package's `Depends`
  and resolved automatically when you install the `.deb` with `apt`:

  ```bash
  sudo apt install ./ai-sandbox-server_<version>_amd64.deb
  ```

- A dedicated runtime user `ai-sandbox-server` in the `docker` group — the
  package install creates it for you (as does `pki init` / `onboard`); you do
  not create it by hand.

The `.deb` is the documented install path: drop it on a clean Ubuntu Server
VM with a Docker engine present, `apt install` resolves the rest, then run
`aisandboxctl onboard`.

## What's installed

`apt install ./ai-sandbox-server_<version>_amd64.deb` lays down
`/opt/ai-sandbox-server/lib/` (the two fat jars), `/opt/ai-sandbox-server/host/`
(the internal orchestration helpers the server invokes plus the container build
context), `/usr/bin/aisandboxctl` (the CLI wrapper, already on `PATH`), and the
systemd unit at `/lib/systemd/system/ai-sandbox-server.service`. No `git clone`
is required.

The server is path-locked to `/opt/ai-sandbox-server/` (read-only),
`/etc/ai-sandbox-server/` (RO), `/var/lib/ai-sandbox-server/` (RW),
`/var/log/ai-sandbox-server/` (RW). The one intentional exception to the
`/etc/ai-sandbox-server/` RO rule is `/etc/ai-sandbox-server/clients/`, the
allowlist directory the service writes to during `POST /v1/enrollment`
(UC04 / UC11 § AC1). The systemd unit carves it out via `ReadWritePaths=` so
the service can land freshly-minted client certs without losing the broader RO
sandbox on cert / key / config files.

The `/opt/ai-sandbox-server/` payload:

```
/opt/ai-sandbox-server/
├── lib/
│   ├── aisandbox-server.jar       # Spring Boot server (manifest carries Implementation-Version)
│   └── aisandboxctl.jar           # PKI / allowlist CLI
├── host/                          # internal orchestration helpers + container build context (frozen at release)
│   ├── docker-compose.yml
│   ├── SandboxDockerfile
│   └── entrypoint.sh
├── README.md
├── openapi.yaml                   # springdoc-generated OAS, committed in repo
├── STREAM_PROTOCOL.md
├── SESSIONS_EVENTS_PROTOCOL.md     # /v1/sessions/events live status-push framing (UC-32)
└── sample-config.yaml             # annotated reference of every tunable knob
```

The `host/` tree is **frozen at server-release time** and read-only at runtime
under the unit's hardening: fixes to the orchestration layer or the container
build context ship through the **next `server-v*` tag**, not by hand-editing
files under `/opt/ai-sandbox-server/host/` (hand-edits either fail or get
reverted on the next upgrade).

> The legacy release zip still ships on each `server-v*` release, but the
> `.deb` is the documented install path. The zip carries the same `/opt`
> payload plus a `bin/aisandboxctl` wrapper you symlink onto `PATH` yourself.

## Install

```bash
# 0. Resolve the latest server-v* tag and download the .deb.
#    The repo's GitHub Releases lives at
#    https://github.com/HaroldHormaechea/ai-sandbox/releases — pin a specific
#    tag if you need reproducibility.
TAG="$(curl -fsSL https://api.github.com/repos/HaroldHormaechea/ai-sandbox/releases \
    | grep -oE '"tag_name":\s*"server-v[^"]+"' | head -1 | cut -d'"' -f4)"
VER="${TAG#server-v}"
curl -fsSL -o /tmp/ai-sandbox-server.deb \
    "https://github.com/HaroldHormaechea/ai-sandbox/releases/download/${TAG}/ai-sandbox-server_${VER}_amd64.deb"

# 1. Install. apt resolves the package Depends (JRE, docker, openssh-client,
#    debconf). The post-install hook daemon-reloads systemd, creates the
#    ai-sandbox-server system user + operator-managed directory tree, and —
#    from a terminal — offers to run onboarding for you. It does NOT enable or
#    start the unit; that is step 4 below.
#    (Alternative: `sudo dpkg -i /tmp/ai-sandbox-server.deb` followed by
#    `sudo apt-get -f install` to pull the dependencies.)
sudo apt install /tmp/ai-sandbox-server.deb

# 2. Onboard — provisions the PKI + directory tree, the self-signed server
#    cert + key, the SSH key, git identity, an optional gh token, and (from a
#    terminal) a Claude pre-init snapshot. Already done if you accepted the
#    post-install invite; run it now otherwise. Granular equivalents:
#    `aisandboxctl pki init` then `aisandboxctl secrets seed`.
#    Re-run with `--force` to refresh creds. See "Out-of-box onboarding" below.
sudo aisandboxctl onboard

# 3. Authorize at least one client. The server starts fine on an empty
#    allowlist, but with clientAuth=OPTIONAL the mTLS enforcement filter
#    refuses every request (401) until a valid client cert is present —
#    so the service is up but unusable until you authorize someone. Mint
#    a cert here, or enroll a device later with `aisandboxctl client
#    invite <name>` (both hot-reload via the allowlist watcher, no
#    restart). Pick a name for the bootstrap operator; subsequent
#    clients are added the same way.
sudo aisandboxctl client mint bootstrap --pem --out /tmp/bootstrap

# 4. Enable + start the unit.
sudo systemctl enable --now ai-sandbox-server
```

**Pinning a specific version.** Production rollouts usually want a
known-good tag, not "latest". Replace the `TAG=` line above with the
tag you want — for example:

```bash
TAG=server-v0.0.3
VER="${TAG#server-v}"
```

The rest of step 0 stays identical. The GitHub Releases page (link
above) lists every `server-v*` tag chronologically.

The unit refuses to start (with a journald-logged reason) when any of:

- server key / cert unreadable
- Docker socket unreachable
- bundled orchestration helpers missing or non-executable under
  `/opt/ai-sandbox-server/host/`
- audit-log directory missing or not writable

An *empty* allowlist is **not** a startup failure — it is the intended
fresh-install state. The server starts and logs a warning; with
`clientAuth=OPTIONAL` every request is rejected (401) until you authorize
a client (`client mint` or `client invite`), which hot-reloads with no
restart.

## Out-of-box onboarding: the `aisandboxctl onboard` wizard

`aisandboxctl onboard` is a single, re-runnable wizard that gets a fresh
install to "spawned sessions just work". It composes `pki init` and
`secrets seed` behind one **per-component check**: it provisions the PKI +
directory tree, the SSH key, git identity, an optional gh token, and (from a
terminal) a Claude pre-init snapshot — but **only for the pieces that are
still missing**. Already-present artifacts are left untouched unless you pass
`--force`, so re-running is safe and idempotent.

```bash
# Interactive — prompts only for what's missing; builds the session image
# lazily the first time a Docker-using step (gh web login / Claude OAuth) runs.
sudo aisandboxctl onboard

# Unattended (Ansible / cloud-init) — every value supplied, nothing prompts.
# Either opt out of the Claude step (--no-claude-preinit) and capture it from a
# terminal later, or seed it zero-touch from a previously-captured claude-config
# template (see "Two Claude capture paths" below — NOT a raw ~/.claude/ dir):
sudo aisandboxctl onboard \
    --git-key /home/operator/.ssh/id_ed25519 \
    --git-name "Alice Operator" --git-email alice@example.com \
    --gh-token-file /tmp/gh-token \
    --claude-config-source /path/to/prebuilt-claude-config
```

**Two Claude capture paths.** The Claude pre-init snapshot — the state that
lets a spawned session start past Claude's first-run setup (theme,
completed-onboarding flag, signed-in account) — can be captured two ways:

- **Interactive device-flow login** (default): a one-time `claude` login in a
  throwaway container. Needs a terminal and a browser.
- **Zero-touch `--claude-config-source <dir>`**: copies a previously-captured
  **claude-config template** — the directory an interactive `aisandboxctl
  onboard` / `secrets seed` produces, with `.claude.json` **and**
  `.credentials.json` at its root — for headless / automated provisioning.
  This is **not** a raw `~/.claude/` directory: `~/.claude.json` (which holds
  the completed-onboarding flag + signed-in account) lives *outside*
  `~/.claude/`, so a bare copy omits it. To hand-assemble a source dir, copy
  your `~/.claude/` contents **and** your `~/.claude.json` (as `.claude.json`)
  into it. A source missing this state now **fails loud** (rather than silently
  seeding a session that still prompts); pass `--no-claude-preinit` if you
  deliberately want no Claude state.

Both produce a template that fully suppresses the in-container first-run
wizard, and both also enable Claude Code's **agent-teams + tmux teammate
backend** in each spawned session's `~/.claude/settings.json`
(`env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` plus `teammateMode: "tmux"`).

**`.deb` install auto-onboarding.** On a `.deb` install onboarding adapts to
whether a terminal is present:

- **From a terminal** — works under both `sudo dpkg -i *.deb` and `sudo apt
  install ./*.deb` — the post-install step offers a `[Y/n]` invite and, on
  yes, runs the **full interactive wizard**: the Claude device-flow login and
  the container-image build included, so sessions spawned afterward start at
  Claude's normal prompt. Any answers already collected by debconf (git
  name/email, SSH key path, gh token) prefill the wizard. Decline, and the
  install finishes with a single command to onboard later. The invite waits up
  to 30 seconds for an answer; if none arrives it defers like a headless
  install (printing the same onboard-later step), so an unattended `dpkg` can
  never stall on the prompt.
- **Headless / unattended** — noninteractive frontend, piped fds, or
  `ssh host 'sudo dpkg -i …'` — the install **never hangs or fails**: if
  onboarding was preseeded via debconf it runs non-interactively (skipping the
  TTY-only Claude login and the slow image build); otherwise it defers cleanly
  and prints exactly one next step. For a zero-touch Claude snapshot on a
  headless host, run `sudo aisandboxctl onboard --claude-config-source <dir>`
  afterward.

The captured gh token is wiped from the debconf database immediately after
install and never appears on the process table. Onboarding is idempotent: a
re-install with a Claude snapshot already captured does **not** re-prompt, and
an upgrade whose snapshot predates this release prints a one-line
`onboard --force` hint. Re-run `sudo aisandboxctl onboard --force` any time to
refresh credentials or re-capture the Claude snapshot.

## Session uid alignment

Session containers run as the **management server's runtime uid**, not the
image's baked-in `claude` user (uid 1000). The server injects
`AI_SANDBOX_RUN_AS_USER=<uid>:0` into `docker-compose.yml`'s `user:` field,
where `<uid>` is the numeric owner of the secrets dir — the same uid that
owns the 0600 `git-key`. This is the OpenShift "arbitrary-uid" recipe:

- The image's `$HOME` (`/home/claude`) and `/workspace` are owned by **group
  0** and made group-writable at build time, so a container running as
  `<uid>:0` can create `~/.ssh`, `~/.claude/*`, `~/.config/rtk`, etc.
- `/etc/passwd` is group-0-writable and `entrypoint.sh` appends a passwd line
  for its own uid on boot (idempotent), so `ssh` / `git` / `gh` resolve the
  user even when it has no pre-existing entry.
- The server pre-creates the per-session bind-mount source dirs
  (`workspace*/`, `claude-config*/`) as the server user **before** `compose
  up`, so Docker never auto-creates them `root`-owned.

The net effect: a session can write its mounted `~/.claude`, read the 0600
`git-key`, clone/commit over SSH, and inherit the Claude pre-init template —
with no post-install `chown` or secret-copying. Developer-mode runs leave
`AI_SANDBOX_RUN_AS_USER` unset, so `user:` falls back to the image's `claude`
user — byte-identical to the pre-UC-17 behaviour.

## What `secrets seed` captures, where, and the at-rest security model

| Step | Output | Mode | At-rest content |
|------|--------|------|----------------|
| SSH key | `/etc/ai-sandbox-server/secrets/git-key` | 0600 | **Decrypted** copy of the operator's private key — passphrase is stripped at install time so `entrypoint.sh` can hand the key to `gh` / `git` without an interactive prompt. The operator's source key on disk is never modified. |
| Git identity | `/etc/ai-sandbox-server/secrets/gitconfig` | 0600 | Plain INI with `user.name` + `user.email`. Bind-mounted RO into containers; `entrypoint.sh` wires it via `git config --global include.path`. |
| gh PAT | `/etc/ai-sandbox-server/secrets/gh-token` | 0600 | Single-line plaintext token captured by `gh auth login --web` inside an ephemeral container, written to the bind-mounted secrets dir. Skipped with `--no-gh`. |
| Claude pre-init | `/etc/ai-sandbox-server/templates/claude-config/` | 0750 | Snapshot of `~/.claude/` after one interactive OAuth session in an ephemeral container. RO bind-mounted into every spawned session at `/etc/claude-template/`; `entrypoint.sh` copies it into `~/.claude/` once per session (gated by `.seeded`). Skipped with `--no-claude-preinit`. |

All four outputs are owned by `ai-sandbox-server:ai-sandbox-server`. The whole
tree lives under `/etc/ai-sandbox-server/` (mode 0750), so non-root users
cannot read it; root and the management server's runtime user can. A
subsequent operator can run `claude /login` inside any spawned session to
override the seeded template for that session's lifetime — respawns lose the
override (sessions are ephemeral).

**Unassisted install example** (Ansible-style, all flags supplied so nothing prompts):

```bash
sudo aisandboxctl secrets seed \
    --git-key /home/operator/.ssh/id_ed25519 \
    --git-name "Alice Operator" --git-email alice@example.com \
    --gh-token-file /tmp/gh-token \
    --no-claude-preinit   # or --claude-config-source <captured-claude-config-dir>
```

### Alternative — manual secrets drop (pre-UC06 / non-wizard deployments)

The pre-UC06 manual-drop flow still works: after `pki init`, drop `git-key`,
`gitconfig`, and optionally `gh-token` into `/etc/ai-sandbox-server/secrets/`
by hand (mode 0600, owned `ai-sandbox-server`). Skip step 3 of the Install
flow and drop the files yourself:

```bash
# SSH key for git (required) + optional gh-token. Mode 0600 each,
# owned ai-sandbox-server.
sudo install -m 0600 -o ai-sandbox-server -g ai-sandbox-server \
    ~/.ssh/id_ed25519 /etc/ai-sandbox-server/secrets/git-key
# Optional: GitHub token used by gh CLI inside the sandbox.
# sudo install -m 0600 -o ai-sandbox-server -g ai-sandbox-server \
#     /path/to/gh-token.txt /etc/ai-sandbox-server/secrets/gh-token
```

Leave `/etc/ai-sandbox-server/templates/claude-config/` empty (or absent) and
sessions skip the Claude pre-init copy. The hand-drop path loses the
unassisted-install story `secrets seed` provides (and the optional Claude
pre-init template), but gives the operator full control over how secrets land
on disk. Either path is supported; pick whichever fits the deployment's
automation posture.

### Cleaning up an already-broken install (pre-UC-17)

UC-17 ships **fresh-install + new-session** behaviour only — it performs **no
migration** of installs that predate it (e.g. a server whose tree is owned by
the old uid, or that has root-owned per-session dirs Docker auto-created when
a bind-mount source was missing). This is experimental; no
backwards-compatibility is promised. Repair a broken install by hand:

```bash
# 1. Re-run onboarding to repair/refresh the server-owned tree + secrets.
sudo aisandboxctl onboard --force

# 2. Re-assert ownership of the operator-managed tree to the runtime user.
sudo chown -R ai-sandbox-server:ai-sandbox-server \
    /etc/ai-sandbox-server /var/lib/ai-sandbox-server

# 3. Remove root-owned per-session dirs Docker auto-created before the fix.
sudo rm -rf /var/lib/ai-sandbox-server/sessions/{workspace-*,claude-config-*}

# 4. Rebuild / re-pull ai-context:latest so it carries the UC-17 Dockerfile
#    changes ($HOME group-0-writable, /etc/passwd self-registration). The
#    first interactive `aisandboxctl onboard` (or a `docker compose build`)
#    rebuilds it.

# 5. Recreate any already-spawned sessions so they pick up the new `user:`
#    (DELETE then POST /v1/sessions via the API). Sessions launched before the
#    upgrade keep running as the old uid until they are recreated.
```

## Upgrade (v0.0.2 → v0.0.3 and onwards)

An upgrade is an `apt` reinstall of the newer `.deb`:

```bash
sudo apt install ./ai-sandbox-server_X.Y.Z_amd64.deb
sudo systemctl restart ai-sandbox-server
```

The package replaces the jars under `/opt/` and the systemd unit under
`/lib/systemd/system/`, and its post-install hook runs `systemctl
daemon-reload` for you. Restart the unit to pick up the new server.

Your operator-managed state (`/etc/ai-sandbox-server/{pki,clients,secrets,config.yaml}`
and `/var/lib/ai-sandbox-server/{sessions,enrollment}/`) is preserved
across the upgrade — only the install dir under `/opt/` is replaced. The
system user created by the original install keeps the same uid/gid.

### v0.0.6 → v0.0.7 — enrollment directory location moved

Fresh installs now place enrollment tokens at
`/var/lib/ai-sandbox-server/enrollment` (FHS-correct — tokens are
runtime state, not PKI material, so they belong under `/var/lib`
rather than `/etc`). **Existing installs are not auto-migrated**: an
`apt upgrade` preserves your `config.yaml` and the service keeps
using the old `/etc/ai-sandbox-server/enrollment` path. To adopt the
new path:

```bash
sudo aisandboxctl pki init --force
```

This rewrites `/etc/ai-sandbox-server/config.yaml` with the new path,
creates the new directory, and orphans the old one (safe to remove
manually after — `sudo rm -rf /etc/ai-sandbox-server/enrollment`).
Tokens at the old path have a 10-minute TTL by default, so the
cutover window is short either way — schedule the `pki init --force`
during a maintenance window when no Android enrollment is in flight.

The systemd unit (v0.0.12+) declares
`ReadWritePaths=/var/log/ai-sandbox-server /var/lib/ai-sandbox-server /etc/ai-sandbox-server/clients`,
so the new `/var/lib/ai-sandbox-server/enrollment/` location is already
writable, and the enrollment-time write of `/etc/ai-sandbox-server/clients/<name>.crt`
also goes through (the UC11 fix — earlier v0.0.11 unit files masked the
clients/ subdir as RO and the redemption path returned HTTP 500).

## Client lifecycle

**`pki init` is the only `aisandboxctl` subcommand that requires root.** All
other subcommands (`client mint`, `client invite`, `client list`,
`client revoke`) run as `ai-sandbox-server`, e.g.
`sudo -u ai-sandbox-server aisandboxctl client mint alice ...`.

### Mint and revoke operator clients

```bash
# Mint a PKCS#12 bundle for a remote operator (passphrase prompted at the TTY).
sudo aisandboxctl client mint alice --out /tmp/alice/

# Mint a PEM trio instead (alice.crt + alice.key + server.crt).
sudo aisandboxctl client mint alice --pem --out /tmp/alice/

# The minted bundle includes:
#   alice.p12  (or alice.crt + alice.key with --pem)
#   server.crt           — for the client to pin (SHA-256 of SPKI; the QR's pin field)
#   README.txt           — usage hint
# The public alice.crt is dropped into /etc/ai-sandbox-server/clients/
# automatically; the watcher picks it up within 1s.

# Revoke. In-flight connections from that cert are torn down within ≤ 1s.
sudo aisandboxctl client revoke alice

# List currently-allowed certs.
sudo aisandboxctl client list
```

### Enroll a device — `aisandboxctl client invite`

For the Android client (UC04), enrollment is QR-based instead of a hand-copied
bundle: `client invite` issues a single-use, 10-minute token, prints its QR,
and the phone redeems it over the mTLS-exempt `POST /v1/enrollment` endpoint.
Like `client mint`, an invite hot-reloads through the allowlist watcher with no
restart.

> The host portion of `--server-url` MUST appear in `server.crt`'s SAN
> list (UC10 § AC6/AC7) — the command refuses with exit 2 otherwise.
> Re-issue with `aisandboxctl pki init --force --san <tag>:<host>` if needed.

```bash
# Issue a single-use 10-minute token + show its QR on stdout.
# Scan it from the app's onboarding screen.
sudo -u ai-sandbox-server aisandboxctl client invite alice-phone \
    --server-url https://your-host:12410 \
    --pki-dir /etc/ai-sandbox-server/pki

# Or emit a 512x512 PNG for sharing over a side channel:
sudo -u ai-sandbox-server aisandboxctl client invite alice-phone \
    --server-url https://your-host:12410 \
    --pki-dir /etc/ai-sandbox-server/pki \
    --out /tmp/alice-phone-invite.png

# For CI / scripted use, `--json` emits machine-clean JSON on stdout
# (single line, no QR, no trailer); the operator-facing trailer goes to
# stderr. With `--json --out <path>`, the same JSON is also written to
# the file (NOT a PNG — `--json` suppresses QR generation entirely).
sudo -u ai-sandbox-server aisandboxctl client invite alice-phone \
    --server-url https://your-host:12410 \
    --pki-dir /etc/ai-sandbox-server/pki \
    --json > /tmp/invite.json
```

The Android client scans the QR, POSTs the token to `POST /v1/enrollment`,
imports the returned PKCS#12 bundle into the Android KeyStore as
**non-exportable**, and uses that key as its sole TLS client identity for every
subsequent call. See [`../android/README.md`](../android/README.md) for the
device side.

## API surface

All paths are **mTLS-gated except `POST /v1/enrollment`** (UC04), which exists
so the Android client can bootstrap mTLS in the first place. The enrollment
endpoint is single-use-token-gated + per-IP rate-limited; trust-surface
analysis lives in [`../docs/THREAT_MODEL.md`](../docs/THREAT_MODEL.md)
§ "Enrollment trust boundary".

| Verb   | Path                              | Notes |
|--------|-----------------------------------|-------|
| GET    | `/v1/sessions`                    | Session list (running + starting + stopped, UC04 AC37). |
| POST   | `/v1/sessions`                    | Spawn (sync, 60s timeout). |
| GET    | `/v1/sessions/{n}`                | Session detail. |
| DELETE | `/v1/sessions/{n}[?force=true]`   | Clean a session. |
| POST   | `/v1/enrollment`                  | **mTLS-exempt.** Single-use-token bootstrap; returns a P12 bundle (UC04). |
| GET    | `/v1/healthz`                     | 200 only if Docker, scripts, TLS are all healthy. |
| GET    | `/v1/clients`                     | Allowlist listing. |
| POST   | `/v1/clients`                     | Add a cert (`{name, certPem}`). |
| DELETE | `/v1/clients/{cnOrFingerprint}`   | Remove a cert. |
| GET    | `/v1/openapi.yaml`                | springdoc-generated OAS, committed to the repo. |
| GET    | `/v1/swagger-ui`                  | Swagger UI (strict CSP). |
| WSS    | `/v1/sessions/{n}/stream`         | Subprotocol `ai-sandbox.v1`. Schema in [STREAM_PROTOCOL.md](STREAM_PROTOCOL.md). |
| WSS    | `/v1/sessions/events`             | Subprotocol `ai-sandbox.v1`. Live sessions-list status push (UC-32). One-way server→client. Schema in [SESSIONS_EVENTS_PROTOCOL.md](SESSIONS_EVENTS_PROTOCOL.md). |

Errors come back as `application/problem+json` per RFC 9457 with a
machine-readable `code` (`session_not_found`, `spawn_timeout`,
`stream_cap_exceeded`, etc.).

## WebSocket framing (`/v1/sessions/{n}/stream`)

- Mandatory subprotocol: `Sec-WebSocket-Protocol: ai-sandbox.v1`.
- Binary frames: raw tty bytes (client → PTY stdin, server → PTY stdout).
- Text frames: JSON control messages (`resize`, `mouse`, `error`, `close`).
- Per-stream output buffer: 256 KiB. Overflow → ERROR text frame
  (`stream_overflow`) + WebSocket close 1009.
- Ping every 30s, pong timeout 15s → close 1001.

Full schema in [STREAM_PROTOCOL.md](STREAM_PROTOCOL.md).

## Live sessions-list push (`/v1/sessions/events`)

- Mandatory subprotocol: `Sec-WebSocket-Protocol: ai-sandbox.v1`. Same mTLS
  allowlist + 4401 cert-revocation behaviour as the terminal stream.
- One-way server→client text frames only: a `snapshot` (full list) on
  subscribe and reconnect, then coalesced `delta` (upsert/remove by `n`)
  frames as the server observes status changes.
- Driven by a subscriber-gated ~1 s server-side reconcile of the same
  enumeration `GET /v1/sessions` uses, so it catches out-of-band transitions
  (a container dying on its own) too. Worst-case push latency ≈ 2 s.
- Foreground-bound on the client and capped at 4 subscriptions per cert.

Full schema in [SESSIONS_EVENTS_PROTOCOL.md](SESSIONS_EVENTS_PROTOCOL.md).

## Operator notes & foot-guns

- The plain-PEM server private key at mode 0600 is the only at-rest
  protection. A passphrase-protected key + systemd `EnvironmentFile=` is the
  documented upgrade path in
  [`../docs/THREAT_MODEL.md`](../docs/THREAT_MODEL.md).
- Binding to all interfaces by default means a misconfigured firewall could
  expose port 12410 to a hostile network. Host-level firewalling is
  recommended even though mTLS is the gate.
- Docker socket access (via the `docker` group) is the privilege boundary that
  mTLS protects. Anyone with a valid client cert can spawn / kill / stream into
  containers that share the host's docker daemon and, via container escape,
  root the host.
- **The CI smoke test (`release-install-smoke` in `server-ci.yml`) is
  required-passing for PR merge.** The GitHub branch-protection setting that
  enforces this lives outside the workflow file; see the repository admin's
  branch-protection rules. Until that gate is in place, the job exists as a
  required-passing CI signal but does not gate merges.
- **Foot-guns inherited from the orchestration layer.** The monotonic session
  counter is not rolled back on spawn failure; the next spawn uses N+1. The
  shared `workspace` / `claude-config` layout is the default — mind concurrent
  file edits and git pushes from sibling sessions. See the repo-root
  [`README.md`](../README.md) § "Known foot-guns" for the full list.

## Build (developer)

```bash
./gradlew :server:build           # compile + spotlessCheck + bootJar + ctl jar
./gradlew :server:spotlessApply   # auto-format the source tree
./gradlew :server:generateOpenApiDocs  # regenerate the committed OAS
./gradlew :server:releaseBundle   # build/release/ai-sandbox-server-*.zip
./gradlew :server:debPackage      # build/distributions/ai-sandbox-server_<v>_amd64.deb
```

Two fat jars come out of `:server:build`:

- `build/libs/aisandbox-server-<v>.jar` — the Spring Boot server.
- `build/libs/aisandboxctl-<v>.jar`     — the PKI / allowlist CLI.

Tests are split into `:server:test` (unit; runs by default) and
`:server:integrationTest` (Testcontainers; auto-skipped unless the
environment variable `AI_SANDBOX_DIND=1` is set — required because the
WebSocket integration suite needs a live Docker socket).
