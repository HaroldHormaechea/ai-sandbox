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
#
# Rule 0 — server pin wins. When the management server set
# AI_SANDBOX_HOST_STATE_ROOT, we already cd'd into it (above) and the dev-mode
# relocation helper is NOT consulted: the historical relative `./workspace` /
# `./workspace-<N>` (resolved against the state-root cwd) is byte-identical to
# pre-UC05/isolated behaviour. Developer-mode runs (the var unset) resolve the
# workspace base OUTSIDE the repo via aisb_dev_workspace_root so a stray
# `cp -a . workspace` can never recurse into the repo (the disk-filler).
if [ -n "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
    WORKSPACE_HOST_PATH="./workspace"
    CLAUDE_CONFIG_HOST_PATH="./claude-config"
    # UC-91 — transcripts (~/.claude/projects) are ALWAYS per-session, even when
    # claude-config is shared (the default). Sessions sharing one claude-config
    # otherwise co-resolve the same projects/<slug>/ dir, so one session's main
    # transcript leaks into another's chat (conversation bleed + wrong agent pills
    # on session→session switch). A dedicated per-session bind isolates the
    # transcript store regardless of the claude-config sharing mode.
    CLAUDE_PROJECTS_HOST_PATH="./claude-projects-${N}"
    if [ "$WORKSPACE_MODE" = "isolated" ]; then
        WORKSPACE_HOST_PATH="./workspace-${N}"
    fi
    if [ "$CLAUDE_CONFIG_MODE" = "isolated" ]; then
        CLAUDE_CONFIG_HOST_PATH="./claude-config-${N}"
    fi
else
    REPO_ROOT="$(pwd -P)"
    # Resolve the dev workspace base (absolute). A non-zero return means the
    # state file is absent AND no override is set — i.e. an unconfigured
    # first run. On a non-interactive run we must NOT silently pick a default
    # (the operator may have a populated in-repo workspace to migrate first),
    # so refuse with instructions. On a TTY, run the shared resolve-migrate-
    # persist routine so the choice is frozen for clean.sh too.
    if WS_ROOT="$(aisb_dev_workspace_root)"; then
        :
    else
        if [ ! -t 0 ]; then
            warn "Dev workspace root is not configured for this repo." >&2
            warn "Run ./setup.sh interactively once to pick (and migrate to) a location," >&2
            warn "or set AI_SANDBOX_DEV_WORKSPACE_ROOT explicitly (use '.' to keep it in-repo)." >&2
            exit 1
        fi
        WS_ROOT="$(aisb_dev_workspace_setup)" || exit 1
    fi

    # Recursion guard — the structural defence against the self-copy disk-filler.
    SHARED_WS="$WS_ROOT/workspace"
    guard_rc=0
    aisb_check_workspace_recursion "$REPO_ROOT" "$SHARED_WS" || guard_rc=$?
    case "$guard_rc" in
        2)
            warn "Refusing to spawn: the resolved workspace ($SHARED_WS) is, contains, or is an" >&2
            warn "ancestor of the repo ($REPO_ROOT). A 'cp -a . workspace' here would recurse and" >&2
            warn "fill the disk. Point AI_SANDBOX_DEV_WORKSPACE_ROOT at a directory outside the repo." >&2
            exit 1
            ;;
        1)
            warn "Workspace ($SHARED_WS) is inside the repo tree — the recorded in-repo opt-in." >&2
            warn "A 'cp -a . workspace' from the repo root would recurse; never copy this repo's" >&2
            warn "working tree into the workspace (use git clone / archive / a bind mount instead)." >&2
            ;;
    esac

    WORKSPACE_HOST_PATH="$WS_ROOT/workspace"
    CLAUDE_CONFIG_HOST_PATH="$WS_ROOT/claude-config"
    # UC-91 — per-session transcript store (see the host-state-root branch above).
    CLAUDE_PROJECTS_HOST_PATH="$WS_ROOT/claude-projects-${N}"
    if [ "$WORKSPACE_MODE" = "isolated" ]; then
        WORKSPACE_HOST_PATH="$WS_ROOT/workspace-${N}"
    fi
    if [ "$CLAUDE_CONFIG_MODE" = "isolated" ]; then
        CLAUDE_CONFIG_HOST_PATH="$WS_ROOT/claude-config-${N}"
    fi
fi

# UC-17 — pre-create the resolved bind-mount source dirs (BOTH shared and
# isolated) before `compose up`. If a bind source does not exist, Docker
# auto-creates it as root:root, which a non-root session container (running as
# the server uid via compose `user:`) then cannot write — the original
# "Permission denied creating ~/.claude/CLAUDE.md" failure. Creating them here,
# as the user who runs spawn.sh (the ai-sandbox-server service user in install
# mode), gives them the right owner up front. `mkdir -p` is idempotent and
# harmless in developer mode where the shared dirs usually already exist.
mkdir -p "$WORKSPACE_HOST_PATH" "$CLAUDE_CONFIG_HOST_PATH" "$CLAUDE_PROJECTS_HOST_PATH"

