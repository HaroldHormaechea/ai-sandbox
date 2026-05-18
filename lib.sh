#!/usr/bin/env bash
# Shared helpers for ai-sandbox operator scripts (setup, spawn, attach, clean).
#
# Sourced from each entry-point script via:
#   . "$(dirname "$0")/lib.sh"
#
# Conventions:
#   - All functions write user-facing text to STDERR via the formatted helpers
#     below. Stdout is reserved for machine-readable output (enumeration records,
#     captured values).
#   - No top-level side effects. Sourcing this file MUST be free of network
#     calls, filesystem writes, and prompts.

# ── Color/format helpers ─────────────────────────────────────────────────────
AISB_CYAN=$'\033[1;36m'
AISB_GREEN=$'\033[1;32m'
AISB_YELLOW=$'\033[1;33m'
AISB_RED=$'\033[1;31m'
AISB_MAGENTA=$'\033[1;35m'
AISB_BOLD=$'\033[1m'
AISB_RESET=$'\033[0m'

# Backwards-compat aliases — setup.sh used short uppercase names.
CYAN="$AISB_CYAN"
GREEN="$AISB_GREEN"
YELLOW="$AISB_YELLOW"
RED="$AISB_RED"
MAGENTA="$AISB_MAGENTA"
BOLD="$AISB_BOLD"
RESET="$AISB_RESET"

ok()   { printf "  %s✓%s %s\n" "$AISB_GREEN" "$AISB_RESET" "$1"; }
warn() { printf "  %s!%s %s\n" "$AISB_YELLOW" "$AISB_RESET" "$1"; }
info() { printf "  %s\n" "$1"; }
hr()   { printf "\n"; }

clear_screen() { printf "\033[H\033[2J"; }

screen_header() {
    local current="$1" total="$2" title="$3"
    printf "%s%s=== Step %s of %s: %s ===%s\n\n" \
        "$AISB_BOLD" "$AISB_CYAN" "$current" "$total" "$title" "$AISB_RESET"
}

press_enter() {
    printf "\n  %sPress Enter to continue%s " "$AISB_BOLD" "$AISB_RESET"
    read -r _
}

# ── Identity-prompt helper (extracted from setup.sh) ─────────────────────────
# Generic prompt loop.
#   prompt_field LABEL DEFAULT VALIDATOR_FN
# Echoes the chosen value on stdout. Returns non-zero if the user typed /skip.
# Calls `exit 0` on /exit. /help shows the identity help screen (if defined by
# the caller).
prompt_field() {
    local label="$1" default="$2" validator="$3"
    local resp last_error="" suffix=""
    [ -n "$default" ] && suffix="[$default]"
    while true; do
        if [ -n "$last_error" ]; then
            warn "$last_error" >&2
            last_error=""
        fi
        printf "  %s %s: " "$label" "$suffix" >&2
        IFS= read -r resp || return 1
        case "$resp" in
            /exit)
                echo "  Exiting setup." >&2
                exit 0
                ;;
            /skip)
                return 1
                ;;
            /help)
                if declare -F show_identity_help_screen >/dev/null; then
                    show_identity_help_screen
                fi
                continue
                ;;
            "")
                if [ -n "$default" ]; then
                    printf "%s" "$default"
                    return 0
                fi
                last_error="Required."
                continue
                ;;
            *)
                # Trim leading/trailing whitespace.
                local trimmed="$resp"
                trimmed="${trimmed#"${trimmed%%[![:space:]]*}"}"
                trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
                if "$validator" "$trimmed"; then
                    printf "%s" "$trimmed"
                    return 0
                fi
                last_error="Invalid format."
                ;;
        esac
    done
}

# ── Counter lock (mkdir-based, NFS-safe atomic) ──────────────────────────────
#
# Why mkdir? It is the only POSIX filesystem operation that's both atomic
# AND fails when the target exists, with no need for `flock`/`fcntl` which
# are non-portable across macOS/Linux/Windows-WSL combos.
#
# Layout:
#   ./.ai-sandbox-counter.lock/   (directory; presence == held lock)
#
# Defensive on signals: a trap removes the lockdir on SIGINT/SIGTERM/EXIT so
# a Ctrl-C between acquire and release doesn't strand the lock.

AISB_LOCKDIR=""
AISB_LOCK_TRAP_INSTALLED=""

_aisb_lock_cleanup() {
    [ -n "$AISB_LOCKDIR" ] || return 0
    [ -d "$AISB_LOCKDIR" ] || return 0
    rmdir "$AISB_LOCKDIR" 2>/dev/null || true
}

# acquire_counter_lock [TIMEOUT_SECS]
# Default timeout: 10s. Retries every 100ms.
acquire_counter_lock() {
    local timeout="${1:-10}"
    local lockdir
    lockdir="$(pwd)/.ai-sandbox-counter.lock"
    local deadline=$(( $(date +%s) + timeout ))
    while ! mkdir "$lockdir" 2>/dev/null; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            warn "Timed out waiting for counter lock at $lockdir" >&2
            warn "If no other ai-sandbox script is running, remove the lockdir manually:" >&2
            warn "  rmdir $lockdir" >&2
            return 1
        fi
        sleep 0.1 2>/dev/null || sleep 1
    done
    AISB_LOCKDIR="$lockdir"
    if [ -z "$AISB_LOCK_TRAP_INSTALLED" ]; then
        trap _aisb_lock_cleanup EXIT INT TERM
        AISB_LOCK_TRAP_INSTALLED=1
    fi
    return 0
}

