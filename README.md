# ai-sandbox

## Description

A self-contained Docker environment for running [Claude Code](https://docs.claude.com/en/docs/claude-code) as a fully autonomous agent. Claude runs inside an Alpine container with all permission prompts disabled, so it can read, write, and execute freely without interrupting you for approvals. On first start the container clones a bootstrap project and opens Claude directly inside it. The container is the sandbox — let Claude work, and detach/reattach to its session whenever you want.

## How to use

### First-time setup

Run the guided setup walkthrough:

```bash
./setup.sh         # Linux / macOS
.\setup.ps1        # Windows
```

It steps you through:

1. **SSH key** — copies your private key to `secrets/git-key` (or confirms it's already there). Used for git clone/push.
2. **Git identity** — sets the `user.name` / `user.email` recorded on every commit Claude makes. Detects defaults from your host `git config --global` (and the SSH key's `.pub` comment as a secondary hint), prompts to confirm or override, writes `secrets/gitconfig`. The container applies it at boot via `git config --global include.path`, so it survives `clean.sh` and image rebuilds (the file lives on the host).
3. **Container image** — builds `ai-context:latest` if needed.
4. **`gh` login (optional)** — launches a disposable container that runs `gh auth login` and writes the resulting token to `secrets/gh-token`. Skip if you don't need `gh issue` / `gh pr` etc.
5. **Claude first-run** — launches Claude in a disposable container so you can do `/login`, accept the "trust this folder" prompt, and acknowledge the bypass-permissions warning. The state lives in `claude-config/` and persists, so the long-running daemon never asks again.
6. **Start sandbox** — brings the container up in the background.

The script is idempotent — re-run it any time to re-authenticate, rebuild, or replay the Claude first-run.

#### Upgrading an existing install

If you cloned this repo before the git-identity step shipped, your `ai-context:latest` image's `entrypoint.sh` does NOT yet apply `secrets/gitconfig`. After re-running `setup.sh` / `setup.ps1` once to capture identity, accept the rebuild prompt at step 3 — the wizard now defaults to Y when `secrets/gitconfig` is present so the new entrypoint logic lands in your image automatically.

After setup completes, attach to Claude:

```bash
./attach.sh         # or .\attach.ps1 on Windows
```

You'll drop straight into your already-authenticated session. None of `secrets/`, `claude-config/`, or `workspace/` is tracked by git.

### Attaching to the Claude session

```bash
./attach.sh         # Linux / macOS
.\attach.ps1        # Windows
```

Detach with `Ctrl+B`, then `D`. Claude keeps running in the background — reattach any time with the same command and you'll see exactly where it left off.

### Opening a separate shell (e.g. to clone more repos)

To run shell commands inside the container without disturbing Claude's session — for example, to clone additional projects into the workspace:

```bash
docker compose exec claude-sandbox sh
```

Inside that shell you're in `/workspace` as the `claude` user, with the SSH key already configured. Anything you clone here also appears in `./workspace/` on the host (and vice versa).

### Stopping

```bash
docker compose down
```

Workspace files, the cloned project, auth config, and the SSH key all persist on the host. The container itself is disposable.

### Resetting (start over from scratch)

If you want to wipe everything and run setup fresh — copied SSH key, gh token, Claude auth, cloned projects, the running container, and the built image:

```bash
./clean.sh         # Linux / macOS
.\clean.ps1        # Windows
```

The script lists exactly what it'll delete, warns about uncommitted work in `workspace/`, and requires you to type `yes` to confirm. It does **not** touch your real `~/.ssh/` keys or your host `gh` login. Re-running `setup.sh` after this rebuilds the image from scratch (slower — minutes instead of seconds).

## How it works

Claude is launched with `--dangerously-skip-permissions`, which disables every permission prompt — file writes, bash commands, network calls, all run without asking. This is safe *only* because the container itself is the trust boundary: Claude is confined to a non-root user inside Alpine, with no access to your host beyond the explicit bind mounts (`workspace/`, `claude-config/`, and the read-only `secrets/` folder).

All git operations are expected to go over SSH; no HTTPS-specific configuration (custom CA cert, credential helper) is set up. `gh` is configured to use SSH for `git_protocol`, so `gh repo clone OWNER/REPO` works the same way as a plain `git clone git@github.com:OWNER/REPO.git`. If `secrets/gh-token` is present (created via the setup walkthrough), the entrypoint also runs `gh auth login --with-token` so `gh`'s API operations work — those still go to `api.github.com` over HTTPS via the system CA bundle.

On boot, an entrypoint script copies the mounted SSH key into `~/.ssh/`, fixes its permissions (SSH refuses world-readable keys), writes an SSH config that pins the key to all hosts, then clones the bootstrap project if it isn't already there. With no command passed, it starts a [`tmux`](https://github.com/tmux/tmux) session named `main` running Claude with the project directory as its working directory, and keeps the container alive with `tail -f /dev/null`. That tmux setup is what makes the detach/reattach workflow possible: Claude is never bound to your terminal, so disconnecting your client doesn't kill it. The `attach.sh` / `attach.ps1` scripts are thin wrappers around `docker compose exec claude-sandbox tmux attach -t main`.

The same entrypoint also supports a one-off mode (used by setup step 5): when given a command like `claude --dangerously-skip-permissions`, it runs the bootstrap and then `exec`'s that command instead of starting tmux. This is how the wizard pre-handles `/login`, the trust dialog, and the bypass-permissions warning — the dialogs fire in a disposable container, but Claude's state is written to the bind-mounted `claude-config/`, so the persistent daemon inherits the accepted state.

Anything Claude can reach — your workspace files, the network, the SSH key (and therefore your git account), any credentials checked into a repo you cloned in — it can also modify or exfiltrate. The autonomous mode trades safety prompts for throughput; treat the workspace folder as "the agent could see and change this."

Build-time, the image fetches three things from the network alongside Alpine `apk` packages and the npm install of `@anthropic-ai/claude-code`: the pinned `gitleaks` release tarball, the latest `rtk` release tarball (see below), and the Alpine package index. All three widen the supply-chain surface to the same degree — no checksum verification is currently done for any of them. Treat upstream compromise of those projects as in scope when you reason about what an attacker could land inside the container at build time.

### Token compression (RTK)

The image bundles [`rtk` (Rust Token Killer)](https://github.com/rtk-ai/rtk), a CLI proxy that compresses Bash output before it reaches the LLM, reducing token spend on noisy commands. RTK is installed at `/usr/local/bin/rtk` from the [latest GitHub release](https://github.com/rtk-ai/rtk/releases/latest) at image build time (rolling-latest, matching the `@anthropic-ai/claude-code` pinning policy — not the pinned-version `gitleaks` pattern). The resolved version is echoed during `docker compose build` so you can see what you got. Upstream is licensed under Apache-2.0 (per the repo's `LICENSE`); both Apache-2.0 and MIT are compatible with ai-sandbox's MIT redistribution.

`entrypoint.sh` runs `rtk init -g` on every container start (idempotently, after the `claude-config/` bind mount is in place), which wires RTK's Bash hook into `~/.claude/settings.json` so Claude's Bash tool calls are transparently rewritten to `rtk <cmd>`. To see how many tokens RTK has saved, run:

```bash
docker compose exec claude-sandbox rtk gain
```

**Important limitation — built-in tools bypass RTK.** Claude Code's built-in `Read`, `Grep`, and `Glob` tools do **not** route through Bash, so they bypass the RTK hook and bypass token compression. The entrypoint appends a directive to `~/.claude/CLAUDE.md` asking Claude to prefer `cat`, `rg`/`grep`, and `find` instead. That directive is a preference, not an enforcement — the agent can still call the built-ins. If you see surprisingly high token usage on file-heavy work, this is the likely cause.

### Secret-leak protection

The image installs [`gitleaks`](https://github.com/gitleaks/gitleaks) and configures a system-wide git pre-commit hook (`git config --system core.hooksPath /etc/git-hooks`) that scans staged changes for credentials before each commit. Any commit inside any cloned project (including ones Claude makes autonomously) gets scanned — if a key, token, or other secret is detected, the commit is aborted with a redacted preview of the match.

To allowlist false positives, drop a `.gitleaks.toml` in the repo root following the [gitleaks config format](https://github.com/gitleaks/gitleaks#configuration). To bypass for a single commit (use sparingly), `git commit --no-verify`.

**Contributing to this repo:** the same scan also runs on the host side via the [pre-commit](https://pre-commit.com/) framework — see `.pre-commit-config.yaml`. After cloning, run once:

```bash
pip install pre-commit
pre-commit install
```

`pre-commit` auto-fetches the pinned gitleaks version into its own cache, so you don't need to install gitleaks separately on the host.
