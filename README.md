# ai-sandbox

## Description

A self-contained Docker environment for running [Claude Code](https://docs.claude.com/en/docs/claude-code) as a fully autonomous agent. Claude runs inside an Alpine container with all permission prompts disabled, so it can read, write, and execute freely without interrupting you for approvals. The container is the sandbox — drop projects into `workspace/`, let Claude work on them, and detach/reattach to its session whenever you want.

## How to use

### First-time setup

Place your git CA certificate at `certs/git-certificate` (so Claude can clone/push over HTTPS). Then build and start the container:

```bash
docker compose up -d --build
```

Authenticate Claude on first run:

```bash
./attach.sh         # or .\attach.ps1 on Windows
# inside Claude: /login
```

The auth token is persisted to `claude-config/`, so you only need to do this once.

### Attaching to the Claude session

```bash
./attach.sh         # Linux / macOS
.\attach.ps1        # Windows
```

Detach with `Ctrl+B`, then `D`. Claude keeps running in the background — reattach any time with the same command and you'll see exactly where it left off.

### Opening a separate shell (e.g. to clone repos)

To run shell commands inside the container without disturbing Claude's session — for example, to `git clone` a project into the workspace:

```bash
docker compose exec claude-sandbox sh
```

Inside that shell you're in `/workspace` as the `claude` user. Anything you clone here also appears in `./workspace/` on the host (and vice versa).

### Stopping

```bash
docker compose down
```

Workspace files, auth config, and the certificate persist on the host. The container itself is disposable.

## How it works

Claude is launched with `--dangerously-skip-permissions`, which disables every permission prompt — file writes, bash commands, network calls, all run without asking. This is safe *only* because the container itself is the trust boundary: Claude is confined to a non-root user inside Alpine, with no access to your host beyond the three explicit bind mounts (`workspace/`, `claude-config/`, and the read-only git cert).

The container stays alive via a `tail -f /dev/null` and runs Claude inside a [`tmux`](https://github.com/tmux/tmux) session named `main`. That's what makes the detach/reattach workflow possible: Claude is never bound to your terminal, so disconnecting your client doesn't kill it. The `attach.sh` / `attach.ps1` scripts are thin wrappers around `docker compose exec claude-sandbox tmux attach -t main`.

Anything Claude can reach — your workspace files, the network, any credentials checked into a repo you cloned in — it can also modify or exfiltrate. The autonomous mode trades safety prompts for throughput; treat the workspace folder as "the agent could see and change this."
