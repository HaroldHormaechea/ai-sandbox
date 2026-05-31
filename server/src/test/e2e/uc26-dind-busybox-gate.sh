#!/usr/bin/env bash
# UC-26 — live end-to-end DinD round-trip gate (busybox write → read → validate).
#
# WHY THIS EXISTS
# ---------------
# The unit/contract suite proves the DinD *plumbing* ships and is wired
# (DevToolsConfigTest, DevToolsStepTest, ReconfigureCommandTest,
# HostScriptComposeEnvTest, ReleaseBundleTest, DebPackageTest). The
# `aisandbox-dind selftest` subcommand proves a one-service alpine container
# comes up and `tmux -V` execs (UC-26 AC#9 c/d). This gate is the STRONGER
# runtime proof the team-lead required: it drives the *real supported spawn
# path* end-to-end and asserts a file written by a busybox container running
# INSIDE the session's rootless daemon round-trips back out to the session
# container's filesystem and matches a known sentinel byte-for-byte.
#
# It maps to:
#   AC#5 — a freshly spawned DinD session can `docker run` with no extra config;
#   AC#7 — the selection is persisted in the ledger and honoured at spawn time;
#   AC#9 — the in-session rootless daemon runs real containers (c/d), here a
#          busybox file round-trip rather than just `tmux -V`.
#
# SCOPE / SAFETY
# --------------
# * Touches ONLY a throwaway state root under $TMPDIR and a high-numbered
#   compose project (default ai-sandbox-91) so it never collides with a live
#   ai-sandbox-1..N the operator is actually using.
# * Uses production code unchanged — the real lib.sh `write_enabled_devtools`
#   (the same writer the wizard/ReconfigureCommand use), the real spawn.sh, the
#   real docker-compose.dind.yml override, and the image-baked
#   `aisandbox-dind` helper. This script only ORCHESTRATES them; it does not
#   reimplement any of the logic under test.
# * Idempotent + self-cleaning: a teardown trap tears the project down (`-v`),
#   removes the temp state root, and is safe to re-run.
#
# PREREQUISITES (the script checks and skips with a clear message if missing):
#   * docker on PATH + a usable daemon
#   * ai-context:latest image built (`docker compose build`)
#   * /dev/fuse on the host (rootless fuse-overlayfs needs it)
#   * outbound network to download.docker.com (rootless tarball, first run) and
#     to docker.io (busybox image pull inside the nested daemon)
#
# USAGE:  server/src/test/e2e/uc26-dind-busybox-gate.sh
# EXIT :  0 = gate passed; 1 = gate failed; 77 = skipped (prereq missing).

set -euo pipefail

# ── Resolve the repo root (this file lives at server/src/test/e2e/) ───────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd -P)"

GATE_N="${GATE_N:-91}"
PROJECT="ai-sandbox-${GATE_N}"
STATE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/uc26-dind-gate.XXXXXX")"
SENTINEL="uc26-dind-roundtrip-$$-$(date +%s 2>/dev/null || echo fixed)"
PROBE_REL="dind-probe"                       # under the session's /workspace
PASS=0

note() { printf '\n=== %s\n' "$*"; }
skip() { printf 'SKIP (prereq): %s\n' "$*"; SKIPPED=1; exit 77; }
SKIPPED=0

teardown() {
    if [ "${KEEP:-0}" = "1" ]; then
        printf '\n(KEEP=1 — leaving %s up for inspection; state=%s)\n' "$PROJECT" "$STATE_ROOT"
        [ "$PASS" -eq 1 ] && printf 'GATE RESULT: PASS\n' || printf 'GATE RESULT: FAIL\n'
        return
    fi
    note "Teardown"
    # Bring the project down with its volumes; tolerate it never having come up.
    docker compose \
        -f "$REPO_ROOT/docker-compose.yml" \
        -f "$REPO_ROOT/docker-compose.dind.yml" \
        --project-directory "$STATE_ROOT" \
        -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true
    # `rm -rf` as the non-root user can fail when the spawned container left
    # root-owned dirs under the state root (docker auto-creates bind-mount
    # sources like templates/claude-config as root). Fall back to a host-docker
    # busybox running as root to finish the job.
    if ! rm -rf "$STATE_ROOT" 2>/dev/null; then
        docker run --rm -v "$(dirname "$STATE_ROOT")":/t busybox \
            rm -rf "/t/$(basename "$STATE_ROOT")" >/dev/null 2>&1 || true
    fi
    if [ "$SKIPPED" -eq 1 ]; then
        printf '\nGATE RESULT: SKIPPED (prerequisite not met)\n'
    elif [ "$PASS" -eq 1 ]; then
        printf '\nGATE RESULT: PASS\n'
    else
        printf '\nGATE RESULT: FAIL\n'
    fi
}
trap teardown EXIT

# ── 0. Prerequisites ──────────────────────────────────────────────────────────
note "0. Prerequisite checks"
command -v docker >/dev/null 2>&1 || skip "docker not on PATH"
docker info >/dev/null 2>&1 || skip "docker daemon not reachable"
docker image inspect ai-context:latest >/dev/null 2>&1 || skip "ai-context:latest image not built (run: docker compose build)"
[ -e /dev/fuse ] || skip "/dev/fuse absent on host — rootless fuse-overlayfs unavailable"
[ -f "$REPO_ROOT/docker-compose.dind.yml" ] || skip "docker-compose.dind.yml missing from repo root"
# Unprivileged user namespaces must be permitted, or the nested rootless
# dockerd's rootlesskit cannot fork its child. Ubuntu 24.04+/26.04 default
# (apparmor_restrict_unprivileged_userns=1) blocks this at the host kernel
# level — and the container's seccomp/apparmor:unconfined does NOT lift it.
# See docs/DinDSelftestVerificationDoc.md § Prerequisites for remediation.
USERNS_RESTRICT="$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns 2>/dev/null || echo 0)"
if [ "$USERNS_RESTRICT" = "1" ]; then
    skip "host blocks unprivileged userns (kernel.apparmor_restrict_unprivileged_userns=1) — rootless DinD cannot start; set it to 0 (needs root) or attach a userns AppArmor profile, then re-run"
