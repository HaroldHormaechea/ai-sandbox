#!/usr/bin/env bash
# server/ci/real-docker-onboarding.sh — UC-17 real-Docker onboarding/uid step.
#
# Proves the UC-17 uid-alignment fix end-to-end through the REAL spawn path:
# build ai-context:latest, lay down a server-owned (uid 4242) scratch tree
# (secrets/git-key 0600, gitconfig, a Claude pre-init template), then drive
# `spawn.sh` exactly as the management server does — install-mode env vars +
# AI_SANDBOX_RUN_AS_USER=4242:0 so the container runs as compose `user: 4242:0`.
#
# The original bug: a session container running as a uid OTHER than the image's
# `claude` (1000) hit "Permission denied" creating ~/.claude/CLAUDE.md (Exited 1)
# and could not read the 0600 git-key ("Permission denied (publickey)"). The
# session container only worked because the operator's uid happened to be 1000.
#
# This script asserts the post-fix contract for an arbitrary uid (4242):
#   • the spawned container stays Up (no Exited 1 on a write to ~/.claude);
#   • getent passwd 4242 resolves (entrypoint self-registered the passwd line);
#   • the entrypoint wrote into the mounted ~/.claude (the EACCES half);
#   • the Claude pre-init template was copied into ~/.claude (cp -a seeding);
#   • ssh config resolves and the 0600 git-key is readable by uid 4242 (the
#     publickey half).
#
# Gating: invoked through Gradle `:server:realDockerOnboardingTest`, disabled
# unless AI_SANDBOX_REAL_DOCKER_IT=1. The script does not re-check the env var —
# the caller decided it should run. Mirrors real-docker-integration.sh's shape.

set -euo pipefail

# ---------------------------------------------------------------------
# 0. Globals + working-dir locations.
# ---------------------------------------------------------------------
SCRIPT_NAME="real-docker-onboarding"
ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
ARTIFACT_DIR="${ROOT_DIR}/server/build/real-docker-onboarding-test"
IMAGE_TAG="ai-context:latest"
RUN_AS_UID=4242

mkdir -p "$ARTIFACT_DIR"

# Scratch tree under $RUNNER_TEMP on GitHub Actions, $TMPDIR or /tmp otherwise.
SCRATCH="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ai-sandbox-onboarding-it-$$"
PROJECT=""

log()  { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
fail() { printf '[%s] FAIL: %s\n' "$SCRIPT_NAME" "$*" >&2; exit 1; }

# docker compose wrapper bound to the spawned project (set after spawn).
compose() {
    docker compose \
        -f "$ROOT_DIR/docker-compose.yml" \
        --project-directory "$SCRATCH/state" \
        -p "$PROJECT" "$@"
}

# ---------------------------------------------------------------------
# Teardown — drop the compose project, remove the (root/4242-owned) scratch.
# ---------------------------------------------------------------------
teardown() {
    rc=$?
    set +e
    if [ -n "$PROJECT" ]; then
        log "teardown: docker compose down -v for $PROJECT"
        compose down -v >>"$ARTIFACT_DIR/docker.log" 2>&1 || true
    fi
    # The scratch tree holds 4242-owned files; rm as the invoking user first,
    # fall back to sudo for the chowned bits.
    rm -rf "$SCRATCH" 2>/dev/null || sudo rm -rf "$SCRATCH" 2>/dev/null || true
    return $rc
}
trap teardown EXIT

# ---------------------------------------------------------------------
# 1. Pre-flight.
# ---------------------------------------------------------------------
preflight() {
    log "preflight: checking host capabilities"
    command -v docker >/dev/null 2>&1 || fail "docker not on PATH"
    docker compose version >/dev/null 2>&1 || fail "docker compose plugin missing"
    command -v sudo >/dev/null 2>&1 || fail "sudo required (need to chown the scratch tree to uid $RUN_AS_UID)"
    command -v ssh-keygen >/dev/null 2>&1 || fail "ssh-keygen required"
    sudo -n true 2>/dev/null || fail "passwordless sudo required on this runner"
}

# ---------------------------------------------------------------------
# 2. Ensure the session image exists.
# ---------------------------------------------------------------------
ensure_image() {
    if docker image inspect "$IMAGE_TAG" >/dev/null 2>&1; then
        log "ensure_image: $IMAGE_TAG already present"
        return
    fi
    log "ensure_image: building $IMAGE_TAG (one-time, may take a few minutes)"
    docker compose -f "$ROOT_DIR/docker-compose.yml" --project-directory "$ROOT_DIR" \
        build claude-sandbox >>"$ARTIFACT_DIR/docker.log" 2>&1 \
        || { tail -80 "$ARTIFACT_DIR/docker.log" >&2 || true; fail "docker compose build claude-sandbox failed"; }
}

# ---------------------------------------------------------------------
# 3. Lay down the server-owned (uid 4242) scratch tree.
# ---------------------------------------------------------------------
build_scratch() {
    log "build_scratch: creating uid-$RUN_AS_UID-owned tree at $SCRATCH"

    # state/ holds the spawn counter + lockdir (written by the invoking user)
    # and the per-session bind sources (workspace/ + claude-config/), which MUST
    # be owned by the runtime uid so the container can write them. Pre-create
    # them 4242-owned; spawn.sh's `mkdir -p` (UC-17) is then an idempotent no-op
    # that does NOT clobber the ownership.
    mkdir -p "$SCRATCH/state"
    sudo install -d -o "$RUN_AS_UID" -g 0 -m 0775 "$SCRATCH/state/workspace"
    sudo install -d -o "$RUN_AS_UID" -g 0 -m 0775 "$SCRATCH/state/claude-config"

    # secrets/ — a throwaway 0600 ed25519 git-key + gitconfig, owned 4242:0
    # exactly as `pki init`/`secrets seed`/`onboard` would leave them.
    mkdir -p "$SCRATCH/secrets"
    ssh-keygen -t ed25519 -N "" -f "$SCRATCH/secrets/git-key" -C onboard-it >/dev/null
    printf '[user]\n\tname = Onboard CI\n\temail = onboard-ci@example.com\n' > "$SCRATCH/secrets/gitconfig"
    sudo chown -R "$RUN_AS_UID:0" "$SCRATCH/secrets"
    sudo chmod 600 "$SCRATCH/secrets/git-key"

    # templates/claude-config/ — a Claude pre-init template with a marker the
    # entrypoint cp -a's into ~/.claude. World-readable so the RO mount is
    # readable by uid 4242.
    mkdir -p "$SCRATCH/templates/claude-config"
    echo "UC17_TEMPLATE_MARKER" > "$SCRATCH/templates/claude-config/uc17-marker"
    chmod -R a+rX "$SCRATCH/templates"
}

# ---------------------------------------------------------------------
# 4. Spawn a session via the real spawn.sh in install mode.
# ---------------------------------------------------------------------
spawn_session() {
    log "spawn_session: invoking spawn.sh with AI_SANDBOX_RUN_AS_USER=$RUN_AS_UID:0"

    # Install-mode env the management server's ScriptExecutorService exports,
    # plus the UC-17 user override. spawn.sh cds to HOST_STATE_ROOT, resolves
    # ./workspace + ./claude-config there, and runs `compose up -d`; the compose
    # `user:` field interpolates AI_SANDBOX_RUN_AS_USER.
    export AI_SANDBOX_COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
    export AI_SANDBOX_HOST_STATE_ROOT="$SCRATCH/state"
    export AI_SANDBOX_SECRETS_HOST_PATH="$SCRATCH/secrets"
    export AI_SANDBOX_CLAUDE_TEMPLATE_HOST_PATH="$SCRATCH/templates/claude-config"
    export AI_SANDBOX_RUN_AS_USER="$RUN_AS_UID:0"

    bash "$ROOT_DIR/spawn.sh" --non-interactive --shared-workspace --shared-claude-config \
        >>"$ARTIFACT_DIR/spawn.log" 2>&1 \
        || { tail -60 "$ARTIFACT_DIR/spawn.log" >&2 || true; fail "spawn.sh failed"; }

    local n
    n="$(tr -d '[:space:]' < "$SCRATCH/state/.ai-sandbox-counter" 2>/dev/null || true)"
    case "$n" in
        ''|*[!0-9]*) fail "could not read spawned session number from counter file" ;;
    esac
    PROJECT="ai-sandbox-$n"
    log "spawn_session: spawned $PROJECT"
}

