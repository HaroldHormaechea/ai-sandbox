# Use Case 03: mTLS-secured Java management server

## Summary

Add a long-running Java (21 LTS) **Spring Boot** server, installed as a `systemd` service on the host that already runs Docker and the UC02 multi-session scripts, that exposes a single TLS port — **port 12410**, bound to all interfaces by default — gated by **mutual TLS** against an explicit allowlist of client certificates. The same port serves both:

1. A **REST/HTTPS** management API under `/v1/*` (HTTP/2) for session lifecycle (list / spawn / kill / inspect) and runtime client-cert allowlist management (add / list / revoke). Every Docker-touching operation delegates to the UC02 host scripts — no Docker logic is duplicated in Java. Specification is **springdoc-generated OpenAPI 3.x**, committed to the repo as `server/openapi.yaml`, runtime-served at `/v1/openapi.yaml`, and verified-against-actual-routes in CI.
2. A **WebSocket** endpoint (`wss://host:12410/v1/sessions/{n}/stream`, mandatory subprotocol `ai-sandbox.v1`) that bridges a remote client to the target session's tmux. Binary WebSocket frames carry raw tty bytes; text frames carry JSON control messages (resize, mouse, error, close). Multiple clients can attach concurrently to the same session — each gets its own tmux session inside the container, linked to `main` (`tmux new-session -t main`), so individual window sizing doesn't disturb other clients.

