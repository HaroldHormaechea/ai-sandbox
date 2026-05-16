# Use Case 02: Multi-session ai-sandbox containers

## Summary
Extend `ai-sandbox` from a single-container setup to multiple concurrent Claude sessions, each running in its own Docker container managed as a separate Docker Compose project named `ai-sandbox-N` (N a positive integer, monotonically increasing — never reused after cleanup). The existing `docker-compose.yml` is reused with `docker compose -p ai-sandbox-N ...`. `setup.sh` / `setup.ps1` is retained as bootstrap (image build, SSH key placement, optional `gh` login, Claude `/login`) and, on first run, implicitly spawns `ai-sandbox-1`. A new `spawn.sh` / `spawn.ps1` creates additional sessions; by default it shares the existing `./workspace/` and `./claude-config/` host directories, with flags to opt into per-session isolated workspace and/or claude-config. `attach.sh` / `attach.ps1` enumerates running `ai-sandbox-*` Compose projects via `docker compose ls`, shows each one's identifier alongside the current tmux window title of its `main` session (with `(idle)` as the fallback label), and either auto-attaches when exactly one exists, prompts when multiple, or errors when none. `clean.sh` / `clean.ps1` cleans a single session when given an `<N>` argument, and when invoked with no argument lists running sessions and prompts the operator. The legacy unnumbered `ai-sandbox` container goes away. POSIX `.sh` + PowerShell `.ps1` parity is preserved across all operator scripts. No new inbound network surface.

## Acceptance Criteria
1. `setup.sh` / `setup.ps1` performs bootstrap (build image, place SSH key, optional `gh` token, Claude `/login` into a disposable container) and, at the end, spawns `ai-sandbox-1` as the first session. Idempotent: re-running setup after a session already exists must not create a duplicate one.
2. A new `spawn.sh` / `spawn.ps1` (POSIX + PowerShell parity) launches a new session as Compose project `ai-sandbox-<N>` via `docker compose -p ai-sandbox-<N> up -d`.
3. `<N>` is read from a gitignored state file `./.ai-sandbox-counter` at the repo root, incremented atomically before use, and written back. The counter is monotonic across the project's lifetime: it never decreases, never repeats a previously issued value, and survives `clean.sh --all`-style operations.
4. The counter file is added to `.gitignore`. If the file is missing on first spawn, `setup.sh` creates it initialized at `1` (so `ai-sandbox-1` is the first issued value).
5. By default, spawn bind-mounts the shared `./workspace/` and `./claude-config/` host directories — identical mount layout as today's single-session setup.
6. The spawn script accepts a flag (and/or interactive prompt) to override the workspace mount to a per-session isolated host directory (e.g. `./workspace-<N>/`, auto-created).
7. The spawn script accepts a separate flag (and/or interactive prompt) to override the claude-config mount to a per-session isolated host directory (e.g. `./claude-config-<N>/`).
8. `attach.sh` / `attach.ps1` enumerate running `ai-sandbox-*` Compose projects via `docker compose ls` (Compose v2 native — matches the existing `docker_compose: "v2+"` minimum in PROJECT_BRIEF.md). Stopped projects are excluded.
9. For each running session, the attach script reads the tmux window title of the `main` session inside the container (e.g. `docker compose -p ai-sandbox-<N> exec claude-sandbox tmux display-message -p -t main '#W'`) and shows it next to the session number. When the title is empty or matches tmux's default, the label `(idle)` is shown instead.
10. If exactly one `ai-sandbox-*` session is running, the attach script attaches to it directly without prompting.
11. If zero `ai-sandbox-*` sessions are running, the attach script exits non-zero with a clear error pointing the user at `spawn.sh`.
12. If multiple are running, the attach script prompts the user to pick one by index (or quit), then `exec`s `tmux attach -t main` into that container.
13. `clean.sh <N>` / `clean.ps1 <N>` brings the `ai-sandbox-<N>` Compose project down and removes its containers and volumes; per-session host directories (`workspace-<N>/`, `claude-config-<N>/`) are wiped only when they exist (i.e. only when that session opted into isolation). The shared `./workspace/` and `./claude-config/` are never touched by a per-N clean.
14. `clean.sh` / `clean.ps1` invoked with no argument lists every running session and prompts the operator to pick one (or "all", or "cancel"). The counter file is NOT decremented or reset by any clean operation.
15. `secrets/` remains a single shared read-only mount across all sessions.
16. The system-wide gitleaks pre-commit hook fires inside every spawned session; the version-pinning discipline (host pre-commit + container `GITLEAKS_VERSION` build arg moving in lockstep) is unchanged.
17. The unnumbered `ai-sandbox` Compose project / container name does not survive; documentation does not reference it.
18. PROJECT_BRIEF.md `build.commands` is updated to reflect the new operator commands (build, setup, spawn, attach, clean) and the legacy single-container `up`/`down` entries are removed or repurposed.
19. README is updated to explain spawn/attach/clean, the shared-by-default workspace + claude-config, the opt-in isolation flags, and a "known foot-guns" note covering: concurrent file edits across sessions sharing a workspace, concurrent writes to `~/.claude/projects/` across sessions sharing claude-config, and concurrent `git push` races against the same remote branch. No code guard is added for any of these — operator-aware behavior only.
20. No new ports are published in any per-session Compose invocation.