# ---------------------------------------------------------------------
# 5. Assert the container stays Up + run the uid-4242 probe battery.
# ---------------------------------------------------------------------
assert_session() {
    local cid
    cid="$(compose ps -q claude-sandbox 2>/dev/null | head -n 1 || true)"
    [ -n "$cid" ] || fail "no claude-sandbox container found for $PROJECT"

    # Wait for the entrypoint to write its ~/.claude + ~/.ssh artifacts (rtk
    # hook + ssh config land before the slow git-clone/tmux tail). If the
    # container Exited 1 (the bug), bail with its logs.
    local ready=0 i
    for i in $(seq 1 60); do
        if [ "$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)" != "true" ]; then
            log "── container exited early — docker logs ──"
            docker logs "$cid" >&2 2>&1 || true
            fail "$PROJECT container is not Running (regression: session Exited on a ~/.claude write?)"
        fi
        if compose exec -T claude-sandbox sh -c \
                'test -f "$HOME/.claude/settings.json" && test -f "$HOME/.ssh/config"' 2>/dev/null; then
            ready=1
            break
        fi
        sleep 2
    done
    [ "$ready" -eq 1 ] || { docker logs "$cid" >&2 2>&1 || true; fail "entrypoint did not finish bootstrap within 120s"; }

    log "assert_session: container Up; running uid-$RUN_AS_UID probe battery"
    # Probes run via `compose exec` (default user = the compose `user:` = 4242:0).
    # The nudge doc is asserted as "CLAUDE.md OR RTK.md": rtk init writes its hook
    # at settings.json, and the bash-nudge lands in CLAUDE.md unless rtk already
    # documented it in RTK.md — either file proves the write to ~/.claude.
    compose exec -T claude-sandbox sh -c '
        set -e
        echo "[probe] uid=$(id -u) gid=$(id -g) HOME=$HOME"
        getent passwd '"$RUN_AS_UID"'
        test -f "$HOME/.claude/settings.json"
        test -f "$HOME/.claude/CLAUDE.md" || test -f "$HOME/.claude/RTK.md"
        grep -q UC17_TEMPLATE_MARKER "$HOME/.claude/uc17-marker"
        ssh -G github.com >/dev/null
        cat /etc/secrets/git-key >/dev/null
        echo "[probe] all uid-'"$RUN_AS_UID"' onboarding probes passed"
    ' 2>&1 | tee "$ARTIFACT_DIR/probes.log" \
        || { docker logs "$cid" >&2 2>&1 || true; fail "uid-$RUN_AS_UID probe battery failed"; }
}

# ---------------------------------------------------------------------
# main
# ---------------------------------------------------------------------
preflight
ensure_image
build_scratch
spawn_session
assert_session
log "PASS — uid-$RUN_AS_UID session spawned, stayed Up, and passed every bootstrap probe."
