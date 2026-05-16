---
schema_version: 1
project:
  name: ai-sandbox
  maturity_target: mvp
stack:
  languages: [Bash, PowerShell, Dockerfile, Java]
  frameworks: [spring-boot]
  runtimes: [desktop, jvm]
  versions:
    alpine: "latest"
    gitleaks: "8.21.0"
    claude_code: "latest-at-build"
    rtk: "latest-at-build"
    node: "alpine-apk"
    tmux: "alpine-apk"
    docker_compose: "v2+"
    java: "21"
    spring_boot: "latest-stable-at-build"
    gradle: "latest-stable-at-build"
  data_stores: []
build:
  tool: docker-compose+gradle
  commands:
    test: "./gradlew :server:test"
    lint: "./gradlew :server:spotlessCheck"
    format: "./gradlew :server:spotlessApply"
    build: "docker compose build"
    up: "docker compose up -d"
    down: "docker compose down"
    setup: "./setup.sh   # or .\\setup.ps1 on Windows"
    attach: "./attach.sh # or .\\attach.ps1 on Windows"
    clean: "./clean.sh   # or .\\clean.ps1 on Windows"
    java_build: "./gradlew :server:bootJar"
    java_test: "./gradlew :server:test"
    release: "git tag server-vX.Y.Z && git push --tags"
profiles: [profile-java-server-architecture, profile-java-call-graph-tool]
paths:
  production: ["**", "!workspace/**", "!secrets/**", "!claude-config/**", "!.git/**"]
  test: ["server/src/test/**"]
  api_boundary: ["server/src/main/java/**/api/**"]
test:
  framework: JUnit 5 + Spring Boot Test
  levels: [unit, integration]
  coverage_target: none
deployment:
  provider: local-host
  iac: docker-compose+systemd
  environments: [local]
vcs:
  enabled: true
  already_initialized: true
  default_branch: main
  remote: https://github.com/HaroldHormaechea/ai-sandbox.git
use_cases:
  index: USE_CASES.md
  folder: use-cases/
---

# Project Brief

Source of truth for the `ai-sandbox` project. Machine-read fields live in the YAML frontmatter above; prose below is for humans. Generated and confirmed via the project-builder agent against the existing repo state on 2026-05-10.

## Overview

- **name:** ai-sandbox
- **problem:** Claude Code's permission prompts interrupt autonomous workflows, but running Claude with `--dangerously-skip-permissions` directly on the host is unsafe — it has unrestricted access to the user's files, network, credentials, and shell. There is no out-of-the-box way to get a fully autonomous Claude that is also confined.
- **users:**
  - Solo developers and tinkerers who want a long-running autonomous Claude on their own machine without it touching the host.
  - Open-source contributors and operators in the Claude Code community who prefer a one-command setup over rolling their own devcontainer.
  - Cross-platform users (Linux, macOS, Windows) who need parity scripts for both POSIX shells and PowerShell.
- **value_proposition:** Zero-prompt autonomous Claude in a disposable Alpine container, persisted across detach/reattach via `tmux`, set up with a single guided wizard, and protected against secret leaks by `gitleaks` running at two layers (host pre-commit and container-wide). The container is the trust boundary, so `--dangerously-skip-permissions` is acceptable. Differentiates on **Simpler**, **More autonomous**, and **Safer** versus running Claude on the host or building a bespoke devcontainer.
- **maturity_target:** MVP — usable by early adopters in the wider community; the maintainer accepts issues and PRs but does not promise SLAs.
- **in_scope:**
  - A self-contained Docker image (Alpine + Node + Claude Code + tmux + gitleaks + gh + git + ssh).
  - A guided `setup.sh` / `setup.ps1` wizard that handles SSH key, image build, optional `gh` login, Claude `/login`, and start.
  - `attach.sh` / `attach.ps1` thin wrappers around `tmux attach -t main` for detach/reattach.
  - `clean.sh` / `clean.ps1` destructive reset path with explicit confirmation.
  - Two-layer gitleaks secret-scan protection (host pre-commit and container-wide git hook).
- **non_goals:**
  - Not a multi-user platform or shared service.
  - Not a cloud or SaaS deployment of Claude.
  - Not a packaged Claude IDE, editor extension, or GUI.
  - Not a hardened security product — the trust boundary is "an Alpine container under the user's Docker daemon," not a VM or microVM.
  - Not a CI/CD orchestrator for Claude.
- **success_criteria:**
  - A new user can clone the repo, run `setup.sh` once, and end up with a fully autonomous Claude running locally.
  - After initial setup, attaching to or detaching from Claude is a single command and never loses session state.
  - Any commit Claude makes (in the host repo or in any cloned project inside the container) is scanned by gitleaks before it lands.
  - Audience: wider community (public, MIT-licensed); contributions welcome but not required.

## Monetization

- **commercial_intent:** no
- **model:** open-source with optional donations
- **license:** MIT
- **target_market:** wider developer community; individual developers, tinkerers, and Claude Code operators who want autonomous Claude in a confined container.
- **tiers:** none (single distribution; everyone gets the same thing).
- **commercial_overlay:** none (no paid hosted version, no paid support, no enterprise edition).
- **donations:** optional via GitHub Sponsors or equivalent; no paywalled features behind donations.
- **constraints:**
  - No ads.
  - No telemetry, no phone-home, no analytics on the user's machine.
  - No data collection from users.
  - Standard MIT license terms (no warranty, attribution required in copies).

## Technologies

The project now spans two co-equal stacks: the original shell + Docker container layer that hosts Claude, and a new host-resident **Java/Spring Boot management server** introduced by UC03 (`use-cases/03-mtls-java-management-server.md`). Both ship from the same repo and are released together; the Java server lives under `ai-sandbox/server/` and runs as a `systemd` service on the host outside the container.

- **constraints:**
  - Must run on the user's host (Linux, macOS, Windows) via Docker — no cloud, no SaaS.
  - Must provide POSIX-shell and PowerShell parity for every operator-facing script (`.sh` + `.ps1` mirror).
  - Container must be self-contained: no host tooling required beyond Docker, a shell, and (optionally) `pre-commit` for contributors of this repo.
  - The Java management server requires **host OpenJDK 21+** at install time on Linux hosts that run the service. The project ships a **fat jar**, not a bundled JRE; the operator is responsible for installing a JDK via their package manager. Windows and macOS hosts can run the container stack without a JDK; the systemd service itself is Linux-only.
