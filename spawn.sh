#!/usr/bin/env bash
# spawn.sh — launch a new ai-sandbox-<N> session.
#
# See README.md § "Spawning additional sessions" for full usage.
#
# Exits non-zero on docker failure; on docker failure the counter is NOT
# rolled back (N is monotonic by design — AC3 in use-cases/02-…).
set -euo pipefail

cd "$(dirname "$0")"
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

# UC05 § AC11,AC25 — when running under the install-mode management server
# the script lives under /opt/ai-sandbox-server/host/ (read-only) and must
# route writes (counter file, lockdir, per-session workspace-N/ + claude-
# config-N/) to /var/lib/ai-sandbox-server/sessions/ instead. The server
# exports AI_SANDBOX_HOST_STATE_ROOT for that purpose; developer-mode runs
# leave it unset and spawn.sh continues to cwd at the repo root.
if [ -n "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
    mkdir -p "$AI_SANDBOX_HOST_STATE_ROOT"
    cd "$AI_SANDBOX_HOST_STATE_ROOT"
fi

usage() {
    cat >&2 <<'EOF'
Usage: ./spawn.sh [flags]

Launches a new Claude session as Docker Compose project ai-sandbox-<N>.

Flags:
  --isolated-workspace      Give this session its own ./workspace-<N>/ folder.
  --shared-workspace        Use the shared ./workspace/ (default).
  --isolated-claude-config  Give this session its own ./claude-config-<N>/.
  --shared-claude-config    Use the shared ./claude-config/ (default).
  --label <value>           Set the com.ai-sandbox.label container label.
  --non-interactive         Never prompt; use defaults for unspecified flags.
  --help, -h                Show this message.

Non-interactive mode is also engaged automatically when stdin is not a TTY.
EOF
}

# ── Parse flags ──────────────────────────────────────────────────────────────
WORKSPACE_MODE=""          # "shared" | "isolated" | "" (unset → ask or default)
CLAUDE_CONFIG_MODE=""      # "shared" | "isolated" | ""
LABEL=""
LABEL_SET=0
NON_INTERACTIVE=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --isolated-workspace)     WORKSPACE_MODE="isolated";     shift ;;
        --shared-workspace)       WORKSPACE_MODE="shared";       shift ;;
        --isolated-claude-config) CLAUDE_CONFIG_MODE="isolated"; shift ;;
        --shared-claude-config)   CLAUDE_CONFIG_MODE="shared";   shift ;;
        --label)
            [ "$#" -ge 2 ] || { warn "--label requires a value" >&2; usage; exit 2; }
            LABEL="$2"; LABEL_SET=1; shift 2 ;;
        --label=*)
            LABEL="${1#--label=}"; LABEL_SET=1; shift ;;
        --non-interactive)        NON_INTERACTIVE=1; shift ;;
        -h|--help)                usage; exit 0 ;;
        *)
            warn "Unknown flag: $1" >&2
            usage
            exit 2
            ;;
    esac
done

# Auto-engage non-interactive when stdin isn't a TTY.
if [ "$NON_INTERACTIVE" -eq 0 ] && [ ! -t 0 ]; then
    NON_INTERACTIVE=1
fi

# ── Resolve workspace / claude-config modes ──────────────────────────────────
if [ -z "$WORKSPACE_MODE" ]; then
    if [ "$NON_INTERACTIVE" -eq 1 ]; then
        WORKSPACE_MODE="shared"
    else
        printf "  Workspace: [s]hared (default) or [i]solated? [S/i]: " >&2
        read -r resp || resp=""
        case "$resp" in
            i|I) WORKSPACE_MODE="isolated" ;;
            *)   WORKSPACE_MODE="shared"   ;;
        esac
    fi
