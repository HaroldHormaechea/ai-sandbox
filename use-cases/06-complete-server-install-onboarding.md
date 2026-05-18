# Use Case 06: Complete server-install onboarding

## Summary

A new `aisandboxctl secrets seed` subcommand walks the operator through every preparation UC02's `setup.sh` wizard does — SSH key for git, git author identity, `gh auth login`, and one-off Claude pre-init — then persists the captured material into operator-managed locations on the server so future spawned sessions inherit it. Steps run in fixed order: (a) pick or generate an SSH key → `/etc/.../secrets/git-key` (encrypted keys are decrypted at copy time after prompting for passphrase); (b) capture git author name + email → `/etc/.../secrets/gitconfig`; (c) `gh auth login --web` (device flow) inside an ephemeral `claude-sandbox` container, surfacing the verification URL + code on stderr; then `gh auth token` extracts a single-line token to `/etc/.../secrets/gh-token` (or skip via `--no-gh`); (d) one-off `claude --dangerously-skip-permissions` session for OAuth sign-in via device flow, capturing the resulting `~/.claude/` as a template at `/etc/.../templates/claude-config/` (or skip via `--no-claude-preinit`). The bundled `docker-compose.yml` adds a read-only bind-mount of that template at `/etc/claude-template/` inside the container; `entrypoint.sh` runs `cp -a /etc/claude-template/. ~/.claude/` exactly once per session (gated by a `~/.claude/.seeded` marker), so each session has its own writable copy seeded from the operator's pre-init state. Clients can run `claude /login` inside their session to override and use their own account; respawns lose the override (sessions are ephemeral). Every step accepts CLI flags (`--git-key`, `--git-key-passphrase-file`, `--git-name`, `--git-email`, `--gh-token-file` / `--no-gh`, `--claude-config-source` / `--no-claude-preinit`) so the whole onboarding runs unassisted from Ansible / cloud-init. Re-run policy mirrors `pki init`: refuse without `--force`; `--force` overwrites all four steps. No refresh shortcut; expired tokens require `secrets seed --force`. The wizard never writes to `/opt/ai-sandbox-server/`. The existing UC05 manual-drop flow remains a documented fallback.

## Acceptance Criteria

1. New subcommand `aisandboxctl secrets seed`. Requires root; exit 2 with explicit message otherwise.
2. **SSH key step** — `--git-key PATH` copies the file; interactive mode lists `~/.ssh/` (excluding `*.pub`, `known_hosts*`, `config`, `authorized_keys`, `*.bak`) and lets operator pick OR generate ed25519 (`ssh-keygen -t ed25519 -f <secrets-dir>/git-key -N ""`). Result: `/etc/ai-sandbox-server/secrets/git-key`, mode 0600, owned `ai-sandbox-server:ai-sandbox-server`.
3. **Encrypted SSH key** — detect via `ssh-keygen -y -P "" -f <key>`; on encrypted, interactive prompts for passphrase (or `--git-key-passphrase-file PATH` / `--git-key-passphrase-env VAR` non-interactive), then writes a decrypted copy as `git-key` (mode 0600). Operator's host key untouched. At-rest-decrypted security model documented in operator notes.
4. **Git identity step** — `--git-name "..." --git-email "..."` flags; interactive prompts default to host's `git config --global user.{name,email}` (per `setup.sh`). Result: `/etc/.../secrets/gitconfig` with `[user]\n\tname = …\n\temail = …\n`, mode 0600, same ownership.
5. **gh auth step** — `--gh-token-file PATH` copies a pre-generated token byte-for-byte; `--no-gh` skips; interactive shells into an ephemeral `claude-sandbox` container with `-it`, runs `gh auth login --hostname github.com --git-protocol ssh --skip-ssh-key --web`, surfaces device-flow URL + code on stderr, then captures via `gh auth token` (single-line stdout). Result: `/etc/.../secrets/gh-token`, mode 0600, same ownership.
6. **Claude pre-init step** — `--claude-config-source PATH` copies an existing `~/.claude/`-shaped dir; `--no-claude-preinit` skips; interactive spawns a one-off `claude-sandbox` container with a scratch claude-config volume, runs `claude --dangerously-skip-permissions` relying on Claude CLI's device-flow OAuth. If the bundled Claude version doesn't support headless device-flow, the wizard fails with a clear remediation pointing at `--claude-config-source` (workstation-built tarball) or `--no-claude-preinit`. Result: `/etc/.../templates/claude-config/`, mode 0750 owned `ai-sandbox-server`, file modes preserved (typically 0640/0750).
7. **Per-session inheritance** — bundled `docker-compose.yml` adds a read-only bind-mount for `/etc/.../templates/claude-config/` → `/etc/claude-template/`. `entrypoint.sh` runs `if [ -d /etc/claude-template ] && [ ! -e ~/.claude/.seeded ]; then cp -a /etc/claude-template/. ~/.claude/ && touch ~/.claude/.seeded; fi` once per session before exec'ing the main command. Empty template (deployments using `--no-claude-preinit`) makes the cp a no-op; sessions behave as today.
8. **Override path** — `claude /login` inside any session overrides the seeded template for that session's lifetime. Documented; respawn loses override.
9. Single command, fixed step order (ssh → identity → gh → claude), `--force` overwrites all four. No per-step subcommands.
10. **Non-interactive contract** — all required flags present → zero prompts. `--no-gh` and `--no-claude-preinit` are the explicit opt-outs.
11. **TTY fallback** — flag missing + TTY → prompt only for that step.
12. **No-TTY failure** — flag missing + no TTY → exit non-zero, list every missing flag on stderr, no files written.
13. **Re-run policy** — refuse without `--force`; with `--force`, stderr lists every overwritten path. Conflict detection covers all four output targets even when individual steps are opted-out.
14. **UC05 migration** — operators on `server-v0.0.3` who already dropped secrets manually get the same refuse-without-`--force` treatment. `secrets seed --force` is the explicit migration path.
15. **No writes under `/opt/ai-sandbox-server/`** — read-only install dir untouched.
16. **Install ordering** — wizard runs `docker image inspect claude-sandbox:latest >/dev/null 2>&1`; on miss, automatically runs `docker compose -f /opt/.../host/docker-compose.yml --project-directory /opt/.../host build claude-sandbox` before proceeding to steps (e)/(f).
17. **README updated** — server install gains a documented `secrets seed` step between `pki init` and `systemctl enable`. UC05 manual-drop becomes an alternative-for-non-wizard-deployments footnote.
18. **PROJECT_BRIEF.md updated** — `## Deployment` reflects the new install story.
19. **No UC03/04/05 regression** — systemd unit, allowlist watcher, enrollment endpoint, release-zip layout, `pki init` semantics unchanged. Only `docker-compose.yml` (bundled) gains the new RO mount; `entrypoint.sh` gains seeding logic. Both ship in the release zip.
20. **CI smoke-test extended** — `release-install-smoke` adds a non-interactive flag-driven phase: `secrets seed --no-gh --no-claude-preinit --git-key /tmp/key --git-name X --git-email "x@y"`. Asserts file outputs, modes, ownership, re-run refusal, `--force` success. Interactive gh + Claude paths NOT smoked (need OAuth interaction); remain operator-tested at install time. AC29.4 ≤ 3 min budget honored.
21. **`setup.sh` / `setup.ps1` wizards untouched** — bundled per UC05 AC4; laptop UX unmodified.
22. **Output summary** — at end of `secrets seed`, print full list of files written (paths, modes, ownership) for operator audit.
23. **OpenAPI surface unchanged** — install-time CLI only; no server REST/WebSocket changes.
24. **Refresh story** — no automatic refresh; expiring gh tokens / Claude sessions require `secrets seed --force` (re-runs all four). Already-running sessions don't pick up new templates (gated by `~/.claude/.seeded`); operator must kill + respawn sessions to inherit.
25. **profile-java-server-architecture exception** — `secrets seed` is a thin install-time CLI command (root check, file I/O, container shell-outs). Deliberately NOT Controller/Facade/Service/Repository — that layering applies to server runtime, not install-time CLI. Documented in `PROJECT_BRIEF.md` `## Profiles` prose as the active exception.

