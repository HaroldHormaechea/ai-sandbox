---
schema_version: 1
project:
  name: ai-sandbox
  maturity_target: mvp
stack:
  languages: [Bash, PowerShell, Dockerfile]
  frameworks: []
  runtimes: [desktop]
  versions:
    alpine: "latest"
    gitleaks: "8.21.0"
    claude_code: "latest-at-build"
    rtk: "latest-at-build"
    node: "alpine-apk"
    tmux: "alpine-apk"
    docker_compose: "v2+"
  data_stores: []
build:
  tool: docker compose
  commands:
    test: null
    lint: null
    format: null
    build: "docker compose build"
    up: "docker compose up -d"
    down: "docker compose down"
    setup: "./setup.sh   # or .\\setup.ps1 on Windows"
    attach: "./attach.sh # or .\\attach.ps1 on Windows"
    clean: "./clean.sh   # or .\\clean.ps1 on Windows"
profiles: []
paths:
  production: ["**", "!workspace/**", "!secrets/**", "!claude-config/**", "!.git/**"]
  test: []
test:
  framework: none
  levels: []
  coverage_target: none
deployment:
  provider: local-host
  iac: docker-compose
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

- **constraints:**
  - Must run on the user's host (Linux, macOS, Windows) via Docker — no cloud, no SaaS.
  - Must provide POSIX-shell and PowerShell parity for every operator-facing script (`.sh` + `.ps1` mirror).
  - Container must be self-contained: no host tooling required beyond Docker, a shell, and (optionally) `pre-commit` for contributors of this repo.
- **runtimes:**
  - Host: Docker (any version supporting Compose v2) + a POSIX shell or PowerShell.
  - Container: Alpine Linux (`alpine:latest`).
  - In-container processes: Node.js (apk-supplied) hosting `@anthropic-ai/claude-code`; `tmux` keeping the Claude session alive across detach/reattach. `rtk` (Rust Token Killer) sits on the PATH as a passive CLI proxy invoked by Claude's Bash hook to compress command output before it reaches the LLM — no daemon process.
- **languages:**
  - Bash — operator scripts (`setup.sh`, `attach.sh`, `clean.sh`), `entrypoint.sh`, system-wide git hooks.
  - PowerShell — Windows mirrors of every `.sh` (`setup.ps1`, `attach.ps1`, `clean.ps1`).
  - Dockerfile — `SandboxDockerfile` that builds the `ai-context:latest` image.
- **frameworks:** none (no application framework; this is shell + Dockerfile + a single Compose service).
- **data_stores:** none. State lives entirely on the host filesystem via three bind mounts (`workspace/`, `secrets/` read-only, `claude-config/`).
- **auth_strategy:**
  - Git over SSH using a user-supplied private key copied into `secrets/git-key`; bind-mounted read-only into the container at `/etc/secrets/git-key` and copied to `~/.ssh/git-key` by the entrypoint.
  - Optional `gh` CLI auth via a token in `secrets/gh-token`; entrypoint runs `gh auth login --with-token` on boot if present.
  - Claude Code `/login` performed once in a disposable container during setup; persisted to `claude-config/` (bind-mounted to `~/.claude/` in the container) so the long-running daemon never re-prompts.
- **external_services:**
  - **GitHub** — git clone/push over SSH, `gh` CLI, `gh` API (`api.github.com` over HTTPS).
  - **Anthropic** — Claude Code authentication and API endpoints (used by `@anthropic-ai/claude-code`).
  - **gitleaks GitHub Releases** — build-time only; `curl` fetches the pinned `gitleaks_${VERSION}_linux_*` tarball during `docker build`.
  - **rtk-ai/rtk GitHub Releases** — build-time only; `curl` fetches the latest `rtk-<arch>-unknown-linux-{musl,gnu}.tar.gz` (amd64: musl; arm64: glibc + `apk add gcompat` shim) during `docker build`.
  - **Alpine `apk` repositories** — build-time only; `apk add` installs `nodejs npm git ripgrep bash ca-certificates tmux github-cli openssh-client curl` (plus `gcompat` on arm64 for the glibc `rtk` binary).