release_counter_lock() {
    _aisb_lock_cleanup
    AISB_LOCKDIR=""
}

# ── Session enumeration ──────────────────────────────────────────────────────
#
# enumerate_ai_sandbox_sessions [--include-stopped]
#
# Writes one record per session to stdout. Records are pipe-delimited:
#
#   N|NAME|STATE|CONTAINER_ID|LABEL|TITLE
#
# Where:
#   N            = session number (parsed from project name "ai-sandbox-<N>")
#   NAME         = full compose project name (e.g. "ai-sandbox-3")
#   STATE        = "running" or "exited" (lowercased docker compose ls state)
#   CONTAINER_ID = id of the claude-sandbox service container, or empty
#   LABEL        = value of com.ai-sandbox.label, or empty
#   TITLE        = normalized tmux window title, or "(idle)" / "(unavailable)"
#
# Title normalization: empty, "bash", "sh", "claude" → "(idle)".
# Probe failures (container down, tmux not running) → "(unavailable)".
enumerate_ai_sandbox_sessions() {
    local include_stopped=0
    local arg
    for arg in "$@"; do
        case "$arg" in
            --include-stopped) include_stopped=1 ;;
        esac
    done

    if ! command -v jq >/dev/null 2>&1; then
        warn "jq is required for session enumeration but was not found in PATH." >&2
        return 1
    fi

    local ls_args=(compose ls --format json)
    [ "$include_stopped" -eq 1 ] && ls_args+=(--all)

    local raw
    raw="$(docker "${ls_args[@]}" 2>/dev/null || printf '[]')"
    [ -n "$raw" ] || raw='[]'

    # docker compose ls --format json may emit either a JSON array or NDJSON,
    # depending on the Compose version. Normalize.
    local projects
    projects="$(printf "%s" "$raw" | jq -c -s 'if length==1 and (.[0]|type=="array") then .[0] else . end | .[] | select(.Name|startswith("ai-sandbox-"))')"
    [ -n "$projects" ] || return 0

    local proj name state n cid label title
    while IFS= read -r proj; do
        [ -n "$proj" ] || continue
        name="$(printf "%s" "$proj" | jq -r '.Name')"
        state="$(printf "%s" "$proj" | jq -r '.Status' | awk '{print tolower($1)}')"
        case "$state" in
            running*) state="running" ;;
            exited*)  state="exited"  ;;
            *)        state="$state"  ;;
        esac
        n="${name#ai-sandbox-}"
        # Skip the legacy unnumbered "ai-sandbox" project.
        case "$n" in
            ''|*[!0-9]*) continue ;;
        esac

        cid=""
        label=""
        title="(unavailable)"

        cid="$(docker compose -p "$name" ps -q claude-sandbox 2>/dev/null | head -n 1 || true)"
        if [ -n "$cid" ]; then
            label="$(docker inspect --format '{{ index .Config.Labels "com.ai-sandbox.label" }}' "$cid" 2>/dev/null || true)"
            case "$label" in
                "<no value>") label="" ;;
            esac
            if [ "$state" = "running" ]; then
                local raw_title
                raw_title="$(docker compose -p "$name" exec -T claude-sandbox tmux display-message -p -t main '#W' 2>/dev/null | tr -d '\r\n' || true)"
                title="$(_aisb_normalize_title "$raw_title")"
            fi
        fi

        printf '%s|%s|%s|%s|%s|%s\n' "$n" "$name" "$state" "$cid" "$label" "$title"
    done <<< "$projects"
}

# Internal: collapse empty / default tmux titles to "(idle)". Probe failures
# (empty AND container running) are also "(idle)" since we can't distinguish
# "container alive but tmux not yet up" from "tmux up but window unnamed".
_aisb_normalize_title() {
    local t="$1"
    # Trim leading/trailing whitespace.
    t="${t#"${t%%[![:space:]]*}"}"
    t="${t%"${t##*[![:space:]]}"}"
    case "$t" in
        ''|bash|sh|claude) printf "%s" "(idle)" ;;
        *) printf "%s" "$t" ;;
    esac
}

# ── docker compose wrapper (UC05 § AC25,AC26,AC27) ───────────────────────────
#
# ai_sandbox_compose <docker-compose-args...>
#
# Wraps `docker compose` so the management server can route bundled scripts
# at a non-default compose file and project directory without editing them:
#
#   AI_SANDBOX_COMPOSE_FILE
#       Absolute path to docker-compose.yml. When set, prepended as `-f <path>`.
#       In install mode the server sets this to
#       /opt/ai-sandbox-server/host/docker-compose.yml.
#
#   AI_SANDBOX_HOST_STATE_ROOT
#       Absolute path to the per-session host-state root. When set, prepended
#       as `--project-directory <path>` so workspace + claude-config bind-mount
#       sources resolve under it (rather than under the read-only install dir).
#       In install mode the server sets this to /var/lib/ai-sandbox-server/sessions.
#
# Both vars unset → behaviour matches plain `docker compose` for developer-
# mode parity. Both flags are harmless for subcommands that ignore them
# (e.g. `compose exec` resolves the project by container labels), and are
# kept for consistency so every invocation is shaped identically.
ai_sandbox_compose() {
    local flags=()
    if [ -n "${AI_SANDBOX_COMPOSE_FILE:-}" ]; then
        flags+=(-f "$AI_SANDBOX_COMPOSE_FILE")
    fi
    if [ -n "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
        flags+=(--project-directory "$AI_SANDBOX_HOST_STATE_ROOT")
    fi
    docker compose "${flags[@]}" "$@"
}