- **runtimes:**
  - Host: Docker (any version supporting Compose v2) + a POSIX shell or PowerShell. Linux hosts that run the management server additionally need OpenJDK 21+ on the host (no JVM inside the container).
  - Container: Alpine Linux (`alpine:latest`).
  - In-container processes: Node.js (apk-supplied) hosting `@anthropic-ai/claude-code`; `tmux` keeping the Claude session alive across detach/reattach. `rtk` (Rust Token Killer) sits on the PATH as a passive CLI proxy invoked by Claude's Bash hook to compress command output before it reaches the LLM — no daemon process.
  - JVM (host): a single long-running JVM 21 process under `systemd` running the Spring Boot server jar; a short-lived JVM invocation when the operator runs the `aisandboxctl` companion jar for PKI / client-mint / revoke / list operations.
- **languages:**
  - Bash — operator scripts (`setup.sh`, `spawn.sh`, `attach.sh`, `clean.sh`), `entrypoint.sh`, system-wide git hooks.
  - PowerShell — Windows mirrors of every `.sh` (`setup.ps1`, `spawn.ps1`, `attach.ps1`, `clean.ps1`).
  - Dockerfile — `SandboxDockerfile` that builds the `ai-context:latest` image.
  - **Java 21 LTS** — Spring Boot management server and the `aisandboxctl` companion CLI; both live under `ai-sandbox/server/` and build with the same Gradle multi-module project.
- **frameworks:**
  - **Spring Boot** (latest stable at implementation time) for the management server — `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, and `spring-boot-starter-websocket`. No other web framework.
- **key Java libraries (prose only — no frontmatter field):**
  - **springdoc-openapi** — runtime generation of the OpenAPI 3.x spec served at `/v1/openapi.yaml` and dumped to `server/openapi.yaml` at build time; CI fails on drift.
  - **Bouncy Castle** (or JDK-native equivalents) — PEM parsing and PKCS#12 emission for `aisandboxctl client mint`.
  - **Picocli** (or Spring Boot's CLI runner) — argument parsing for the `aisandboxctl` companion jar; choice deferred to the dev-team but must match the prevailing convention of the chosen Spring Boot release line.
- **data_stores:** none. The container side keeps state in three bind mounts (`workspace/`, `secrets/` read-only, `claude-config/`). The Java server keeps trust state on disk under `/etc/ai-sandbox-server/` (allowlist folder of client certs + server cert + config YAML) and audit state under `/var/log/ai-sandbox-server/`. No relational, document, key-value, or vector store.
- **auth_strategy:**
  - **Container side (unchanged):** Git over SSH using a user-supplied private key copied into `secrets/git-key`; bind-mounted read-only into the container at `/etc/secrets/git-key` and copied to `~/.ssh/git-key` by the entrypoint. Optional `gh` CLI auth via a token in `secrets/gh-token`; entrypoint runs `gh auth login --with-token` on boot if present. Claude Code `/login` performed once in a disposable container during setup; persisted to `claude-config/` (bind-mounted to `~/.claude/` in the container).
  - **Management server (new in UC03):** mutual TLS against a **folder-of-allowed-client-certs (fingerprint allowlist)** at `/etc/ai-sandbox-server/clients/`. No CA, no IdP, no password. Server cert + private key live under `/etc/ai-sandbox-server/pki/`. Both directories are filesystem-watched and hot-reload without restart. All valid client certs are equal-authority admins for this MVP; identity is logged per call (CN for display, SHA-256 fingerprint for audit).
- **external_services:**
  - **GitHub** — git clone/push over SSH, `gh` CLI, `gh` API (`api.github.com` over HTTPS).
  - **Anthropic** — Claude Code authentication and API endpoints (used by `@anthropic-ai/claude-code`).
  - **gitleaks GitHub Releases** — build-time only; `curl` fetches the pinned `gitleaks_${VERSION}_linux_*` tarball during `docker build`.
  - **rtk-ai/rtk GitHub Releases** — build-time only; `curl` fetches the latest `rtk-<arch>-unknown-linux-{musl,gnu}.tar.gz` (amd64: musl; arm64: glibc + `apk add gcompat` shim) during `docker build`.
  - **Alpine `apk` repositories** — build-time only; `apk add` installs `nodejs npm git ripgrep bash ca-certificates tmux github-cli openssh-client curl` (plus `gcompat` on arm64 for the glibc `rtk` binary).
  - **Maven Central** (and any plugin portal the chosen Spring Boot release line uses) — build-time only; Gradle resolves Spring Boot, springdoc-openapi, Bouncy Castle / Picocli, Testcontainers, and JUnit 5 transitives during `./gradlew :server:bootJar` and `./gradlew :server:test`.
- **ai_ml_dependency:** Anthropic Claude (via `@anthropic-ai/claude-code`); model selection and configuration are owned by Claude Code itself, not by this project. The Java management server itself does not call any AI/ML service — it brokers terminal traffic to/from Claude running inside the containers.
- **versions / pinning policy (intentional):**
  - `alpine` — rolling `latest` (rebuild picks up upstream changes).
  - `@anthropic-ai/claude-code` — rolling latest at image build time (no version pin).
  - `rtk` — rolling latest at image build time (matches the `@anthropic-ai/claude-code` policy, not the pinned `gitleaks` pattern). The resolved version is echoed during `docker build` so the actual version landed is visible in the build log.
  - `gitleaks` — pinned to `8.21.0` via the `GITLEAKS_VERSION` build arg in `SandboxDockerfile` and via the host pre-commit hook in `.pre-commit-config.yaml`. Both layers must move together when bumped.
  - **Java** — pinned to **21 LTS**. Bumping requires a deliberate move to the next LTS once available.
  - **Spring Boot** — latest stable at the time the server's `/develop` run lands; the resolved version is recorded in `gradle/libs.versions.toml` (or equivalent) and surfaced in the release zip's README. Patch-level updates land via PRs that pass the existing CI.
  - **Gradle** — latest stable at the time the server's `/develop` run lands, pinned via the Gradle wrapper.
- **build.tool:** **`docker-compose+gradle`** — two co-equal build surfaces in the same repo. The container side uses `docker compose` (Compose v2); the Java side uses the Gradle wrapper checked into `ai-sandbox/server/`. Neither side blocks the other: a contributor working only on shell scripts never needs to run Gradle, and a contributor working only on the Java server only needs Docker to run the Testcontainers integration tests.
- **build.commands:**
  - build image: `docker compose build`
  - bring container up: `docker compose up -d`
  - bring container down: `docker compose down`
  - first-run wizard: `./setup.sh` / `.\setup.ps1`
  - spawn an additional session: `./spawn.sh` / `.\spawn.ps1` (per UC02)
  - attach to a session: `./attach.sh` / `.\attach.ps1`
  - destructive reset (per N): `./clean.sh [N]` / `.\clean.ps1 [N]`
  - host-side secret scan (contributors): `pre-commit run --all-files`
  - build the Java server fat jar: `./gradlew :server:bootJar`
  - run Java unit + integration tests: `./gradlew :server:test`
  - cut a release of the Java server: `git tag server-vX.Y.Z && git push --tags` (triggers `server-release.yml` — see `## Deployment`).
  - test / lint / format on the shell side: none configured today.

