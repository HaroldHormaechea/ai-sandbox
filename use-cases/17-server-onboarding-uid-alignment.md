# Use Case 17: Out-of-box server onboarding + uid-aligned session permissions

## Summary
On a fresh `.deb` install the server guides the operator through one-time onboarding so spawned sessions work out of the box, and fixes the uid/permission misalignment that currently breaks them. Onboarding is delivered as a **wizard**: triggered via **debconf** during install (with a non-interactive fallback that defers cleanly), and re-runnable later as a standalone `aisandboxctl` command to override/refresh config. It reuses the existing `pki init` / `secrets seed` machinery — SSH key, git name/email, optional gh token, and a Claude pre-init snapshot captured via an ephemeral OAuth container into `/etc/ai-sandbox-server/{secrets,templates/claude-config}` — with a **per-component check** so already-present artifacts are left alone unless `--force`. The permission fix **runs the session container as the `ai-sandbox-server` uid (compose `user:`)** so it matches the server-owned tree it mounts, preserving the README "secrets readable by the runtime user" model (no chowning secrets to a container-specific uid); it also pre-creates per-session bind-mount dirs with the right owner so Docker never auto-creates them as root. Scope is **fresh installs + newly-spawned sessions only** — this is experimental, so no backwards-compatibility/migration for existing installs is required; the PR and the run's console output document the manual cleanup for already-broken installs. Ships as a new `server-vX.Y.Z`; extends UC-06.

## Acceptance Criteria
1. A fresh `.deb` install triggers an interactive onboarding wizard (debconf) capturing SSH key, git name/email, optional gh token, and Claude pre-init; under no-TTY/unattended conditions it does **not** hang or fail the package install — it defers with a clear instruction to run the wizard later.
2. The same onboarding wizard is re-runnable later via a standalone `aisandboxctl` command to override/refresh configuration.
3. Per-component check: each already-present artifact (pki, `git-key`, `gitconfig`, `gh-token`, claude template) is left untouched unless `--force`; only missing pieces are gathered.
4. After onboarding, secrets exist under `/etc/ai-sandbox-server/secrets/` owned by the `ai-sandbox-server` user per the existing security model (`git-key` mode 0600).
5. Session containers run as the `ai-sandbox-server` uid:gid (compose `user:`), so they can read those secrets and read/write the mounted `~/.claude` and `/workspace`.
6. A spawned session stays `Up` (no `Permission denied` creating `~/.claude/CLAUDE.md` or `RTK.md`), clones the configured repo over SSH (no `Permission denied (publickey)`), `git commit` uses the seeded author identity, and the session inherits the Claude pre-init template on first boot.
7. uid alignment covers per-session isolated dirs created by `spawn.sh` at runtime (`claude-config-N`, `workspace-N`) — created with the correct owner before `compose up`, so Docker never auto-creates them as `root`.
8. The interactive Claude OAuth step is skippable for headless installs (`--no-claude-preinit`); sessions degrade gracefully (skip the template copy) when it is absent.
9. Idempotent: re-running the wizard or reinstalling the `.deb` does not overwrite existing config without `--force` and does not break a working install.
10. No migration of existing installs is performed; the PR description **and the run's console output** document the manual cleanup steps for already-broken installs (e.g. re-seed, or `chown` the existing `/etc/ai-sandbox-server` tree and per-session dirs to the runtime uid).

## Potential Pitfalls & Open Questions
- **Risk** — running the container as the server uid requires the in-image `/home/claude` (owned by `claude` uid 1000 at build) and its **non-mounted** dotdirs (`~/.ssh`, `~/.config/rtk`, the `~/.claude.json` symlink target) to be writable by the host-variable server uid. The Dockerfile/entrypoint must make `$HOME` writable by the runtime uid (e.g. group-writable + matching gid, or a root entrypoint pre-step that chowns then drops privilege via gosu/su-exec). Note: the live manual fix on potato-server validated the **alternative** approach (chown the server tree to the container's uid 1000). The dev-team should confirm run-as-server-uid is genuinely cleaner than pin-uid+chown and flag back to the user if the in-image-home complication makes pinning preferable.
- **Open** — the `ai-sandbox-server` uid is host-assigned by the `.deb` `useradd` (997 on the reference host, may vary). The spawn path must inject the real uid:gid into compose `user:` at runtime (e.g. via an env var the server resolves from its own process uid), not hardcode a value.
- **Edge case** — passphrase-protected SSH keys must be stripped/kept passphrase-free for non-interactive git inside the container (the existing `secrets seed --git-key-passphrase-file/--git-key-passphrase-env` flags).
- **Assumption** — the existing `aisandboxctl pki init` / `secrets seed` machinery (including the ephemeral-container Claude OAuth login and template snapshot) is reused and wired into the wizard, not rebuilt.

## Original Description
When installing the .deb package, the operator should be prompted to run the setup/seed step (unless a previous installation already has it configured), so that everything is set up out of the box. The prompt should capture: the GitHub SSH key, git author name/email, and gh token. It should also perform the Claude initial setup (OAuth/login) using a temporary container — the same thing we currently do manually — and snapshot that into the claude-config template so spawned sessions inherit it.

Critically, this work MUST also fix the permission/uid issues we just diagnosed on potato-server:
- Session containers run as `claude` uid 1000, but the server-managed tree under `/etc/ai-sandbox-server/` (secrets, sessions/claude-config, sessions/workspace, templates/claude-config) and the per-session bind-mount source dirs are owned by the `ai-sandbox-server` user (uid 997) or by root (0:0, when Docker auto-creates a missing bind-mount source). Result: the session container cannot WRITE its mounted `~/.claude` (entrypoint.sh dies with "Permission denied" creating CLAUDE.md/RTK.md → container Exited 1) and cannot READ the seeded `git-key` (0600 owned by 997) → git clone over SSH fails with "Permission denied (publickey)".
- The base/operator container works only because the operator's own uid happens to be 1000, matching the container.
- The durable fix is uid alignment across the whole server-managed tree: either pin the container `claude` uid and have the installer/seed create + chown the tree (and per-session dirs) to that uid, or run the session container as the `ai-sandbox-server` uid via compose `user:` (which matches the README's "secrets readable by the runtime user" security model). The fix must cover BOTH the shared session dirs created at install AND the per-session isolated dirs created by spawn.sh at runtime (spawn.sh runs as the non-root server user, so it needs CAP_CHOWN or a root-capable step).

Goal: a fresh .deb install → guided setup → spawned sessions boot cleanly, inherit Claude pre-init, and can clone/commit over SSH, all out of the box, with no manual chown/secret-copying afterward. This is a server-release-side change (would ship as a new server-vX.Y.Z).

## Clarifications
- Q: How should the container/host uid mismatch be resolved (the core of the permission fix)?
  A: Run the session container as the `ai-sandbox-server` uid (compose `user:`), matching the README "secrets readable by the runtime user" model.
- Q: How should a fresh install prompt the operator to onboard (given Debian postinst can't reliably prompt under apt/unattended)?
  A: Use debconf to invoke a wizard. That wizard must also be invokable later (standalone CLI) to override the setup, as done manually today.
- Q: What should count as "already configured" so onboarding is skipped on reinstall/upgrade?
  A: Per-component check — only gather the missing artifacts.
- Q: Should this also repair an already-broken/mis-owned install (like potato-server now), or only guarantee fresh installs + new sessions?
  A: Fresh installs + new sessions only. This is experimental; no backwards-compatibility is accepted. Output (in the PR and in the run's chat console) what to do to clean up old installs.
