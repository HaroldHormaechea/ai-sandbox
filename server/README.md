# ai-sandbox management server (UC03)

mTLS-gated REST + WebSocket API for the ai-sandbox UC02 multi-session kit.
Lives in this `server/` subdirectory; build is fully independent of the
Bash/PowerShell + Docker orchestration scripts at the repo root.

## Audience

Operators running the ai-sandbox kit on a Linux host who want to drive
session lifecycle and attach to live tmux sessions from a remote workstation.

## Prerequisites

The release zip is **self-contained** (UC05) — drop it on a clean Ubuntu
Server VM with only the following installed, then run `pki init`:

- **OpenJDK 21+** (`openjdk-21-jdk-headless` on Ubuntu).
- **Docker engine + Compose plugin** (`docker.io` + `docker-compose-plugin`).
- **`curl` + `unzip`** for the download + unpack steps below. Usually
  already present on Ubuntu Server but not guaranteed on minimal images.

One-line install on a fresh Ubuntu Server VM:

```bash
sudo apt update && sudo apt install -y \
    openjdk-21-jdk-headless docker.io docker-compose-plugin curl unzip
```

The release zip bundles the UC02 host scripts (`spawn.sh` / `clean.sh` /
`attach.sh` / `lib.sh` / `setup.sh` + PowerShell counterparts) and the
container build context (`docker-compose.yml`, `SandboxDockerfile`,
`entrypoint.sh`). No `git clone` is required; the server is path-locked
to `/opt/ai-sandbox-server/` (read-only), `/etc/ai-sandbox-server/` (RO),
`/var/lib/ai-sandbox-server/` (RW), `/var/log/ai-sandbox-server/` (RW).
The one intentional exception to the `/etc/ai-sandbox-server/` RO rule
is `/etc/ai-sandbox-server/clients/`, the allowlist directory the
service writes to during `POST /v1/enrollment` (UC04 / UC11 § AC1). The
systemd unit carves it out via `ReadWritePaths=` so the service can
land freshly-minted client certs without losing the broader RO sandbox
on cert / key / config files.

## What's in the bundle

After `unzip ai-sandbox-server-X.Y.Z.zip -d /opt/ai-sandbox-server/` the
layout is:

```
/opt/ai-sandbox-server/
├── lib/
│   ├── aisandbox-server.jar       # Spring Boot server (manifest carries Implementation-Version)
│   └── aisandboxctl.jar           # PKI / allowlist CLI
├── bin/
│   └── aisandboxctl               # POSIX shell wrapper around aisandboxctl.jar — symlink onto PATH (see Install § step 1b)
├── host/                          # UC02 host-script bundle (frozen at release)
│   ├── spawn.sh   clean.sh   attach.sh   lib.sh   setup.sh
│   ├── spawn.ps1  clean.ps1  attach.ps1  lib.ps1  setup.ps1
│   ├── docker-compose.yml
│   ├── SandboxDockerfile
│   └── entrypoint.sh
├── systemd/
│   └── ai-sandbox-server.service
├── README.md
├── openapi.yaml                   # springdoc-generated OAS, committed in repo
├── STREAM_PROTOCOL.md
└── sample-config.yaml             # annotated reference of every tunable knob
```

**A note on `setup.sh` / `setup.ps1`.** They are bundled for byte parity
with the repo (groundwork for a future Windows installer), but they
**must not** be run against the install dir. The wizards expect a writable
working directory and would fail / corrupt `/opt/ai-sandbox-server/host/`
on attempt. The systemd installer never invokes them.

## Install

```bash
# 0. Resolve the latest server-v* tag and download the release zip.
#    The repo's GitHub Releases lives at
#    https://github.com/HaroldHormaechea/ai-sandbox/releases — pin a specific
#    tag if you need reproducibility.
TAG="$(curl -fsSL https://api.github.com/repos/HaroldHormaechea/ai-sandbox/releases \
    | grep -oE '"tag_name":\s*"server-v[^"]+"' | head -1 | cut -d'"' -f4)"
VER="${TAG#server-v}"
curl -fsSL -o /tmp/ai-sandbox-server.zip \
    "https://github.com/HaroldHormaechea/ai-sandbox/releases/download/${TAG}/ai-sandbox-server-${VER}.zip"

# 1. Unpack the release zip.
sudo install -d /opt/ai-sandbox-server
sudo unzip /tmp/ai-sandbox-server.zip -d /opt/ai-sandbox-server

# 1b. Symlink the CLI wrapper onto PATH (zip-install only).
#     The .deb install path drops /usr/bin/aisandboxctl automatically;
#     the zip-install path leaves the wrapper at /opt/ai-sandbox-server/
#     bin/aisandboxctl (where it lands when you unzip) and asks the
#     operator to symlink it onto PATH. Either /usr/local/bin (the
#     POSIX convention for operator-installed binaries) or any other
#     directory on the service-user's PATH works.
#
#     Pre-v0.0.9 upgraders: if you had previously hand-rolled this
#     symlink against /opt/ai-sandbox-server/lib/aisandboxctl.jar via
#     a wrapper script of your own, point the symlink at the bundled
#     wrapper instead and remove the old shim — `sudo ln -sf
#     /opt/ai-sandbox-server/bin/aisandboxctl /usr/local/bin/aisandboxctl`.
sudo ln -s /opt/ai-sandbox-server/bin/aisandboxctl /usr/local/bin/aisandboxctl