## Architecture

The architecture is now a two-tier composition: (a) the original container side — one Alpine container per Claude session, organised as a separate Docker Compose project `ai-sandbox-N` per UC02 — and (b) a single host-resident **Java/Spring Boot management server** introduced by UC03, running as a `systemd` service outside any container. Both tiers are deliverables of the same repo and they communicate through the UC02 host scripts.

- **platforms:**
  - Desktop / CLI on the operator's host (Linux, macOS, Windows) — the container side as before.
  - **Host-resident systemd service** on Linux for the Java management server. Exposes a single TLS port (default **12410**, bound to all interfaces by default) carrying both an HTTP/2 REST API (`/v1/*`) and a WebSocket endpoint (`wss://host:12410/v1/sessions/{n}/stream`, subprotocol `ai-sandbox.v1`).
  - Nothing is published to a web, mobile, or browser-extension surface in this repo — those clients are deferred to a later use case and will consume the same REST + WebSocket surface.
- **service_shape:**
  - **Container tier:** single-container monolith *per Compose project*. Each `ai-sandbox-N` runs one Compose service (`claude-sandbox`) from one Alpine image (`ai-context:latest`). UC02 turned the historical singleton into a fleet, but each member is still a monolith inside its boundary.
  - **Host tier:** a single Spring Boot fat-jar process — the management server — plus the short-lived `aisandboxctl` fat-jar for PKI/admin tasks. The server is a sibling to the containers, not embedded in any of them.
  - Net shape: one Java server per host + N containers per host + the UC02 host scripts that the server invokes. No services-talking-to-services pattern.
