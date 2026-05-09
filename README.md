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
2. **Container image** — builds `ai-context:latest` if needed.
3. **`gh` login (optional)** — launches a disposable container that runs `gh auth login` and writes the resulting token to `secrets/gh-token`. Skip if you don't need `gh issue` / `gh pr` etc.
4. **Start sandbox** — brings the container up in the background.

The script is idempotent — re-run it any time to re-authenticate or rebuild.

After the container is running, attach to Claude and authenticate it on first run:

```bash
./attach.sh         # or .\attach.ps1 on Windows
# inside Claude: /login
```

The auth token is persisted to `claude-config/`, so you only need to do this once. None of `secrets/`, `claude-config/`, or `workspace/` is tracked by git.

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

## How it works

Claude is launched with `--dangerously-skip-permissions`, which disables every permission prompt — file writes, bash commands, network calls, all run without asking. This is safe *only* because the container itself is the trust boundary: Claude is confined to a non-root user inside Alpine, with no access to your host beyond the explicit bind mounts (`workspace/`, `claude-config/`, and the read-only `secrets/` folder).

All git operations are expected to go over SSH; no HTTPS-specific configuration (custom CA cert, credential helper) is set up. `gh` is configured to use SSH for `git_protocol`, so `gh repo clone OWNER/REPO` works the same way as a plain `git clone git@github.com:OWNER/REPO.git`. If `secrets/gh-token` is present (created via the setup walkthrough), the entrypoint also runs `gh auth login --with-token` so `gh`'s API operations work — those still go to `api.github.com` over HTTPS via the system CA bundle.

On boot, an entrypoint script copies the mounted SSH key into `~/.ssh/`, fixes its permissions (SSH refuses world-readable keys), writes an SSH config that pins the key to all hosts, then clones the bootstrap project if it isn't already there. After that, it starts a [`tmux`](https://github.com/tmux/tmux) session named `main` running Claude with the project directory as its working directory, and keeps the container alive with `tail -f /dev/null`. That tmux setup is what makes the detach/reattach workflow possible: Claude is never bound to your terminal, so disconnecting your client doesn't kill it. The `attach.sh` / `attach.ps1` scripts are thin wrappers around `docker compose exec claude-sandbox tmux attach -t main`.

Anything Claude can reach — your workspace files, the network, the SSH key (and therefore your git account), any credentials checked into a repo you cloned in — it can also modify or exfiltrate. The autonomous mode trades safety prompts for throughput; treat the workspace folder as "the agent could see and change this."