# ── Launch ───────────────────────────────────────────────────────────────────
export AI_SANDBOX_WORKSPACE_HOST_PATH="$WORKSPACE_HOST_PATH"
export AI_SANDBOX_CLAUDE_CONFIG_HOST_PATH="$CLAUDE_CONFIG_HOST_PATH"
export AI_SANDBOX_CLAUDE_PROJECTS_HOST_PATH="$CLAUDE_PROJECTS_HOST_PATH"
export AI_SANDBOX_LABEL="$LABEL"

info "Spawning $PROJECT" >&2
info "  workspace      : $WORKSPACE_HOST_PATH" >&2
info "  claude-config  : $CLAUDE_CONFIG_HOST_PATH" >&2
info "  claude-projects: $CLAUDE_PROJECTS_HOST_PATH" >&2
if [ "$LABEL_SET" -eq 1 ] && [ -n "$LABEL" ]; then
    info "  label         : $LABEL" >&2
fi

# UC-27 — read the persisted devtools selection (./.ai-sandbox-devtools or
# $AI_SANDBOX_HOST_STATE_ROOT/.ai-sandbox-devtools) and inject all spawn-time
# wiring BEFORE `ai_sandbox_compose up`. This is fully generic: inject_devtool_
# spawn_env sources each enabled capability's manifest and runs its
# devtool_spawn_env hook, which exports the per-capability env (e.g.
# AI_SANDBOX_DEVTOOL_DIND=1, AI_SANDBOX_DEVTOOL_ANDROID=1) AND layers the matching
# compose override. KVM passthrough is now handled by the android manifest's hook
# (it calls host_kvm_gid + layers docker-compose.kvm.yml when /dev/kvm exists) —
# the old image-label gate is gone. It also exports AI_SANDBOX_DEVTOOLS (the
# enabled-id list), passed into the container via docker-compose.yml so the
# entrypoint provisions each capability eagerly at spawn (AC#3,#12). Persistence
# is the only source of truth — changes propagate to NEW sessions only (AC#12);
# an empty selection is a no-op and spawned sessions are byte-identical to today
# (AC#6,#12). Covers management-server-spawned sessions too (AI_SANDBOX_COMPOSE_FILE
# resolves the overrides next to the install bundle).
inject_devtool_spawn_env
if [ -n "${AI_SANDBOX_DEVTOOLS:-}" ]; then
    info "  devtools      : ${AI_SANDBOX_DEVTOOLS} (provisioned eagerly at spawn)" >&2
    if [ "${AI_SANDBOX_DEVTOOL_DIND:-0}" = "1" ]; then
        info "  devtools      : DinD enabled (rootless dockerd will start inside the session)" >&2
    fi
    if [ "${AI_SANDBOX_DEVTOOL_ANDROID:-0}" = "1" ]; then
        if [ -n "${AI_SANDBOX_KVM_GID:-}" ]; then
            info "  devtools      : Android enabled — /dev/kvm passthrough on (gid ${AI_SANDBOX_KVM_GID}); emulator can boot accelerated" >&2
        else
            info "  devtools      : Android enabled — no /dev/kvm on host; emulator will be slow (build+JVM-test lane unaffected)" >&2
        fi
    fi
fi

if ! ai_sandbox_compose -p "$PROJECT" up -d; then
    warn "docker compose up failed for $PROJECT." >&2
    warn "Counter NOT rolled back (monotonic by design); next spawn will use N=$(( N + 1 ))." >&2
    exit 1
fi

# UC-27 — when capabilities are enabled, wait for the entrypoint to finish eager
# provisioning before reporting "ready" (so the session is genuinely usable at
# handover — AC#12). The entrypoint writes /tmp/aisandbox-ready AFTER tmux/claude
# is up and provisioning has run; we poll it via `compose exec`. Transient exec
# failures during early boot are expected and treated as not-ready (keep polling
# to the timeout — challenger note #3). A cold Android SDK pull (~1.5 GB) can take
# minutes, hence the generous window. With no capabilities this is skipped → the
# spawn path is byte-identical to today (AC#6,#12).
if [ -n "${AI_SANDBOX_DEVTOOLS:-}" ]; then
    info "  devtools      : waiting for in-container provisioning to finish…" >&2
    ready=0
    tries=0
    while [ "$tries" -lt 600 ]; do
        if ai_sandbox_compose -p "$PROJECT" exec -T claude-sandbox test -f /tmp/aisandbox-ready >/dev/null 2>&1; then
            ready=1
            break
        fi
        tries=$((tries + 1))
        sleep 2
    done
    if [ "$ready" = "1" ]; then
        ok "Devtools provisioned; session ready."
    else
        warn "Session started but the readiness marker was not seen within ~20 min — provisioning may still be running or may have failed."
        warn "Check inside the session: ./attach.sh --session $N  then run \`aisandbox-<capability> doctor\`."
    fi
fi

ok "$PROJECT is running. Attach with: ./attach.sh --session $N"