fi
printf 'OK: docker=%s, image present, /dev/fuse present\n' "$(docker version --format '{{.Server.Version}}' 2>/dev/null)"

# Pre-teardown any stale project from a prior aborted run (idempotency).
docker compose -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true

# ── 1. Enable DinD via the REAL ledger writer (same path the wizard uses) ─────
note "1. Enable DinD via lib.sh write_enabled_devtools (real supported path)"
export AISB_DEVTOOLS_FILE="$STATE_ROOT/.ai-sandbox-devtools"
# shellcheck source=/dev/null
. "$REPO_ROOT/lib.sh"
write_enabled_devtools dind
printf 'ledger %s:\n' "$AISB_DEVTOOLS_FILE"; sed 's/^/  /' "$AISB_DEVTOOLS_FILE"
read_enabled_devtools | grep -qx dind || { echo "ledger did not enable dind"; exit 1; }

# ── 2. Spawn a session via the REAL spawn.sh → inherits docker-compose.dind.yml
note "2. Spawn $PROJECT via ./spawn.sh (isolated workspace/config, high N = no collision)"
# Seed the counter so spawn.sh issues GATE_N (avoids colliding with a live
# ai-sandbox-1). State root isolates counter+ledger+workspace under $TMPDIR.
printf '%s\n' "$((GATE_N - 1))" > "$STATE_ROOT/.ai-sandbox-counter"
export AI_SANDBOX_HOST_STATE_ROOT="$STATE_ROOT"
export AI_SANDBOX_COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
"$REPO_ROOT/spawn.sh" --non-interactive --isolated-workspace --isolated-claude-config

# Confirm the override actually landed on the container (AC#5/#7 wiring proof).
note "2b. Confirm the DinD override applied to the container"
have_fuse="$(docker inspect "${PROJECT}-claude-sandbox-1" \
    --format '{{range .HostConfig.Devices}}{{.PathOnHost}} {{end}}' 2>/dev/null || true)"
printf 'container devices: %s\n' "$have_fuse"
case "$have_fuse" in *"/dev/fuse"*) echo "OK: /dev/fuse passed through" ;; *) echo "WARN: /dev/fuse not on container"; esac

# ── 3+4. Inside the session: rootless daemon → busybox writes → read back ─────
note "3+4. Busybox DinD round-trip (write sentinel → read back → validate)"
printf 'sentinel = %s\n' "$SENTINEL"
PROBE_OUT="$(docker compose \
    -f "$REPO_ROOT/docker-compose.yml" \
    -f "$REPO_ROOT/docker-compose.dind.yml" \
    --project-directory "$STATE_ROOT" \
    -p "$PROJECT" exec -T claude-sandbox bash -lc '
        set -e
        export AI_SANDBOX_DEVTOOL_DIND=1
        export XDG_RUNTIME_DIR="/run/user/$(id -u)"
        export DOCKER_HOST="unix://${XDG_RUNTIME_DIR}/docker.sock"
        export PATH="/workspace/environment-utilities/dind/bin:$PATH"

        # entrypoint already ran install+start; this is idempotent insurance and
        # also covers a still-downloading first boot.
        aisandbox-dind start >/dev/null 2>&1 || true

        # Wait (up to ~120s) for the rootless daemon to answer — first boot has
        # to download the static tarball from download.docker.com.
        for i in $(seq 1 120); do
            if docker info >/dev/null 2>&1; then break; fi
            sleep 1
        done
        docker info >/dev/null 2>&1 || { echo "DAEMON_DOWN"; exit 3; }

        SENT="'"$SENTINEL"'"
        PROBE_DIR="/workspace/'"$PROBE_REL"'"
        rm -rf "$PROBE_DIR"; mkdir -p "$PROBE_DIR"

        # (a) a busybox container running IN the nested rootless daemon writes
        #     the sentinel to a bind-mounted path that maps back to the session
        #     container filesystem.
        docker run --rm -v "$PROBE_DIR":/data busybox \
            sh -c "printf %s \"$SENT\" > /data/probe.txt; sync" >/dev/null

        # (b) read it back FROM THE SESSION CONTAINER (not from inside busybox)
        #     to prove the write round-tripped out of the nested container.
        GOT="$(cat "$PROBE_DIR/probe.txt")"
        echo "BUSYBOX_VERSION=$(docker run --rm busybox busybox | head -1)"
        echo "READBACK=$GOT"
        if [ "$GOT" = "$SENT" ]; then echo "ROUNDTRIP_OK"; else echo "ROUNDTRIP_MISMATCH"; fi
    ' 2>&1)" || true
printf '%s\n' "$PROBE_OUT" | sed 's/^/  /'

# ── 5. Validate the round-trip on the host side too ───────────────────────────
note "5. Validate sentinel match"
if printf '%s\n' "$PROBE_OUT" | grep -qx "ROUNDTRIP_OK" \
   && printf '%s\n' "$PROBE_OUT" | grep -qx "READBACK=${SENTINEL}"; then
    echo "MATCH: busybox-in-DinD wrote the sentinel and the session container read it back identically."
    PASS=1
    exit 0
else
    echo "NO MATCH: see transcript above."
    exit 1
fi
