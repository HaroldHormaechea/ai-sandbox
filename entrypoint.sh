#!/usr/bin/env bash
# UC-27 — runs under bash (present on the glibc base) so it can source the
# devtool capability manifests (which declare bash arrays + use BASH_SOURCE) and
# provision the operator-selected capabilities generically at boot.
set -e

PROJECT_DIR=/workspace/project-builder
KEY_FILE=/etc/secrets/git-key
TOKEN_FILE=/etc/secrets/gh-token
GITCONFIG_FILE=/etc/secrets/gitconfig
export RTK_TELEMETRY_DISABLED=1

# UC-27 — devtool provisioning wiring.
#   AI_SANDBOX_DEVTOOLS  — space-separated capability ids the operator selected
#                          (set by spawn.sh, passed through docker-compose.yml's
#                          environment:). Empty/unset → no provisioning, a
#                          session byte-identical to the no-capability default.
#   DEVTOOLS_DIR         — the manifests COPYed into the image (slice D).
#   READY_MARKER         — written AFTER tmux/claude is up; spawn.sh polls it so
#                          attach tolerates the provisioning window. Removed at
#                          the TOP of every run so a `compose restart`
#                          re-provision can't expose a stale "ready".
DEVTOOLS_DIR=/opt/ai-sandbox/devtools.d
READY_MARKER=/tmp/aisandbox-ready
ENV_UTILS_ROOT="${AISB_ENV_UTILS_ROOT:-/workspace/environment-utilities}"
rm -f "$READY_MARKER" 2>/dev/null || true

# devtools_enabled ID → 0 if ID appears in the AI_SANDBOX_DEVTOOLS list.
devtools_enabled() {
    case " ${AI_SANDBOX_DEVTOOLS:-} " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

# provision_capability ID — provision ID after its DEPENDS_ON (deps-first, via a
# visited set so the order is correct regardless of the list order — e.g.
# `android` provisions `java` first even though the list is alphabetical). Each
# manifest's devtool_provision runs the in-container install + PATH wiring. A
# failed provision is warn-and-continue (UC-27 offline policy §E) — it does NOT
# mask the failure from the live G1 gate, which checks real binary resolution.
_provisioned=" "
provision_capability() {
    local id="$1" manifest="$DEVTOOLS_DIR/$1/manifest.sh" deps dep
    case "$_provisioned" in *" $id "*) return 0 ;; esac
    if [ ! -f "$manifest" ]; then
        echo "WARNING: no manifest for devtool '$id' at $manifest — skipping." >&2
        return 0
    fi
    # Read DEPENDS_ON in a subshell (isolates the manifest's hook definitions),
    # provision each dependency first.
    deps="$( ID=""; DEPENDS_ON=(); . "$manifest" >/dev/null 2>&1; \
             for dep in ${DEPENDS_ON[@]+"${DEPENDS_ON[@]}"}; do printf '%s\n' "$dep"; done )"
    for dep in $deps; do
        provision_capability "$dep"
    done
    # Source for real and run the provision hook.
    unset -f devtool_spawn_env devtool_provision devtool_server_install 2>/dev/null || true
    ID=""; LABEL=""; APPLY_AT=""; ARCH=""; WARNING=""; DEPENDS_ON=()
    # shellcheck disable=SC1090
    . "$manifest"
    _provisioned="$_provisioned$id "
    if declare -F devtool_provision >/dev/null 2>&1; then
        echo "Provisioning devtool capability: $id ..." >&2
        devtool_provision || echo "WARNING: provisioning '$id' failed; continuing (offline policy). The capability's binaries may be unavailable this session." >&2
    fi
    unset -f devtool_spawn_env devtool_provision devtool_server_install 2>/dev/null || true
}

