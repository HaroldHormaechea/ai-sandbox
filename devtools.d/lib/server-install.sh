# shellcheck shell=bash
# Shared helpers for the UC-30 server-side capability install stage.
#
# Sourced — like lib/provision.sh — purely for its function definitions: this
# file MUST stay free of side effects (no installs, no network, no I/O, no
# top-level array initialisation). The live-shell globals the helpers touch
# (AISB_SERVER_INSTALL_NOTES, _AISB_SI_REASON) are owned and initialised by the
# dispatcher `run_devtool_server_install` in lib.sh, never here.
#
# A capability opts into the server-install stage by defining a
# `devtool_server_install()` hook in its manifest (mirroring `devtool_spawn_env`
# / `devtool_provision`). The setup wizard runs that hook in the privileged
# setup context where /etc-equivalent host files are writable, BEFORE any
# in-container `devtool_provision`. A capability with no such hook behaves
# byte-for-byte as today (UC-30 AC#1).

# aisb_subuid_ensure_line FILE OWNER START COUNT
# ──────────────────────────────────────────────────────────────────────────────
# Idempotent ensure-or-append of a single `OWNER:START:COUNT` delegation line in
# an /etc/subuid- or /etc/subgid-style FILE. The unit-testable core of the dind
# server-install hook (UC-30 AC#3/#6).
#
#   • Creates FILE's parent directory and FILE itself if missing (as the calling
#     user, so the host file is owned correctly up front).
#   • A no-op when the EXACT line is already present — re-running setup makes no
#     duplicate entries and no repeated mutation (AC#3).
#   • Appends without touching any other line, so a pre-existing `claude` entry
#     is never clobbered when adding the `sandbox` entry, and vice versa (AC#6).
#
# Returns non-zero only if the directory/file could not be created or the append
# failed (e.g. ownership/permission problem) — the caller hard-gates on that.
aisb_subuid_ensure_line() {
    local file="$1" owner="$2" start="$3" count="$4" dir line
    line="${owner}:${start}:${count}"
    dir="$(dirname -- "$file")"
    [ -d "$dir" ] || mkdir -p -- "$dir" || return 1
    [ -f "$file" ] || : > "$file" || return 1
    # Exact line already delegated → nothing to do (idempotent, AC#3).
    if grep -qxF -- "$line" "$file" 2>/dev/null; then
        return 0
    fi
    printf '%s\n' "$line" >> "$file" || return 1
}

# aisb_server_install_note MESSAGE
# ──────────────────────────────────────────────────────────────────────────────
# Register a post-requisite notice (e.g. "a respawn is required") from inside a
# server-install hook. The dispatcher aggregates every note across all selected
# capabilities and setup.sh prints a consolidated list at the END of the run
# (UC-30 AC#5). `+=` on an unset array is well-defined even under `set -u`.
aisb_server_install_note() {
    AISB_SERVER_INSTALL_NOTES+=("$1")
}

# aisb_server_install_reason MESSAGE
# ──────────────────────────────────────────────────────────────────────────────
# Record WHY a hook is hard-gating, immediately before it `return`s non-zero. The
# dispatcher reads _AISB_SI_REASON after a non-zero hook return and surfaces it in
# the "DISABLED capabilities" block (UC-30 AC#4). Kept distinct from a post-req
# note: a reason explains a failure, a note explains a follow-up after success.
aisb_server_install_reason() {
    _AISB_SI_REASON="$1"
}
