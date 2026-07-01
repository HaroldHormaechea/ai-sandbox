# shellcheck shell=bash
# UC-94 — sole owner of the per-session bind-source REPAIR name-set.
#
# Single implementation of "re-own stale root-owned per-session bind sources
# under the host state root, and remove the legacy root-owned dind debris". The
# Docker daemon auto-creates any missing bind source as a root:root directory;
# because /var/lib is not dpkg-tracked, that debris survives `apt purge` +
# reinstall (UC-94). spawn.sh runs as the NON-ROOT service user and cannot
# chown root-owned dirs, so the repair lives in root-privileged install paths:
#   • the deb postinst (root, every install/reinstall),
#   • `aisandboxctl onboard` (root, via DindStateStep → ProcessRunner).
# Both invoke THIS script via `bash <path>` (the deb ships it NON-EXECUTABLE) →
# postinst and the Java side cannot drift (anti-drift, UC-94 Part B/D).
#
# Usage:
#   bash repair-state-root.sh --state-root <dir> [--owner <user:group>]
#
#   --state-root  The per-session host-state root
#                 (e.g. /var/lib/ai-sandbox-server/sessions).
#   --owner       Optional <user:group> to chown the known bind sources to
#                 (e.g. ai-sandbox-server:ai-sandbox-server). Omitted → only the
#                 legacy-debris removal runs.
#
# SCOPE IS DELIBERATELY NARROW. It chowns ONLY the known per-session bind-source
# names — never a blanket `sessions/*` glob — so the UC-62 host-shell siblings
# (server-ssh.sock, server-ssh-home) and any other state are provably untouched.
# It is idempotent and NEVER fatal: a missing arg, missing dir, or a failing
# chown is a no-op, so postinst can stay `exit 0`.
set -u

_aisb_state_root=""
_aisb_owner=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --state-root) _aisb_state_root="${2:-}"; shift 2 ;;
        --state-root=*) _aisb_state_root="${1#*=}"; shift ;;
        --owner) _aisb_owner="${2:-}"; shift 2 ;;
        --owner=*) _aisb_owner="${1#*=}"; shift ;;
        *) echo "repair-state-root.sh: ignoring unknown argument: $1" >&2; shift ;;
    esac
done

# Never fatal: nothing to repair if the state root was not given or absent.
if [ -z "$_aisb_state_root" ] || [ ! -d "$_aisb_state_root" ]; then
    exit 0
fi

# Remove the legacy root-owned dind fallback debris. Pre-UC-94, the dind subuid/
# subgid bind sources defaulted to ./secrets/dind/ under the spawn cwd (the state
# root in install mode), where the Docker daemon auto-created them root:root. The
# canonical home is now the server-owned secrets dir, so this stale tree must not
# linger. Guarded + best-effort.
if [ -e "$_aisb_state_root/secrets/dind" ] || [ -L "$_aisb_state_root/secrets/dind" ]; then
    rm -rf -- "$_aisb_state_root/secrets/dind" 2>/dev/null || true
    # Drop an empty secrets/ if that was all it held; rmdir refuses a non-empty
    # dir, so we never clobber unrelated content.
    rmdir -- "$_aisb_state_root/secrets" 2>/dev/null || true
fi

# Re-own ONLY the known per-session bind-source names. Patterns are quoted in the
# outer loop (matched against the state root, not the cwd) and globbed in the
# inner loop. An unmatched glob stays literal and is filtered by the `-e` guard.
if [ -n "$_aisb_owner" ]; then
    for _aisb_pat in 'workspace' 'workspace-*' 'claude-config' 'claude-config-*' 'claude-projects-*'; do
        for _aisb_entry in "$_aisb_state_root/"$_aisb_pat; do
            [ -e "$_aisb_entry" ] || continue
            chown -R "$_aisb_owner" "$_aisb_entry" 2>/dev/null || true
        done
    done
fi

exit 0