## Potential Pitfalls & Open Questions
- **Accepted risk** — Shared `./claude-config/` across multiple concurrent Claude processes: `~/.claude/projects/`, `~/.claude/settings.json`, and hook state are shared mutable state; concurrent writes can clobber each other. Documented in README; no engineering mitigation.
- **Accepted risk** — Shared `./workspace/` across multiple concurrent sessions: file-edit races, conflicting git branches, etc. Documented in README; no engineering mitigation.
- **Accepted risk** — Concurrent `git push` from multiple sessions sharing one SSH key can race on the same remote branch. Documented in README; no engineering mitigation.
- **Edge case** — `attach.sh` reads the tmux window title via `docker compose exec` once per running session, so list latency grows linearly with session count. Acceptable for a desktop tool; revisit only if it becomes painful.

## Original Description
Currently this application starts a docker, and everything is sort of manual. I have to connect to ssh to the server this is running, and then attach to the container. This is for one annoying, and then I lose the ability to use tmux panel resizing etc because events are not properly transferred via the ssh.

I want this to be different.

The FINAL shape of this would be to have a client-server app that, via certificate, allows secure auth and connection and a proper UI to manage MULTIPLE claude sessions working in the server, each one in its docker container.

This is the FINAL shape, but I need to split this into multiple use cases, which I for now assume to be:

1. Allow multiple claude sessions, with the same or isolated workspaces: We'd need to create scripts that can spawn new docker containers with different names (e.g. ai-sandbox-N, with N being an integer) and the user should be able to choose if attach to one (with attach.sh offering to which session to attach by showing a list of active ones with their current claude conversation "name", attaching automatically if only one is active, or erroring out if none are) or another script allowing you to create a new one.

2. Offer later a client+server app that communicates via some scure connection, authenticates via certificate, and offer an UI view of active sessions, and menus to create new ones, kill, access, etc (this one would initially be handled by using claude design to design the UI)

## Clarifications
- Q: What should the "current Claude conversation name" displayed by attach.sh be?
  A: The tmux window/session title (Claude Code keeps it updated with the active task).
- Q: How should the existing single container be handled?
  A: Fresh start — the unnumbered `ai-sandbox` container is removed.
- Q: How should workspaces and Claude config be shared by default?
  A: Default to shared workspace + shared claude-config; flags/prompts to opt into per-session isolation.
- Q: How should sessions be orchestrated?
  A: Compose project name (`-p ai-sandbox-N`) on top of the existing `docker-compose.yml`.
- Q: What should clean.sh do?
  A: `./clean.sh <N>` cleans one; no-arg lists running sessions and prompts.
- Q: What should setup.sh do once the unnumbered container is gone?
  A: Bootstrap + implicit spawn of ai-sandbox-1.
- Q: How should N be assigned?
  A: Monotonic max+1 — never reused, even after cleanup.
- Q: What should the spawn script be named?
  A: `spawn.sh` / `spawn.ps1`.
- Q: Where should the monotonic counter live?
  A: Gitignored state file `./.ai-sandbox-counter` at the repo root.
- Q: What should attach.sh show when the tmux window title is empty/default?
  A: The literal label `(idle)`.
- Q: How should attach.sh enumerate sessions?
  A: `docker compose ls` (Compose v2 native).
- Q: Should anything actively guard against concurrent git pushes?
  A: Doc-only — README warning, no code.
