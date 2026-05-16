# ai-sandbox management server (UC03)

mTLS-gated REST + WebSocket API for the ai-sandbox UC02 multi-session kit.
Lives in this `server/` subdirectory; build is fully independent of the
Bash/PowerShell + Docker orchestration scripts at the repo root.

## Audience

Operators running the ai-sandbox kit on a Linux host who want to drive
session lifecycle and attach to live tmux sessions from a remote workstation.

## Prerequisites

- Host **OpenJDK 21+** at install time.
- Docker engine reachable on `/var/run/docker.sock`.
- UC02's `spawn.sh`, `clean.sh`, `attach.sh` present and executable at
  the path declared by `ai-sandbox.server.hostscripts.repo-root`.
- A dedicated POSIX user `ai-sandbox-server` in the `docker` group.

## Install

```bash
# Create the runtime user.
sudo useradd -r -s /usr/sbin/nologin -G docker ai-sandbox-server

# Unpack the release zip (jars + OAS + sample config + systemd unit).
sudo install -d -m 0755 /opt/ai-sandbox-server /opt/ai-sandbox-server/lib
sudo install -d -m 0750 -o ai-sandbox-server -g ai-sandbox-server /var/log/ai-sandbox-server
sudo unzip ai-sandbox-server-*.zip -d /opt/ai-sandbox-server

sudo install -m 0644 /opt/ai-sandbox-server/sample-config.yaml /etc/ai-sandbox-server/config.yaml

# PKI bootstrap — generates server cert + key, makes the allowlist dir,
# and drops a sample config (idempotent; --force overwrites).
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar pki init

# systemd unit.
sudo install -m 0644 /opt/ai-sandbox-server/systemd/ai-sandbox-server.service \
    /etc/systemd/system/ai-sandbox-server.service
sudo systemctl daemon-reload
sudo systemctl enable --now ai-sandbox-server
```

The unit refuses to start (with a journald-logged reason) when any of:

- server key / cert unreadable
- allowlist folder empty (refuse-to-start policy)
- Docker socket unreachable
- UC02 scripts missing or non-executable
- audit-log directory missing or not writable

## Mint and revoke clients

```bash
# Mint a PKCS#12 bundle for a remote operator (passphrase prompted at the TTY).
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar client mint alice --out /tmp/alice/

# Mint a PEM trio instead.
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar client mint alice --pem --out /tmp/alice/

# The minted bundle includes:
#   alice.p12  (or alice.crt + alice.key with --pem)
#   server.crt           — for the client to pin
#   README.txt           — usage hint
# The public alice.crt is dropped into /etc/ai-sandbox-server/clients/
# automatically; the watcher picks it up within 1s.

# Revoke. Tears down in-flight connections from that cert.
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar client revoke alice

# List currently-allowed certs.
sudo java -jar /opt/ai-sandbox-server/lib/aisandboxctl.jar client list
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
