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

# UC22 — Android-testing images can boot a headless emulator, which needs
# hardware KVM. When the built image carries the Android toolchain AND the host
# exposes /dev/kvm, layer the KVM passthrough override so `aisandbox-emulator`
# inside the session can run an accelerated AVD (AC11). Both gates must hold;
# either missing → no override → behaviour identical to a normal session. This
# also covers management-server-spawned sessions (AC13), since the server
# invokes this very script with AI_SANDBOX_COMPOSE_FILE set.
if image_supports_android; then
    if [ -e /dev/kvm ]; then
        kvm_override="docker-compose.kvm.yml"
        if [ -n "${AI_SANDBOX_COMPOSE_FILE:-}" ]; then
            kvm_override="$(dirname "$AI_SANDBOX_COMPOSE_FILE")/docker-compose.kvm.yml"
        fi
        if [ -f "$kvm_override" ]; then
            # UC22 BUG-1 fix — passing the device alone is not enough: inside the
            # container /dev/kvm is `crw-rw---- root <kvm-gid>` and the runtime
            # user (uid1000/gid1000 in dev mode, <uid>:0 for server-spawned) is
            # NOT in that group, so opening it fails with EACCES and the emulator
            # refuses to start. Detect the host kvm GID and pass it as a
            # supplementary group via docker-compose.kvm.yml's group_add. One
            # change covers BOTH developer-mode and management-server-spawned
            # sessions (AC13). If the host has no kvm group, fall back to 0 so the
            # override still parses (the device passthrough is harmless and the
            # emulator helper will report inaccessibility cleanly, AC12).
            kvm_gid="$(host_kvm_gid)"
            export AI_SANDBOX_KVM_GID="$kvm_gid"
            export AI_SANDBOX_EXTRA_COMPOSE_FILES="${AI_SANDBOX_EXTRA_COMPOSE_FILES:+$AI_SANDBOX_EXTRA_COMPOSE_FILES }$kvm_override"
            info "  kvm           : /dev/kvm detected → passthrough enabled (gid $kvm_gid, $kvm_override)" >&2
        else
            warn "Android image + /dev/kvm present but $kvm_override missing — emulator will be unaccelerated." >&2
        fi
    else
        info "  kvm           : no /dev/kvm on host → emulator slow/unavailable (build+JVM-test lane unaffected)" >&2
    fi
fi

if ! ai_sandbox_compose -p "$PROJECT" up -d; then
    warn "docker compose up failed for $PROJECT." >&2
    warn "Counter NOT rolled back (monotonic by design); next spawn will use N=$(( N + 1 ))." >&2
    exit 1
fi

ok "$PROJECT is running. Attach with: ./attach.sh --session $N"
