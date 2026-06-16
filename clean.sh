#!/usr/bin/env bash
# clean.sh — bring down ai-sandbox sessions.
#
#   ./clean.sh <N>       — clean session N only (down -v, optional per-N dirs).
#   ./clean.sh --all     — clean every ai-sandbox-* session.
#   ./clean.sh           — interactively pick from running sessions.
#
# NEVER touches: shared ./workspace, shared ./claude-config, ./secrets, or
# ./.ai-sandbox-counter. The counter is monotonic across cleans (AC3, AC14).
# For a full factory reset, see README § "Resetting".
set -euo pipefail

cd "$(dirname "$0")"
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

# UC05 § AC11,AC25 — match spawn.sh: route per-session host state under
# /var/lib/ai-sandbox-server/sessions/ when the management server sets
# AI_SANDBOX_HOST_STATE_ROOT. Developer-mode runs leave the var unset
# and clean.sh continues operating against the repo root.
if [ -n "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
    mkdir -p "$AI_SANDBOX_HOST_STATE_ROOT"
    cd "$AI_SANDBOX_HOST_STATE_ROOT"
fi

# ── Resolve the per-session host-dir base ────────────────────────────────────
#
# Rule 0 — server pin wins. Under AI_SANDBOX_HOST_STATE_ROOT the per-session
# dirs live under the state-root cwd as the historical relative
# ./workspace-<N> / ./claude-config-<N>, so the base is ".".
# Developer-mode runs resolve the SAME out-of-tree base spawn.sh used (read
# from the frozen state file — clean only ever READS, never writes/migrates).
# If the dev base can't be resolved (no spawn has ever run in this repo), there
# is nothing relocated to clean, so fall back to "." — the per-session dirs
# simply won't exist and the rm guards are no-ops.
SESSION_DIR_BASE="."
if [ -z "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
    if WS_ROOT="$(aisb_dev_workspace_root 2>/dev/null)"; then
        SESSION_DIR_BASE="$WS_ROOT"
    fi
fi

usage() {
    cat >&2 <<'EOF'
Usage: ./clean.sh [<N>] [flags]
       ./clean.sh --all [flags]

Brings down one or more ai-sandbox-<N> Compose projects and removes their
containers and volumes. Per-session host directories (./workspace-<N>/,
./claude-config-<N>/) are wiped only when they exist.

Flags:
  --session <N>            Clean session N (same as positional arg).
  --all                    Clean every ai-sandbox-* session.
  --keep-workspace         Don't delete ./workspace-<N>/ if it exists.
  --keep-claude-config     Don't delete ./claude-config-<N>/ if it exists.
  --non-interactive        Never prompt. With no target, exits non-zero.
  --help, -h               Show this message.

NEVER touches: ./workspace, ./claude-config, ./secrets, ./.ai-sandbox-counter.
EOF
}

TARGET_N=""
DO_ALL=0
KEEP_WORKSPACE=0
KEEP_CLAUDE_CONFIG=0
NON_INTERACTIVE=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --session)
            [ "$#" -ge 2 ] || { warn "--session requires a value" >&2; usage; exit 2; }
            TARGET_N="$2"; shift 2 ;;
        --session=*) TARGET_N="${1#--session=}"; shift ;;
        --all)                DO_ALL=1; shift ;;
        --keep-workspace)     KEEP_WORKSPACE=1; shift ;;
        --keep-claude-config) KEEP_CLAUDE_CONFIG=1; shift ;;
        --non-interactive)    NON_INTERACTIVE=1; shift ;;
        -h|--help)            usage; exit 0 ;;
        --*)
            warn "Unknown flag: $1" >&2
            usage
            exit 2
            ;;
        *)
            if [ -z "$TARGET_N" ]; then
                TARGET_N="$1"; shift
            else
                warn "Unexpected positional arg: $1" >&2
                usage
                exit 2
            fi
            ;;
    esac
done

# Auto non-interactive on non-TTY stdin.
if [ "$NON_INTERACTIVE" -eq 0 ] && [ ! -t 0 ]; then
    NON_INTERACTIVE=1
fi

# Validate positional <N>.
if [ -n "$TARGET_N" ]; then
    case "$TARGET_N" in
        ''|*[!0-9]*)
            warn "Invalid session number: '$TARGET_N'" >&2
            exit 2
            ;;
    esac
fi