- **ai_ml_dependency:** Anthropic Claude (via `@anthropic-ai/claude-code`); model selection and configuration are owned by Claude Code itself, not by this project.
- **versions / pinning policy (intentional):**
  - `alpine` — rolling `latest` (rebuild picks up upstream changes).
  - `@anthropic-ai/claude-code` — rolling latest at image build time (no version pin).
  - `rtk` — rolling latest at image build time (matches the `@anthropic-ai/claude-code` policy, not the pinned `gitleaks` pattern). The resolved version is echoed during `docker build` so the actual version landed is visible in the build log.
  - `gitleaks` — pinned to `8.21.0` via the `GITLEAKS_VERSION` build arg in `SandboxDockerfile` and via the host pre-commit hook in `.pre-commit-config.yaml`. Both layers must move together when bumped.
- **build.tool:** `docker compose` (the only build surface).
- **build.commands:**
  - build image: `docker compose build`
  - bring container up: `docker compose up -d`
  - bring container down: `docker compose down`
  - first-run wizard: `./setup.sh` / `.\setup.ps1`
  - attach to Claude: `./attach.sh` / `.\attach.ps1`
  - destructive reset: `./clean.sh` / `.\clean.ps1`
  - host-side secret scan (contributors): `pre-commit run --all-files`
  - test / lint / format: none configured today.

## Architecture

