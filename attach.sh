#!/usr/bin/env bash
# attach.sh — attach to a running ai-sandbox-<N> session's tmux `main` window.
#
# Behavior:
#   - 0 running sessions  → exit non-zero with a pointer at ./spawn.sh
#   - exactly 1 running   → attach directly (no prompt)
#   - >1 running          → list and prompt (or honor --session <N>)
set -euo pipefail

cd "$(dirname "$0")"
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

usage() {
    cat >&2 <<'EOF'
Usage: ./attach.sh [--session <N>]

Attaches to the ai-sandbox-<N> session's tmux `main` window.

Flags:
  --session <N>   Attach to session <N> directly. Required when stdin is
                  not a TTY and more than one session is running.
  --help, -h      Show this message.
EOF
}

REQUESTED_N=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --session)
            [ "$#" -ge 2 ] || { warn "--session requires a value" >&2; usage; exit 2; }
            REQUESTED_N="$2"; shift 2 ;;
        --session=*) REQUESTED_N="${1#--session=}"; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)
            warn "Unknown flag: $1" >&2
            usage
            exit 2
            ;;
    esac
done

# Enumerate running sessions only.
mapfile -t SESSIONS < <(enumerate_ai_sandbox_sessions | awk -F'|' '$3 == "running"')

if [ "${#SESSIONS[@]}" -eq 0 ]; then
    warn "No running ai-sandbox-* sessions." >&2
    warn "Start one with: ./spawn.sh" >&2
    exit 1
fi

attach_to_n() {
    # `n` and `name` MUST stay on SEPARATE `local` lines: a single
    # `local n="$1" name="ai-sandbox-${n}"` expands ${n} against the OUTER
    # scope before the n="$1" assignment lands, crashing under `set -u` with
    # "n: unbound variable". Same gotcha documented in clean.sh's clean_one().
    local n="$1"
    local name="ai-sandbox-${n}"
    # UC-27 — a freshly-spawned session may still be provisioning capabilities:
    # the entrypoint launches the tmux `main` session only AFTER eager
    # provisioning finishes. Tolerate that window so `./attach.sh` right after a
    # `./spawn.sh` (or a direct attach during boot) doesn't race the bootstrap
    # and fail with "can't find session main". Wait briefly for `main` to exist;
    # transient `compose exec` failures during early boot are treated as
    # not-ready. (spawn.sh already polls the readiness marker, so the normal
    # spawn→attach flow rarely waits here.) If it never appears within the window
    # we fall through and let `tmux attach` print its own message.
    local waited=0
    while [ "$waited" -lt 60 ]; do
        if ai_sandbox_compose -p "$name" exec -T claude-sandbox tmux has-session -t main >/dev/null 2>&1; then
            break
        fi
        [ "$waited" -eq 0 ] && info "  Session $n is still starting — waiting for tmux 'main'…" >&2
        waited=$((waited + 1))
        sleep 1
    done
    # AC25: pass -f and --project-directory on every docker compose call
    # for consistency; `compose exec` identifies the project by container
    # labels so the flags are functionally a no-op here. Kept for parity
    # with spawn/clean so every invocation looks the same.
    exec ai_sandbox_compose -p "$name" exec claude-sandbox tmux attach -t main
}

# Direct-pick path: --session <N>
if [ -n "$REQUESTED_N" ]; then
    for rec in "${SESSIONS[@]}"; do
        IFS='|' read -r N NAME STATE CID LABEL TITLE <<< "$rec"
        if [ "$N" = "$REQUESTED_N" ]; then
            if [ "$TITLE" = "(unavailable)" ]; then
                warn "Session $N is running but tmux probe failed; cannot attach." >&2
                exit 1
            fi
            attach_to_n "$N"
        fi
    done
    warn "No running session ai-sandbox-${REQUESTED_N}." >&2
    exit 1
fi

# Exactly one running → attach directly.
if [ "${#SESSIONS[@]}" -eq 1 ]; then
    IFS='|' read -r N NAME STATE CID LABEL TITLE <<< "${SESSIONS[0]}"
    if [ "$TITLE" = "(unavailable)" ]; then
        warn "ai-sandbox-${N} is running but tmux probe failed; cannot attach." >&2
        exit 1
    fi
    attach_to_n "$N"
fi

# Multiple running → list + prompt.
if [ ! -t 0 ]; then
    warn "Multiple sessions running; --session <N> is required when stdin is not a TTY." >&2
    for rec in "${SESSIONS[@]}"; do
        IFS='|' read -r N NAME STATE CID LABEL TITLE <<< "$rec"
        if [ -n "$LABEL" ]; then
            warn "  ai-sandbox-${N}  ${TITLE}  label=${LABEL}" >&2
        else
            warn "  ai-sandbox-${N}  ${TITLE}" >&2
        fi
    done
    exit 1
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
    printf "\n  Pick a session [1-%d] or 'q' to quit: " "${#SESSIONS[@]}" >&2
    if ! read -r choice; then
        warn "EOF on stdin — aborting." >&2
        exit 1
    fi
    case "$choice" in
        q|Q) exit 0 ;;
        ''|*[!0-9]*)
            warn "Enter a number between 1 and ${#SESSIONS[@]} (or 'q')." >&2
            ;;
        *)
            if [ "$choice" -ge 1 ] && [ "$choice" -le "${#SESSIONS[@]}" ]; then
                N="${INDEX_TO_N[$(( choice - 1 ))]}"
                attach_to_n "$N"
            fi
            warn "Out of range." >&2
            ;;
    esac
done