fi
if [ -z "$CLAUDE_CONFIG_MODE" ]; then
    if [ "$NON_INTERACTIVE" -eq 1 ]; then
        CLAUDE_CONFIG_MODE="shared"
    else
        printf "  Claude config: [s]hared (default) or [i]solated? [S/i]: " >&2
        read -r resp || resp=""
        case "$resp" in
            i|I) CLAUDE_CONFIG_MODE="isolated" ;;
            *)   CLAUDE_CONFIG_MODE="shared"   ;;
        esac
    fi
fi

# ── Acquire counter, read, increment, write, release ────────────────────────
if ! acquire_counter_lock 30; then
    warn "Could not acquire ./.ai-sandbox-counter.lock — aborting." >&2
    exit 1
fi

COUNTER_FILE="./.ai-sandbox-counter"
if [ ! -f "$COUNTER_FILE" ]; then
    # Defensive: setup.sh should have created this at 0, but tolerate missing.
    # File holds the last issued N (increment-before-use), so 0 → first issue
    # is ai-sandbox-1.
    printf "0\n" > "$COUNTER_FILE"
fi

CUR="$(tr -d '[:space:]' < "$COUNTER_FILE" || true)"
case "$CUR" in
    ''|*[!0-9]*)
        warn "Counter file $COUNTER_FILE is corrupt (value: '$CUR'). Resetting to 1." >&2
        CUR="0"
        ;;
esac

N=$(( CUR + 1 ))
# Atomic write: tmp + rename, then fsync via shell's `sync` for good measure.
TMP="${COUNTER_FILE}.tmp.$$"
printf "%s\n" "$N" > "$TMP"
mv "$TMP" "$COUNTER_FILE"
sync 2>/dev/null || true

release_counter_lock

PROJECT="ai-sandbox-${N}"

# ── Pre-create per-session host dirs ─────────────────────────────────────────
WORKSPACE_HOST_PATH="./workspace"
CLAUDE_CONFIG_HOST_PATH="./claude-config"

if [ "$WORKSPACE_MODE" = "isolated" ]; then
    WORKSPACE_HOST_PATH="./workspace-${N}"
fi
if [ "$CLAUDE_CONFIG_MODE" = "isolated" ]; then
    CLAUDE_CONFIG_HOST_PATH="./claude-config-${N}"
fi

# UC-17 — pre-create the resolved bind-mount source dirs (BOTH shared and
# isolated) before `compose up`. If a bind source does not exist, Docker
# auto-creates it as root:root, which a non-root session container (running as
# the server uid via compose `user:`) then cannot write — the original
# "Permission denied creating ~/.claude/CLAUDE.md" failure. Creating them here,
# as the user who runs spawn.sh (the ai-sandbox-server service user in install
# mode), gives them the right owner up front. `mkdir -p` is idempotent and
# harmless in developer mode where the shared dirs usually already exist.
mkdir -p "$WORKSPACE_HOST_PATH" "$CLAUDE_CONFIG_HOST_PATH"

# ── Launch ───────────────────────────────────────────────────────────────────
export AI_SANDBOX_WORKSPACE_HOST_PATH="$WORKSPACE_HOST_PATH"
export AI_SANDBOX_CLAUDE_CONFIG_HOST_PATH="$CLAUDE_CONFIG_HOST_PATH"
export AI_SANDBOX_LABEL="$LABEL"

info "Spawning $PROJECT" >&2
info "  workspace     : $WORKSPACE_HOST_PATH" >&2
info "  claude-config : $CLAUDE_CONFIG_HOST_PATH" >&2
if [ "$LABEL_SET" -eq 1 ] && [ -n "$LABEL" ]; then
    info "  label         : $LABEL" >&2
fi

if ! ai_sandbox_compose -p "$PROJECT" up -d; then
    warn "docker compose up failed for $PROJECT." >&2
    warn "Counter NOT rolled back (monotonic by design); next spawn will use N=$(( N + 1 ))." >&2
    exit 1
fi

ok "$PROJECT is running. Attach with: ./attach.sh --session $N"