clean_one() {
    # `n` and `name` MUST be on SEPARATE `local` lines. A single
    # `local n="$1" name="ai-sandbox-${n}"` expands ${n} against the OUTER
    # scope (before the `n="$1"` assignment takes effect), so under `set -u`
    # it crashes with "n: unbound variable" on the explicit-N path (Branch 2 —
    # the server's DELETE route) where no global `n` exists; the --all /
    # interactive branches only survived by leaking a global `n` from their
    # `for` loop (and would even read a stale value). Splitting makes ${n}
    # read the just-set local. (bash 5.x.)
    local n="$1"
    local name="ai-sandbox-${n}"
    info "Cleaning $name" >&2
    # AC9 — capture the real teardown exit code. The old `|| true` (combined
    # with the 2>/dev/null) swallowed a failed `down`, so a container that
    # never actually tore down still yielded exit 0 → server 204 → the row
    # reappeared on the next refresh. errexit stays satisfied because the `||`
    # branch makes the line always succeed.
    local down_rc=0
    ai_sandbox_compose -p "$name" down -v --remove-orphans 2>/dev/null || down_rc=$?

    # Per-session isolated dirs live under SESSION_DIR_BASE (the same out-of-tree
    # base spawn.sh resolved in dev mode, or "." under a server pin). Using the
    # resolved base — not a hardcoded ./workspace-<N> — is what prevents the
    # silent leak after relocation.
    local ws_dir="${SESSION_DIR_BASE}/workspace-${n}"
    local cc_dir="${SESSION_DIR_BASE}/claude-config-${n}"
    # UC-91 — the always-per-session transcript store (spawn.sh creates
    # ./claude-projects-<N> for every session). Tear it down with the rest of the
    # session's claude state so it doesn't orphan on the host.
    local cp_dir="${SESSION_DIR_BASE}/claude-projects-${n}"
    if [ "$KEEP_WORKSPACE" -eq 0 ] && [ -d "$ws_dir" ]; then
        info "  rm -rf $ws_dir" >&2
        rm -rf "$ws_dir"
    fi
    if [ "$KEEP_CLAUDE_CONFIG" -eq 0 ] && [ -d "$cc_dir" ]; then
        info "  rm -rf $cc_dir" >&2
        rm -rf "$cc_dir"
    fi
    if [ "$KEEP_CLAUDE_CONFIG" -eq 0 ] && [ -d "$cp_dir" ]; then
        info "  rm -rf $cp_dir" >&2
        rm -rf "$cp_dir"
    fi

    # If `down` reported failure, re-check whether containers actually remain
    # before treating it as fatal: a non-zero exit with nothing left running
    # (already-gone / never-existed) is tolerated; lingering containers are a
    # genuine teardown failure that MUST propagate a non-zero return (AC9).
    if [ "$down_rc" -ne 0 ]; then
        local remaining
        remaining="$(ai_sandbox_compose -p "$name" ps -q 2>/dev/null || true)"
        if [ -n "$remaining" ]; then
            warn "$name: teardown failed (compose down exit $down_rc; containers still present)" >&2
            return 1
        fi
    fi
    ok "$name cleaned"
    return 0
}

# ── Branch 1: --all ──────────────────────────────────────────────────────────
if [ "$DO_ALL" -eq 1 ]; then
    if [ -n "$TARGET_N" ]; then
        warn "--all and <N> are mutually exclusive." >&2
        exit 2
    fi
    # Snapshot all (running + stopped) at the moment of invocation.
    mapfile -t ALL_SESSIONS < <(enumerate_ai_sandbox_sessions --include-stopped | awk -F'|' '{print $1}')
    if [ "${#ALL_SESSIONS[@]}" -eq 0 ]; then
        info "No ai-sandbox-* sessions to clean." >&2
        exit 0
    fi
    rc=0
    for n in "${ALL_SESSIONS[@]}"; do
        clean_one "$n" || rc=$?
    done
    exit "$rc"
fi

# ── Branch 2: explicit <N> ───────────────────────────────────────────────────
if [ -n "$TARGET_N" ]; then
    # The management server's clean path. A teardown failure here MUST yield a
    # non-zero exit so the server returns 500 and the client surfaces it (AC9).
    rc=0
    clean_one "$TARGET_N" || rc=$?
    exit "$rc"
fi

# ── Branch 3: no-arg — list running and prompt ──────────────────────────────
if [ "$NON_INTERACTIVE" -eq 1 ]; then
    warn "Non-interactive: a target is required. Pass <N>, --session <N>, or --all." >&2
    exit 2
fi

mapfile -t SESSIONS < <(enumerate_ai_sandbox_sessions | awk -F'|' '$3 == "running"')
if [ "${#SESSIONS[@]}" -eq 0 ]; then
    info "No running ai-sandbox-* sessions to clean." >&2
    exit 0
fi

printf "\n  %sRunning sessions:%s\n\n" "$AISB_BOLD" "$AISB_RESET" >&2
i=1
INDEX_TO_N=()
for rec in "${SESSIONS[@]}"; do
    IFS='|' read -r N NAME STATE CID LABEL TITLE <<< "$rec"
    INDEX_TO_N+=("$N")
    if [ -n "$LABEL" ]; then
        printf "  [%d] ai-sandbox-%-3s  %s  label=%s\n" "$i" "$N" "$TITLE" "$LABEL" >&2
    else
        printf "  [%d] ai-sandbox-%-3s  %s\n" "$i" "$N" "$TITLE" >&2
    fi
    i=$(( i + 1 ))
done

while true; do
    printf "\n  Pick [1-%d], 'all', or 'cancel': " "${#SESSIONS[@]}" >&2
    if ! read -r choice; then
        warn "EOF on stdin — cancelling." >&2
        exit 0
    fi
    case "$choice" in
        cancel|c|C|q|Q) exit 0 ;;
        all|A|a)
            rc=0
            for n in "${INDEX_TO_N[@]}"; do
                clean_one "$n" || rc=$?
            done
            exit "$rc"
            ;;
        ''|*[!0-9]*)
            warn "Enter a number, 'all', or 'cancel'." >&2
            ;;
        *)
            if [ "$choice" -ge 1 ] && [ "$choice" -le "${#SESSIONS[@]}" ]; then
                rc=0
                clean_one "${INDEX_TO_N[$(( choice - 1 ))]}" || rc=$?
                exit "$rc"
            fi
            warn "Out of range." >&2
            ;;
    esac
done
