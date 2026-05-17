# Use Case 05: Self-contained server release

## Summary

The UC03 server release zip becomes truly self-contained — drop the zip on a clean Ubuntu Server VM with only `openjdk-21-jdk-headless` + `docker.io` + `docker-compose-plugin` installed, run `sudo aisandboxctl pki init`, start systemd, done. The zip bundles **everything**: UC02 host scripts (`spawn.sh` / `clean.sh` / `attach.sh` / `lib.sh` / `setup.sh`) in both POSIX and PowerShell forms, the container build context (`docker-compose.yml`, `SandboxDockerfile`, `entrypoint.sh`), and the wizards (`setup.sh` / `setup.ps1`) for parity. Java still shells out to the same scripts — no Java reimplementation. Layout: install dir `/opt/ai-sandbox-server/` with `lib/` for jars and `host/` for the bundled scripts + Compose context; session state at `/var/lib/ai-sandbox-server/sessions/`; secrets at `/etc/ai-sandbox-server/secrets/`. The bundled copy is **frozen at server-release time**: UC02 bugfixes ship via a new server release tag. POSIX and PowerShell variants are both bundled even though today's Linux-only systemd installer only consumes `.sh` — `.ps1` is for a future Windows installer. `aisandboxctl pki init` is extended to be **the** "one-time per-host setup" command: creates the `ai-sandbox-server` system user if missing, populates `pki/`, `clients/`, `enrollment/`, `secrets/` placeholder, and `/var/lib/.../sessions/`, writes a `config.yaml` with all defaults baked-in. Re-running `pki init` refuses to overwrite existing material unless `--force` is passed. Script-executor invokes Compose with explicit `--project-directory /var/lib/.../sessions/` so workspace bind-mounts land outside the read-only install. A CI self-test job in `server-ci.yml` unzips the release artifact in a docker container, runs `pki init`, asserts the directory tree + modes match expectations. No backwards-compat for v0.0.2: clean cutover.

## Acceptance Criteria

### Release zip layout

1. `server-release.yml` produces a release zip whose top-level layout is: `lib/`, `host/`, `systemd/`, `openapi.yaml`, `STREAM_PROTOCOL.md`, `README.md`, `sample-config.yaml`.
2. `lib/` contains both fat jars (`aisandbox-server-X.Y.Z.jar`, `aisandboxctl-X.Y.Z.jar`).
3. `host/` contains UC02 host scripts: `spawn.sh`, `clean.sh`, `attach.sh`, `lib.sh`, `setup.sh`. POSIX mode `0755` inside the zip.
4. `host/` contains the PowerShell counterparts: `spawn.ps1`, `clean.ps1`, `attach.ps1`, `lib.ps1`, `setup.ps1`. Byte-identical to the repo originals.
5. `host/` contains the container build context: `docker-compose.yml`, `SandboxDockerfile`, `entrypoint.sh`. POSIX mode `0755` on `entrypoint.sh`.
6. Mutual relative paths inside `host/` are preserved (`setup.sh` sourcing `./lib.sh`, `docker-compose.yml` referencing `./SandboxDockerfile`).
7. Bundled scripts MUST NOT contain operator-environment-specific paths; they are byte-identical to the repo originals at the commit being released.
8. The Gradle `releaseBundle` task is the **single point of bundling** — it gains `from(rootProject.file("spawn.sh"))` etc. clauses. `server-release.yml` does no extra file-shuffling beyond invoking the task.
9. A re-run of the same release tag produces a byte-identical zip (deterministic bundling).

### Install-time layout (after operator unzips to /opt/ai-sandbox-server/)

10. The install dir tree is: `/opt/ai-sandbox-server/{lib,host,systemd,README.md,openapi.yaml,sample-config.yaml,STREAM_PROTOCOL.md}`.
11. Operator-managed dirs after `pki init`: `/etc/ai-sandbox-server/{pki,clients,enrollment,secrets,config.yaml}`, `/var/lib/ai-sandbox-server/sessions/`, `/var/log/ai-sandbox-server/`.
12. No code path inside the server jar reads from the user's home directory or from any external `ai-sandbox` repo. The server is path-locked to `/opt/ai-sandbox-server/` (RO), `/etc/ai-sandbox-server/` (RO), `/var/lib/ai-sandbox-server/` (RW), `/var/log/ai-sandbox-server/` (RW).

### `aisandboxctl pki init` — one-time per-host setup

