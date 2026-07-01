# shellcheck shell=bash
# UC-94 — sole writer of the server-owned DinD subuid/subgid delegation files.
#
# This is the single implementation of "provision the dind subuid/subgid files".
# It is invoked from THREE call sites, all via `bash <path>` (the deb ships it
# NON-EXECUTABLE):
#   • the deb postinst (root, every install/reinstall),
#   • `aisandboxctl onboard`   (root, via DindStateStep → ProcessRunner),
#   • `aisandboxctl secrets seed` (root, via DindStateStep → ProcessRunner).
# One implementation → postinst and the Java side cannot drift (anti-drift,
# UC-94 Part B). The spawn-time guard `_aisb_dind_ensure_subid_files` is
# defense-in-depth; THIS script (run as root) is the AC#2 guarantee.
#
# It writes BOTH /etc/subuid- and /etc/subgid-style files under
# <secrets-dir>/dind/, each carrying BOTH delegation lines
# (claude:100000:65536 + sandbox:165536:65536), then chowns the dir + files to
# the service user so the (root) Docker daemon can bind them and the non-root
# service user can read/heal them.
#
# Usage:
#   bash ensure-host-subid.sh --secrets-dir <dir> [--owner <user:group>]
#
#   --secrets-dir  REQUIRED. The server-owned secrets dir
#                  (e.g. /etc/ai-sandbox-server/secrets). The dind files land in
#                  <dir>/dind/{subuid,subgid}.
#   --owner        Optional <user:group> to chown the created dir + files to
#                  (e.g. ai-sandbox-server:ai-sandbox-server). Omitted → no chown
#                  (the files are owned by the calling user).
#
# Exit status: 0 on success; 2 on a usage error; 1 if the dir/files could not be
# created, written, or chowned. Callers that must never fail the install
# (postinst) guard the invocation with `|| true`.

# Resolve helpers relative to THIS script's location (not the cwd) so the
# manifest's path helpers + the shared aisb_subuid_ensure_line are always found.
_aisb_here="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=../lib/server-install.sh disable=SC1091
. "$_aisb_here/../lib/server-install.sh"
# shellcheck source=./manifest.sh disable=SC1091
. "$_aisb_here/manifest.sh"

_aisb_secrets_dir=""
_aisb_owner=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --secrets-dir) _aisb_secrets_dir="${2:-}"; shift 2 ;;
        --secrets-dir=*) _aisb_secrets_dir="${1#*=}"; shift ;;
        --owner) _aisb_owner="${2:-}"; shift 2 ;;
        --owner=*) _aisb_owner="${1#*=}"; shift ;;
        *) echo "ensure-host-subid.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$_aisb_secrets_dir" ]; then
    echo "ensure-host-subid.sh: --secrets-dir <dir> is required" >&2
    exit 2
fi

_aisb_dind_dir="$_aisb_secrets_dir/dind"

# Point the manifest's path helpers at the server-owned dind dir so
# _aisb_dind_ensure_subid_files writes EXACTLY the files docker-compose.dind.yml
# binds (kept in sync with ScriptExecutorService.composeEnv()'s wiring).
export AI_SANDBOX_DIND_SUBUID_HOST_PATH="$_aisb_dind_dir/subuid"
export AI_SANDBOX_DIND_SUBGID_HOST_PATH="$_aisb_dind_dir/subgid"

if ! install -d -m 0700 -- "$_aisb_dind_dir"; then
    echo "ensure-host-subid.sh: could not create $_aisb_dind_dir" >&2
    exit 1
fi

# Delegate both ranges into both files (idempotent; reason recorded on failure).
if ! _aisb_dind_ensure_subid_files; then
    echo "ensure-host-subid.sh: failed to write subuid/subgid delegation under $_aisb_dind_dir" \
        "${_AISB_SI_REASON:+— $_AISB_SI_REASON}" >&2
    exit 1
fi

if [ -n "$_aisb_owner" ]; then
    if ! chown "$_aisb_owner" \
        "$_aisb_dind_dir" \
        "$_aisb_dind_dir/subuid" \
        "$_aisb_dind_dir/subgid"; then
        echo "ensure-host-subid.sh: could not chown $_aisb_dind_dir to $_aisb_owner" >&2
        exit 1
    fi
fi

exit 0