# 2. One-shot per-host setup — creates the ai-sandbox-server system user,
#    every operator-managed directory under /etc/ai-sandbox-server/,
#    /var/lib/ai-sandbox-server/sessions/, /var/log/ai-sandbox-server/,
#    mints the self-signed server cert + key, and writes
#    /etc/ai-sandbox-server/config.yaml with install-layout defaults
#    baked in.
#
#    Idempotent only with --force; refuses to overwrite by default.
sudo aisandboxctl pki init

# 3. Walk through the container's pre-flight state: SSH key for git,
#    git author identity, gh PAT, Claude pre-init. Every step has a
#    CLI flag so the same command can run unassisted under Ansible /
#    cloud-init — add `--no-gh` and/or `--no-claude-preinit` to opt
#    out of optional steps. Re-run with `--force` to refresh creds
#    when they expire. The Claude step captures two ways: an interactive
#    device-flow login (default, needs a terminal) or a zero-touch
#    `--claude-config-source <dir>` seed from a pre-built ~/.claude/ tree
#    for headless hosts; both also enable the agent-teams + tmux backend.
#    See the top-level README for the full at-rest security model, the
#    .deb auto-onboarding behavior, and an unassisted-install flag example.
sudo aisandboxctl secrets seed

# 4. Authorize at least one client. The server starts fine on an empty
#    allowlist, but with clientAuth=OPTIONAL the mTLS enforcement filter
#    refuses every request (401) until a valid client cert is present —
#    so the service is up but unusable until you authorize someone. Mint
#    a cert here, or enroll a device later with `aisandboxctl client
#    invite <name>` (both hot-reload via the allowlist watcher, no
#    restart). Pick a name for the bootstrap operator; subsequent
#    clients are added the same way.
sudo aisandboxctl client mint bootstrap --pem --out /tmp/bootstrap

# 5. systemd unit.
sudo install -m 0644 /opt/ai-sandbox-server/systemd/ai-sandbox-server.service \
    /etc/systemd/system/ai-sandbox-server.service
sudo systemctl daemon-reload
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

**Alternative — manual secrets drop (pre-UC06 / non-wizard
deployments).** If you'd rather hand-drop the secrets after `pki init`
instead of running `secrets seed`, the pre-wizard flow still works.
Skip step 3 above and drop the files yourself:

```bash
# SSH key for git (required) + optional gh-token. Mode 0600 each,
# owned ai-sandbox-server.
sudo install -m 0600 -o ai-sandbox-server -g ai-sandbox-server \
    ~/.ssh/id_ed25519 /etc/ai-sandbox-server/secrets/git-key
# Optional: GitHub token used by gh CLI inside the sandbox.
# sudo install -m 0600 -o ai-sandbox-server -g ai-sandbox-server \
#     /path/to/gh-token.txt /etc/ai-sandbox-server/secrets/gh-token
```

The hand-drop path loses the unassisted-install story `secrets seed`
provides (and the optional Claude pre-init template), but gives the
operator full control over how secrets land on disk. Either path is
supported; pick whichever fits the deployment's automation posture.

The unit refuses to start (with a journald-logged reason) when any of:

- server key / cert unreadable
- Docker socket unreachable
- bundled host scripts missing or non-executable under
  `/opt/ai-sandbox-server/host/`
- audit-log directory missing or not writable

An *empty* allowlist is **not** a startup failure — it is the intended
fresh-install state. The server starts and logs a warning; with
`clientAuth=OPTIONAL` every request is rejected (401) until you authorize
a client (`client mint` or `client invite`), which hot-reloads with no
restart.

## Upgrade (v0.0.2 → v0.0.3 and onwards)

A v0.0.2 → v0.0.3 upgrade is a **clean cutover**. No backwards-compat
shim is provided; you are expected to stop the service, swap the jars,
and restart:

```bash
sudo systemctl stop ai-sandbox-server
sudo rm -rf /opt/ai-sandbox-server/lib /opt/ai-sandbox-server/host
sudo unzip ai-sandbox-server-X.Y.Z.zip -d /opt/ai-sandbox-server
sudo systemctl daemon-reload   # only if the systemd/ unit changed
sudo systemctl restart ai-sandbox-server
```

Your operator-managed state (`/etc/ai-sandbox-server/{pki,clients,secrets,config.yaml}`
and `/var/lib/ai-sandbox-server/{sessions,enrollment}/`) is preserved
across the swap — only the install dir under `/opt/` is replaced. The
system user created by the original `pki init` keeps the same uid/gid.

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