# load_devtool_env — source every provisioned capability's env snippet into THIS
# (the entrypoint's) environment so tmux/claude and EVERY `sh -c`/`bash -c` child
# inherit JAVA_HOME / ANDROID_HOME / PATH (AC#10 layer 1 — non-interactive shells
# read no profile/rc, so inheritance is the only path). The provisioners write
# <cache>/env.sh; dind writes its own profile snippet. Also points BASH_ENV at an
# aggregator so a fresh non-interactive bash re-applies the env (belt-and-braces
# layer 3).
load_devtool_env() {
    local ef
    if [ -d "$ENV_UTILS_ROOT" ]; then
        for ef in "$ENV_UTILS_ROOT"/*/env.sh; do
            # shellcheck disable=SC1090
            [ -r "$ef" ] && . "$ef"
        done
    fi
    # shellcheck disable=SC1090
    [ -r "$HOME/.profile.aisandbox-dind" ] && . "$HOME/.profile.aisandbox-dind"
    # Aggregator for BASH_ENV (non-interactive bash) — re-sources the same files.
    local agg="$ENV_UTILS_ROOT/aisandbox-env.sh"
    if [ -d "$ENV_UTILS_ROOT" ]; then
        {
            printf '# Managed by ai-sandbox entrypoint (UC-27) — do not hand-edit.\n'
            printf 'for _ef in %s/*/env.sh; do [ -r "$_ef" ] && . "$_ef"; done\n' "$ENV_UTILS_ROOT"
            printf '[ -r "$HOME/.profile.aisandbox-dind" ] && . "$HOME/.profile.aisandbox-dind"\n'
        } > "$agg" 2>/dev/null || true
        [ -f "$agg" ] && export BASH_ENV="$agg"
    fi
}

# UC-17 — uid self-registration. MUST be the first thing we do, before any
# ssh / git / gh / $HOME-resolving call. When the management server runs this
# container as an arbitrary host uid (compose `user: <uid>:0`) that has no
# /etc/passwd entry, getpwuid() lookups fail and ssh/git/gh error with "no such
# user" / cannot determine home. Appending a minimal passwd line fixes that.
# The build-time `chgrp 0 /etc/passwd && chmod g+w /etc/passwd` (SandboxDockerfile)
# makes the append possible for any gid-0 process. Idempotent: the default
# uid-1000 `claude` user already resolves, so this is a no-op in dev mode. The
# synthesised name is `sandbox` (deliberately != the image's `claude`) so the
# two entries never collide; gid 0 + home /home/claude match the compose `user:`
# and the group-0-writable $HOME the Dockerfile prepared.
if ! getent passwd "$(id -u)" >/dev/null 2>&1; then
    echo "sandbox:x:$(id -u):0:sandbox:/home/claude:/bin/sh" >> /etc/passwd
fi

# Claude Code stores some global state (trusted folders, onboarding, default
# mode, theme) at ~/.claude.json — a file outside the ~/.claude/ directory we
# mount. Symlink it inside that mounted dir so the state persists across runs.
ln -sf "$HOME/.claude/.claude.json" "$HOME/.claude.json"

# UC06 § AC7 — seed ~/.claude/ from the operator-built template
# bind-mounted RO at /etc/claude-template/ exactly once per session.
# The `.seeded` marker pins this to first-start of a fresh
# claude-config volume; operators rotating the template on the host
# must respawn affected sessions to pick it up. The cp is best-effort:
# a failure (e.g., template held a stale ACL we can't replicate) is
# warned-and-continued so the session still boots — sessions will just
# lack the pre-init state, exactly the same as a deployment that ran
# `secrets seed --no-claude-preinit`. Ordered BEFORE the RTK / CLAUDE.md
# blocks so those operate on the seeded ~/.claude/ contents.
if [ -d /etc/claude-template ] && [ ! -e "$HOME/.claude/.seeded" ]; then
    mkdir -p "$HOME/.claude"
    cp -a /etc/claude-template/. "$HOME/.claude/" \
        || echo "WARNING: claude template seeding failed; sessions may lack Claude pre-init state." >&2
    touch "$HOME/.claude/.seeded"
fi

