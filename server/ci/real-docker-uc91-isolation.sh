#!/usr/bin/env bash
# server/ci/real-docker-uc91-isolation.sh — UC-91 cross-session transcript
# isolation regression (the durable, two-container behavioral guard).
#
# THE BUG (UC-91): two sessions spawned with the DEFAULT shared `~/.claude`
# (./claude-config) AND shared `/workspace` write their conversation transcripts
# into the SAME `~/.claude/projects/<cwd-slug>/` dir. The in-container
# transcript resolver (`aisandbox-conversation-tail`) then adopts a CONCURRENTLY
# ACTIVE foreign session's transcript for the wrong session — so switching from
# one session's chat to another shows the OLD conversation (sticky) and the
# agent pills resolve to the wrong session.
#
# THE FIX (Approach B): nest a PER-SESSION bind at `~/.claude/projects`
# (`./claude-projects-<N>`) on top of the shared `~/.claude` mount, so each
# session's transcript store is physically isolated while auth / settings /
# session-registry under `~/.claude` stay shared. The bleed becomes structurally
# impossible: container B can never SEE container A's transcript file.
#
# Why this can ONLY be a real two-container test (not a unit test): the original
# `/proc`-process lineage idea was inert because sessions are separate, PID-
# isolated containers (B can't see A's process). And in a dev sandbox with a
# LEAKY /proc, a /proc-based fix would FALSE-POSITIVE. The only trustworthy guard
# is the filesystem-level behavioral assertion below: spawn TWO real containers
# with the production compose + per-session projects mount, write a transcript in
# A, and prove B's projects dir does NOT contain it.
#
# Asserts:
#   (a) ISOLATION  — container B's `~/.claude/projects/<slug>/` does NOT contain
#                    container A's transcript (cross-session bleed impossible).
#   (b) CO-RESIDENCE — within ONE container, the main transcript AND a teammate /
#                    subagent transcript share the same per-session slug dir, so
#                    UC-60 agent-pill enumeration is NOT over-isolated.
#   (c) SHARED AUTH — `~/.claude/.credentials.json` (non-projects) stays the
#                    SHARED copy across both containers.
#
# Layout (mirrors real-docker-integration.sh's contract):
#   set -euo pipefail; log/fail helpers; per-PID scratch tree; trap-EXIT
#   teardown that drops both compose projects and removes the scratch.
#
# Gating: invoked via the Gradle task `:server:realDockerUc91IsolationTest`
# (disabled unless AI_SANDBOX_REAL_DOCKER_IT=1). The script does not re-check the
# env var — it assumes the caller decided it should run. Requires the
# `ai-context:latest` image (built by real-docker-onboarding.sh).
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
IMAGE="${AI_SANDBOX_IMAGE:-ai-context:latest}"
SLUG="-workspace"                       # slugFromCwd('/workspace')
PROJ_A="ai-sandbox-91a"
PROJ_B="ai-sandbox-91b"

SCRATCH="${TMPDIR:-/tmp}/uc91-iso-$$"
SHARED_CLAUDE="$SCRATCH/claude-config"  # shared ~/.claude
PROJECTS_A="$SCRATCH/claude-projects-a" # per-session projects for A
PROJECTS_B="$SCRATCH/claude-projects-b" # per-session projects for B
WORKSPACE="$SCRATCH/workspace"
SECRETS="$SCRATCH/secrets"
TEMPLATE="$SCRATCH/template"

