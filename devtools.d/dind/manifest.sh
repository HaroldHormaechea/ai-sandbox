# shellcheck shell=bash
# DinD capability manifest (UC-27). A capability is an auto-discovered, sourced
# shell snippet: adding one is dropping a directory here — no edits to the
# selector, resolver, or persistence code (AC#2).
#
# Sourced in three contexts:
#   1. lib.sh metadata reads (in a subshell) — to render the selector.
#   2. spawn.sh host-side injection (in the live shell) — devtool_spawn_env.
#   3. entrypoint.sh in-container provisioning — devtool_provision.
# Because every manifest defines identically-named hook functions, the loader
# sources them one at a time and unsets the hooks between; manifests must not
# rely on another manifest's variables being present.

# Resolve shared version constants relative to this manifest's own location so
# LABEL and the install can never drift (AC#9).
# shellcheck source=../lib/versions.sh disable=SC1091
. "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/.." && pwd)/lib/versions.sh"

ID="dind"
LABEL="Docker-in-Docker (rootless dockerd ${AISB_DIND_DOCKER_VERSION})"
DEPENDS_ON=()
APPLY_AT="session-spawn"
ARCH="any"
WARNING='Enabling Docker-in-Docker (rootless) lets code running inside a session start its own docker / docker compose commands. The rootless daemon runs as the non-root session user with no host-socket bind, so it does NOT widen the host trust boundary — but it DOES widen what code inside a session can reach (the session can now launch and inspect containers). Project policy is "the container is the trust boundary"; enabling this is a deliberate, opt-in expansion of that boundary.'

# Host-side (spawn.sh): flag the capability for the container and layer the
# DinD compose override (/dev/fuse + unconfined apparmor/seccomp for rootless
# dockerd). _aisb_append_compose_override is provided by lib.sh.
devtool_spawn_env() {
    export AI_SANDBOX_DEVTOOL_DIND=1
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