13. `pki init` creates the `ai-sandbox-server` system user if missing (`useradd --system --no-create-home --shell /usr/sbin/nologin --user-group --groups docker ai-sandbox-server`). Requires being invoked as root.
14. `pki init` creates these directories owned by `ai-sandbox-server:ai-sandbox-server`:
    - `/etc/ai-sandbox-server/` (mode `0750`)
    - `/etc/ai-sandbox-server/pki/` (mode `0700`)
    - `/etc/ai-sandbox-server/clients/` (mode `0700`)
    - `/etc/ai-sandbox-server/enrollment/` (mode `0700`)
    - `/etc/ai-sandbox-server/secrets/` (mode `0700`, empty placeholder)
    - `/var/lib/ai-sandbox-server/sessions/` (mode `0750`)
    - `/var/log/ai-sandbox-server/` (mode `0750`)
15. `pki init` mints the server cert + key at `/etc/ai-sandbox-server/pki/{server.crt,server.key}` (key mode `0600`). Same behaviour as today.
16. `pki init` writes `/etc/ai-sandbox-server/config.yaml` with the install-layout defaults baked in: `hostscripts.repo-root: /opt/ai-sandbox-server/host`, `sessions.host-state-root: /var/lib/ai-sandbox-server/sessions`, `secrets.dir: /etc/ai-sandbox-server/secrets`, `enrollment.dir: /etc/ai-sandbox-server/enrollment`. Operator is not asked to edit any path post-install.
17. **`pki init` refuses to run if any of `/etc/ai-sandbox-server/{pki,clients,enrollment,secrets,config.yaml}` already exists, unless `--force` is passed.** Exit code 2; stderr lists the conflicting paths.
18. `pki init --force` overwrites; explicit operator opt-in. Documented in `aisandboxctl pki init --help`.
19. `pki init` is the only way to set up a new host. There is no longer a documented `install -d` step in the README for any of the operator-managed dirs.

### systemd unit

20. `ai-sandbox-server.service` declares: `User=ai-sandbox-server`, `Group=docker`, `ExecStart=/usr/bin/java ... -jar /opt/ai-sandbox-server/lib/aisandbox-server.jar`.
21. `ProtectHome=true` (no home-dir traversal needed — install is fully under `/opt`, `/etc`, `/var`).
22. `ReadOnlyPaths=/etc/ai-sandbox-server /opt/ai-sandbox-server/host`.
23. `ReadWritePaths=/var/log/ai-sandbox-server /var/lib/ai-sandbox-server`.
24. All other hardening directives unchanged from UC03 (`NoNewPrivileges`, `ProtectSystem=strict`, `PrivateTmp`, `RestrictAddressFamilies`, etc.).

### Runtime — Compose invocation

25. The script-executor service shells out as: `docker compose -f /opt/ai-sandbox-server/host/docker-compose.yml --project-directory /var/lib/ai-sandbox-server/sessions -p ai-sandbox-N <subcommand>`. **`--project-directory` is always passed explicitly** so workspace bind-mounts land at `/var/lib/.../sessions/workspace-N/` rather than under `/opt/.../host/` (read-only).
26. Per-session bind-mount paths derived from `--project-directory`: `workspace-N/`, `claude-config-N/`. UC02's existing env-var overrides (`AI_SANDBOX_WORKSPACE_HOST_PATH` etc.) continue to work.
27. The `secrets/` bind-mount resolves to `/etc/ai-sandbox-server/secrets/` (read-only into the container per UC02 convention).
28. The server's startup-check validates `spawn.sh` / `clean.sh` / `attach.sh` exist + executable under `/opt/ai-sandbox-server/host/` and passes without operator intervention.

### CI self-test

29. `server-ci.yml` gains a new job `release-install-smoke` that runs on every PR + every push to main. It:
    1. Invokes `./gradlew :server:releaseBundle` to produce the release zip.
    2. Spins up an `ubuntu:24.04` Docker container with `openjdk-21-jdk-headless` installed (Docker itself is not required for the install assertions — see step c).
    3. Inside the container: unzips the release into `/opt/ai-sandbox-server/`, runs `aisandboxctl pki init` (as root), asserts:
        - `/etc/ai-sandbox-server/{pki/server.crt,pki/server.key,clients,enrollment,secrets,config.yaml}` all exist with the documented modes.
        - `/var/lib/ai-sandbox-server/sessions/` exists.
        - `id ai-sandbox-server` returns valid.
        - `config.yaml` contains the four baked-in defaults from AC16.
        - Re-running `aisandboxctl pki init` exits non-zero with the conflict list, AND with `--force` succeeds.
    4. Total job time ≤ 3 min.
30. The smoke-test job is required-passing for PR merge (same gate as the existing `build` job).

### Documentation + upgrade