log()  { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
fail() { printf '[%s] FAIL: %s\n' "$SCRIPT_NAME" "$*" >&2; exit 1; }

# A minimal container run for one project: shared ~/.claude + per-session
# projects, root user (so the bind dirs are writable regardless of host uid),
# entrypoint overridden to bash so we DON'T trigger the heavy devtools
# provisioning — we are only exercising the mount topology + resolver.
run_in() { # <project> <projects-host-dir> <bash-snippet>
    AI_SANDBOX_CLAUDE_CONFIG_HOST_PATH="$SHARED_CLAUDE" \
    AI_SANDBOX_CLAUDE_PROJECTS_HOST_PATH="$2" \
    AI_SANDBOX_WORKSPACE_HOST_PATH="$WORKSPACE" \
    AI_SANDBOX_SECRETS_HOST_PATH="$SECRETS" \
    AI_SANDBOX_CLAUDE_TEMPLATE_HOST_PATH="$TEMPLATE" \
    AI_SANDBOX_RUN_AS_USER=0 \
    docker compose -p "$1" -f "$COMPOSE_FILE" \
        run --rm --no-deps --entrypoint bash claude-sandbox -c "$3"
}

teardown() {
    local rc=$?
    log "teardown (exit so far: $rc)"
    AI_SANDBOX_COMPOSE_FILE="$COMPOSE_FILE" docker compose -p "$PROJ_A" -f "$COMPOSE_FILE" down --remove-orphans >/dev/null 2>&1 || true
    AI_SANDBOX_COMPOSE_FILE="$COMPOSE_FILE" docker compose -p "$PROJ_B" -f "$COMPOSE_FILE" down --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$SCRATCH" 2>/dev/null || true
}
trap teardown EXIT

# ── Pre-flight ───────────────────────────────────────────────────────────────
command -v docker >/dev/null || fail "docker not on PATH"
docker info >/dev/null 2>&1 || fail "docker daemon not reachable"
docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || fail "$IMAGE missing — run real-docker-onboarding.sh first to build it"
[ -f "$COMPOSE_FILE" ] || fail "compose file not found: $COMPOSE_FILE"

mkdir -p "$SHARED_CLAUDE/sessions" "$PROJECTS_A" "$PROJECTS_B" "$WORKSPACE" "$SECRETS" "$TEMPLATE"
# A shared (non-projects) auth file — must be visible identically in BOTH containers.
printf 'SHARED-CREDS-MARKER' > "$SHARED_CLAUDE/.credentials.json"

# ── 1. Container A writes its transcript into its per-session projects ────────
log "container A ($PROJ_A): seeding ALPHA transcript"
run_in "$PROJ_A" "$PROJECTS_A" '
    set -e
    mkdir -p /home/claude/.claude/projects/'"$SLUG"'
    printf "%s\n" "{\"type\":\"summary\",\"sessionId\":\"sess-A\",\"summary\":\"ALPHA\"}" \
                  "{\"type\":\"assistant\",\"sessionId\":\"sess-A\",\"message\":{\"role\":\"assistant\",\"content\":\"I AM SESSION ALPHA\"}}" \
        > /home/claude/.claude/projects/'"$SLUG"'/sess-A.jsonl
' || fail "container A seed run failed"
[ -f "$PROJECTS_A/$SLUG/sess-A.jsonl" ] \
    || fail "A's transcript did not land in its per-session projects host dir"

# ── 2. Container B: assert isolation + shared auth + co-residence + resolver ──
log "container B ($PROJ_B): asserting isolation + co-residence + resolver"
B_OUT="$(run_in "$PROJ_B" "$PROJECTS_B" '
    set -e
    P=/home/claude/.claude/projects/'"$SLUG"'
    mkdir -p "$P"
    # (a) ISOLATION — B must NOT see A'\''s transcript.
    if [ -f "$P/sess-A.jsonl" ]; then echo "ISO=BLEED"; else echo "ISO=OK"; fi
    # (c) SHARED AUTH — the non-projects auth file is the shared copy.
    if [ "$(cat /home/claude/.claude/.credentials.json 2>/dev/null)" = "SHARED-CREDS-MARKER" ]; then echo "CREDS=SHARED"; else echo "CREDS=BROKEN"; fi
    # B writes its OWN main transcript + a teammate transcript + a subagent file.
    printf "%s\n" "{\"type\":\"summary\",\"sessionId\":\"sess-B\",\"summary\":\"BRAVO\"}" \
                  "{\"type\":\"assistant\",\"sessionId\":\"sess-B\",\"message\":{\"role\":\"assistant\",\"content\":\"I AM SESSION BRAVO\"}}" \
        > "$P/sess-B.jsonl"
    printf "%s\n" "{\"type\":\"summary\",\"sessionId\":\"team-qa\"}" \
                  "{\"type\":\"attachment\",\"agentName\":\"qa\",\"teamName\":\"t1\"}" \
        > "$P/team-qa.jsonl"
    mkdir -p "$P/sess-B/subagents"
    printf "%s\n" "{\"type\":\"assistant\",\"message\":{\"content\":\"sub\"}}" > "$P/sess-B/subagents/agent-x1.jsonl"
    # (b) CO-RESIDENCE — main + teammate share the same per-session slug dir.
    if [ -f "$P/sess-B.jsonl" ] && [ -f "$P/team-qa.jsonl" ] && [ -f "$P/sess-B/subagents/agent-x1.jsonl" ]; then echo "CORESIDE=OK"; else echo "CORESIDE=MISSING"; fi
    # (c) RESOLVER — the production helper resolves B'\''s OWN transcript and only
    #     ever sees B'\''s files (A absent ⇒ bleed impossible even pre-helper-fix).
    node -e "
      const h=require(\"/usr/local/bin/aisandbox-conversation-tail\");
      const dir=\"$P\";
      const c=h.listTopLevelTranscripts(dir).map(x=>{const id=h.readTranscriptIdentity(x.path,65536);return{path:x.path,stem:x.stem,mtimeMs:x.mtimeMs,agentNamePresent:id.agentNamePresent};});
      const stems=c.map(x=>x.stem).sort().join(\",\");
      const r=h.selectMainCurrent(c,\"sess-B\")||\"\";
      console.log(\"STEMS=\"+stems);
      console.log(\"RESOLVED=\"+(r.indexOf(\"sess-A\")>=0?\"A-FOREIGN\":(r.indexOf(\"sess-B\")>=0?\"B-OWN\":\"none\")));
    "
    # (d) UC-65 /clear-follow (rotation-follow restored post-revert) + a RED control
    # that proves the resolver WOULD bleed if a foreign file were co-resident — i.e.
    # the per-session mount isolation is what makes (a)/(c) GREEN.
    RG=/tmp/uc91-rg;  rm -rf "$RG";  mkdir -p "$RG"
    printf "%s\n" "{\"type\":\"summary\",\"sessionId\":\"sess-B\"}" > "$RG/sess-B.jsonl"      # B launch (older)
    printf "%s\n" "{\"type\":\"summary\",\"sessionId\":\"bnew\"}"   > "$RG/bnew.jsonl"        # B post-/clear (newer, B-owned)
    touch -d "2020-01-01 00:00:00" "$RG/sess-B.jsonl"; touch -d "2020-01-01 00:05:00" "$RG/bnew.jsonl"
    RED=/tmp/uc91-red; rm -rf "$RED"; mkdir -p "$RED"
    printf "%s\n" "{\"type\":\"summary\",\"sessionId\":\"sess-B\"}" > "$RED/sess-B.jsonl"     # B (older)
    printf "%s\n" "{\"type\":\"summary\",\"sessionId\":\"sess-A\"}" > "$RED/sess-A.jsonl"     # A foreign (newer) — pre-fix co-residence
    touch -d "2020-01-01 00:00:00" "$RED/sess-B.jsonl"; touch -d "2020-01-01 00:05:00" "$RED/sess-A.jsonl"
    node -e "
      const h=require(\"/usr/local/bin/aisandbox-conversation-tail\");
      const C=d=>h.listTopLevelTranscripts(d).map(x=>{const id=h.readTranscriptIdentity(x.path,65536);return{path:x.path,stem:x.stem,mtimeMs:x.mtimeMs,agentNamePresent:id.agentNamePresent};});
      const rg=h.selectMainCurrent(C(\"/tmp/uc91-rg\"),\"sess-B\")||\"\";
      console.log(\"CLEARFOLLOW=\"+(rg.indexOf(\"bnew\")>=0?\"POST-CLEAR\":(rg.indexOf(\"sess-B\")>=0?\"STALE-LAUNCH\":\"none\")));
      const red=h.selectMainCurrent(C(\"/tmp/uc91-red\"),\"sess-B\")||\"\";
      console.log(\"REDCONTROL=\"+(red.indexOf(\"sess-A\")>=0?\"BLEEDS-A\":(red.indexOf(\"sess-B\")>=0?\"B\":\"none\")));
    "
')" || fail "container B assertion run failed"

echo "$B_OUT" | sed 's/^/[B] /'

echo "$B_OUT" | grep -q 'ISO=OK'        || fail "(a) ISOLATION: container B can SEE A's transcript — cross-session bleed!"
echo "$B_OUT" | grep -q 'CREDS=SHARED'  || fail "(c) SHARED AUTH: ~/.claude/.credentials.json is not the shared copy in B"
echo "$B_OUT" | grep -q 'CORESIDE=OK'   || fail "(b) CO-RESIDENCE: main+teammate+subagent did not co-reside in B's slug dir"
echo "$B_OUT" | grep -q 'STEMS=sess-B'  || fail "(a) ISOLATION: B's resolver candidate set is not B-only (foreign stems present)"
echo "$B_OUT" | grep -q 'RESOLVED=B-OWN' || fail "(c) RESOLVER: B did not resolve its OWN transcript"
echo "$B_OUT" | grep -q 'RESOLVED=A-FOREIGN' && fail "(c) RESOLVER: B resolved A's FOREIGN transcript — bleed!"
# (d) UC-65 rotation-follow must be RESTORED post-revert (interim fail-closed must be gone).
echo "$B_OUT" | grep -q 'CLEARFOLLOW=POST-CLEAR' || fail "(d) UC-65 /clear-follow broken: helper did not adopt B's newer post-/clear transcript (stuck on stale launch file)"
# RED control: the resolver MUST bleed when a foreign file is co-resident — this is
# exactly the pre-fix condition the per-session mount eliminates. If it does NOT
# bleed here, the resolver semantics changed and our isolation argument is moot.
echo "$B_OUT" | grep -q 'REDCONTROL=BLEEDS-A' || fail "RED control invalid: resolver did not bleed on co-resident foreign file — re-derive the isolation argument"

log "PASS — (a) cross-session isolation, (b) agent co-residence (UC-60 pills), (c) shared auth + B-own resolution, (d) UC-65 /clear-follow restored. RED control confirms the mount isolation is load-bearing (resolver bleeds when foreign files are co-resident)."
