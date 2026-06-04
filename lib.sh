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
    local base="${AI_SANDBOX_COMPOSE_FILE:-}"
    # UC22 — when override files are requested in developer mode (no explicit
    # base compose file), make the base explicit. Otherwise a bare `-f
    # <override>` makes docker compose IGNORE the default docker-compose.yml.
    if [ -n "${AI_SANDBOX_EXTRA_COMPOSE_FILES:-}" ] && [ -z "$base" ]; then
        base="docker-compose.yml"
    fi
    [ -n "$base" ] && flags+=(-f "$base")
    # UC22 — optional override compose files (e.g. docker-compose.kvm.yml for
    # /dev/kvm passthrough on Android-testing images). Space-separated, applied
    # AFTER the base so they layer on top. Unset → behaviour identical to
    # pre-UC22.
    local extra
    for extra in ${AI_SANDBOX_EXTRA_COMPOSE_FILES:-}; do
        flags+=(-f "$extra")
    done
    if [ -n "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
        flags+=(--project-directory "$AI_SANDBOX_HOST_STATE_ROOT")
    fi
    docker compose "${flags[@]}" "$@"
}

# ── UC27 — manifest-driven development-tools catalog ────────────────────────
#
# Capabilities are auto-discovered, sourced shell manifests at
# `devtools.d/<id>/manifest.sh` (AC#2). Each manifest declares `ID`, a
# version-bearing `LABEL`, `DEPENDS_ON` (array of capability ids), an optional
# `WARNING`, `APPLY_AT` (`session-spawn`), `ARCH` (`any`|`amd64`|…), and two hook
# functions: `devtool_spawn_env` (host-side env/compose wiring, run by spawn.sh)
# and `devtool_provision` (in-container install + PATH wiring, run by
# entrypoint.sh). Adding a capability is dropping a directory here — no edits to
# the selector, resolver, persistence, or injection code.
#
# The operator's selection persists as one record per line: `<id>\t<apply_at>`.
# Comments and blank lines are tolerated.
AISB_DEVTOOLS_FILE="${AISB_DEVTOOLS_FILE:-.ai-sandbox-devtools}"

# Resolve devtools.d/ relative to THIS file's own location so discovery works
# regardless of cwd (developer mode: repo root; install mode: the host bundle
# dir under /opt/ai-sandbox-server/host/). Override with AISB_DEVTOOLS_DIR.
if [ -z "${AISB_LIB_DIR:-}" ]; then
    AISB_LIB_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd)"
fi
AISB_DEVTOOLS_DIR="${AISB_DEVTOOLS_DIR:-$AISB_LIB_DIR/devtools.d}"

# aisb_host_arch → normalize `uname -m` to amd64|arm64|<raw>. Used to gate
# arch-restricted capabilities (Android is amd64-only — AC#11).
aisb_host_arch() {
    case "$(uname -m)" in
        x86_64|amd64)  printf 'amd64' ;;
        aarch64|arm64) printf 'arm64' ;;
        *)             uname -m ;;
    esac
}

# _aisb_manifest_path ID → echo the path to ID's manifest.sh; non-zero if absent.
_aisb_manifest_path() {
    local p="$AISB_DEVTOOLS_DIR/$1/manifest.sh"
    [ -f "$p" ] || return 1
    printf '%s' "$p"
}

