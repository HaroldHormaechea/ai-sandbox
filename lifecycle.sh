#!/usr/bin/env bash
# lifecycle.sh — drive a single ai-sandbox-<N> session through the Docker
# container lifecycle (stop / start / pause / unpause).
#
#   ./lifecycle.sh --session <N> --action <stop|start|pause|unpause>
#   ./lifecycle.sh <N> <action>            # positional shorthand
#
# UC-46. Unlike clean.sh (which runs `down -v` and DESTROYS the container +
# volumes), the actions here are non-destructive and resumable:
#
#   stop    — `docker compose stop`   : SIGTERM, container + volumes preserved.
#   start   — `docker compose start`  : bring a stopped container back up. Its
#             entrypoint.sh re-runs idempotently (clears + rewrites
#             /tmp/aisandbox-ready and recreates the tmux `main` session on
#             every boot), so the session contract is re-established.
#   pause   — `docker compose pause`  : SIGSTOP via the cgroup freezer.
#   unpause — `docker compose unpause`: resume a paused container.
#
# NEVER touches: shared ./workspace, shared ./claude-config, ./secrets, or
# ./.ai-sandbox-counter. State validity (e.g. "start only when stopped") is
# enforced by the management server BEFORE this script is invoked; the docker
# verbs are also self-guarding (docker rejects an out-of-state verb non-zero).
set -euo pipefail

cd "$(dirname "$0")"
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

# UC05 § AC11,AC25 — match spawn.sh / clean.sh: when the management server
# sets AI_SANDBOX_HOST_STATE_ROOT, route the compose project resolution under
# it. Developer-mode runs leave the var unset and operate against the repo root.
if [ -n "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
    mkdir -p "$AI_SANDBOX_HOST_STATE_ROOT"
    cd "$AI_SANDBOX_HOST_STATE_ROOT"
fi

usage() {
    cat >&2 <<'EOF'
Usage: ./lifecycle.sh --session <N> --action <stop|start|pause|unpause>
       ./lifecycle.sh <N> <action>

Drives one ai-sandbox-<N> Compose project through a non-destructive Docker
lifecycle action. To DESTROY a session (down -v) use ./clean.sh instead.

Flags:
  --session <N>            Target session number (same as the first positional).
  --action <verb>          One of: stop | start | pause | unpause.
  --non-interactive        Never prompt (the management-server invocation path).
  --help, -h               Show this message.
EOF
}

TARGET_N=""
ACTION=""
NON_INTERACTIVE=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --session)
            [ "$#" -ge 2 ] || { warn "--session requires a value" >&2; usage; exit 2; }
            TARGET_N="$2"; shift 2 ;;
        --session=*) TARGET_N="${1#--session=}"; shift ;;
        --action)
            [ "$#" -ge 2 ] || { warn "--action requires a value" >&2; usage; exit 2; }
            ACTION="$2"; shift 2 ;;
        --action=*)  ACTION="${1#--action=}"; shift ;;
        --non-interactive) NON_INTERACTIVE=1; shift ;;
        -h|--help)         usage; exit 0 ;;
        --*)
            warn "Unknown flag: $1" >&2
            usage
            exit 2
            ;;
        *)
            if [ -z "$TARGET_N" ]; then
                TARGET_N="$1"; shift
            elif [ -z "$ACTION" ]; then
                ACTION="$1"; shift
            else
                warn "Unexpected positional arg: $1" >&2
                usage
                exit 2
            fi
            ;;
    esac
done

# ── Validate <N> ─────────────────────────────────────────────────────────────
if [ -z "$TARGET_N" ]; then
    warn "A session number is required (--session <N> or positional)." >&2
    usage
    exit 2
fi
case "$TARGET_N" in
    ''|*[!0-9]*)
        warn "Invalid session number: '$TARGET_N'" >&2
        exit 2
        ;;
esac

# ── Validate <action> ────────────────────────────────────────────────────────
case "$ACTION" in
    stop|start|pause|unpause) ;;
    '')
        warn "An action is required (--action <stop|start|pause|unpause>)." >&2
        usage
        exit 2
        ;;
    *)
        warn "Invalid action: '$ACTION' (expected stop|start|pause|unpause)" >&2
        exit 2
        ;;
esac

NAME="ai-sandbox-${TARGET_N}"
info "Lifecycle: ${ACTION} ${NAME}" >&2

# Capture the REAL exit code of the docker verb (mirrors clean.sh's
# `|| rc=$?` pattern): the `||` branch keeps errexit satisfied while still
# letting a failed action propagate a non-zero exit to the server (→ 500).
rc=0
ai_sandbox_compose -p "$NAME" "$ACTION" || rc=$?

if [ "$rc" -ne 0 ]; then
    warn "${NAME}: '${ACTION}' failed (docker compose exit ${rc})" >&2
    exit "$rc"
fi

ok "${NAME} ${ACTION} ok"
exit 0
