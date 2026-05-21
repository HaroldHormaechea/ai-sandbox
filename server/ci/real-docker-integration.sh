#!/usr/bin/env bash
# server/ci/real-docker-integration.sh — UC-17 real-Docker integration step.
#
# Boots the real server fat-jar against a real Docker daemon and issues
# mTLS curls against the docker-touching REST surface (`/v1/sessions*`,
# `/v1/healthz`). Catches post-commit `UnsupportedOperationException`
# regressions in SecurityHeadersFilter and any sibling-filter drift
# that re-introduces sealed-response mutation.
#
# The unit-test suite cannot catch this class of bug because
# MockServerWebExchange never transitions to COMMITTED and a stubbed
# Flux endpoint never triggers `Mono.then`-style header writes after
# the response has flushed. This script exercises the same code paths
# with a real Reactor-Netty pipeline + real Docker side-effects so
# the bug class shows up in CI before it ships.
#
# Layout:
#   1. Pre-flight (docker / java / curl / openssl / passwordless sudo).
#   2. Bootstrap PKI under a scratch dir (sudo for pki init only).
#   3. Mint a curl-ready PEM client trio.
#   4. Boot the server fat-jar in the background with the scratch
#      paths wired in via Spring CLI args (no production paths touched).
#   5. Pre-create a real docker-compose project (`ai-sandbox-99`) so
#      `/v1/sessions` enumeration has a real target to find.
#   6. Real mTLS curl battery: /v1/healthz, /v1/sessions, /v1/sessions/99.
#   7. Bug-catching assertions — grep server stderr+stdout for the
#      three forbidden strings (UnsupportedOperationException, "already
#      committed", "Unmapped exception in REST flow"). Each MUST be
#      absent post-fix; presence => regression.
#   8. trap-EXIT teardown — kill server, drop compose project, rm scratch.
#
# Gating: this script is invoked through the Gradle task
# `:server:realDockerIntegrationTest`, which is disabled unless
# `AI_SANDBOX_REAL_DOCKER_IT=1`. The script itself does not re-check
# the env var — it assumes the caller has decided it should run.

set -euo pipefail

# ---------------------------------------------------------------------
# 0. Globals + working dir locations.
# ---------------------------------------------------------------------
SCRIPT_NAME="real-docker-integration"
ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
SERVER_JAR="${ROOT_DIR}/server/build/libs/aisandbox-server.jar"
CTL_JAR="${ROOT_DIR}/server/build/libs/aisandboxctl.jar"
ARTIFACT_DIR="${ROOT_DIR}/server/build/real-docker-integration-test"
SERVER_PORT="${AI_SANDBOX_SERVER_PORT:-12410}"
COMPOSE_PROJECT="ai-sandbox-99"
SESSION_N=99

mkdir -p "$ARTIFACT_DIR"

# Scratch tree used for the entire run. Lives under $RUNNER_TEMP on
# GitHub Actions, $TMPDIR or /tmp otherwise. The trailing PID keeps
# concurrent local runs from colliding.
SCRATCH="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ai-sandbox-real-docker-it-$$"
SERVER_PID=""