Trust model is a **folder-of-allowed-client-certs (fingerprint allowlist)** at `/etc/ai-sandbox-server/clients/`; no CA needed. Server cert + key live under `/etc/ai-sandbox-server/pki/`; both the allowlist and the server cert hot-reload via filesystem watch (no restart). An `aisandboxctl` companion fat jar handles PKI init, client mint, and revoke; mint produces a self-contained per-client bundle (PKCS#12 + `server.crt` + short README; `--pem` flag for the trio shape) and writes the client's public cert into the allowlist folder. All valid client certs are equal-authority admins in this MVP; identity is logged per call (CN for display, SHA-256 fingerprint for audit). Revocation deletes from the allowlist; active streams from a revoked cert are torn down on the next watch reload.

The service runs as a dedicated non-root user `ai-sandbox-server` in the `docker` group. Logs are JSON Lines to journald; a separate `/var/log/ai-sandbox-server/audit.log` (app-managed daily rotation, 7-day retention, no size cap) carries the audit stream. Tests are JUnit 5 + Spring Boot Test (unit) + Testcontainers (integration); CI is GitHub Actions on PRs and merges to `main`, plus a tag-driven release pipeline that emits a single zip (jars + OAS + README) similar to `java-class-call-scanning`'s workflow. The Java codebase lives under `ai-sandbox/server/` in the existing repo, with its own Gradle build, independent of the shell scripts; the server requires host **OpenJDK 21+** at install time.

This use case covers the server only; a CLI client is deferred to a separate later use case. PROJECT_BRIEF.md needs a `/revise-brief` pass **before** `/develop` is invoked on this UC, to reflect the new stack (Java, Spring Boot, Gradle), the new deployment shape (a systemd service exposing a TLS port), the new test framework, the new CI, and the new threat-model document.

## Acceptance Criteria

### Build, packaging, install

1. The server is implemented in Java 21 LTS on Spring Boot (latest stable at implementation time), built with Gradle, in `ai-sandbox/server/`. Its build is independent of the shell scripts.
2. The build produces a **fat jar**; the server requires host OpenJDK 21+ at install time. A separate **`aisandboxctl` fat jar** is shipped alongside (mint/revoke/list/init subcommands).
3. A `systemd` unit file is delivered. Hardening directives are set: `NoNewPrivileges=true`, `ProtectSystem=strict`, `ProtectHome=true`, `PrivateTmp=true`, `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`. `Restart=on-failure` with `RestartSec=5s`, `StartLimitBurst=5`, `StartLimitIntervalSec=60s`. `WantedBy=multi-user.target`.
4. The service runs as a dedicated non-root system user `ai-sandbox-server`, member of the `docker` group, with read access to the ai-sandbox repo root.
5. Server configuration is YAML at `/etc/ai-sandbox-server/config.yaml`. PKI dir defaults to `/etc/ai-sandbox-server/pki/`; client allowlist dir defaults to `/etc/ai-sandbox-server/clients/`. All paths overridable via config.
6. The systemd unit fails to start (non-zero exit, journald log explains why) if any of: server key/cert unreadable, allowlist folder empty (refuse-to-start policy), Docker socket unreachable, ai-sandbox repo root missing UC02 scripts (`spawn.sh`, `attach.sh`, `clean.sh`).
7. `aisandboxctl pki init` is a one-shot helper that produces: a self-signed server cert + key in `/etc/ai-sandbox-server/pki/` (server cert subject defaults to `CN=ai-sandbox-server`, overridable via `--cn`); an empty `clients/` directory at mode 0750; a sample `config.yaml` at mode 0644.
8. The server's private key is stored as a plain PEM file at mode 0600, owned by `ai-sandbox-server`. The `docs/THREAT_MODEL.md` deliverable explicitly flags this for future-review (passphrase-protected key + EnvironmentFile passphrase as the planned upgrade path).

### TLS and authentication (non-negotiable)

9. The server listens on a **single TLS port** (default 12410, configurable), bound to all interfaces (`0.0.0.0` and `::`) by default. mTLS is the sole authentication gate — no plaintext fallback, no anonymous TLS, no opt-out.
10. TLS 1.3 only. Cipher allowlist is explicit, not JVM-default: `TLS_AES_256_GCM_SHA384`, `TLS_CHACHA20_POLY1305_SHA256`, `TLS_AES_128_GCM_SHA256`.
11. HTTP/2 over TLS (ALPN-negotiated) for REST traffic. WebSocket upgrade is supported on the same listener.
12. The client trust set is the contents of the allowlist folder: a connecting client is accepted iff its presented cert is byte-identical (or fingerprint-identical) to a cert file in the allowlist folder. The folder is watched via filesystem events; changes are picked up without server restart.
13. When a client cert is removed from the allowlist while that client holds an active connection (REST or WebSocket), the server tears down that connection on the next watch reload (≤ 1s).
14. Server cert + key paths are also filesystem-watched; replacing them swaps TLS material for new handshakes. In-flight TLS sessions continue under their original cert.
15. Each accepted client cert is logged on every request and stream open/close: CN (displayed) and SHA-256 fingerprint (full string) are both emitted. No private key material, no full PEM body, is ever logged.
16. Per-IP TCP rate limit before TLS handshake: default **10 new connections per 10s** and **10 concurrent connections** per source IP. Configurable. Trips return TCP-level rejection (no TLS handshake attempted).
17. All valid client certs are equal-authority admins for this MVP. Authorization differentiation (read-only certs, per-session ACLs) is out of scope.

### REST management API (`/v1/*`)

18. All endpoints require mTLS, including `/v1/healthz` and `/v1/openapi.yaml`. There are no anonymous paths.
19. Endpoints (finalised in the springdoc-generated OAS):
    - `GET /v1/sessions` — list running `ai-sandbox-*` sessions: N, label, tmux window title (or `(idle)`), uptime, active stream-client count.
    - `POST /v1/sessions` — spawn a new session. Body: optional `label`, `workspace_mode` (`shared`/`isolated`), `claude_config_mode` (`shared`/`isolated`). Sync; returns 201 with the assigned N when up. 504 if the configurable spawn timeout (default 60s) is hit.
    - `GET /v1/sessions/{n}` — detail: list fields plus per-session bind-mount paths and the list of connected client identities.
    - `DELETE /v1/sessions/{n}` — clean a single session. Refuses cleanly when active streams exist unless `force=true` is supplied. Per-N mutex serializes; a concurrent second caller gets 404 (`session_not_found`) once the first has cleaned.
    - `GET /v1/healthz` — returns 200 only when Docker is reachable, UC02 scripts are present and executable, and TLS materials are loaded.
    - `POST /v1/clients` — add a client cert to the allowlist. Body: `application/json` with `{"name": "...", "cert_pem": "..."}`. Writes `<name>.crt` into the allowlist folder; watch reload picks it up.
    - `GET /v1/clients` — list current allowlisted clients: name, CN, SHA-256 fingerprint, serial, added timestamp.
    - `DELETE /v1/clients/{cnOrFingerprint}` — remove from allowlist; resolves both CN and fingerprint to the corresponding file.
20. Request body size cap: **64 KiB** for all POST endpoints. Trips return 413 Payload Too Large.
21. Errors are returned as `application/problem+json` per **RFC 9457** with stable error codes (`session_not_found`, `spawn_timeout`, `spawn_cap_exceeded`, `stream_cap_exceeded`, `invalid_cert_pem`, etc.).
22. Security headers on every REST response: `Strict-Transport-Security: max-age=63072000`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`. Swagger UI gets a strict CSP allowing only its own assets.
23. The springdoc-generated OAS is served at `/v1/openapi.yaml`. Swagger UI is served at `/v1/swagger-ui` (still mTLS-gated). The same OAS is dumped at build time to `server/openapi.yaml` and committed to the repo; CI fails if the committed file drifts from the build-generated one.
24. Every Docker-touching operation in §19 (the `/v1/sessions/*` endpoints) is implemented by shelling out to the UC02 host scripts — `spawn.sh`, `clean.sh`, and an enumerate path using `docker compose ls` with a `com.docker.compose.project=ai-sandbox-*` filter. Argument-array exec only; no shell interpolation. Inputs (N, label, mode flags) are validated against an allowlist before exec.
25. `POST /v1/sessions` is serialized by a server-wide spawn mutex; concurrent calls execute one at a time. Per-N mutexes serialize per-session operations (DELETE, stream-open, attach state changes).
26. If `spawn.sh` exits non-zero, the server invokes `clean.sh <N>` as a best-effort cleanup before responding 500 with the captured stderr in a Problem-Details body. The monotonic counter still advances (N is consumed) — documented behavior inherited from UC02.

### WebSocket streaming endpoint (`/v1/sessions/{n}/stream`)

27. Endpoint URL: `wss://host:12410/v1/sessions/{n}/stream`. mTLS is required to complete the TLS handshake. The WebSocket upgrade requires `Sec-WebSocket-Protocol: ai-sandbox.v1`; missing or unrecognized subprotocol → 400.
28. Stream concurrency caps: per-stream idle timeout **2 hours** of no I/O; **10** concurrent streams per client cert; **100** concurrent streams server-wide. All configurable. Cap exceeded at upgrade time → 503 with Problem-Details body (`stream_cap_exceeded`); the WebSocket is never opened.
29. Max WebSocket message size: **256 KiB binary**, **16 KiB text**. Configurable.
30. Per-stream output buffer is bounded to **256 KiB**. On overflow, server emits an ERROR text frame (`code: stream_overflow`, RFC 9457 shape) and closes the WebSocket with close code 1009 (message too big).
31. Frame discipline:
    - **Binary frames** carry raw tty bytes the client writes to its terminal stdout, and that the server forwards from the tmux client's stdin.
    - **Text frames** carry JSON control messages: `{type: "resize", cols, rows}`, `{type: "mouse", ...}`, `{type: "error", ...RFC9457...}`, `{type: "close", reason}`. Schemas are documented in `server/STREAM_PROTOCOL.md`.
32. On stream open, the server runs `docker compose -p ai-sandbox-<N> exec -it claude-sandbox tmux new-session -d -s client-<streamId> -t main` (creating a per-client tmux session linked to `main`'s group) and pipes the client's WebSocket through that session via `tmux attach -t client-<streamId>`. On stream close, the per-client tmux session is killed. The underlying `main` session is unaffected.
33. WebSocket keepalive: server pings every **30 seconds**; if no pong is received within **15 seconds**, the server closes the WebSocket with close code 1001 (going away).
34. Resumable streams are **not** supported: a network drop ends the stream; the client must reconnect for a fresh attach. Claude's tmux session itself is unaffected.
35. A handshake against an unknown or just-cleaned N returns 404 pre-upgrade with Problem-Details body (`session_not_found`). A race where N is cleaned between upgrade and `tmux attach` results in an ERROR text frame followed by a clean WebSocket close.
36. The forwarded interactive surface supports: keystrokes (incl. control sequences), terminal output, terminal-resize via the JSON resize frame, mouse events via the JSON mouse frame (right-click / drag / pane-resize), copy-mode, and tmux command-prefix interactions.

### PKI provisioning (`aisandboxctl`)

37. `aisandboxctl pki init` → §7.
38. `aisandboxctl client mint <name>` → produces, in a chosen output directory:
    - `<name>.p12` (PKCS#12 bundle containing the client cert + private key, password-protected; passphrase prompted at mint time) **OR**, when `--pem` is given, a trio: `<name>.crt`, `<name>.key`, plus the server cert.
    - `server.crt` — copy of the server's public cert, for the client to pin.
    - `README.txt` — 2–3 lines telling the client how to use the bundle.
    AND copies the client's public cert into the server allowlist folder as `<name>.crt`. The watch reload picks it up; the client can connect immediately.
39. `aisandboxctl client revoke <name|fingerprint>` deletes the matching allowlist file. The watch reload tears down any in-flight connection from that cert within ≤ 1s.
40. `aisandboxctl client list` lists current allowlist (matches the `GET /v1/clients` API output shape).

### Logging and audit

41. Operational logs go to stdout in **JSON Lines** format and are captured by journald. Each line includes at minimum: `ts`, `level`, `logger`, `msg`, and — when applicable — `identity` (CN), `fingerprint`, `action`, `target`, `outcome`.
42. Audit-tagged events are written **both** to journald (with `audit=true` field) and to a separate file `/var/log/ai-sandbox-server/audit.log` in JSON Lines. The file is rotated daily by the application; 7-day retention; no size cap. Audit events: spawn, kill, attach-open, attach-close, client-cert add, client-cert remove, server-cert rotation, healthz-fail.
43. No secret material is ever logged: no private keys, no full PEM bodies, no passwords, no certs in raw bytes. CN and fingerprint are the only cert identifiers in logs.

### Shutdown

44. On SIGTERM (systemd `stop`), the server stops accepting new REST and stream connections, lets in-flight REST calls finish (max 30s grace), and sends `STREAM_CLOSE` text frames to each active WebSocket before closing them with close code 1001. After a configurable total grace period (default 60s) the process exits regardless.

### Testing and CI

45. Tests use **JUnit 5 + Spring Boot Test** (unit) and **Testcontainers** (integration; spins up a real server with disposable certs and exercises REST + WebSocket paths).
46. **GitHub Actions** workflow `.github/workflows/server-ci.yml` runs the Gradle build + unit + Testcontainers integration tests on every PR touching `server/**` and on every merge to `main`.
47. A separate **release pipeline** workflow `.github/workflows/server-release.yml` is triggered by version tags (`server-vX.Y.Z`). It builds the server jar, the `aisandboxctl` jar, refreshes the OAS, and emits a single **release zip** containing the jars, the OAS, the systemd unit file, the sample config, and the README — modelled after the `java-class-call-scanning` project's release flow. No intermediate artifacts are published.

### Documentation

48. README is updated to document: install steps (host JDK 21+ prerequisite, unpacking the release zip), systemd enable/start commands, `aisandboxctl pki init` + `client mint` + `client revoke` flow, the single TLS port and its default bind, the OAS and Swagger UI URLs, and the "known foot-guns" notes inherited from UC02 (shared workspace/claude-config and git push races).
49. `server/STREAM_PROTOCOL.md` documents the WebSocket framing: subprotocol name and version policy, binary-vs-text frame discrimination, JSON schemas for `resize` / `mouse` / `error` / `close`, and the close-code matrix.
50. `docs/THREAT_MODEL.md` is created (this UC's surface only). Scope: mTLS gate properties, single-port-on-all-interfaces default, Docker-socket-as-privilege-boundary risk, plain-PEM at-rest server key flagged for future review (passphrase-protected key + EnvironmentFile path), shell-out injection mitigations, multi-attach tmux behavior, allowlist-folder trust model. PROJECT_BRIEF.md's `security.threat_model` field is updated to reference this file during the revise-brief follow-up.

### UC02 dependencies (hard preconditions)

51. UC02's `spawn.sh` / `spawn.ps1` and `clean.sh` / `clean.ps1` must support **non-interactive flag-only invocation** (no TTY prompts). The server cannot answer interactive prompts. This must be true **before** this UC's `/develop` run begins.
52. UC02's `spawn.sh` / `spawn.ps1` must accept `--label <value>` and set `com.ai-sandbox.label=<value>` as a Docker Compose label on the new container. This must be true **before** this UC's `/develop` run begins.

### Brief-revision follow-up (operator action, not dev-team)

53. `/revise-brief` must be run **before** `/develop` on this UC, and must update the frontmatter to add: `java` to `stack.languages`; `spring-boot` to `stack.frameworks`; `java: "21"`, `spring_boot: "<chosen-stable>"`, `gradle: "<chosen-stable>"` to `stack.versions`; `gradle` as a co-equal `build.tool` entry alongside `docker compose`; `JUnit 5 + Spring Boot Test` and `Testcontainers` to `test.framework` / `test.levels`; GitHub Actions to `deployment.ci_cd`. Prose: refresh `## Architecture` trust-boundaries to reflect the new TLS port + Docker-socket-via-Java vector; refresh `## Quality & Standards` to reflect tests and CI; refresh `## Deployment` to reflect the systemd service.

## Potential Pitfalls & Open Questions

- **Accepted risk** — Docker socket access via the `docker` group is a privilege boundary: anyone who can run a command against `/var/run/docker.sock` can typically escape to root on the host. mTLS is the only authentication layer; the Java code must avoid any unauthenticated path. Documented in `docs/THREAT_MODEL.md`.
- **Accepted risk** — Binding to all interfaces by default means a misconfigured firewall could expose the port to a hostile network. mTLS protects authentication; every additional CVE in the TLS stack or Spring/Netty is externally reachable. README calls this out and recommends host-level firewalling regardless.
- **Accepted risk** — Plain-PEM server private key at mode 0600 is the protection; flagged in `docs/THREAT_MODEL.md` for future passphrase-protected upgrade.
- **Accepted risk** — Spring Boot is a large dependency surface; transitives expand the CVE-watch footprint. CI must keep pace with patch releases.
- **Edge case** — A revoked cert holding an in-flight WebSocket sees the connection torn down within ≤ 1s of the allowlist watch reload. This timing window is documented; not engineered tighter.
- **Edge case** — Per-client tmux sessions (`tmux new-session -t main`) create one tmux client per stream; under heavy multi-attach, PTY count and /dev/pts size are real limits. The systemd unit does not set ulimits explicitly; if this bites in practice, raise `LimitNOFILE=` in a follow-up.

## Original Description

We must create a client-server wrapper that allows us to communicate between any other computer (or mobile phone) and get the list of sessions and be able to spawn more or connect to existing ones. 
At this point I'm willing to have this as some sort of CLI application.
Server must be in java. It must work in a way that it can not only show the list of sessions and operations to spawn / kill them, but also "connect" to them and be able to interact directly with the claude sessions via TMUX, with some way of moving the window borders, and use TMUX operations (e.g. right click and such)
Authentication between client and server will be done using an asymetric certificate.
Connection must be secured using those certificates.
More than one client must be supported (via their respective certificates).
More than one client must be able to connect to the same claude session
Claude docker operations (spawn a new one, kill, enumerate..) must use the pre-existing scripts generated in UC02
This task must only cover the SERVER part. Client side will be defined in another UC later on after this one is implemented.
Part of the output must be a OAS specification with the ENDPOINTS (HTTPS? rest-like?) to execute the management operations.
I ASSUME there must be a second port to enstablish the tmux connections - this port if so MUST be secured with the same certificate mechanism and proper in-transit encryption, THIS IS NOT NEGOTIABLE. 
Communication security IS A MUST AND MUST BE TAKEN INTO ACCOUNT IN ANY SOLUTION PROPOSAL.
Server must be able to be defined as a system-level service for linux

(Follow-up from the user, mid-clarification: "I want to ask something: Would it be better to use websockets for the tcp real time communication (e.g. for tmux) instead so we can later also create web clients or so?" — answered yes, switched stream transport to WebSocket-over-TLS on a single port.)

## Clarifications

- **Framework:** Spring Boot (latest stable) on Java 21 LTS; Gradle build.
- **Stream transport:** WebSocket-over-TLS (`wss://`), updated from the initial raw-TLS-bytestream choice once the user raised the web/mobile-client future need.
- **Port layout:** Single TLS port 12410 hosting both REST (`/v1/*` over HTTP/2) and WebSocket (`/v1/sessions/{n}/stream`).
- **PKI / trust model:** Folder-of-allowed-client-certs (fingerprint allowlist) at `/etc/ai-sandbox-server/clients/`; no CA on either side. Server cert+key in `/etc/ai-sandbox-server/pki/`. Both directories filesystem-watched for hot-reload.
- **PKI helper:** Separate `aisandboxctl` fat jar. `client mint` emits `.p12` by default, `--pem` for the trio, also outputs `server.crt` and a per-client README, and writes the public cert into the allowlist folder.
- **Cert revocation:** Delete from allowlist folder; watch reload tears down in-flight connections.
- **Server cert rotation:** Filesystem-watched hot reload.
- **Authorization:** Flat — every valid cert is admin (MVP).
- **Cert mgmt API:** `POST /v1/clients` (JSON with PEM), `GET /v1/clients`, `DELETE /v1/clients/{cnOrFingerprint}` complement `aisandboxctl`.
- **Identity in logs:** CN displayed, SHA-256 fingerprint also logged.
- **Bind interface:** All interfaces (`0.0.0.0` / `::`) by default; mTLS is the gate.
- **TLS:** 1.3 only; explicit cipher allowlist; HTTP/2 for REST.
- **Conn rate limit:** Per-IP, configurable; default 10 conn/10s, 10 concurrent.
- **Body size cap:** 64 KiB on POST.
- **API versioning:** `/v1/` URL prefix.
- **OAS:** springdoc-generated; served at `/v1/openapi.yaml`; Swagger UI at `/v1/swagger-ui`; committed at `server/openapi.yaml`; CI verifies drift.
- **Errors:** `application/problem+json` per **RFC 9457** on REST; equivalent JSON shape inside WebSocket text frames.
- **WebSocket:** Mandatory subprotocol `ai-sandbox.v1`; binary frames = tty bytes; text frames = JSON control. Ping 30s / 15s pong timeout. Max msg 256 KiB binary / 16 KiB text. Per-stream output buffer 256 KiB, overflow → close 1009.
- **Stream caps:** idle 2h / 10 per client / 100 total; configurable. Cap exceeded → HTTP 503 pre-upgrade.
- **Resumable streams:** No.
- **Multi-attach tmux:** Per-client `tmux new-session -t main` so each client has independent sizing.
- **Spawn semantics:** Sync, 60s timeout. Failure rolls back via `clean.sh <N>`. Server-wide spawn mutex.
- **DELETE race:** Per-N mutex; second concurrent caller gets 404.
- **Session label:** Optional `label` on POST `/v1/sessions`; stored as Compose container label.
- **PKI init scope:** Server cert+key + empty allowlist + sample config.
- **Server cert default CN:** `ai-sandbox-server`; overridable via `--cn`.
- **Server private key at rest:** Plain PEM 0600; flagged for future-review in `docs/THREAT_MODEL.md`.
- **Service user:** `ai-sandbox-server` in the `docker` group.
- **Healthz:** mTLS-gated (no anonymous endpoints).
- **Tests:** JUnit 5 + Spring Boot Test + Testcontainers.
- **CI:** GitHub Actions — build+tests on PR/merge to main; tag-triggered release pipeline emits a single zip (jars + OAS + README) like the `java-class-call-scanning` flow; no intermediate artifacts.
- **Code location:** `ai-sandbox/server/` subdirectory.
- **JDK delivery:** Host OpenJDK 21+ prerequisite; fat jar only.
- **Logs:** JSON Lines on stdout to journald + separate `/var/log/ai-sandbox-server/audit.log` (app-managed daily rotation, 7-day retention, unlimited size).
- **systemd:** `Restart=on-failure`, `RestartSec=5s`, `StartLimitBurst=5/60s`, `WantedBy=multi-user.target`.
- **Config:** YAML at `/etc/ai-sandbox-server/config.yaml`.
- **Hot config reload:** Restart-only (only certs/allowlist hot-reload).
- **Threat-model doc:** New `docs/THREAT_MODEL.md` scoped to this UC's surface.
- **UC02 preconditions (hard):** spawn.sh / clean.sh non-interactive flag-only; spawn.sh `--label <value>` setting `com.ai-sandbox.label=<value>`.
- **PROJECT_BRIEF revision:** Run `/revise-brief` before `/develop` on this UC.