## Potential Pitfalls & Open Questions

- **Edge case** — **Claude CLI headless device-flow availability.** The version bundled in the `claude-sandbox` image must support headless OAuth. Verify at implementation time. If it doesn't, AC6's "clear remediation" path (point operators at `--claude-config-source` / `--no-claude-preinit`) becomes the standard guidance.
- **Edge case** — **PTY allocation for ephemeral-container OAuth.** Device-flow `gh auth login --web` needs an interactive TTY to surface the URL + code cleanly. Pass `-it` to the `docker run` invocation; verify that stdout/stderr from inside the container reach the operator's terminal without buffering.
- **Edge case** — **Mid-flow failure recovery.** If step (e) succeeds but step (f) fails halfway, the operator has partial state. `--force` re-runs all four (correct but wasteful). No automatic per-step recovery; doc explains: on partial failure, re-run with `--force` once the external issue is resolved.

## Original Description

I need a new use case to make the "installer" to be more complete. The idea is that when you install this, before adding the service to your linux system, it has already prompted you with all the steps and set-up we had before in the setup.sh script: installing gh, ssh keys, triggering one claude session to confirm auth bypass permissions and such, git info, etc.

Future claude sessions created should inherit those settings when spawned.

All these items should be also capable of being provided via installer launch parameters to allow unassisted installations

## Clarifications

- Q: How should the onboarding wizard be wired into aisandboxctl?
  A: New subcommand: `aisandboxctl secrets seed`.

- Q: Where should the captured Claude template live, and how does each new session inherit it?
  A: `/etc` template, bind-mounted RO, `entrypoint.sh cp -a /template/. ~/.claude/` on first start.

- Q: How does the Claude account boundary work for multi-operator servers?
  A: Template is a starter; sessions can re-authenticate via `claude /login` to override.

- Q: How granular should the wizard be?
  A: Single command walks all 4 steps in order; `--force` overwrites all four.

- Q: How should the wizard handle headless OAuth for gh and Claude (server is typically a remote VM with no browser)?
  A: Force device-flow; surface URL + code on stderr so the operator can complete the flow from their workstation browser.

- Q: How should the wizard handle SSH keys that are encrypted with a passphrase?
  A: Prompt for passphrase, persist a decrypted copy in `/etc/.../secrets/git-key`.

- Q: How should the gh-auth step extract the token from the ephemeral container?
  A: Extract just the token value into `/etc/.../secrets/gh-token` as a single line (matches today's UC05 convention).

- Q: What's the refresh story when gh tokens / Claude sessions expire or templates change?
  A: No special refresh path — operator re-runs `secrets seed --force` to rebuild all four steps; new template only applies to newly-spawned sessions.