# Android emulator skill. The runbook ships at /opt/ai-sandbox/skills/ on EVERY
# image now (UC-27 — the base no longer forks by flavour), so the seed is gated
# on the `android` capability being enabled for THIS session rather than on the
# dir's mere presence — otherwise a no-capability session would carry an Android
# skill it can't use (G1 no-trace). ~/.claude is bind-mounted, so a build-time
# copy into ~/.claude/skills/ would be masked; install it here instead — BEFORE
# tmux launches claude, so Claude Code sees it at session start (it only
# hot-loads skills present when the session begins). Refreshed every start; a
# copy failure warns-and-continues so the session still boots.
if devtools_enabled android && [ -d /opt/ai-sandbox/skills ]; then
    mkdir -p "$HOME/.claude/skills"
    for _skill_src in /opt/ai-sandbox/skills/*/; do
        [ -d "$_skill_src" ] || continue
        _skill_name=$(basename "$_skill_src")
        rm -rf "$HOME/.claude/skills/$_skill_name"
        cp -a "$_skill_src" "$HOME/.claude/skills/$_skill_name" \
            || echo "WARNING: failed to install Android skill '$_skill_name' into ~/.claude/skills." >&2
    done
fi

# RTK (Rust Token Killer) wiring. MUST run here (post-mount, pre-tmux), NOT in
# the Dockerfile: anything `rtk init -g` writes lives under ~/.claude/, which is
# bind-mounted from the host's claude-config/ at runtime — the build-time copy
# would be masked by the mount. Running it in entrypoint.sh means the hook
# lands in the persisted host folder and survives docker compose down/up.
mkdir -p "$HOME/.claude"
# UC-17 — RTK also writes non-mounted state under ~/.config/rtk. When running as
# an arbitrary uid that dir may not exist yet (only ~/.claude is mounted); create
# it so rtk init never trips on a missing parent. Harmless when it already exists.
mkdir -p "$HOME/.config"

# 1. Let RTK install its global hook into ~/.claude/settings.json (and possibly
#    emit ~/.claude/RTK.md). Idempotent per upstream; warn-and-continue on
#    failure so a flaky run does not block the container from booting.
yes n | rtk init -g  --auto-patch || echo "WARNING: rtk init -g failed; RTK hook may be missing." >&2

# 2. Conditionally append a directive to ~/.claude/CLAUDE.md nudging Claude to
#    prefer Bash equivalents (cat, rg, find) over the built-in Read / Grep /
#    Glob tools, since those bypass the Bash hook and therefore bypass RTK.
#    Sentinel-guarded so repeated container starts do not duplicate the block.
#    We also peek inside whatever upstream RTK may have written: if either
#    CLAUDE.md or RTK.md already nudges away from the built-ins, skip the append
#    to avoid conflicting / duplicate guidance.
RTK_SENTINEL_START="<!-- ai-sandbox:rtk-bypass-START -->"
RTK_SENTINEL_END="<!-- ai-sandbox:rtk-bypass-END -->"
_rtk_already_documented() {
    # Args: $1 = file to check. Returns 0 if the file references both at least
    # one built-in tool name (Read/Grep/Glob) and at least one Bash equivalent
    # (cat/rg/find/grep) — a heuristic for "upstream already covers this".
    [ -f "$1" ] || return 1
    grep -qE 'Read|Grep|Glob' "$1" 2>/dev/null \
        && grep -qE '\bcat\b|\brg\b|\bgrep\b|\bfind\b' "$1" 2>/dev/null
}
if grep -qF "$RTK_SENTINEL_START" "$HOME/.claude/CLAUDE.md" 2>/dev/null; then
    : # Already appended on a previous start — idempotent no-op.
elif _rtk_already_documented "$HOME/.claude/CLAUDE.md" \
  || _rtk_already_documented "$HOME/.claude/RTK.md"; then
    echo "INFO: existing CLAUDE.md/RTK.md already nudges away from built-in Read/Grep/Glob; skipping ai-sandbox append." >&2
else
    cat >> "$HOME/.claude/CLAUDE.md" <<EOF
$RTK_SENTINEL_START
## Prefer Bash equivalents over built-in Read / Grep / Glob

Prefer Bash tool calls over Claude Code's built-in file tools:

- Use \`cat\` instead of the \`Read\` tool.
- Use \`rg\` (preferred) or \`grep\` instead of the \`Grep\` tool.
- Use \`find\` instead of the \`Glob\` tool.

Rationale: built-in Read / Grep / Glob bypass the Bash hook and therefore
bypass RTK token compression. Routing through Bash keeps RTK in the loop.
$RTK_SENTINEL_END
EOF
fi

if [ -f "$KEY_FILE" ]; then
    mkdir -p "$HOME/.ssh"
    chmod 700 "$HOME/.ssh"
    cp "$KEY_FILE" "$HOME/.ssh/git-key"
    chmod 600 "$HOME/.ssh/git-key"
    cat > "$HOME/.ssh/config" <<EOF
Host *
  IdentityFile $HOME/.ssh/git-key
  IdentitiesOnly yes
  StrictHostKeyChecking accept-new
EOF
    chmod 600 "$HOME/.ssh/config"

    # Ensure `gh repo clone` and friends use SSH instead of HTTPS.
    gh config set git_protocol ssh >/dev/null 2>&1 || true
else
    echo "WARNING: no SSH key at $KEY_FILE — git over SSH will not work." >&2
fi

# Apply git author identity (user.name / user.email) from the bind-mounted
# gitconfig if present, by adding it to git's global include.path so every
# commit Claude makes — in any cloned project — inherits these values.
# Check-before-add keeps include.path idempotent across container restarts.
if [ -f "$GITCONFIG_FILE" ]; then
    if ! git config --global --get-all include.path 2>/dev/null | grep -qx "$GITCONFIG_FILE"; then
        git config --global --add include.path "$GITCONFIG_FILE"
    fi
else
    echo "WARNING: no git identity at $GITCONFIG_FILE — git commit will fail. Re-run host setup." >&2
fi

# Authenticate gh from the token file if present (used for API ops:
# gh issue list, gh pr create, etc. — cloning still goes over SSH).
if [ -f "$TOKEN_FILE" ] && ! gh auth status >/dev/null 2>&1; then
    gh auth login --with-token < "$TOKEN_FILE" || echo "WARNING: gh auth login failed." >&2
fi

if [ ! -d "$PROJECT_DIR/.git" ]; then
    echo "Cloning project-builder into $PROJECT_DIR..."
    git clone git@github.com:HaroldHormaechea/project-builder.git "$PROJECT_DIR" \
        || echo "WARNING: clone failed; continuing without project." >&2
fi

START_DIR="$PROJECT_DIR"
[ -d "$START_DIR" ] || START_DIR=/workspace

# UC-27 — provision every operator-selected capability EAGERLY AT SPAWN, BEFORE
# tmux launches claude, so the session is ready at handover and pays no first-use
# install delay (AC#3,#12). This is the generic capability dispatch loop: it
# sources each manifest and runs its devtool_provision hook (deps-first), with no
# per-id `case` — adding a capability needs no edit here (AC#2). DinD's
# provision hook runs the same `aisandbox-dind install` + `start` as before;
# Java/Android provision their toolchains into the persisted cache.
#
# Then load every provisioned capability's env into the entrypoint's own
# environment so claude and every non-login `sh -c` child inherit JAVA_HOME /
# ANDROID_HOME / PATH (AC#10 — the entrypoint PATH-inheritance fix, not just a
# profile.d snippet). With no capabilities the loop is a no-op and the session is
# byte-identical to today (AC#12).
if [ -n "${AI_SANDBOX_DEVTOOLS:-}" ]; then
    for _devtool_id in ${AI_SANDBOX_DEVTOOLS}; do
        provision_capability "$_devtool_id"
    done
    load_devtool_env
fi

# If a command is passed (e.g. one-off setup runs), execute it in the project
# directory after bootstrap. Otherwise launch the persistent tmux session.
if [ "$#" -gt 0 ]; then
    cd "$START_DIR"
    exec "$@"
fi

# Wrap claude in a restart loop. On /exit:
#   1. detach any attached clients (kicks them back to the host shell)
#   2. wipe the pane (visible + scrollback) so the next attach lands clean
#   3. relaunch claude so it's ready for the next ./attach
# The tmux session itself never dies.
tmux new-session -d -s main -c "$START_DIR" \
    'while true; do cid="$(cat /proc/sys/kernel/random/uuid)"; claude --session-id "$cid" --dangerously-skip-permissions; tmux detach-client -s main 2>/dev/null; printf "\033c\033[3J"; sleep 1; done'

# UC-27 — signal readiness AFTER the main tmux session exists (capabilities are
# already provisioned above, so toolchains are ready at handover). spawn.sh polls
# this marker via `docker compose exec` before reporting the session "running",
# and attach tolerates the provisioning window. The marker was rm -f'd at the top
# of this run, so a `compose restart` re-provision never exposes a stale ready.
touch "$READY_MARKER" 2>/dev/null || true

exec tail -f /dev/null
