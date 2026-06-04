# shellcheck shell=bash
# DinD capability manifest (UC-27). A capability is an auto-discovered, sourced
# shell snippet: adding one is dropping a directory here — no edits to the
# selector, resolver, or persistence code (AC#2).
#
# Sourced in four contexts:
#   1. lib.sh metadata reads (in a subshell) — to render the selector.
#   2. spawn.sh host-side injection (in the live shell) — devtool_spawn_env.
#   3. setup.sh server-side install stage (live shell) — devtool_server_install.
#   4. entrypoint.sh in-container provisioning — devtool_provision.
# Because every manifest defines identically-named hook functions, the loader
# sources them one at a time and unsets the hooks between; manifests must not
# rely on another manifest's variables being present.

# Resolve shared helpers relative to this manifest's own location so LABEL and
# the install can never drift (AC#9). versions.sh feeds the version-bearing
# LABEL; server-install.sh provides aisb_subuid_ensure_line / the note + reason
# helpers used by devtool_server_install below (UC-30).
_aisb_dind_lib_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/.." && pwd)/lib"
# shellcheck source=../lib/versions.sh disable=SC1091
. "$_aisb_dind_lib_dir/versions.sh"
# shellcheck source=../lib/server-install.sh disable=SC1091
. "$_aisb_dind_lib_dir/server-install.sh"

ID="dind"
LABEL="Docker-in-Docker (rootless dockerd ${AISB_DIND_DOCKER_VERSION})"
DEPENDS_ON=()
APPLY_AT="session-spawn"
ARCH="any"
WARNING='Enabling Docker-in-Docker (rootless) lets code running inside a session start its own docker / docker compose commands. The rootless daemon runs as the non-root session user with no host-socket bind, so it does NOT widen the HOST trust boundary. It DOES widen the per-session boundary: the session can launch and inspect containers, and to make the nested rootless daemon start and run containers on a hardened kernel the session container is granted CAP_SYS_ADMIN, /dev/net/tun, unconfined apparmor/seccomp, and systempaths=unconfined (which un-masks /proc on the session). The host stays unaffected — no host docker socket is mounted, no ports are published, and the session runs non-root + userns-mapped. Project policy is "the container is the trust boundary"; enabling this is a deliberate, opt-in expansion of that boundary.'

# Host paths bind-mounted read-only over the session's /etc/subuid + /etc/subgid
# by docker-compose.dind.yml. Defaults sit under ./secrets/dind/ (resolved
# against the spawn/​setup cwd, which is the repo root in developer mode and the
# host state root in install mode); the management server overrides them via
# AI_SANDBOX_DIND_SUBUID_HOST_PATH / _SUBGID_ so the same files the compose bind
# resolves are the ones we write here. Keep these in sync with the `${…:-…}`
# defaults in docker-compose.dind.yml's volumes block.
_aisb_dind_subuid_path() { printf '%s' "${AI_SANDBOX_DIND_SUBUID_HOST_PATH:-./secrets/dind/subuid}"; }
_aisb_dind_subgid_path() { printf '%s' "${AI_SANDBOX_DIND_SUBGID_HOST_PATH:-./secrets/dind/subgid}"; }

# _aisb_dind_ensure_subid_files → idempotently delegate two DISJOINT, collision-
# safe ranges in BOTH host files (AC#3/#6). The bind mount MASKS the image's
# baked /etc/subuid, so the host file must carry BOTH names:
#   • claude:100000:65536  — dev-mode dind (runtime uid 1000, name `claude`).
#   • sandbox:165536:65536 — the arbitrary-uid case: entrypoint.sh self-registers
#     ANY runtime uid in /etc/passwd under the FIXED name `sandbox`, so one
#     `sandbox` range covers every server-spawned uid (e.g. 997). The ranges are
#     disjoint (100000–165535 vs 165536–231071), so neither clobbers the other.
# Shared by both the setup-time hook and the mandatory spawn-time guard. Returns
# non-zero (with a reason recorded) if any write fails.
_aisb_dind_ensure_subid_files() {
    local f
    for f in "$(_aisb_dind_subuid_path)" "$(_aisb_dind_subgid_path)"; do
        if ! aisb_subuid_ensure_line "$f" claude 100000 65536 \
            || ! aisb_subuid_ensure_line "$f" sandbox 165536 65536; then
            aisb_server_install_reason "could not write subuid/subgid delegation to $f — check that $(dirname -- "$f") exists and is writable by the user running setup/spawn."
            return 1
        fi
    done
}

# Server-side install (setup.sh, UC-30): runs in the privileged setup context
# where the host ./secrets tree is writable. Idempotently delegates the subuid/
# subgid ranges (AC#3/#6) and hard-gates (AC#4/#7) if host /dev/fuse is absent —
# rootless dockerd cannot mount fuse-overlayfs without it, and /etc is read-only
# in-session so it cannot be fixed at runtime. The OTHER half of AC#7
# (fuse-overlayfs resolvable on PATH) is provisioned into the session cache at
# spawn and can only be checked from inside the session — `aisandbox-dind doctor`
# is the compensating control that reports it post-spawn. On success registers a
# respawn post-requisite note (AC#5). Never throws under the dispatcher's set -e.
devtool_server_install() {
    # AC#7 (host half): /dev/fuse must exist on the host for fuse-overlayfs.
    if [ ! -e /dev/fuse ]; then
        aisb_server_install_reason "host /dev/fuse is absent — rootless dockerd needs it for fuse-overlayfs storage. Load the fuse kernel module on the host (e.g. \`sudo modprobe fuse\`) or run on a kernel that exposes /dev/fuse, then re-run \`./setup.sh --reconfigure\`."
        return 1
    fi

    # AC#3/#6: idempotent subuid/subgid delegation (reason set on failure).
    _aisb_dind_ensure_subid_files || return 1

    # AC#5: existing sessions keep the old userns mapping; only a NEW session
    # picks up the freshly-delegated ranges via the compose bind mount.
    aisb_server_install_note "DinD: respawn your session(s) for the new subuid/subgid delegation to take effect — run ./spawn.sh (existing sessions keep their old mapping). Then verify with \`aisandbox-dind doctor\` inside the session (subuid + fuse-overlayfs + /dev/fuse)."
}

# Host-side (spawn.sh): flag the capability for the container and layer the
# DinD compose override (/dev/fuse + unconfined apparmor/seccomp for rootless
# dockerd). _aisb_append_compose_override is provided by lib.sh.
devtool_spawn_env() {
    export AI_SANDBOX_DEVTOOL_DIND=1
    # MANDATORY spawn-time guard (UC-30): ensure the bind-mount source files
    # exist as FILES before `compose up`. If they are missing, Docker autocreates
    # the bind source as a root:root DIRECTORY, which then masks /etc/subuid with
    # an empty dir and regresses even dev-mode dind on a pre-UC-30 ledger. This
    # ensure runs BEFORE the compose override is layered. Idempotent → a no-op on
    # warm spawns; best-effort (a write failure is left for setup to hard-gate).
    _aisb_dind_ensure_subid_files || true
    _aisb_append_compose_override "docker-compose.dind.yml"
}

# In-container (entrypoint.sh): provision + start the rootless daemon. Both
# steps are idempotent and cached under /workspace/environment-utilities/dind/.
# Returns non-zero on failure so the caller can decide warn-and-continue vs. a
# hard gate (UC-27 offline policy §E).
devtool_provision() {
    /usr/local/bin/aisandbox-dind install || return 1
    /usr/local/bin/aisandbox-dind start   || return 1
}