## Frozen UC02 host scripts

The `host/` bundle is **frozen at server-release time**. If you run into
a bug in `spawn.sh` / `clean.sh` / `attach.sh` / `lib.sh` / `setup.sh`
or in the container build context, it ships through the **next server
release tag** (`server-vX.Y.Z`), not by hand-editing files under
`/opt/ai-sandbox-server/host/`. The install dir is read-only at runtime
under the systemd unit's `ReadOnlyPaths` hardening; hand-edits would
either fail or get silently reverted on the next upgrade.

## Operator notes

- **`pki init` is the only `aisandboxctl` subcommand that requires
  root.** All other subcommands (`client mint`, `client invite`,
  `client list`, `client revoke`) run as `ai-sandbox-server`:
  `sudo -u ai-sandbox-server aisandboxctl client mint alice ...`.
- **`setup.sh` / `setup.ps1` are bundled but must not be invoked
  against the install dir** — see "What's in the bundle" above.
- **The CI smoke test (`release-install-smoke` in `server-ci.yml`) is
  required-passing for PR merge.** The GitHub branch-protection setting
  that enforces this lives outside the workflow file; see the repository
  admin's branch-protection rules. Until that gate is in place, the job
  exists as a required-passing CI signal but does not gate merges.

## Mint and revoke clients

```bash
# Mint a PKCS#12 bundle for a remote operator (passphrase prompted at the TTY).
sudo aisandboxctl client mint alice --out /tmp/alice/

# Mint a PEM trio instead.
sudo aisandboxctl client mint alice --pem --out /tmp/alice/

# The minted bundle includes:
#   alice.p12  (or alice.crt + alice.key with --pem)
#   server.crt           — for the client to pin (SHA-256 of SPKI; the QR's pin field)
#   README.txt           — usage hint
# The public alice.crt is dropped into /etc/ai-sandbox-server/clients/
# automatically; the watcher picks it up within 1s.

# Revoke. Tears down in-flight connections from that cert.
sudo aisandboxctl client revoke alice

# List currently-allowed certs.
sudo aisandboxctl client list
```

## API surface (mTLS-gated, all paths)

| Verb   | Path                              | Notes |
|--------|-----------------------------------|-------|
| GET    | `/v1/sessions`                    | Running session list. |
| POST   | `/v1/sessions`                    | Spawn (sync, 60s timeout). |
| GET    | `/v1/sessions/{n}`                | Session detail. |
| DELETE | `/v1/sessions/{n}[?force=true]`   | Clean a session. |
| GET    | `/v1/healthz`                     | 200 only if Docker, scripts, TLS are all healthy. |
| GET    | `/v1/clients`                     | Allowlist listing. |
| POST   | `/v1/clients`                     | Add a cert (`{name, certPem}`). |
| DELETE | `/v1/clients/{cnOrFingerprint}`   | Remove a cert. |
| GET    | `/v1/openapi.yaml`                | springdoc-generated OAS, committed to the repo. |
| GET    | `/v1/swagger-ui`                  | Swagger UI (strict CSP). |
| WSS    | `/v1/sessions/{n}/stream`         | Subprotocol `ai-sandbox.v1`. |

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

## Operator notes

- The plain-PEM server private key at mode 0600 is the only at-rest
  protection. A passphrase-protected key + systemd EnvironmentFile is the
  documented upgrade path in
  [`../docs/THREAT_MODEL.md`](../docs/THREAT_MODEL.md).
- Binding to all interfaces by default means a misconfigured firewall could
  expose port 12410 to a hostile network. Host-level firewalling is
  recommended even though mTLS is the gate.
- Docker socket membership (`docker` group) is a privilege boundary; anyone
  with a valid client cert can spawn / kill / stream into containers that
  share the host's docker daemon.

## Foot-guns inherited from UC02

- The monotonic session counter is not rolled back on spawn failure; the
  next spawn uses N+1.
- Shared `./workspace` / `./claude-config` are the default — mind concurrent
  git pushes from sibling sessions.

## Build (developer)

```bash
./gradlew :server:build           # compile + format-check + bootJar + ctl jar
./gradlew :server:spotlessApply   # auto-format the source tree
./gradlew :server:generateOpenApiDocs  # regenerate the committed OAS
./gradlew :server:releaseBundle   # build/release/ai-sandbox-server-*.zip
```

Two fat jars come out of `:server:build`:

- `build/libs/aisandbox-server-<v>.jar` — the Spring Boot server.
- `build/libs/aisandboxctl-<v>.jar`     — the PKI / allowlist CLI.

Tests are split into `:server:test` (unit; runs by default) and
`:server:integrationTest` (Testcontainers; auto-skipped unless the
environment variable `AI_SANDBOX_DIND=1` is set — required because the
WebSocket integration suite needs a live Docker socket).