- **components:**
  - **Operator scripts (host)** — `setup.sh`/`setup.ps1`, `spawn.sh`/`spawn.ps1`, `attach.sh`/`attach.ps1`, `clean.sh`/`clean.ps1`. Wizard, multi-session spawn (UC02), tmux attach wrapper, destructive reset. All must support non-interactive flag-only invocation; `spawn.sh` accepts `--label <value>` setting `com.ai-sandbox.label` on the container (UC02 ACs 21–22, hard preconditions for UC03).
  - **`SandboxDockerfile` (build)** — produces `ai-context:latest`: Alpine + nodejs/npm + git + ripgrep + bash + tmux + github-cli + openssh-client + curl + gitleaks 8.21.0 + `@anthropic-ai/claude-code` (global) + `rtk` (Rust Token Killer, rolling-latest; arm64 also pulls `gcompat`) + non-root `claude` user + system-wide git hooks at `/etc/git-hooks/`.
  - **`entrypoint.sh` (container PID 1)** — copies the SSH key from `/etc/secrets/git-key` to `~/.ssh/`, fixes perms, writes an SSH config, optionally `gh auth login --with-token` if `gh-token` is present, clones the bootstrap project into `/workspace/project-builder` if missing, then either `exec`s a one-off command (used by the wizard for `/login`) or starts `tmux new-session -d -s main` running Claude in a while-loop. Also runs `rtk init -g` idempotently on every start (after `claude-config/` is bind-mounted so the hook config persists) and conditionally appends an RTK bypass directive to `~/.claude/CLAUDE.md` under sentinel markers, skipping if upstream RTK already documented the bypass in `CLAUDE.md` or `RTK.md`.
  - **`tmux` session `main` (in-container, long-running)** — keeps Claude alive across `docker compose exec` detach/reattach cycles; restarts Claude in a loop on `/exit` so `attach` always lands in a fresh prompt. UC03 layers per-client tmux sessions (`tmux new-session -t main`) on top so each WebSocket client gets its own sizing/cursor state without disturbing the shared underlying session.
  - **System-wide git hooks (in-container)** — `/etc/git-hooks/pre-commit` runs gitleaks on staged changes for any commit Claude makes inside any cloned project.
  - **Host pre-commit hook (contributors only)** — `.pre-commit-config.yaml` invokes the same gitleaks v8.21.0 via the [pre-commit](https://pre-commit.com/) framework on the host before commits land in *this* repo.
  - **Java management server (host, systemd)** — Spring Boot fat jar under `ai-sandbox/server/`. Runs as the non-root `ai-sandbox-server` user (member of the `docker` group) under the `ai-sandbox-server.service` systemd unit. Owns: TLS termination on port 12410, mTLS validation against `/etc/ai-sandbox-server/clients/`, the REST API under `/v1/*`, the WebSocket bridge to per-session tmux, the springdoc-generated OAS at `/v1/openapi.yaml`, the Swagger UI at `/v1/swagger-ui`, and the audit-log writer at `/var/log/ai-sandbox-server/audit.log`. Filesystem-watches both `/etc/ai-sandbox-server/pki/` and `/etc/ai-sandbox-server/clients/` for hot reload.
  - **`aisandboxctl` companion jar (host, one-shot)** — short-lived Java CLI shipped alongside the server jar. Subcommands: `pki init` (self-signed server cert + key + sample config), `client mint <name>` (emits `.p12` by default or PEM trio with `--pem`, plus `server.crt` and a per-client README; copies the new client cert into the allowlist), `client revoke <name|fingerprint>`, `client list`.
- **communication:**
  - **Operator → Docker daemon** — `docker compose` over the local socket (from operator shells and from the Java server, both via the UC02 scripts).
  - **Operator → container** — `docker compose exec` for `tmux attach -t main` and ad-hoc shells (still works directly when the operator is at the host console).
  - **Container → host filesystem** — three bind mounts per session: `./workspace ↔ /workspace` (rw, shared by default per UC02), `./secrets ↔ /etc/secrets` (ro, single shared mount), `./claude-config ↔ /home/claude/.claude` (rw, shared by default per UC02).
  - **Container → GitHub** — git over SSH (port 22) using `~/.ssh/git-key`; `gh` CLI/API over HTTPS to `api.github.com`.
  - **Container → Anthropic** — HTTPS, owned by `@anthropic-ai/claude-code`.
  - **Remote client → Java server (REST)** — HTTPS with **mTLS over HTTP/2** on port **12410**, path prefix `/v1/*`. Bodies capped at 64 KiB; errors as `application/problem+json` (RFC 9457).
  - **Remote client → Java server (WebSocket)** — `wss://` on the *same* port 12410, mandatory subprotocol `ai-sandbox.v1`. Binary frames carry raw tty bytes; text frames carry JSON control (`resize`, `mouse`, `error`, `close`). Same mTLS context as the REST path.
  - **Java server → UC02 host scripts** — argv-only `exec` of `spawn.sh`, `clean.sh`, and the enumerate path (`docker compose ls` with a `com.docker.compose.project=ai-sandbox-*` filter). No shell interpolation; inputs validated against an allowlist before exec.
  - **Java server → per-session tmux** — for each open WebSocket, the server runs `docker compose -p ai-sandbox-<N> exec -it claude-sandbox tmux new-session -d -s client-<streamId> -t main` and pipes the client's WebSocket through `tmux attach -t client-<streamId>`. The per-client tmux session is killed on stream close; the underlying `main` session is unaffected.
- **async_workloads:**
  - The Claude `tmux` while-loop (per container) is still the only long-running in-container process.
  - The Java server itself is event-driven (Spring Boot embedded servlet container + Netty for HTTP/2 and WebSocket). No schedulers, queues, brokers, or background workers. Filesystem watchers on `/etc/ai-sandbox-server/pki/` and `/etc/ai-sandbox-server/clients/` are passive — they react to inotify-style events, not on a poll loop.
- **integrations:**
  - **GitHub** — `git clone`/`fetch`/`push` over SSH from inside containers; `gh` CLI for API operations.
  - **Anthropic Claude** — via `@anthropic-ai/claude-code` running inside containers.
  - **gitleaks GitHub Releases** — pulled at `docker build` time only.
  - **rtk-ai/rtk GitHub Releases** — pulled at `docker build` time only.
  - **Alpine `apk` repos** — pulled at `docker build` time only.
  - **Maven Central / Gradle plugin portal** — pulled at `./gradlew` time only (Spring Boot, springdoc-openapi, Bouncy Castle / Picocli, JUnit 5, Testcontainers, etc.).
  - **UC02 host scripts** — invoked by the Java server as a first-class integration (argv exec). Treated as a stable external surface from the Java side; their non-interactive + `--label` contract (UC02 ACs 21–22) is what makes this integration viable.
  - **Docker daemon** — read-only enumeration via `docker compose ls` (the Java server never calls the Docker API or socket directly; all writes go through the UC02 scripts).
- **data_flow_narrative:**
  - **Local operator flow (unchanged):** operator runs a host script → `docker compose -p ai-sandbox-N` boots `claude-sandbox` from `ai-context:latest` → entrypoint hydrates auth and starts `tmux main` → Claude runs inside `/workspace` (host's `./workspace/` by default per UC02) and reaches out to GitHub/Anthropic → operator attaches via `docker compose exec`.
  - **Remote operator flow (new):** a remote client (CLI today; web/mobile later) terminates TLS at the Java management server on port 12410, presenting a client cert that must match a file in `/etc/ai-sandbox-server/clients/`. For management calls, the client hits `/v1/sessions`, `/v1/clients`, `/v1/healthz`, etc.; the server validates the request, dispatches Docker-touching operations to the UC02 scripts (`spawn.sh`, `clean.sh`, or the `docker compose ls` enumerate), captures stdout/stderr, and replies as JSON or `application/problem+json`. For terminal sessions, the client upgrades to `wss://.../v1/sessions/{n}/stream` with subprotocol `ai-sandbox.v1`; the server allocates a per-client tmux session inside container N (`tmux new-session -t main`), pipes WebSocket binary frames in/out as raw tty bytes, and translates text frames (`resize`/`mouse`/`error`/`close`) into tmux commands. Multiple concurrent clients can attach to the same container — each gets independent sizing via its own tmux client.
  - **Audit / log flow:** every authenticated request and every stream open/close emits one JSON Lines entry to stdout (captured by journald) and, for audit-tagged events (`spawn`, `kill`, `attach-open`, `attach-close`, `client-cert add/remove`, `server-cert rotation`, `healthz-fail`), an additional line to `/var/log/ai-sandbox-server/audit.log` (app-managed daily rotation, 7-day retention).
- **trust_boundaries:**
  - **Container-as-sandbox.** Each container is still a trust boundary. Claude runs as the non-root `claude` user inside Alpine and can only see the host through its bind mounts. `--dangerously-skip-permissions` remains acceptable because the grant is bounded by Linux user perms + Docker isolation.
  - **`secrets/` is read-only.** The shared `:ro` bind mount means even an autonomous Claude cannot rewrite the SSH key or `gh` token in place — though it can read them, and via SSH it has the same authority over remote git as the operator.
  - **gitleaks at two layers.** Host pre-commit (this repo's contributors) and container-wide system hook (every commit Claude makes inside any container). Both pinned to gitleaks 8.21.0 and must move together.
  - **Java management server is a NEW host-level network attack surface.** Port 12410 is bound to **all interfaces** (`0.0.0.0` and `::`) by default. mTLS against the folder-of-allowed-client-certs is the **sole** authentication gate: no plaintext fallback, no anonymous TLS, no opt-out. TLS 1.3 only, explicit cipher allowlist. A misconfigured host firewall could expose the port to a hostile network; the README and `docs/THREAT_MODEL.md` both call this out and recommend host-level firewalling regardless.
  - **`docker` group is a privilege boundary.** The server runs as `ai-sandbox-server`, a non-root user in the `docker` group. Anyone with code execution inside the server process can typically escape to host root via `/var/run/docker.sock`. mTLS is the only layer between the network and that capability; the Java code must keep every unauthenticated path closed. `/v1/healthz` is mTLS-gated for exactly this reason.
  - **Server private key at rest = plain PEM 0600.** Stored at `/etc/ai-sandbox-server/pki/`, owned by `ai-sandbox-server`. Mode 0600 is the protection. `docs/THREAT_MODEL.md` flags this for future review (passphrase-protected key + EnvironmentFile passphrase as the planned upgrade path).
  - **Per-client tmux isolation.** The WebSocket endpoint runs `tmux new-session -t main` per stream, so client window sizes and mouse state are independent. The underlying `main` session is still shared — concurrent clients see the same Claude conversation — and that is intentional. Revoking a cert tears down its in-flight WebSocket within ≤ 1s of the allowlist watch reload.
  - **No inbound network surface on the container tier.** Compose projects publish no ports; containers are reachable only via `docker compose exec` (from the operator at the host console or from the Java server via the UC02 scripts).
  - **Bind-mount dirs are not project source.** `workspace/`, `secrets/`, and `claude-config/` are runtime state owned by the operator/Claude — they are excluded from `paths.production` so the dev-team never edits them.
  - **RTK as a CLI proxy on the PATH, not a new trust boundary.** `rtk` is a passive binary at `/usr/local/bin/rtk` invoked by Claude's Bash hook. It runs in-process as the `claude` user; it does not add inbound surface or change isolation properties. The supply-chain note for `rtk-ai/rtk` GitHub Releases (build-time fetch, rolling-latest, no checksum) is the relevant risk.
- **multi_tenancy:** still **not applicable**. There is one operator, one host, one fleet of containers, one Java server. The Java server now supports **multiple authenticated clients concurrently** — but they are all the same operator coming in from different devices (laptop + mobile + a second laptop). All valid client certs are equal-authority admins in this MVP; the server does not partition state per client.

### Production / test path scopes

- `paths.production`: `["**", "!workspace/**", "!secrets/**", "!claude-config/**", "!.git/**"]` — wide scope: the developer agent may edit anything at the repo root EXCEPT the three bind-mount dirs (runtime state, not source) and `.git/` (VCS metadata). The new Java tree under `server/**` is included implicitly by the wildcard.
- `paths.test`: `["server/src/test/**"]` — the Gradle convention path for both unit tests (JUnit 5 + Spring Boot Test) and integration tests (Testcontainers) on the Java side. The shell side still has no test harness.
- `paths.api_boundary`: `["server/src/main/java/**/api/**"]` — recorded as a hint for the dev-team. The exact final package convention (`api/`, `controller/`, or `web/`) is the developer's call during `/develop`; the dev-team may refine this glob once the package layout is settled.

## Quality & Standards

Quality bar now spans two stacks: the original shell + Docker container layer (unchanged, still no formal harness — operator-aware behavior is the contract) and the new Java/Spring Boot server layer introduced by UC03, which brings real tests, lint, and CI for the first time in this repo.

- **style_guide:**
  - Shell + PowerShell + Dockerfile: language defaults; no formal house style. Match the conventions already present in `setup.sh` / `setup.ps1` for parity.
  - **Java:** **Spring Boot's official style guide as expressed by [google-java-format](https://github.com/google/google-java-format)**. The Spring Boot ecosystem aligns on google-java-format in modern setups; this also matches the `profile-java-server-architecture` opinion baseline. The dev-team may not deviate without an explicit brief amendment.
- **linters_formatters:**
  - Bash: none configured today (shellcheck/shfmt remain reasonable future additions).
  - PowerShell: none configured today (PSScriptAnalyzer remains a reasonable future addition).
  - Dockerfile: none configured today (hadolint remains a reasonable future addition).
  - **Java: Spotless** via the `com.diffplug.spotless` Gradle plugin, formatter set to `google-java-format`. Autoformat with `./gradlew :server:spotlessApply`; CI-enforced with `./gradlew :server:spotlessCheck` (the `server-ci.yml` workflow fails the PR on violations). Spotless is the single source of truth for Java formatting — no IDE-specific config takes precedence.
- **testing:**
  - **levels:** `unit`, `integration`. Unit tests cover pure Java logic (request/response mapping, allowlist parsing, problem-json shaping, control-frame JSON, etc.). Integration tests cover the full server boot + TLS + REST + WebSocket surface against disposable certs and a real Docker daemon via Testcontainers.
  - **frameworks:** **JUnit 5** + **Spring Boot Test** for unit and slice tests; **Testcontainers** for integration (declared by UC03 §45). No bats/Pester on the shell side today.
  - **coverage_target:** none committed for MVP. UC03 did not pin a coverage number, so we do not impose one. Coverage tooling (Jacoco) may be added later if the operator wants a gate; the dev-team should not add it unilaterally without a brief revision.
  - **CI:** `.github/workflows/server-ci.yml` runs `./gradlew :server:spotlessCheck :server:test` on every PR touching `server/**` and on every merge to `main`. Failures block merge.
- **security:**
  - **Secret scanning — host layer.** `.pre-commit-config.yaml` invokes gitleaks v8.21.0 via the [pre-commit](https://pre-commit.com/) framework before commits land in this repo. Contributors install with `pip install pre-commit && pre-commit install`.
  - **Secret scanning — container layer.** `SandboxDockerfile` installs gitleaks 8.21.0 and configures a system-wide git pre-commit hook (`git config --system core.hooksPath /etc/git-hooks`) that scans every commit Claude makes in any cloned project inside the container. To allowlist a finding, drop a `.gitleaks.toml` in the affected repo's root; to bypass for a single commit, `git commit --no-verify` (used sparingly).
  - **Pinning discipline.** Both gitleaks layers are pinned to the same version; bumping requires updating `.pre-commit-config.yaml` and the `GITLEAKS_VERSION` build arg in `SandboxDockerfile` together.
  - **Container hygiene.** Non-root `claude` user inside the image; no published ports in `docker-compose.yml`; `secrets/` bind mount is `:ro`.
  - **Management-server authentication = mTLS only.** UC03's Java server (host, systemd) terminates a single TLS port (12410) and is gated by mutual TLS against a folder-of-allowed-client-certs at `/etc/ai-sandbox-server/clients/`. TLS 1.3 only; explicit cipher allowlist; no plaintext fallback; no anonymous endpoint (including `/v1/healthz`). All valid certs are equal-authority admins for MVP; identity = CN (displayed) + SHA-256 fingerprint (audited). Revocation = delete from allowlist; in-flight connections torn down on the next watch reload (≤ 1s).
  - **At-rest server key.** Plain PEM 0600 at `/etc/ai-sandbox-server/pki/`, owned by `ai-sandbox-server`. Mode 0600 is the protection. Flagged for future review (passphrase-protected key + EnvironmentFile passphrase as the planned upgrade path) — see `docs/THREAT_MODEL.md`.
  - **Audit log.** `/var/log/ai-sandbox-server/audit.log` (JSON Lines, app-managed daily rotation, 7-day retention, no size cap) captures: spawn, kill, attach-open, attach-close, client-cert add, client-cert remove, server-cert rotation, healthz-fail. Same lines also flow to journald with an `audit=true` field. No secret material is ever logged (no private keys, no full PEM bodies, no passwords, no certs in raw bytes).
  - **Threat-model document.** **`docs/THREAT_MODEL.md`** is a committed deliverable, produced by UC03's `/develop` run. Scope: UC03's surface only — mTLS gate properties, single-port-on-all-interfaces default, Docker-socket-as-privilege-boundary risk, plain-PEM-at-rest server key flagged for future review, shell-out injection mitigations, multi-attach tmux behavior, allowlist-folder trust model. The original container-tier risks (SSH key reachable by Claude, outbound network to GitHub/Anthropic, shared host kernel) remain documented informally in the README; this brief does not commit to expanding the threat model to cover them at this time.
  - **No dependency scanner today.** No `npm audit` / `pip-audit` / Trivy / Snyk wired up on the container side. **Note:** Spring Boot is a large dependency surface (UC03 explicitly accepts this risk); transitives expand the CVE-watch footprint. The CI workflow building on every PR is the only automatic feedback channel today — Dependabot / Renovate / Snyk are reasonable future additions but not committed.
  - **No SAST today.**
  - **Docker-group privilege boundary.** The `ai-sandbox-server` user is a member of the `docker` group; anyone with code execution inside the server process can typically escape to host root via `/var/run/docker.sock`. mTLS is the only authentication layer; the Java code must keep every unauthenticated path closed. Accepted risk, documented in `docs/THREAT_MODEL.md`.
- **accessibility_target:** not applicable. The CLI surfaces (operator scripts, `aisandboxctl`) and the mTLS REST/WebSocket API have no UI. The Swagger UI shipped at `/v1/swagger-ui` is for API discovery only and is mTLS-gated; not a user-facing product surface.
- **performance_budgets:** none. Image build time, `attach` latency, REST p95, WebSocket throughput, and stream open-time are all operator-perceived but no formal budget is set. UC03 sets configurable caps (10 conn/10s per IP, 10 concurrent streams per client, 100 server-wide, 256 KiB binary / 16 KiB text WebSocket message, 256 KiB per-stream output buffer, 64 KiB POST body, 60s spawn timeout) — these are safety caps, not performance targets.
- **documentation:**
  - **README.md** — single source of operator documentation. Per UC03 §48, refreshed to cover: host JDK 21+ prerequisite, unpacking the release zip, `systemd enable/start`, `aisandboxctl pki init` + `client mint` + `client revoke` flow, the single TLS port + default bind, the OAS + Swagger UI URLs, and the inherited UC02 "known foot-guns" (shared workspace/claude-config, git push races).
  - **LICENSE** (MIT) at the repo root.
  - **PROJECT_BRIEF.md** (this file) — source of truth for project shape and contracts.
  - **`docs/THREAT_MODEL.md`** — UC03 deliverable. Scope as described under `security` above. Authored by the dev-team during UC03's `/develop` run; does not exist yet.
  - **`server/STREAM_PROTOCOL.md`** — UC03 deliverable (§49). Documents the WebSocket framing: subprotocol name + version policy, binary-vs-text frame discrimination, JSON schemas for `resize` / `mouse` / `error` / `close`, and the close-code matrix. Authored by the dev-team during UC03's `/develop` run.
  - **`server/openapi.yaml`** — springdoc-generated OpenAPI 3.x spec. Runtime-served at `/v1/openapi.yaml` and dumped at build time to the committed file; CI fails on drift (UC03 §23).
  - No ADRs and no docs site today.
- **observability:**
  - **Container tier (unchanged):** `docker compose logs claude-sandbox` is the only window into container behavior. No metrics, no tracing, no aggregation. Single-user local tool.
  - **Java server tier (new):** operational logs are **JSON Lines on stdout** (captured by journald via the systemd unit). Required line fields: `ts`, `level`, `logger`, `msg`, plus — where applicable — `identity` (CN), `fingerprint`, `action`, `target`, `outcome`. The separate **audit log** at `/var/log/ai-sandbox-server/audit.log` carries the audit subset (daily rotation, 7-day retention, JSON Lines). No metrics endpoint in MVP; no Prometheus, no tracing, no APM. Spring Boot Actuator is in scope only for the operational endpoints UC03 names (notably `/v1/healthz`, which is mTLS-gated like everything else).

## Profiles

This project opts into the following profile skills. Per `CLAUDE.md`'s precedence rules, the brief always wins; active profiles apply where the brief is silent; model defaults are the final fallback.

- **`profile-java-server-architecture`** — opinionated Java server conventions: Gradle (latest stable), Spring Boot (latest stable), Java (latest LTS, here pinned to 21); repositories return DTOs (never JPA entities); internal DTOs never cross the API boundary (the controller layer has its own request/response DTOs); strict Controller/Job → Facade → Service → Repository call chain with **transactions owned by the facade**. The dev-team must follow this layering when implementing the management server under `server/src/main/java/**`. The repositories-return-DTOs rule applies even though there is no data store today — if the dev-team introduces persistent state during implementation, it must comply.
- **`profile-java-call-graph-tool`** — provisions the [java-class-call-scanning](https://github.com/HaroldHormaechea/java-class-call-scanning) bytecode call-graph analyzer for the dev-team. The `develop` skill's Step 3b downloads the latest release jar to a per-user cache and writes an MCP server entry to `<TARGET_DIR>/.mcp.json`; agents use the nine query operations (`find-callers`, `find-callees`, `methods-in-class`, `methods-at-line`, `find-field-readers`, `find-field-writers`, `impact-of-diff`, `tests-for-diff`, `refresh-index`) either through MCP tools or against the cached jar.

## Deployment

Deployment now spans two co-located targets on the same operator host: the **container tier** (one Docker Compose project per Claude session, unchanged from UC02) and the new **Java management server tier** (a systemd-managed long-running JVM process introduced by UC03). Both ship from the same repo and the same git tag flow, but they install differently: the container side is clone-and-build, the Java side ships as a tag-triggered release zip.

### Production

- **hosting:** the operator's own host machine (Linux for the full feature set; macOS and Windows can still run the container tier but **not** the systemd-managed Java service). No cloud, no PaaS, no managed runtime. The "production environment" is whatever Docker daemon the operator has installed, plus — on Linux — a host OpenJDK 21+ runtime and `systemd`. A dedicated non-root system user **`ai-sandbox-server`** (member of the `docker` group) owns the Java service process.
- **cloud:** none.
- **iac:**
  - **Container tier:** `docker-compose.yml` + `SandboxDockerfile` are the entire infrastructure surface for the per-session containers. No Terraform, no Pulumi, no CDK.
  - **Java server tier:** a `systemd` unit file (`ai-sandbox-server.service`) is shipped alongside the server fat jar. Hardening directives include `NoNewPrivileges=true`, `ProtectSystem=strict`, `ProtectHome=true`, `PrivateTmp=true`, `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`, `Restart=on-failure`, `RestartSec=5s`, `StartLimitBurst=5`, `StartLimitIntervalSec=60s`, `WantedBy=multi-user.target` (UC03 §3). A sample `config.yaml` (server-side YAML at `/etc/ai-sandbox-server/config.yaml`) is shipped beside the unit file. No higher-level IaC tool.
- **ci_cd: GitHub Actions.** Two workflows live in `.github/workflows/`:
  - **`server-ci.yml`** — runs on every PR touching `server/**` and on every merge to `main`. Executes `./gradlew :server:spotlessCheck :server:test` (which exercises unit + Testcontainers integration tests, UC03 §45–46). Failures block merge. The shell/Dockerfile side has no CI today.
  - **`server-release.yml`** — triggered by version tags matching `server-vX.Y.Z` (UC03 §47). Builds the server fat jar, builds the `aisandboxctl` fat jar, regenerates the springdoc OAS, and emits **a single release zip** containing: the two jars, `server/openapi.yaml`, the systemd unit file, the sample `config.yaml`, and a release README. Modelled after the `java-class-call-scanning` project's release flow; no intermediate artifacts are published (no per-module jars, no plugin Maven repository).
- **distribution:**
  - **Container tier (unchanged):** source on GitHub at `https://github.com/HaroldHormaechea/ai-sandbox`. Operators clone, run `./setup.sh` or `.\setup.ps1`, and get a local container. The image (`ai-context:latest`) is built locally on each operator's host — not published to any registry.
  - **Java server tier (new):** **tag-driven release zip** published as a GitHub Release asset by `server-release.yml`. The operator downloads the zip from the GitHub Releases page, unpacks it, installs the jars/unit/config to their target paths, and starts the service via `systemctl enable --now ai-sandbox-server`. Clone-and-build via `./gradlew :server:bootJar` still works for contributors.
- **environments:** a single `local` environment per operator. There is no dev/staging/prod split — there is no shared deployment target. The Java server runs in the same single environment as the containers.
- **secrets:**
  - **Container tier (unchanged):** host-side `secrets/` folder (gitignored, `.gitkeep` placeholder), bind-mounted read-only into each container at `/etc/secrets`. Holds `git-key` (SSH private key) and optional `gh-token`. The operator places these via the setup wizard (`setup.sh` step 1 for SSH; step 3 for `gh auth login` writing to `gh-token`). No external secrets manager — the host filesystem with `0600` perms is the entire policy.
  - **Java server tier (new):** all material lives under `/etc/ai-sandbox-server/`:
    - `pki/server.crt` + `pki/server.key` — server cert and **plain PEM private key at mode 0600**, owned by `ai-sandbox-server`. `docs/THREAT_MODEL.md` flags the at-rest key for future review (passphrase-protected key + EnvironmentFile as the planned upgrade path).
    - `clients/<name>.crt` — one file per allowed client cert; folder at mode 0750. This **is** the trust anchor for mTLS; no CA. Filesystem-watched for hot reload; revocation = delete the file.
    - `config.yaml` — server config at mode 0644 (port, paths, caps, timeouts).
    - All four artifacts are generated/managed by the `aisandboxctl` companion jar (`aisandboxctl pki init`, `aisandboxctl client mint`, `aisandboxctl client revoke`, `aisandboxctl client list`). No external secrets manager.
- **observability:**
  - **Container tier (unchanged):** `docker compose logs claude-sandbox` is the only window into container behavior. No metrics, no tracing, no aggregation.
  - **Java server tier (new):** operational logs as **JSON Lines on stdout**, captured by journald via the systemd unit. Audit-tagged events additionally written to `/var/log/ai-sandbox-server/audit.log` (JSON Lines, app-managed daily rotation, 7-day retention, no size cap). No metrics endpoint in MVP, no tracing, no APM. `/v1/healthz` is mTLS-gated like every other endpoint.
- **dr:** none — state remains the operator's responsibility:
  - `workspace/*` (cloned project work, per UC02 shared-by-default), `workspace-<N>/` (per-N isolated overlay if used), `claude-config/` (Claude `/login` state) — backed up by the operator's host backup strategy.
  - `secrets/` — operator-supplied SSH key + optional `gh-token`; the operator maintains the source of truth.
  - **PKI material** under `/etc/ai-sandbox-server/pki/` and the client allowlist under `/etc/ai-sandbox-server/clients/` — the operator backs these up (or accepts that a loss means rotating via `aisandboxctl pki init` + reissuing client bundles via `aisandboxctl client mint`).
  - The container images, jars, and systemd unit are all rebuildable from a git tag, so no DR plan beyond "preserve the operator's host-side state and the source repo" is required.

### Development

- **environment:** three relevant audiences:
  - **End-user / operator (container tier):** the container *is* the dev environment for whatever Claude is working on. The host needs only Docker + a shell.
  - **Contributor to this repo — shell/Dockerfile side:** edit `.sh` / `.ps1` / `SandboxDockerfile` / `entrypoint.sh` natively on the host; validate with `docker compose build` and a fresh `setup.sh` run. Install `pre-commit` (`pip install pre-commit && pre-commit install`) so the host gitleaks scan fires before every commit.
  - **Contributor to this repo — Java server side:** edit under `server/` natively; install OpenJDK 21+ via the host package manager. Iterate with:
    - `./gradlew :server:test` — unit + integration tests. **Requires a working Docker daemon on the contributor host** because Testcontainers spins up real containers during integration tests.
    - `./gradlew :server:spotlessApply` — autoformat; `./gradlew :server:spotlessCheck` — verify (same command CI runs).
    - `./gradlew :server:bootJar` — build the server fat jar.
    - Local end-to-end run: generate dev PKI with `aisandboxctl pki init` against a scratch directory, mint a dev client cert with `aisandboxctl client mint dev`, then `java -jar server/build/libs/server-*.jar --spring.config.location=file:./config.yaml` pointed at the scratch PKI + clients dirs.
- **containerization:**
  - Container tier: required and central — the per-session product **is** a container; there is no non-containerized mode for the Claude side.
  - Java server tier: **not** containerized. The Java server runs as a host-resident JVM process under systemd, deliberately outside any container, so it can drive Docker via the UC02 host scripts and watch host filesystem paths (`/etc/ai-sandbox-server/`) directly. Running it in a container would re-introduce the very abstraction it must escape.
- **hot_reload:**
  - Container tier: not applicable. The container runtime is `tmux` running Claude in a while-loop; iterating on the image itself requires `docker compose down && docker compose build && docker compose up -d` (or just `./clean.sh` then `./setup.sh`).
  - Java server tier: Spring Boot DevTools is permitted in the contributor inner loop (re-runs on classpath changes) but is **off by default for the production fat jar** and never enabled in the systemd-deployed binary. UC03 explicitly hot-reloads only TLS materials and the client allowlist via filesystem watches; everything else is restart-only.
- **seed_data:**
  - Container tier: none. Operator-supplied (`secrets/git-key`, optional `secrets/gh-token`, the project that gets cloned into `/workspace`).
  - Java server tier: none. Trust state is the operator's PKI material; there is no data store to seed.
- **migrations:** not applicable. Neither tier has a data store or schema.

## Use Cases

Use cases are captured individually under `use-cases/` and indexed in `USE_CASES.md`.

## Scaffolding Plan

This repo already exists and contains all production assets (`SandboxDockerfile`, `docker-compose.yml`, `entrypoint.sh`, `setup.sh`/`setup.ps1`, `attach.sh`/`attach.ps1`, `clean.sh`/`clean.ps1`, `git-hooks/`, `.pre-commit-config.yaml`, `.gitignore`, `LICENSE`, `README.md`, plus the bind-mount placeholder dirs `workspace/`, `secrets/`, `claude-config/` each with a `.gitkeep`). It is already a git repo on `main` with remote `origin = https://github.com/HaroldHormaechea/ai-sandbox.git`.

The scaffold therefore reduces to creating the brief itself plus any project-builder-owned tracking files. Nothing to overwrite, nothing to re-init.

**Files to create (project-builder scope):**

- `PROJECT_BRIEF.md` — this file. Single source of truth for the project (already written and being finalized by this scaffolding step).

**Files NOT created here (deferred to other entry points):**

- `USE_CASES.md` (root ledger) and `use-cases/` (folder) — owned by `define-use-case`'s first invocation. Per `<SESSION_DIR>/CLAUDE.md`, `project-builder` MUST NOT create the ledger; it is created the first time the user runs `/define-use-case` against this project. The frontmatter `use_cases.index = USE_CASES.md` and `use_cases.folder = use-cases/` are recorded so `define-use-case` knows where to put them.
- `.claude/allowed-commands.yaml` — owned by the dev-team's developer agent during the first `/develop` run, not by project-builder.

**Shell commands to run:** none.

- No `mkdir` calls — `workspace/`, `secrets/`, and `claude-config/` already exist with `.gitkeep` files.
- No `git init` — already a work tree on `main`.
- No `git remote add origin …` — `origin` is already `https://github.com/HaroldHormaechea/ai-sandbox.git`.
- No `.gitignore` write — already present and correct (ignores `workspace/*`, `secrets/*`, `claude-config/*` while keeping `.gitkeep`).
- No commit, no push.

**VCS state (already true; recorded in frontmatter):**

- `vcs.enabled: true`
- `vcs.already_initialized: true`
- `vcs.default_branch: main`
- `vcs.remote: https://github.com/HaroldHormaechea/ai-sandbox.git`