log()  { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
fail() { printf '[%s] FAIL: %s\n' "$SCRIPT_NAME" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------
# 1. Pre-flight checks.
# ---------------------------------------------------------------------
preflight() {
    log "preflight: checking host capabilities"

    # Linux is the only supported runner for this step. macOS Docker
    # Desktop usually lacks passwordless sudo, and `useradd` does not
    # exist; the analyst's risk #13 says fail fast on this case.
    if [ "$(uname -s)" != "Linux" ]; then
        fail "this script requires Linux (got $(uname -s)). pki init shells out to useradd."
    fi

    command -v docker >/dev/null   || fail "docker not on PATH"
    command -v java >/dev/null     || fail "java not on PATH"
    command -v curl >/dev/null     || fail "curl not on PATH"
    command -v openssl >/dev/null  || fail "openssl not on PATH"
    command -v jq >/dev/null       || fail "jq not on PATH"

    docker version  >/dev/null 2>&1 || fail "docker daemon not reachable (\`docker version\` failed)"
    docker info     >/dev/null 2>&1 || fail "docker daemon not reachable (\`docker info\` failed)"
    docker compose version >/dev/null 2>&1 \
        || fail "docker compose plugin not available (\`docker compose version\` failed)"

    test -f "$CTL_JAR"    || fail "aisandboxctl.jar missing at $CTL_JAR — run \`./gradlew :server:aisandboxctlJar\` first"
    test -f "$SERVER_JAR" || fail "aisandbox-server.jar missing at $SERVER_JAR — run \`./gradlew :server:bootJar\` first"

    java -jar "$CTL_JAR" --version >/dev/null 2>&1 || {
        # `aisandboxctl` does not implement --version today; fall back
        # to `--help`. Both probes go through the same picocli surface
        # so either proves the jar is invokable.
        java -jar "$CTL_JAR" --help >/dev/null 2>&1 \
            || fail "aisandboxctl.jar refuses to launch (neither --version nor --help worked)"
    }

    # Passwordless sudo is required for `pki init` (useradd, chown).
    # The rest of the script (server boot, curl battery, compose) runs
    # as the unprivileged caller — only the pki init step uses sudo.
    if [ "$(id -u)" -ne 0 ]; then
        sudo -n true 2>/dev/null \
            || fail "passwordless sudo required for pki init step (sudo -n true failed)"
    fi

    log "preflight: ok (docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown), java $(java -version 2>&1 | head -1))"
}

# ---------------------------------------------------------------------
# 2. PKI bootstrap.
# ---------------------------------------------------------------------
bootstrap_pki() {
    log "bootstrap_pki: creating scratch tree at $SCRATCH"
    mkdir -p "$SCRATCH" "$SCRATCH/hostscripts" "$SCRATCH/compose"
    # pki init creates: pki/, clients/, enrollment/, secrets/,
    # sessions/, audit/, config.yaml — under $SCRATCH because we pass
    # --pki-dir $SCRATCH/pki, etc., and PkiInitCommand derives etcRoot
    # from pkiDir.parent.

    local aisbx_user
    aisbx_user="$(id -un)"
    # PkiInitCommand requires root; `sudo` for this step only. The
    # --user flag chowns the resulting tree to the caller so the
    # background-server process (which runs unprivileged) can read
    # server.key (mode 0600) and write the audit log.
    local sudo_cmd=""
    if [ "$(id -u)" -ne 0 ]; then
        sudo_cmd="sudo --preserve-env=JAVA_HOME -n"
    fi

    log "bootstrap_pki: running aisandboxctl pki init (user=$aisbx_user)"
    $sudo_cmd java -jar "$CTL_JAR" pki init \
        --pki-dir        "$SCRATCH/pki" \
        --clients-dir    "$SCRATCH/clients" \
        --enrollment-dir "$SCRATCH/enrollment" \
        --secrets-dir    "$SCRATCH/secrets" \
        --sessions-dir   "$SCRATCH/sessions" \
        --log-dir        "$SCRATCH/audit" \
        --config         "$SCRATCH/config.yaml" \
        --cn             "realdocker-it" \
        --san            DNS:localhost,IP:127.0.0.1 \
        --user           "$aisbx_user" \
        --force \
        >"$ARTIFACT_DIR/pki-init.stdout.log" 2>"$ARTIFACT_DIR/pki-init.stderr.log" \
        || {
            log "pki init FAILED — stdout/stderr below:"
            tail -120 "$ARTIFACT_DIR/pki-init.stdout.log" >&2 || true
            tail -120 "$ARTIFACT_DIR/pki-init.stderr.log" >&2 || true
            fail "aisandboxctl pki init returned non-zero"
        }

    test -f "$SCRATCH/pki/server.crt" || fail "pki init did not produce server.crt at $SCRATCH/pki/server.crt"
    test -f "$SCRATCH/pki/server.key" || fail "pki init did not produce server.key at $SCRATCH/pki/server.key"
    log "bootstrap_pki: cert + key materialised under $SCRATCH/pki"
}

mint_client_cert() {
    log "mint_client_cert: minting realdocker-it-client (--pem)"
    # client mint does NOT require root — only pki init does.
    java -jar "$CTL_JAR" client mint realdocker-it-client \
        --pem \
        --clients-dir "$SCRATCH/clients" \
        --pki-dir     "$SCRATCH/pki" \
        --out         "$SCRATCH/clients" \
        >"$ARTIFACT_DIR/client-mint.stdout.log" 2>"$ARTIFACT_DIR/client-mint.stderr.log" \
        || {
            log "client mint FAILED — stdout/stderr below:"
            tail -80 "$ARTIFACT_DIR/client-mint.stdout.log" >&2 || true
            tail -80 "$ARTIFACT_DIR/client-mint.stderr.log" >&2 || true
            fail "aisandboxctl client mint returned non-zero"
        }
    test -f "$SCRATCH/clients/realdocker-it-client.crt" \
        || fail "mint did not produce realdocker-it-client.crt under $SCRATCH/clients"
    test -f "$SCRATCH/clients/realdocker-it-client.key" \
        || fail "mint did not produce realdocker-it-client.key under $SCRATCH/clients"
    # The mint also drops a copy of server.crt next to the bundle so
    # curl can pin it; clients_dir == out so it lands in the same dir.
    test -f "$SCRATCH/clients/server.crt" \
        || fail "mint did not copy server.crt to $SCRATCH/clients (server cert pin missing)"
}

# ---------------------------------------------------------------------
# 3. Boot server.
# ---------------------------------------------------------------------
start_server() {
    log "start_server: binding to 127.0.0.1:$SERVER_PORT"
    # All paths overridden via Spring CLI args so the run is
    # hermetic — no /etc/ai-sandbox-server/ writes, no /var/log/
    # writes. Bind to loopback only.
    nohup java \
        -Dfile.encoding=UTF-8 \
        -jar "$SERVER_JAR" \
        --ai-sandbox.server.tls.port="$SERVER_PORT" \
        --ai-sandbox.server.tls.bind-address=127.0.0.1 \
        --ai-sandbox.server.pki.dir="$SCRATCH/pki" \
        --ai-sandbox.server.clients.dir="$SCRATCH/clients" \
        --ai-sandbox.server.hostscripts.repo-root="$SCRATCH/hostscripts" \
        --ai-sandbox.server.sessions.host-state-root="$SCRATCH/sessions" \
        --ai-sandbox.server.secrets.dir="$SCRATCH/secrets" \
        --ai-sandbox.server.enrollment.dir="$SCRATCH/enrollment" \
        --ai-sandbox.server.audit.file="$SCRATCH/audit/audit.log" \
        >"$ARTIFACT_DIR/server.stdout.log" \
        2>"$ARTIFACT_DIR/server.stderr.log" &
    SERVER_PID=$!
    echo "$SERVER_PID" > "$SCRATCH/server.pid"
    log "start_server: pid=$SERVER_PID"

    # Wait up to 60s for "Started ServerApplication" in stderr (Spring
    # Boot logs to stderr by default), OR a TCP connect to succeed on
    # the configured port. Either signal is enough; both are belt-and-
    # braces.
    local i
    for i in $(seq 1 60); do
        if grep -q "Started ServerApplication" "$ARTIFACT_DIR/server.stderr.log" 2>/dev/null; then
            log "start_server: Spring boot completed (\"Started ServerApplication\" seen) after ${i}s"
            return 0
        fi
        if (exec 3<>/dev/tcp/127.0.0.1/"$SERVER_PORT") 2>/dev/null; then
            exec 3<&- 3>&- || true
            log "start_server: tcp 127.0.0.1:$SERVER_PORT accepted after ${i}s"
            return 0
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            log "start_server: server pid $SERVER_PID exited before listener came up"
            log "── server stderr (tail 200) ──"
            tail -200 "$ARTIFACT_DIR/server.stderr.log" >&2 || true
            log "── server stdout (tail 80) ──"
            tail -80  "$ARTIFACT_DIR/server.stdout.log" >&2 || true
            fail "server JVM exited before port $SERVER_PORT opened"
        fi
        sleep 1
    done
    log "start_server: TIMEOUT waiting 60s for server to come up"
    log "── server stderr (tail 200) ──"
    tail -200 "$ARTIFACT_DIR/server.stderr.log" >&2 || true
    fail "server did not open port $SERVER_PORT within 60s"
}

# ---------------------------------------------------------------------
# 4. Real docker-compose project for /v1/sessions enumeration.
# ---------------------------------------------------------------------
provision_real_session() {
    log "provision_real_session: starting compose project $COMPOSE_PROJECT"
    cat > "$SCRATCH/compose/docker-compose.yml" <<'EOF'
# Minimal compose project so SessionFacade.listSessions() sees a real
# entry under the ai-sandbox-N project-name pattern. The image is
# alpine:3 + sleep 600 to avoid building the heavy SandboxDockerfile;
# `docker compose ls --all` still reports it as a recognised compose
# project keyed by the `com.docker.compose.project` label.
services:
  claude-sandbox:
    image: alpine:3
    command: sleep 600
    labels:
      com.ai-sandbox.test: realdocker-it
EOF
    # Pull image first so `up -d` doesn't race with the registry.
    docker pull alpine:3 >>"$ARTIFACT_DIR/docker.log" 2>&1 \
        || fail "docker pull alpine:3 failed (check connectivity)"
    docker compose -p "$COMPOSE_PROJECT" \
        -f "$SCRATCH/compose/docker-compose.yml" \
        up -d >>"$ARTIFACT_DIR/docker.log" 2>&1 \
        || {
            log "── docker.log tail ──"
            tail -60 "$ARTIFACT_DIR/docker.log" >&2 || true
            fail "docker compose up -d failed for project $COMPOSE_PROJECT"
        }
    # Sanity-check: the project should now appear in `docker compose ls`.
    docker compose ls --all --format json >"$ARTIFACT_DIR/compose-ls.json" 2>>"$ARTIFACT_DIR/docker.log"
    if ! grep -q "\"Name\":\"$COMPOSE_PROJECT\"" "$ARTIFACT_DIR/compose-ls.json"; then
        log "── compose-ls.json ──"
        cat "$ARTIFACT_DIR/compose-ls.json" >&2 || true
        fail "$COMPOSE_PROJECT did not appear in docker compose ls --all output"
    fi
    log "provision_real_session: $COMPOSE_PROJECT visible to docker compose"
}

# ---------------------------------------------------------------------
# 5. Real mTLS curl battery.
# ---------------------------------------------------------------------
curl_mtls() {
    # Wrapper for the mTLS curl shape used everywhere in this script.
    curl --silent --show-error \
        --cacert "$SCRATCH/clients/server.crt" \
        --cert   "$SCRATCH/clients/realdocker-it-client.crt" \
        --key    "$SCRATCH/clients/realdocker-it-client.key" \
        "$@"
}

assert_endpoints() {
    log "assert_endpoints: GET /v1/healthz"
    local healthz_body healthz_code
    healthz_code="$(curl_mtls -o "$ARTIFACT_DIR/healthz.body" -w '%{http_code}' \
        "https://127.0.0.1:${SERVER_PORT}/v1/healthz" || echo 000)"
    if [ "$healthz_code" != "200" ]; then
        log "── healthz response body ──"; cat "$ARTIFACT_DIR/healthz.body" >&2 || true
        fail "/v1/healthz returned $healthz_code (expected 200)"
    fi
    healthz_body="$(cat "$ARTIFACT_DIR/healthz.body")"
    log "assert_endpoints: /v1/healthz -> 200 body=$(printf '%s' "$healthz_body" | head -c 200)"

    log "assert_endpoints: GET /v1/sessions"
    local sessions_code
    sessions_code="$(curl_mtls -o "$ARTIFACT_DIR/sessions.body" -w '%{http_code}' \
        "https://127.0.0.1:${SERVER_PORT}/v1/sessions" || echo 000)"
    if [ "$sessions_code" != "200" ]; then
        log "── /v1/sessions response body ──"; cat "$ARTIFACT_DIR/sessions.body" >&2 || true
        fail "/v1/sessions returned $sessions_code (expected 200)"
    fi
    # Body must be a JSON array AND must contain n=99 somewhere.
    jq -e 'type=="array"' < "$ARTIFACT_DIR/sessions.body" >/dev/null \
        || { cat "$ARTIFACT_DIR/sessions.body" >&2; fail "/v1/sessions body is not a JSON array"; }
    jq -e --argjson n "$SESSION_N" 'map(.n) | index($n) != null' \
        < "$ARTIFACT_DIR/sessions.body" >/dev/null \
        || { cat "$ARTIFACT_DIR/sessions.body" >&2; fail "/v1/sessions did not include n=$SESSION_N (compose project $COMPOSE_PROJECT)"; }
    log "assert_endpoints: /v1/sessions -> 200, contains n=$SESSION_N"

    log "assert_endpoints: GET /v1/sessions/$SESSION_N"
    local detail_code
    detail_code="$(curl_mtls -o "$ARTIFACT_DIR/session-detail.body" -w '%{http_code}' \
        "https://127.0.0.1:${SERVER_PORT}/v1/sessions/${SESSION_N}" || echo 000)"
    if [ "$detail_code" != "200" ]; then
        log "── /v1/sessions/$SESSION_N response body ──"; cat "$ARTIFACT_DIR/session-detail.body" >&2 || true
        fail "/v1/sessions/$SESSION_N returned $detail_code (expected 200)"
    fi
    jq -e --argjson n "$SESSION_N" '.n == $n' \
        < "$ARTIFACT_DIR/session-detail.body" >/dev/null \
        || { cat "$ARTIFACT_DIR/session-detail.body" >&2; fail "/v1/sessions/$SESSION_N body missing \"n\":$SESSION_N"; }
    log "assert_endpoints: /v1/sessions/$SESSION_N -> 200, n=$SESSION_N confirmed"
}

# ---------------------------------------------------------------------
# 6. Bug-catching log assertions (the load-bearing checks).
# ---------------------------------------------------------------------
assert_no_postcommit_uoe() {
    log "assert_no_postcommit_uoe: scanning server stdout+stderr for forbidden patterns"

    # Concatenated stream for the three checks. We grep with the
    # original files separately so the failure message can name the
    # source file. The hits-must-be-zero contract uses `if grep -q ...`
    # rather than `! grep -q ...` so failure mode reports the actual
    # matched lines, not just exit 1.
    local stderr="$ARTIFACT_DIR/server.stderr.log"
    local stdout="$ARTIFACT_DIR/server.stdout.log"

    local forbidden_patterns=(
        "UnsupportedOperationException"
        "ServerHttpResponse already committed"
        "Unmapped exception in REST flow"
    )

    local pat fail_count=0
    for pat in "${forbidden_patterns[@]}"; do
        local hits_stderr hits_stdout
        hits_stderr="$(grep -nF "$pat" "$stderr" 2>/dev/null || true)"
        hits_stdout="$(grep -nF "$pat" "$stdout" 2>/dev/null || true)"
        if [ -n "$hits_stderr" ] || [ -n "$hits_stdout" ]; then
            fail_count=$((fail_count + 1))
            log "REGRESSION: server log contains forbidden pattern: $pat"
            if [ -n "$hits_stderr" ]; then
                log "── matches in server.stderr.log ──"
                printf '%s\n' "$hits_stderr" >&2
            fi
            if [ -n "$hits_stdout" ]; then
                log "── matches in server.stdout.log ──"
                printf '%s\n' "$hits_stdout" >&2
            fi
        fi
    done

    if [ "$fail_count" -gt 0 ]; then
        fail "$fail_count forbidden pattern(s) found in server log — UC-17 regression"
    fi
    log "assert_no_postcommit_uoe: all 3 forbidden patterns absent (clean log)"
}

# ---------------------------------------------------------------------
# 7. Teardown.
# ---------------------------------------------------------------------
teardown() {
    local rc=$?
    log "teardown: cleaning up (script exit code so far: $rc)"

    # Kill the server first so it stops touching the compose project.
    if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        log "teardown: SIGTERM server pid $SERVER_PID"
        kill -TERM "$SERVER_PID" 2>/dev/null || true
        local i
        for i in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$SERVER_PID" 2>/dev/null; then
            log "teardown: server did not exit within 10s, SIGKILL"
            kill -KILL "$SERVER_PID" 2>/dev/null || true
        fi
    fi

    # Drop the compose project if we created one.
    if [ -f "$SCRATCH/compose/docker-compose.yml" ]; then
        log "teardown: docker compose down -p $COMPOSE_PROJECT"
        docker compose -p "$COMPOSE_PROJECT" \
            -f "$SCRATCH/compose/docker-compose.yml" \
            down --volumes --remove-orphans \
            >>"$ARTIFACT_DIR/docker.log" 2>&1 || true
    fi

    # Scratch tree may have root-owned subtrees (pki init chown'd them
    # back to $(id -un), but the parent etcRoot itself may have been
    # adjusted). Try plain rm first, then escalate to sudo if non-root.
    if [ -d "$SCRATCH" ]; then
        log "teardown: rm -rf $SCRATCH"
        rm -rf "$SCRATCH" 2>/dev/null || {
            if [ "$(id -u)" -ne 0 ] && sudo -n true 2>/dev/null; then
                sudo rm -rf "$SCRATCH" || true
            fi
        }
    fi

    if [ "$rc" -ne 0 ]; then
        log "teardown: script exiting with rc=$rc — artefacts retained under $ARTIFACT_DIR"
    fi
    exit "$rc"
}
trap teardown EXIT

# ---------------------------------------------------------------------
# Main.
# ---------------------------------------------------------------------
preflight
bootstrap_pki
mint_client_cert
start_server
provision_real_session
assert_endpoints
assert_no_postcommit_uoe

log "ALL CHECKS PASSED — no post-commit UOE regression detected"