- **platforms:** desktop / CLI on the operator's host (Linux, macOS, Windows). The product is the container plus its operator scripts; nothing is published to a web, mobile, or extension surface.
- **service_shape:** single-container monolith. One Compose service (`claude-sandbox`) running one Alpine image (`ai-context:latest`). No internal service-to-service architecture.
- **components:**
  - **Operator scripts (host)** — `setup.sh`/`setup.ps1`, `attach.sh`/`attach.ps1`, `clean.sh`/`clean.ps1`. Wizard, tmux attach wrapper, destructive reset.
  - **`SandboxDockerfile` (build)** — produces `ai-context:latest`: Alpine + nodejs/npm + git + ripgrep + bash + tmux + github-cli + openssh-client + curl + gitleaks 8.21.0 + `@anthropic-ai/claude-code` (global) + `rtk` (Rust Token Killer, rolling-latest; arm64 also pulls `gcompat`) + non-root `claude` user + system-wide git hooks at `/etc/git-hooks/`.
  - **`entrypoint.sh` (container PID 1)** — copies the SSH key from `/etc/secrets/git-key` to `~/.ssh/`, fixes perms, writes an SSH config, optionally `gh auth login --with-token` if `gh-token` is present, clones the bootstrap project into `/workspace/project-builder` if missing, then either `exec`s a one-off command (used by the wizard for `/login`) or starts `tmux new-session -d -s main` running Claude in a while-loop. Also runs `rtk init -g` idempotently on every start (after `claude-config/` is bind-mounted so the hook config persists) and conditionally appends an RTK bypass directive to `~/.claude/CLAUDE.md` under sentinel markers, skipping if upstream RTK already documented the bypass in `CLAUDE.md` or `RTK.md`.
  - **`tmux` session `main` (in-container, long-running)** — keeps Claude alive across `docker compose exec` detach/reattach cycles; restarts Claude in a loop on `/exit` so `attach` always lands in a fresh prompt.
  - **System-wide git hooks (in-container)** — `/etc/git-hooks/pre-commit` runs gitleaks on staged changes for any commit Claude makes inside any cloned project.
  - **Host pre-commit hook (contributors only)** — `.pre-commit-config.yaml` invokes the same gitleaks v8.21.0 via the [pre-commit](https://pre-commit.com/) framework on the host before commits land in *this* repo.
- **communication:**
  - Operator → Docker daemon — `docker compose` over the local socket.
  - Operator → container — `docker compose exec` for `tmux attach -t main` and ad-hoc shells.
  - Container → host filesystem — three bind mounts: `./workspace ↔ /workspace` (rw), `./secrets ↔ /etc/secrets` (ro), `./claude-config ↔ /home/claude/.claude` (rw).
  - Container → GitHub — git over SSH (port 22) using `~/.ssh/git-key`; `gh` CLI/API over HTTPS to `api.github.com`.
  - Container → Anthropic — HTTPS, owned by `@anthropic-ai/claude-code`.
- **async_workloads:**
  - The Claude `tmux` while-loop is the only long-running process. No schedulers, queues, brokers, or background workers.
- **integrations:**
  - **GitHub** — `git clone`/`fetch`/`push` over SSH; `gh` CLI for API operations (`gh issue`, `gh pr`, etc.).
  - **Anthropic Claude** — via `@anthropic-ai/claude-code`.
  - **gitleaks GitHub Releases** — pulled at `docker build` time only.
  - **rtk-ai/rtk GitHub Releases** — pulled at `docker build` time only.
  - **Alpine `apk` repos** — pulled at `docker build` time only.
- **data_flow_narrative:** The operator runs a host script (`setup.sh`/`attach.sh`/etc.). That script invokes `docker compose` against the local Docker daemon, which boots `claude-sandbox` from `ai-context:latest`. The entrypoint hydrates auth (SSH key from `secrets/`, optional `gh` token, persisted Claude `/login` state from `claude-config/`) and starts the `tmux main` session. Claude runs inside that session, reading and writing within `/workspace` (which is the host's `./workspace/` directory), and reaching out to GitHub and Anthropic when it needs to clone, push, or talk to its model. The operator detaches and reattaches via `docker compose exec claude-sandbox tmux attach -t main`. Nothing inbound from the network — there are no exposed ports.
- **trust_boundaries:**
  - **Container-as-sandbox.** The container itself is the trust boundary. Claude runs as the non-root `claude` user inside Alpine and can only see the host through the three bind mounts. The operator may run Claude with `--dangerously-skip-permissions` because *that* permission grant is bounded by Linux user perms + Docker isolation, not by an in-app prompt.
  - **`secrets/` is read-only.** The bind mount is `:ro`, so even a fully autonomous Claude cannot rewrite the SSH key or `gh` token in place — though it can read them, and via SSH it has the same authority over remote git as the operator.
  - **gitleaks at two layers.** Host pre-commit (this repo's contributors) and container-wide system hook (every commit Claude makes anywhere inside the container). Both pinned to gitleaks 8.21.0 and must move together.
  - **No inbound network surface.** No ports published in `docker-compose.yml`; the container is reachable only via `docker compose exec` from the same host.
  - **Bind-mount dirs are not project source.** `workspace/`, `secrets/`, and `claude-config/` are runtime state owned by the operator/Claude — they are excluded from `paths.production` so the dev-team never edits them.
  - **RTK as a CLI proxy on the PATH, not a new trust boundary.** `rtk` is a passive binary at `/usr/local/bin/rtk` invoked by Claude's Bash hook (configured by `rtk init -g` writing to `~/.claude/settings.json`). It runs in-process as the `claude` user; it does not add inbound surface or change isolation properties. The supply-chain note above for `rtk-ai/rtk` GitHub Releases (build-time fetch, rolling-latest, no checksum) is the relevant risk.
- **multi_tenancy:** not applicable. Single-user, single-host, single-container. There is no concept of "another tenant"; there is only "the operator running this on their machine."

### Production / test path scopes

- `paths.production`: `["**", "!workspace/**", "!secrets/**", "!claude-config/**", "!.git/**"]` — wide scope: the developer agent may edit anything at the repo root EXCEPT the three bind-mount dirs (which are runtime state, not source) and `.git/` (VCS metadata).
- `paths.test`: `[]` — no test code today. To be revisited if a shell/PowerShell test harness is added later.
- `paths.api_boundary`: not applicable — there is no API layer.

## Quality & Standards

- **style_guide:** language defaults; no formal house style. Match the conventions already present in `setup.sh` / `setup.ps1` for shell and PowerShell parity.
- **linters_formatters:**
  - Bash: none configured today (shellcheck/shfmt are reasonable future additions).
  - PowerShell: none configured today (PSScriptAnalyzer is a reasonable future addition).
  - Dockerfile: none configured today (hadolint is a reasonable future addition).
- **testing:**
  - levels: none today.
  - coverage_target: none.
  - frameworks: none. (bats and Pester are the obvious choices if a test harness is added later, but no commitment today.)
- **security:**
  - **Secret scanning — host layer.** `.pre-commit-config.yaml` invokes gitleaks v8.21.0 via the [pre-commit](https://pre-commit.com/) framework before commits land in this repo. Contributors install with `pip install pre-commit && pre-commit install`.
  - **Secret scanning — container layer.** `SandboxDockerfile` installs gitleaks 8.21.0 and configures a system-wide git pre-commit hook (`git config --system core.hooksPath /etc/git-hooks`) that scans every commit Claude makes in any cloned project inside the container. To allowlist a finding, drop a `.gitleaks.toml` in the affected repo's root; to bypass for a single commit, `git commit --no-verify` (used sparingly).
  - **Pinning discipline.** Both gitleaks layers are pinned to the same version; bumping requires updating `.pre-commit-config.yaml` and the `GITLEAKS_VERSION` build arg in `SandboxDockerfile` together.
  - **Container hygiene.** Non-root `claude` user inside the image; no published ports in `docker-compose.yml`; `secrets/` bind mount is `:ro`.
  - **No dependency scanner today.** No `npm audit` / `pip-audit` / Trivy / Snyk wired up. (npm-side surface is just `@anthropic-ai/claude-code`; system packages come from Alpine `apk`.)
  - **No SAST today.**
  - **Threat-model document — none today.** No `docs/THREAT_MODEL.md` exists; one is not committed as a deliverable. The container-as-trust-boundary assumptions and surviving risks (SSH key reachable by Claude, outbound network to GitHub/Anthropic, shared host kernel) are described informally in the README. Authoring a formal threat model may be revisited later but is not part of the current scope.
- **accessibility_target:** not applicable. No UI surface.
- **performance_budgets:** none. Image build time and `attach` latency are operator-perceived but no formal budget is set.
- **documentation:**
  - README.md is the single source of operator documentation; it covers first-run setup, attach/detach, stop, reset, the security model, and secret-leak protection.
  - LICENSE (MIT) at the repo root.
  - PROJECT_BRIEF.md (this file) is the source of truth for project shape and contracts.
  - No ADRs and no docs site today.
- **observability:**
  - logs only, ad hoc — `docker compose logs claude-sandbox` is the only window into container behavior. No metrics, no tracing, no aggregation. Single-user local tool; nothing to centralize.

## Profiles

None

## Deployment

### Production

- **hosting:** the operator's own host machine (Linux, macOS, or Windows running Docker). No cloud, no PaaS, no managed runtime. The "production environment" is whatever Docker daemon the user has installed.
- **cloud:** none.
- **iac:** `docker-compose.yml` plus `SandboxDockerfile` are the entire infrastructure surface. No Terraform, no Pulumi, no CDK.
- **ci_cd:** none today. No `.github/workflows/`. Releases happen by pushing source to GitHub; users pull and rebuild.
- **distribution:** source on GitHub at `https://github.com/HaroldHormaechea/ai-sandbox`. Users clone, run `./setup.sh` or `.\setup.ps1`, and get a local container. The container image (`ai-context:latest`) is built locally on each user's host — it is not published to any registry.
- **environments:** a single `local` environment per user. There is no dev/staging/prod split because there is no shared deployment target.
- **secrets:** host-side `secrets/` folder (gitignored, `.gitkeep` placeholder), bind-mounted read-only into the container at `/etc/secrets`. Holds `git-key` (SSH private key) and optional `gh-token`. The operator places these via the setup wizard (`setup.sh` step 1 for SSH; step 3 for `gh auth login` writing to `gh-token`). No external secrets manager — the host filesystem with `0600` perms is the entire policy.
- **observability:** `docker compose logs claude-sandbox` only. No metrics, no tracing, no log aggregation. Single-user local tool.
- **dr:** none. State is the operator's responsibility — `workspace/` (cloned project work) and `claude-config/` (Claude `/login` state) live on the host filesystem and are backed up by whatever the operator's host backup strategy is. The image and container itself are disposable; `clean.sh` / `clean.ps1` exists specifically to wipe and rebuild from scratch.

### Development

- **environment:** there are two relevant audiences:
  - **End-user / operator:** the container *is* the dev environment for whatever Claude is working on. The host needs only Docker + a shell.
  - **Contributor to this repo:** edit `.sh` / `.ps1` / `SandboxDockerfile` / `entrypoint.sh` natively on the host, validate with `docker compose build` and a fresh `setup.sh` run. Install `pre-commit` (`pip install pre-commit && pre-commit install`) so the host gitleaks scan fires before every commit.
- **containerization:** required and central — the project IS a container. There is no non-containerized mode.
- **hot_reload:** not applicable. The runtime is `tmux` running Claude in a while-loop; iteration on the image itself requires `docker compose down && docker compose build && docker compose up -d` (or just `./clean.sh` then `./setup.sh`).
- **seed_data:** none. All state is operator-supplied (`secrets/git-key`, optional `secrets/gh-token`, the project that gets cloned into `/workspace`).
- **migrations:** not applicable. No data store, no schema.

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