# devtool_catalog_ids → emit one capability ID per line, in byte-stable
# sorted-glob order. The deterministic order is what makes the persisted ledger
# reproducible and lets the shell be the single source of truth (AC#4).
devtool_catalog_ids() {
    local m
    for m in "$AISB_DEVTOOLS_DIR"/*/manifest.sh; do
        [ -f "$m" ] || continue
        basename "$(dirname "$m")"
    done | LC_ALL=C sort
}

# _aisb_manifest_field ID VAR → source ID's manifest in a SUBSHELL and echo VAR's
# value (ID/LABEL/APPLY_AT/ARCH/WARNING). For DEPENDS_ON, emit one element per
# line. The subshell isolates each manifest's identically-named hook functions
# so they never collide in the caller. Returns non-zero if the manifest is
# missing or fails to source.
_aisb_manifest_field() {
    local id="$1" var="$2" path
    path="$(_aisb_manifest_path "$id")" || return 1
    (
        ID="" LABEL="" APPLY_AT="" ARCH="" WARNING=""
        DEPENDS_ON=()
        # shellcheck disable=SC1090
        . "$path" || exit 1
        if [ "$var" = "DEPENDS_ON" ]; then
            local d
            for d in ${DEPENDS_ON[@]+"${DEPENDS_ON[@]}"}; do printf '%s\n' "$d"; done
        else
            eval "printf '%s' \"\${$var-}\""
        fi
    )
}

# devtool_label ID → version-bearing human label; non-zero if unknown.
devtool_label() { _aisb_manifest_field "$1" LABEL; }

# devtool_apply_at ID → `session-spawn` (default when unset); non-zero if unknown.
devtool_apply_at() {
    local v
    v="$(_aisb_manifest_field "$1" APPLY_AT)" || return 1
    printf '%s' "${v:-session-spawn}"
}

# devtool_warning ID → inline trust-boundary warning (empty when none).
devtool_warning() { _aisb_manifest_field "$1" WARNING; }

# devtool_arch ID → required arch (`any` default); non-zero if unknown.
devtool_arch() {
    local v
    v="$(_aisb_manifest_field "$1" ARCH)" || return 1
    printf '%s' "${v:-any}"
}

# devtool_depends_on ID → emit ID's DIRECT dependency ids, one per line.
devtool_depends_on() { _aisb_manifest_field "$1" DEPENDS_ON; }

# devtool_is_available ID → 0 if ID can run on this host's arch. ARCH=any (or
# unset) → always available; otherwise it must equal the host arch. The selector
# uses this to show, but disable, arch-incompatible rows (AC#11) rather than
# offering-then-breaking them.
devtool_is_available() {
    local arch
    arch="$(devtool_arch "$1" 2>/dev/null || true)"
    [ -z "$arch" ] && arch="any"
    [ "$arch" = "any" ] && return 0
    [ "$arch" = "$(aisb_host_arch)" ]
}

# devtool_deps_transitive ID → emit ID's full transitive DEPENDS_ON set (NOT ID
# itself), each id once, in discovery order. Worklist (no recursion) so it is
# robust under `set -u`. Used by the selector to auto-select dependencies (AC#5).
devtool_deps_transitive() {
    local seen=" " queue=() cur dep
    while IFS= read -r dep; do [ -n "$dep" ] && queue+=("$dep"); done < <(devtool_depends_on "$1")
    while [ "${#queue[@]}" -gt 0 ]; do
        cur="${queue[0]}"
        queue=("${queue[@]:1}")
        case "$seen" in *" $cur "*) continue ;; esac
        seen="$seen$cur "
        printf '%s\n' "$cur"
        while IFS= read -r dep; do [ -n "$dep" ] && queue+=("$dep"); done < <(devtool_depends_on "$cur")
    done
}

# devtool_dependents_among ID [CANDIDATE...] → among the CANDIDATE ids, emit
# those that transitively depend on ID (one per line). Used by the selector to
# cascade-deselect dependents when ID is turned off (AC#5).
devtool_dependents_among() {
    local target="$1" cand dep
    shift
    for cand in "$@"; do
        [ "$cand" = "$target" ] && continue
        while IFS= read -r dep; do
            if [ "$dep" = "$target" ]; then
                printf '%s\n' "$cand"
                break
            fi
        done < <(devtool_deps_transitive "$cand")
    done
}

# _aisb_append_compose_override FILENAME → append FILENAME to
# AI_SANDBOX_EXTRA_COMPOSE_FILES, resolved next to the active base compose file
# when AI_SANDBOX_COMPOSE_FILE is set (install mode) or in cwd (developer mode).
# Warns and skips if the file is missing. Shared by every manifest's
# devtool_spawn_env hook so the compose-layering rule lives in one place.
_aisb_append_compose_override() {
    local name="$1" path="$1"
    if [ -n "${AI_SANDBOX_COMPOSE_FILE:-}" ]; then
        path="$(dirname "$AI_SANDBOX_COMPOSE_FILE")/$name"
    fi
    if [ -f "$path" ]; then
        export AI_SANDBOX_EXTRA_COMPOSE_FILES="${AI_SANDBOX_EXTRA_COMPOSE_FILES:+$AI_SANDBOX_EXTRA_COMPOSE_FILES }$path"
    else
        warn "Compose override $path missing — sessions will start without it." >&2
    fi
}

# devtool_is_enabled ID → 0 if ID is listed in the devtools file (first column).
devtool_is_enabled() {
    [ -f "$AISB_DEVTOOLS_FILE" ] || return 1
    local id="$1" line first
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|\#*) continue ;;
        esac
        # shellcheck disable=SC2086
        set -- $line
        first="${1:-}"
        [ "$first" = "$id" ] && return 0
    done < "$AISB_DEVTOOLS_FILE"
    return 1
}

# read_enabled_devtools → emit one enabled ID per line, in file order.
# Tolerates comments + blank lines. Echoes nothing when the file is missing.
read_enabled_devtools() {
    [ -f "$AISB_DEVTOOLS_FILE" ] || return 0
    local line first
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|\#*) continue ;;
        esac
        # shellcheck disable=SC2086
        set -- $line
        first="${1:-}"
        [ -n "$first" ] && printf '%s\n' "$first"
    done < "$AISB_DEVTOOLS_FILE"
}

# write_enabled_devtools [ID...] → truncate + rewrite the devtools file. The
# enabled ids are written in catalog (sorted-glob) order — NOT argument order —
# so the persisted ledger is byte-stable regardless of the selection sequence,
# which is what lets the shell be the single source of truth (AC#4). Each id's
# apply_at is resolved from its manifest. Ids with no manifest are dropped (a
# stale ledger self-heals on rewrite; no migration code — AC#7). Zero enabled →
# empty file.
write_enabled_devtools() {
    : > "$AISB_DEVTOOLS_FILE"
    local want=" $* " id apply_at
    while IFS= read -r id; do
        case "$want" in
            *" $id "*)
                apply_at="$(devtool_apply_at "$id" 2>/dev/null || true)"
                [ -n "$apply_at" ] || apply_at="session-spawn"
                printf '%s\t%s\n' "$id" "$apply_at" >> "$AISB_DEVTOOLS_FILE"
                ;;
        esac
    done < <(devtool_catalog_ids)
}

# inject_devtool_spawn_env → consult the persisted ledger and, for each enabled
# capability, source its manifest and invoke the host-side `devtool_spawn_env`
# hook (env exports + compose-override appends). Also exports the generic
# `AI_SANDBOX_DEVTOOLS` list (space-separated enabled ids, catalog order) that
# the container's entrypoint reads to drive provisioning. Generic — adding a
# capability needs no edit here (AC#2,#3). Manifests are sourced one at a time
# into the live shell and the hooks unset between, so the identically-named
# hooks never collide.
inject_devtool_spawn_env() {
    local id manifest enabled=()
    while IFS= read -r id; do
        [ -n "$id" ] || continue
        enabled+=("$id")
    done < <(read_enabled_devtools)

    # Export the generic list (catalog order via write-time stability) so the
    # entrypoint can loop over it. Empty selection → var unset → byte-identical
    # to today's no-devtools session (AC#12).
    if [ "${#enabled[@]}" -gt 0 ]; then
        export AI_SANDBOX_DEVTOOLS="${enabled[*]}"
    fi

    for id in ${enabled[@]+"${enabled[@]}"}; do
        manifest="$(_aisb_manifest_path "$id" 2>/dev/null)" || {
            warn "Enabled devtool '$id' has no manifest under $AISB_DEVTOOLS_DIR — skipping." >&2
            continue
        }
        unset -f devtool_spawn_env devtool_provision devtool_server_install 2>/dev/null || true
        ID="" LABEL="" APPLY_AT="" ARCH="" WARNING=""
        DEPENDS_ON=()
        # shellcheck disable=SC1090
        . "$manifest" || { warn "Failed to source manifest for '$id'." >&2; continue; }
        if declare -F devtool_spawn_env >/dev/null 2>&1; then
            devtool_spawn_env || warn "spawn-env hook for '$id' returned non-zero." >&2
        fi
    done
    unset -f devtool_spawn_env devtool_provision devtool_server_install 2>/dev/null || true
}

# run_devtool_server_install → UC-30 server-side install stage. Consult the
# persisted ledger and, for each enabled capability that defines the optional
# `devtool_server_install` hook, run it IN THE LIVE SHELL (so it can mutate host
# files and register post-req notes) in the privileged setup context, BEFORE any
# in-container devtool_provision. Mirrors inject_devtool_spawn_env: generic, no
# per-id case — adding a capability needs no edit here (AC#1/#2).
#
# Hard-gate semantics (AC#4): a hook returning non-zero does NOT abort setup or
# the other capabilities' installs. The failing id + its reason are collected, an
# inline ✗ is printed, the loop CONTINUES, and AFTER the loop the ledger is
# rewritten with the failed ids REMOVED (faithful "mark unavailable") via
# write_enabled_devtools. Post-req notes (AC#5) accumulate in
# AISB_SERVER_INSTALL_NOTES; the disabled set lands in AISB_SERVER_INSTALL_DISABLED
# (one "id<TAB>reason" record per element). setup.sh reads both globals at
# end-of-run. Always returns 0 — never lets `set -e` abort the wizard.
run_devtool_server_install() {
    # Live-shell globals consumed by setup.sh at end-of-run. Reset each run so a
    # --reconfigure pass does not inherit a previous invocation's notes.
    AISB_SERVER_INSTALL_NOTES=()
    AISB_SERVER_INSTALL_DISABLED=()

    local id manifest enabled=() kept=() any_disabled=0
    while IFS= read -r id; do
        [ -n "$id" ] || continue
        enabled+=("$id")
    done < <(read_enabled_devtools)

    for id in ${enabled[@]+"${enabled[@]}"}; do
        manifest="$(_aisb_manifest_path "$id" 2>/dev/null)" || {
            warn "Enabled devtool '$id' has no manifest under $AISB_DEVTOOLS_DIR — skipping." >&2
            kept+=("$id")
            continue
        }
        unset -f devtool_spawn_env devtool_provision devtool_server_install 2>/dev/null || true
        ID="" LABEL="" APPLY_AT="" ARCH="" WARNING=""
        DEPENDS_ON=()
        _AISB_SI_REASON=""
        # shellcheck disable=SC1090
        . "$manifest" || { warn "Failed to source manifest for '$id'." >&2; kept+=("$id"); continue; }
        # No hook → capability behaves exactly as today (AC#1). Keep it enabled.
        if ! declare -F devtool_server_install >/dev/null 2>&1; then
            kept+=("$id")
            continue
        fi
        if devtool_server_install; then
            ok "server-install: $id"
            kept+=("$id")
        else
            local reason="${_AISB_SI_REASON:-no reason reported by the hook}"
            printf "  %s✗%s server-install: %s — %s\n" \
                "$AISB_RED" "$AISB_RESET" "$id" "$reason" >&2
            AISB_SERVER_INSTALL_DISABLED+=("$id"$'\t'"$reason")
            any_disabled=1
        fi
    done
    unset -f devtool_spawn_env devtool_provision devtool_server_install 2>/dev/null || true

    # Hard-gate (AC#4): rewrite the ledger with the failed ids removed, but only
    # when something actually failed (avoid a needless rewrite on the clean path).
    if [ "$any_disabled" -eq 1 ]; then
        write_enabled_devtools ${kept[@]+"${kept[@]}"}
    fi
    return 0
}

# ── UC27 — retired: UC-22 toolchain ledger / image-label helpers ─────────────
#
# `.ai-sandbox-toolchains`, `toolchain_is_enabled`, `write_enabled_toolchains`,
# `image_supports_android`, `export_android_build_env`, and
# `AISB_ANDROID_BASE_DEFAULT` are GONE. Android is no longer a build-time image
# flavour stamped with a label — it is an opt-in capability configured through
# the devtools selector and provisioned eagerly at spawn (no migration; a stale
# `.ai-sandbox-toolchains` file is simply ignored — AC#7,#8). KVM passthrough is
# now gated on "the `android` capability is enabled AND /dev/kvm is present",
# handled by the android manifest's devtool_spawn_env (which calls host_kvm_gid
# below and layers docker-compose.kvm.yml). The single glibc base means there is
# no base-image flip to choose anymore.

# host_kvm_gid — echo the host's kvm group GID, or "0" if there is no kvm group.
# Used by spawn.sh to pass /dev/kvm's group as a supplementary group into the
# session (docker-compose.kvm.yml group_add) so the runtime user can actually
# open the device (UC22 BUG-1). Prefer the group that OWNS /dev/kvm (most
# correct — the device's gid is what matters), falling back to the named `kvm`
# group, then 0. `stat` is coreutils on Linux hosts (where /dev/kvm exists at
# all); getent covers the named-group path.
host_kvm_gid() {
    local gid=""
    if [ -e /dev/kvm ]; then
        gid="$(stat -c '%g' /dev/kvm 2>/dev/null || true)"
    fi
    if [ -z "$gid" ]; then
        gid="$(getent group kvm 2>/dev/null | cut -d: -f3 || true)"
    fi
    printf '%s' "${gid:-0}"
}

# ── Dev-mode workspace root (relocate-out-of-tree) ───────────────────────────
#
# Historically, developer-mode runs put the shared workspace at the in-repo
# `./workspace` and isolated workspaces at `./workspace-<N>`. If an operator
# ever pointed Claude at the repo itself and ran `cp -a . ./workspace`, the
# copy recursed into the freshly-created `./workspace` (and then `./workspace/
# workspace`, …) until the disk filled. Moving the dev workspace OUTSIDE the
# repo working tree makes that self-copy structurally impossible.
#
# `aisb_dev_workspace_root` returns an ABSOLUTE base dir. Both spawn and clean
# call it so they always agree on the location. The shared workspace lives at
# `<root>/workspace`; isolated workspaces at `<root>/workspace-<N>` and their
# claude-config siblings at `<root>/claude-config-<N>`.
#
# Precedence (highest first):
#   Rule 0 — server pin wins, helper NOT consulted. When the management server
#            sets AI_SANDBOX_WORKSPACE_HOST_PATH (per-spawn) or
#            AI_SANDBOX_HOST_STATE_ROOT (install-mode cwd reroot), the existing
#            server/isolated flow owns the path. Rule 0 is enforced by the
#            CALLER (spawn.sh / clean.sh) which only invokes this helper in the
#            developer-mode branch; the guard below is defensive.
#   Rule 1 — explicit operator override: AI_SANDBOX_DEV_WORKSPACE_ROOT, used
#            verbatim (e.g. `=.` is the recorded in-repo opt-in).
#   Rule 2 — persisted choice: an absolute path in the gitignored state file
#            `<repo>/.ai-sandbox-workspace-root` (the determinism anchor —
#            spawn and clean always read the same frozen value).
#   Rule 3 — first-run default: ${XDG_STATE_HOME:-$HOME/.local/state}/ai-sandbox.
#            Persisting that default is the job of the INTERACTIVE setup path
#            (setup.sh / the migrate-or-keep routine), NOT this read-only
#            helper — see aisb_dev_workspace_setup. On a non-interactive spawn
#            with no persisted file, the caller refuses rather than writing.
#
# This function NEVER writes the state file and NEVER prompts. Resolution only.
AISB_DEV_WORKSPACE_STATE_FILE="${AISB_DEV_WORKSPACE_STATE_FILE:-.ai-sandbox-workspace-root}"

# aisb_abspath PATH → echo an absolute, normalized path (no symlink resolution
# required for a path that may not exist yet). Used so the state file and the
# exported bind-source are always absolute regardless of the caller's cwd.
aisb_abspath() {
    local p="$1"
    case "$p" in
        /*) ;;                       # already absolute
        *)  p="$(pwd)/$p" ;;
    esac
    printf '%s' "$p"
}

# aisb_dev_workspace_root → echo the resolved absolute dev workspace base dir.
# Read-only: applies Rules 1-3 (Rule 0 is the caller's gate). Returns non-zero
# only on a Rule-3 first run when the state file is absent AND no override is
# set — the caller decides whether that is a fatal (non-interactive) condition.
aisb_dev_workspace_root() {
    # Rule 0 (defensive): never run under a server pin.
    if [ -n "${AI_SANDBOX_WORKSPACE_HOST_PATH:-}" ] || [ -n "${AI_SANDBOX_HOST_STATE_ROOT:-}" ]; then
        warn "aisb_dev_workspace_root called under a server pin — ignoring (Rule 0)." >&2
        return 2
    fi
    # Rule 1 — explicit operator override.
    if [ -n "${AI_SANDBOX_DEV_WORKSPACE_ROOT:-}" ]; then
        aisb_abspath "$AI_SANDBOX_DEV_WORKSPACE_ROOT"
        return 0
    fi
    # Rule 2 — persisted choice.
    if [ -f "$AISB_DEV_WORKSPACE_STATE_FILE" ]; then
        local persisted
        persisted="$(tr -d '\r\n' < "$AISB_DEV_WORKSPACE_STATE_FILE" || true)"
        if [ -n "$persisted" ]; then
            aisb_abspath "$persisted"
            return 0
        fi
    fi
    # Rule 3 — first-run default (NOT persisted here; setup does that).
    printf '%s' "${XDG_STATE_HOME:-$HOME/.local/state}/ai-sandbox"
    # Signal "this came from the default, nothing persisted yet" so callers can
    # distinguish a fresh run from a recorded one.
    return 1
}

# aisb_write_dev_workspace_root ABS_PATH → persist ABS_PATH to the state file
# (truncate-and-write). Caller is responsible for passing an absolute path; we
# store it verbatim.
aisb_write_dev_workspace_root() {
    printf '%s\n' "$1" > "$AISB_DEV_WORKSPACE_STATE_FILE"
}

# aisb_dir_has_real_content DIR → 0 if DIR exists and contains anything other
# than a tracked `.gitkeep`. The `.gitkeep`-awareness is critical: `.gitignore`
# tracks `workspace/.gitkeep`, so a FRESH clone has a non-empty `./workspace`
# (it holds `.gitkeep`) — without this exclusion, first-run detection would
# wrongly classify a clean checkout as a "populated legacy workspace".
aisb_dir_has_real_content() {
    local d="$1"
    [ -d "$d" ] || return 1
    local entry
    for entry in "$d"/* "$d"/.[!.]* "$d"/..?*; do
        [ -e "$entry" ] || continue
        case "$(basename "$entry")" in
            .gitkeep) ;;
            *) return 0 ;;
        esac
    done
    return 1
}

# aisb_has_legacy_inrepo_workspace → 0 if the repo (cwd) carries a legacy
# in-repo workspace that predates relocation: a populated `./workspace`
# (ignoring `.gitkeep`) OR any `./workspace-*/` isolated dir.
aisb_has_legacy_inrepo_workspace() {
    if aisb_dir_has_real_content "./workspace"; then
        return 0
    fi
    local d
    for d in ./workspace-*/; do
        [ -d "$d" ] || continue
        return 0
    done
    return 1
}

# aisb_dev_workspace_setup → the single interactive resolve-migrate-persist
# routine shared by setup.sh and (defensively) any interactive spawn. It:
#   1. Resolves the target root (Rule 1/2/3 via aisb_dev_workspace_root).
#   2. If the state file is already populated, does nothing (asked once).
#   3. Else, if a legacy in-repo workspace exists:
#        - TTY  → prompt migrate / keep-in-place; migrate `mv`s the dirs to the
#                 new root and persists the XDG root; keep persists the absolute
#                 repo root (recorded opt-in).
#        - no TTY → refuse (return non-zero) without writing — the caller turns
#                   that into a hard, instructive failure.
#   4. Else (no legacy) persists the resolved default so spawn/clean agree.
# Echoes the chosen absolute root on stdout on success.
#
# Rule 0: never call this under a server pin (the caller gates on that).
aisb_dev_workspace_setup() {
    # Already recorded → freeze; just echo it.
    if [ -f "$AISB_DEV_WORKSPACE_STATE_FILE" ]; then
        local existing
        existing="$(tr -d '\r\n' < "$AISB_DEV_WORKSPACE_STATE_FILE" || true)"
        if [ -n "$existing" ]; then
            aisb_abspath "$existing"
            return 0
        fi
    fi

    # An explicit env override is authoritative; persist it so clean agrees too.
    if [ -n "${AI_SANDBOX_DEV_WORKSPACE_ROOT:-}" ]; then
        local override
        override="$(aisb_abspath "$AI_SANDBOX_DEV_WORKSPACE_ROOT")"
        aisb_write_dev_workspace_root "$override"
        printf '%s' "$override"
        return 0
    fi

    local repo_root default_root
    repo_root="$(pwd -P)"
    default_root="${XDG_STATE_HOME:-$HOME/.local/state}/ai-sandbox"

    if aisb_has_legacy_inrepo_workspace; then
        if [ ! -t 0 ]; then
            warn "A populated in-repo workspace was found, but this is a non-interactive run." >&2
            warn "Refusing to migrate or keep it silently (it may be a multi-GB / live-git tree)." >&2
            warn "Re-run ./setup.sh interactively, or set AI_SANDBOX_DEV_WORKSPACE_ROOT to choose a" >&2
            warn "location explicitly (use '.' to deliberately keep it in the repo)." >&2
            return 1
        fi
        printf "\n  %sWorkspace relocation%s\n" "$AISB_BOLD" "$AISB_RESET" >&2
        info "The dev workspace now lives OUTSIDE the repo by default, so a stray" >&2
        info "'cp -a . workspace' can never recurse and fill your disk." >&2
        info "An existing in-repo workspace was detected at:" >&2
        info "    $repo_root/workspace (and/or workspace-*/)" >&2
        info "New default location:" >&2
        info "    $default_root" >&2
        printf "\n  [m]igrate to the new location, or [k]eep it in the repo? [M/k]: " >&2
        local resp
        read -r resp || resp=""
        case "$resp" in
            k|K)
                aisb_write_dev_workspace_root "$repo_root"
                ok "Keeping the workspace in the repo (recorded in $AISB_DEV_WORKSPACE_STATE_FILE)." >&2
                printf '%s' "$repo_root"
                return 0
                ;;
            *)
                mkdir -p "$default_root"
                local d base
                for d in ./workspace ./workspace-*/; do
                    [ -e "$d" ] || continue
                    base="$(basename "$d")"
                    # Skip a workspace that holds only .gitkeep (nothing to move);
                    # the .gitkeep itself stays tracked in the repo.
                    if [ "$base" = "workspace" ] && ! aisb_dir_has_real_content "$d"; then
                        continue
                    fi
                    if [ -e "$default_root/$base" ]; then
                        warn "$default_root/$base already exists — leaving $d in place to avoid clobbering." >&2
                        continue
                    fi
                    # Cross-filesystem mv becomes copy+delete and can be slow on a
                    # large tree; say so up front so it doesn't look hung.
                    info "Moving $base → $default_root/ (may take a while if it's large or on another disk)…" >&2
                    mv "$d" "$default_root/$base"
                    # The shared ./workspace dir carries the TRACKED workspace/
                    # .gitkeep bind-mount placeholder; moving the whole dir would
                    # delete it from the working tree. Re-seed it so the repo
                    # stays clean and a fresh clone still finds the placeholder.
                    if [ "$base" = "workspace" ]; then
                        mkdir -p ./workspace
                        : > ./workspace/.gitkeep
                    fi
                done
                aisb_write_dev_workspace_root "$default_root"
                ok "Workspace relocated to $default_root (recorded in $AISB_DEV_WORKSPACE_STATE_FILE)." >&2
                printf '%s' "$default_root"
                return 0
                ;;
        esac
    fi

    # No legacy tree — persist the safe default so spawn and clean agree.
    aisb_write_dev_workspace_root "$default_root"
    printf '%s' "$default_root"
    return 0
}

# aisb_check_workspace_recursion REPO_ROOT WS_PATH → recursion guard used by
# spawn (dev mode). Canonicalizes both paths, then:
#   - returns 2 (HARD FAIL) if WS is, contains, or is an ancestor of the repo
#     root — the genuine `cp -a . <ws>` self-copy case.
#   - returns 1 (WARN) if WS is a strict descendant inside the repo (the
#     recorded `=.` opt-in) — caller warns but proceeds.
#   - returns 0 otherwise (WS safely outside the repo tree).
# Canonicalization resolves symlinks/.. via `pwd -P` on whichever ancestor of
# WS already exists (WS itself may not exist yet on a first spawn).
aisb_check_workspace_recursion() {
    local repo="$1" ws="$2"
    local repo_c ws_c
    repo_c="$(cd "$repo" 2>/dev/null && pwd -P)" || repo_c="$repo"
    # Resolve the deepest existing ancestor of ws, then re-append the missing
    # tail so a not-yet-created workspace still canonicalizes correctly.
    local probe="$ws" tail=""
    while [ -n "$probe" ] && [ ! -d "$probe" ]; do
        tail="/$(basename "$probe")$tail"
        local parent
        parent="$(dirname "$probe")"
        [ "$parent" = "$probe" ] && break
        probe="$parent"
    done
    if [ -d "$probe" ]; then
        ws_c="$(cd "$probe" 2>/dev/null && pwd -P)$tail" || ws_c="$ws"
    else
        ws_c="$ws"
    fi

    # Equal, or ws is an ancestor of repo, or ws contains repo → hard fail.
    if [ "$ws_c" = "$repo_c" ]; then
        return 2
    fi
    case "$repo_c/" in
        "$ws_c"/*) return 2 ;;     # ws is an ancestor of repo
    esac
    case "$ws_c/" in
        "$repo_c"/*) return 1 ;;   # ws is a strict descendant inside repo → warn
    esac
    return 0
}