31. The release README documents the new install flow as: download → unzip to `/opt/ai-sandbox-server/` → `sudo aisandboxctl pki init` → operator populates `/etc/ai-sandbox-server/secrets/` (SSH keys + optional GH token) → `systemctl daemon-reload && systemctl enable --now ai-sandbox-server`. **No `git clone` step. No `chmod` step. No path-traversal step.**
32. The release README notes the v0.0.2 → v0.0.3 upgrade is a clean cutover: stop service, `rm -rf /opt/ai-sandbox-server/lib`, re-unzip new release, `systemctl restart`. Operator's existing `/etc/ai-sandbox-server/{pki,clients,enrollment,secrets,config.yaml}` is preserved across upgrade.
33. The release README documents the frozen-UC02 property: UC02 bugfixes ship via the next server release tag.
34. The aisandboxctl `--help` text reflects the new `pki init` behaviour (user creation, `--force`, directory list).

### UC04 continuity

35. The UC04 `aisandboxctl client invite` flow continues working end-to-end on the new layout: token file in `/etc/ai-sandbox-server/enrollment/`, server endpoint at `POST /v1/enrollment`, P12 round-trip, client cert lands in `/etc/ai-sandbox-server/clients/`.
36. The bundled `.ps1` files are not invoked by anything in v0.0.3 but are present and byte-identical to the repo originals (groundwork for a future Windows installer; out of scope for this UC).

## Resolved during clarification

- **Bundled directory layout** — `/opt/ai-sandbox-server/host/`.
- **Session state location** — `/var/lib/ai-sandbox-server/sessions/` (fixed default, no config knob).
- **Secrets location** — `/etc/ai-sandbox-server/secrets/` (fixed default).
- **`pki init` scope** — folds in `enrollment/`, `secrets/` placeholder, `/var/lib/.../sessions/`, AND creates the `ai-sandbox-server` system user.
- **`pki init` re-run contract** — refuses without `--force`. Idempotent recovery deferred to operator-manual.
- **Compose project-directory wiring** — explicit `--project-directory` passed on every invocation.
- **CI self-test** — yes, in `server-ci.yml`, every PR + every main push.
- **`setup.sh` / `setup.ps1` in bundle** — kept (matches "everything"), never invoked by server.
- **POSIX + PowerShell parity** — both shipped.
- **UC02 frozen at release time** — accepted; documented in README.
- **No v0.0.2 backwards-compat** — clean cutover; documented in README.

## Potential Pitfalls & Open Questions

- **Edge case** — **`SandboxDockerfile` build context.** When `docker compose up` builds the image, the build context is the dir containing `docker-compose.yml`. With the file at `/opt/.../host/`, the context is that dir, read-only. Docker doesn't need to write to it (only read), so this should work — but worth a smoke-test step in the CI job that does a `docker compose build --no-cache` on the released zip to make sure.
- **Risk** — **`pki init` running as root** is a privilege expansion vs UC04's "run as ai-sandbox-server" pattern. Required because `useradd` is root-only. Mitigation: README and `--help` make it explicit: `pki init` is the **only** aisandboxctl subcommand that needs root; everything else (`client mint`, `client invite`, `client list`, `client revoke`) runs as `ai-sandbox-server`.

## Original Description

- Self contained: Everything, including wizards and all files. I want this to work in a totally clean environment that only has java and docker installed.
- Bash/powershell still acceptable - don't reimplement in java yet
- Frozen at server release tiem
- Embed all in the package (ps1, sh); even if now systemd is linux only I may opt to create a windows installer later on
- No backwards compat required

## Clarifications

- Q: Bundled-host directory layout — where do the scripts live inside the install dir?
  A: `/opt/ai-sandbox-server/host/` — single dir at install root, matches the `host/` framing in the zip.

- Q: Where do per-session workspace + claude-config bind-mounts default to?
  A: `/var/lib/ai-sandbox-server/sessions/` — FHS-clean, service-owned `/var/lib` slot, fixed default with no config knob.

- Q: Where does the operator-supplied secrets/ folder live (SSH keys + GH token mounted into claude-sandbox)?
  A: `/etc/ai-sandbox-server/secrets/` — next to PKI material, operator-managed, mode `0700`, fixed default.

- Q: Should aisandboxctl pki init also create the UC04 enrollment dir?
  A: Yes — fold it in. `pki init` becomes the single "one-time per-host setup" creating pki/, clients/, enrollment/, secrets/ placeholder, sessions/, and config.yaml.

- Q: Should aisandboxctl pki init create the ai-sandbox-server system user too, or stay operator-managed?
  A: `pki init` creates the user if missing. Single-command setup; requires root.

- Q: On a second `aisandboxctl pki init` invocation, what's the contract?
  A: Refuse without `--force`. Exit code 2; stderr lists the conflicting paths. `--force` overrides per item.

- Q: Self-test in CI: should server-ci.yml validate the install flow on every PR?
  A: Yes — add a smoke-test job that unzips the release artifact and invokes pki init in a docker container.

- Q: Compose project-directory wiring — how does the script know where session state goes?
  A: Pass `--project-directory` explicitly in each `docker compose` call. No env-var trickery.
