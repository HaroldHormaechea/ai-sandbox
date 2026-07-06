#!/usr/bin/env bash
# UC-97 (AC10) — drift-guard LIVE leg driver, invoked by android/gate.sh step 6
# when GATE_LIVE_CLAUDE=1. Kept SEPARATE from gate.sh so the deterministic,
# LLM-free gate (gate.sh steps 1-5) is never coupled to the live-Claude path.
#
# What it proves: a REAL, pinned Claude Code raises a live single- and multi-
# question AskUserQuestion, and the pending question is
#   (1) DETECTED at the pane layer by the in-container streaming helper
#       (`__ctrl__<TAB>pending-question`), and
#   (2) RENDERED in-view on-device (the GATE_LIVE_TEST_PACKAGE instrumented suite).
# If a future Claude Code version drifts the TUI shape out from under the scraper,
# detection (1) goes RED here BEFORE release — the guard the use case asks for.
#
# Detection asserts the PANE signal, NOT `--scan-pending`: the latter reads the
# transcript tail and is blind to a live blocking AskUserQuestion (buffered in
# memory, never written to the transcript while Claude blocks — UC-49/UC-50).
#
# ── Real-server vs. replay (why this leg has its own topology) ────────────────────
# gate.sh steps 1-5 stand up the server under the `replay` Spring profile (synthetic
# fixture sessions; the docker tail is replaced by fixtures) and enroll the emulator to
# THAT server. A render assertion against a replay server is HOLLOW — it does not tail
# the real docker session where the live AskUserQuestion is raised, so the app can never
# observe a genuinely live question. Therefore the AC10-valid on-device RENDER leg needs
# its OWN topology: a REAL dev-mode server (no replay profile → it enumerates + tails real
# docker sessions) + a real ai-sandbox-<N> + the emulator enrolled to THAT server. One
# emulator can only be enrolled to one server at a time, so live-render and replay-render
# are MUTUALLY EXCLUSIVE — this is a distinct, opt-in path (GATE_LIVE_REAL_SERVER=1), fully
# self-contained here and torn down on exit; gate.sh's deterministic replay flow is never
# touched. (QA-validated: Path-A live-render E2E.)
#
# Env contract (exported by gate.sh):
#   GATE_DIR, REPO, GATE_CLAUDE_VERSION           (required)
#   JAVA_HOME                                      (required in real-server mode)
#   DEV, ADB, ANDROID_HOME, GATE_LIVE_TEST_PACKAGE (for the on-device render leg)
# Optional knobs:
#   GATE_LIVE_REAL_SERVER    set 1 to stand up the REAL-server topology (real dev-mode
#                       server + real ai-sandbox-<N> + emulator enroll) so the render leg
#                       observes a LIVE question (AC10). Default 0. When 0, the emulator
#                       stays on whatever it is enrolled to (gate.sh's replay server), which
#                       does NOT tail the real session, so the render leg is SKIPPED (hollow)
#                       and only pane DETECTION runs. The release gate sets this to 1.
#   GATE_LIVE_CLAUDE_CONFIG  (real-server mode) REQUIRED — a seeded claude-config host dir
#                       (.credentials.json + .claude.json hasCompletedOnboarding:true). The
#                       harness VERIFIES + world-reads it; it NEVER fabricates credentials.
#   GATE_LIVE_N         (real-server mode) session number to spawn (default 1 → ai-sandbox-1).
#   GATE_LIVE_PORT      (real-server mode) server TLS port (default 18443).
#   GATE_LIVE_E2E_DIR   (real-server mode) work dir for pki/config/enrollment (default $GATE_DIR/live-e2e).
#   GATE_LIVE_WORKSPACE / GATE_LIVE_PROJECTS  (real-server mode) session bind-mount host dirs.
#   GATE_LIVE_KEEP      (real-server mode) set 1 to leave the server + session up on exit (debug).
#   GATE_LIVE_SESSION   (default mode) compose project of a RUNNING sandbox (default: first
#                       running ai-sandbox-* discovered via `docker compose ls`).
#   GATE_LIVE_PANE      tmux pane/target for the main Claude session (default: main).
#   GATE_LIVE_TIMEOUT   seconds to wait for the pane pending-question signal (default 90).
#   GATE_LIVE_SKIP_ONDEVICE  set 1 to assert pane detection only (skip the render leg).
#   GATE_LIVE_REQUIRED  set 1 to hard-fail (not skip) on environmental unavailability.
#
# ⚠️ LIVE-VALIDATED BY QA (task #3): the @qa-tune prompts/pane/timings and the real-server
#    topology (server launch/profile, session spawn, enroll route) are confirmed against the
#    real emulator+server+Claude on 2.1.169. The orchestration + pass/fail semantics are fixed.
set -euo pipefail

: "${GATE_DIR:?gate-live-claude.sh must be invoked by gate.sh (GATE_DIR unset)}"
: "${REPO:?REPO unset}"
: "${GATE_CLAUDE_VERSION:?GATE_CLAUDE_VERSION unset}"
PANE="${GATE_LIVE_PANE:-main}"
TIMEOUT="${GATE_LIVE_TIMEOUT:-90}"
HELPER="aisandbox-conversation-tail"

log() { printf '\n\033[1;35m[live-guard] %s\033[0m\n' "$*"; }
# Hard failure — used for ACTUAL drift/guard violations (version mismatch, missing
# pane signal, render failure). Always red.
fail() { echo "::error:: UC-97 live drift-guard: $*"; exit 1; }
# Environmental unavailability (no running sandbox, version unreadable) is a SKIP by
# default, but the release gate sets GATE_LIVE_REQUIRED=1 to make it a hard failure
# (the user's "fully functionally validated" constraint — see gate.sh step 6).
skip_or_fail() {
  [ "${GATE_LIVE_REQUIRED:-0}" = "1" ] && fail "$*"
  log "SKIPPED (live env unavailable): $* — set GATE_LIVE_REQUIRED=1 to hard-fail instead."
  exit 0
}

# ── X11 runtime precheck (emulator dependency) ───────────────────────────────────
# The headless emulator's QEMU binary dynamically links libX11 even with -no-window;
# on an unprovisioned host it crashes silently at boot (QA hit exactly this; CI's
# /dev/kvm smoke does NOT catch it). Verify the libs resolve and fail LOUD here —
# SKIP under GATE_LIVE_REQUIRED=0, hard-fail with a clear message under =1.
x11_ok=1
for lib in libX11.so.6 libX11-xcb.so.1; do
  if command -v ldconfig >/dev/null 2>&1; then
    ldconfig -p 2>/dev/null | grep -q "$lib" || x11_ok=0
  elif ! find /usr/lib /lib -name "$lib" 2>/dev/null | grep -q .; then
    x11_ok=0
  fi
done
[ "$x11_ok" = 1 ] || skip_or_fail "X11 runtime libs (libX11.so.6 / libX11-xcb.so.1) not resolvable — the emulator's QEMU crashes at boot without them. Install them (e.g. apt-get install -y libx11-6 libx11-xcb1) to run the live leg."

# ── real-server topology (GATE_LIVE_REAL_SERVER) — QA Path-A recipe ───────────────
# Self-contained: real dev-mode server (no replay) + real ai-sandbox-<N> + emulator
# enroll. Torn down on exit. gate.sh's replay flow is never touched.
REAL_SERVER_PID=""
SESSION_SPAWNED=0   # only tear down the session if WE spawned it (never a pre-existing one)
teardown_real_server() {
  [ -n "$REAL_SERVER_PID" ] && kill "$REAL_SERVER_PID" >/dev/null 2>&1 || true
  if [ "${SESSION_SPAWNED:-0}" = "1" ] && [ "${GATE_LIVE_KEEP:-0}" != "1" ]; then
    docker compose -p "ai-sandbox-${GATE_LIVE_N:-1}" down >/dev/null 2>&1 || true
  fi
}

resolve_jars() {
  SERVER_JAR="${GATE_SERVER_JAR:-$(ls "$REPO"/server/build/libs/aisandbox-server*.jar 2>/dev/null | head -1)}"
  CTL_JAR="${GATE_CTL_JAR:-$(ls "$REPO"/server/build/libs/aisandboxctl*.jar 2>/dev/null | head -1)}"
  [ -f "${SERVER_JAR:-/nonexistent}" ] || skip_or_fail "real-server mode: aisandbox-server jar not found under $REPO/server/build/libs (run :server:bootJar), or set GATE_SERVER_JAR."
  [ -f "${CTL_JAR:-/nonexistent}" ] || skip_or_fail "real-server mode: aisandboxctl jar not found under $REPO/server/build/libs (run :server:aisandboxctlJar), or set GATE_CTL_JAR."
}

start_real_server() {
  command -v docker >/dev/null 2>&1 || skip_or_fail "real-server mode needs docker on PATH (the server's DockerTailSource execs 'docker compose … exec')."
  mkdir -p "$E2E"/{pki,clients,enrollment,secrets,sessions,log}
  # Server cert — SAN MUST include the emulator's host alias 10.0.2.2 (recipe).
  if [ ! -s "$E2E/pki/server.crt" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
      -keyout "$E2E/pki/server.key" -out "$E2E/pki/server.crt" \
      -subj "/CN=ai-sandbox-server" \
      -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" \
      -addext "basicConstraints=critical,CA:FALSE" \
      -addext "keyUsage=critical,digitalSignature,keyEncipherment"
    chmod 644 "$E2E/pki/server.crt"; chmod 600 "$E2E/pki/server.key"
  fi
  cat > "$E2E/config.yaml" <<YAML
ai-sandbox:
  server:
    tls: { port: $LIVE_PORT, bind-address: "0.0.0.0" }
    pki:        { dir: $E2E/pki }
    clients:    { dir: $E2E/clients }
    enrollment: { dir: $E2E/enrollment, default-ttl-minutes: 10, rate-limit-per-window: 20, rate-limit-window-seconds: 60 }
    hostscripts: { repo-root: $REPO }
    sessions:   { host-state-root: $E2E/sessions }
    secrets:    { dir: $E2E/secrets }
    audit:      { file: $E2E/log/audit.log, retention-days: 7 }
YAML
  log "real-server: launching dev-mode server (NO replay profile) on :$LIVE_PORT"
  # NO --spring.profiles.active=replay → the server enumerates + tails REAL docker
  # sessions. DOCKER_HOST + docker on PATH so its DockerTailSource can exec into the
  # ai-sandbox-<N> container (recipe step 1).
  DOCKER_HOST="${DOCKER_HOST:-unix:///var/run/docker.sock}" \
  "$JAVA_HOME/bin/java" -Xms256m -Xmx1g -Dfile.encoding=UTF-8 \
    -Dai-sandbox.server.audit.file="$E2E/log/audit.log" \
    -jar "$SERVER_JAR" \
    --spring.config.additional-location=file:"$E2E/config.yaml" \
    > "$E2E/log/server.log" 2>&1 &
  REAL_SERVER_PID=$!
  local i
  for i in $(seq 1 90); do
    grep -q "Netty started on port $LIVE_PORT" "$E2E/log/server.log" 2>/dev/null && break
    kill -0 "$REAL_SERVER_PID" 2>/dev/null || { tail -40 "$E2E/log/server.log" || true; fail "real-server exited early — see $E2E/log/server.log"; }
    sleep 1
  done
  grep -q "Netty started on port $LIVE_PORT" "$E2E/log/server.log" || { tail -40 "$E2E/log/server.log" || true; fail "real-server did not start on :$LIVE_PORT — see $E2E/log/server.log"; }
  log "real-server up on :$LIVE_PORT (tails real docker sessions)"
}

spawn_real_session() {
  local cfg="${GATE_LIVE_CLAUDE_CONFIG:-}"
  [ -n "$cfg" ] && [ -d "$cfg" ] || skip_or_fail "real-server mode: GATE_LIVE_CLAUDE_CONFIG must be a seeded claude-config dir (.credentials.json + .claude.json hasCompletedOnboarding:true). The harness will not fabricate credentials."
  [ -f "$cfg/.credentials.json" ] || skip_or_fail "real-server mode: $cfg/.credentials.json missing — seed live Claude credentials first."
  grep -q '"hasCompletedOnboarding"[[:space:]]*:[[:space:]]*true' "$cfg/.claude.json" 2>/dev/null \
    || skip_or_fail "real-server mode: $cfg/.claude.json lacks hasCompletedOnboarding:true — seed onboarding state first."
  # World-readable so the rootless-mapped container uid can read the seeded creds (recipe).
  chmod -R o+rwX "$cfg" 2>/dev/null || true
  local ws="${GATE_LIVE_WORKSPACE:-$E2E/session-ws}" proj="${GATE_LIVE_PROJECTS:-$E2E/session-proj}"
  mkdir -p "$ws" "$proj"; chmod -R o+rwX "$ws" "$proj" 2>/dev/null || true
  log "real-server: spawning ai-sandbox-$LIVE_N (devtools OFF)"
  # AI_SANDBOX_DEVTOOLS="" is CRITICAL — the outer env leaks it and would trigger a
  # ~1.5GB in-session Android-SDK download (recipe step 2).
  AI_SANDBOX_CLAUDE_CONFIG_HOST_PATH="$cfg" \
  AI_SANDBOX_WORKSPACE_HOST_PATH="$ws" \
  AI_SANDBOX_CLAUDE_PROJECTS_HOST_PATH="$proj" \
  AI_SANDBOX_DEVTOOLS="" \
    docker compose -p "ai-sandbox-$LIVE_N" -f "$REPO/docker-compose.yml" up -d \
    || fail "real-server: 'docker compose up' for ai-sandbox-$LIVE_N failed (is ai-context:latest built with the pinned Claude?)"
  SESSION_SPAWNED=1   # we own this session now — teardown may bring it down
  # Wait for the container's readiness marker (mirrors lib.sh's readiness probe).
  local i
  for i in $(seq 1 60); do
    docker compose -p "ai-sandbox-$LIVE_N" exec -T claude-sandbox test -f /tmp/aisandbox-ready >/dev/null 2>&1 && break
    sleep 2
  done
  # Advance the one-time bypass-perms + fullscreen-renderer prompts (Down+Enter ×2), else
  # the driven AskUserQuestion lands in a wizard (recipe step 2 — the seeded
  # hasCompletedOnboarding is necessary-but-not-sufficient).
  sleep 3
  docker compose -p "ai-sandbox-$LIVE_N" exec -T claude-sandbox tmux send-keys -t main Down Enter 2>/dev/null || true
  sleep 1
  docker compose -p "ai-sandbox-$LIVE_N" exec -T claude-sandbox tmux send-keys -t main Down Enter 2>/dev/null || true
  sleep 2
  log "real-server: ai-sandbox-$LIVE_N up + onboarding/bypass advanced"
}

enroll_emulator_to_real() {
  : "${DEV:?real-server mode needs DEV (emulator) for enrollment}"
  : "${ADB:?ADB unset}"
  local enroll_class="${GATE_ENROLL_CLASS:-com.aisandbox.android.net.E2eQrFileEnrollmentTest}"
  log "real-server: enrolling emulator to https://10.0.2.2:$LIVE_PORT"
  # Capture STDOUT ONLY — a chown warning pollutes stderr (recipe step 3).
  local payload
  payload="$("$JAVA_HOME/bin/java" -jar "$CTL_JAR" client invite gate-live \
    --pki-dir "$E2E/pki" --enrollment-dir "$E2E/enrollment" \
    --server-url "https://10.0.2.2:$LIVE_PORT" --json 2>/dev/null)"
  [ -n "$payload" ] || fail "real-server: ctl invite produced no enrollment payload"
  printf '%s' "$payload" > "$E2E/invite.json"
  local zxing
  zxing="$(find "$HOME/.gradle/caches" -path '*zxing*' -name 'core-*.jar' 2>/dev/null | head -1)"
  [ -n "$zxing" ] || fail "real-server: ZXing core jar not found in the gradle cache (build the app first)"
  cat > "$E2E/QrGen.java" <<'JAVA'
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
public class QrGen {
  public static void main(String[] a) throws Exception {
    String payload = new String(Files.readAllBytes(new File(a[0]).toPath())).trim();
    BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 600, 600);
    BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++)
      img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
    ImageIO.write(img, "png", new File(a[1]));
  }
}
JAVA
  ( cd "$E2E" && "$JAVA_HOME/bin/javac" -cp "$zxing" QrGen.java \
      && "$JAVA_HOME/bin/java" -cp ".:$zxing" QrGen "$E2E/invite.json" "$E2E/invite-qr.png" ) \
    || fail "real-server: QR generation failed"
  "$ADB" -s "$DEV" push "$E2E/invite-qr.png" /sdcard/Download/invite-qr.png
  local out="$E2E/log/enroll.out"
  "$ADB" -s "$DEV" shell "am instrument -w \
    -e class $enroll_class \
    -e qrImagePath /sdcard/Download/invite-qr.png \
    com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out"
  grep -q 'FAILURES!!!' "$out" && fail "real-server: emulator enrollment failed — see $out"
  grep -qE 'OK \([1-9][0-9]* test' "$out" || fail "real-server: enrollment ran zero tests — see $out"
  log "real-server: emulator enrolled to the real server ✅"
}

# ── resolve the sandbox session to drive ──────────────────────────────────────────
if [ "${GATE_LIVE_REAL_SERVER:-0}" = "1" ]; then
  REAL_SERVER=1
  : "${JAVA_HOME:?real-server mode needs JAVA_HOME (JDK 21)}"
  LIVE_PORT="${GATE_LIVE_PORT:-18443}"
  LIVE_N="${GATE_LIVE_N:-1}"
  E2E="${GATE_LIVE_E2E_DIR:-$GATE_DIR/live-e2e}"
  trap teardown_real_server EXIT
  resolve_jars
  start_real_server
  spawn_real_session
  enroll_emulator_to_real
  SESSION="ai-sandbox-$LIVE_N"
else
  REAL_SERVER=0
  # Attach to an already-running session for pane detection; the on-device RENDER leg is
  # skipped below (the emulator is not enrolled to a real docker-tailing server).
  SESSION="${GATE_LIVE_SESSION:-}"
  if [ -z "$SESSION" ]; then
    SESSION="$(docker compose ls --format json 2>/dev/null \
      | grep -oE '"Name":"ai-sandbox-[^"]+"' | head -1 | cut -d'"' -f4 || true)"
  fi
  [ -n "$SESSION" ] || skip_or_fail "no running ai-sandbox-* session found (spawn one, set GATE_LIVE_SESSION, or use GATE_LIVE_REAL_SERVER=1). The live leg needs a live pinned Claude to drive."
fi
log "using sandbox session: $SESSION"

dexec() { docker compose -p "$SESSION" exec -T claude-sandbox "$@"; }

# ── version gate: the live Claude MUST be the pinned version ──────────────────────
# A mismatch here means the deployed image is NOT on the pin — the exact drift the
# use case is about — so it is a hard failure, not a skip.
LIVE_VER="$(dexec claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
[ -n "$LIVE_VER" ] || skip_or_fail "could not read the live Claude Code version in $SESSION (session not ready / claude not on PATH)"
[ "$LIVE_VER" = "$GATE_CLAUDE_VERSION" ] \
  || fail "live Claude Code is $LIVE_VER but the pin is $GATE_CLAUDE_VERSION — rebuild the image (docker compose build) so the pin reaches the session."
log "live Claude Code version matches the pin ($LIVE_VER) ✅"

# ── onboarding gate: the session must be seeded PAST Claude Code onboarding ────────
# A fresh session shows onboarding (theme → trust-folder → bypass-perms warning) which
# EATS the first prompt, so the driven AskUserQuestion is never raised (QA-confirmed).
# Require the session to be seeded (~/.claude/.claude.json with hasCompletedOnboarding +
# creds) BEFORE driving. We VERIFY (never fabricate creds) and skip/fail with a clear
# pointer if unseeded, rather than silently sending into an onboarding dialog.
if ! dexec sh -c 'cat "$HOME/.claude/.claude.json" 2>/dev/null' \
     | grep -q '"hasCompletedOnboarding"[[:space:]]*:[[:space:]]*true'; then
  skip_or_fail "live session $SESSION is not past Claude Code onboarding (~/.claude/.claude.json lacks hasCompletedOnboarding:true) — onboarding would eat the driven prompt. Seed the session's creds + onboarding state first (the entrypoint's pre-init template, or stage ~/.claude)."
fi
log "live session is past onboarding ✅"

# ── on-device render prerequisites (validated up-front) ───────────────────────────
# The on-device leg (QA's com.aisandbox.android.gate.live suite) asserts the sheet
# actually RENDERS in-app — the core of what the use case wants validated. It MUST run
# while a question is still PENDING (see the per-shape loop below), and reads two
# instrumentation args from the suite's header contract: `-e liveSessionN <N>` (the
# ai-sandbox-<N> under test) and `-e liveQuestionKind <single|multi>`; without them the
# suite Assume-SKIPs (a no-op green). Set GATE_LIVE_SKIP_ONDEVICE=1 to assert pane
# detection only.
ONDEVICE=1
if [ "${GATE_LIVE_SKIP_ONDEVICE:-0}" = "1" ]; then
  ONDEVICE=0
  log "on-device render assertion: DISABLED (GATE_LIVE_SKIP_ONDEVICE=1) — pane detection only"
elif [ "$REAL_SERVER" != "1" ]; then
  # Without the real-server topology the emulator is enrolled to gate.sh's REPLAY server,
  # which does NOT tail the real session — a render assertion there is hollow. Skip it and
  # run pane detection only; set GATE_LIVE_REAL_SERVER=1 for the AC10-valid live render.
  ONDEVICE=0
  log "on-device render assertion: SKIPPED — emulator not enrolled to a REAL docker-tailing server (set GATE_LIVE_REAL_SERVER=1 for live render). Pane detection only."
else
  : "${DEV:?DEV unset (needed for the on-device render leg)}"
  : "${ADB:?ADB unset}"
  : "${GATE_LIVE_TEST_PACKAGE:?GATE_LIVE_TEST_PACKAGE unset}"
  # LIVE_N was set to GATE_LIVE_N when the real session was spawned; validate numeric.
  case "$LIVE_N" in
    '' | *[!0-9]*)
      fail "real-server mode: LIVE_N='$LIVE_N' is not numeric (GATE_LIVE_N must be the ai-sandbox-<N> number)."
      ;;
  esac
fi

# ── raise a question and assert the PANE signal fires (does NOT dismiss) ───────────
# Sends the prompt, then tails the pane via the streaming helper for up to $TIMEOUT and
# asserts `__ctrl__<TAB>pending-question` appears. Leaves the question PENDING on return
# (Claude blocks until answered/escaped) so the on-device suite has a live sheet to render.
raise_and_assert_pane() {
  local kind="$1" prompt="$2" cap="$GATE_DIR/log/live-$kind.tail"
  log "raising a live $kind AskUserQuestion"
  # Prompts are QA-live-validated on Claude Code 2.1.169 (the sheet renders ~20-24s after
  # send; the default GATE_LIVE_TIMEOUT=90 is comfortable).
  dexec tmux send-keys -t "$PANE" -l "$prompt"
  dexec tmux send-keys -t "$PANE" Enter
  # `timeout` bounds the tail; grep -q returns as soon as the control line appears. Killing
  # the read-only observer helper does NOT resolve the prompt — it stays pending.
  if dexec timeout "$TIMEOUT" "$HELPER" --pane "$PANE" 2>/dev/null \
       | tee "$cap" | grep -q 'pending-question'; then
    log "$kind: pane pending-question signal detected ✅"
  else
    dexec tmux send-keys -t "$PANE" Escape 2>/dev/null || true
    fail "$kind: pane pending-question signal NOT detected within ${TIMEOUT}s — Claude Code $LIVE_VER may have drifted the TUI shape out from under PENDING_QUESTION_CHROME. Retune the scraper for this version or fall back to the next-newest gate-passing pin. (capture: $cap)"
  fi
}

# ── run QA's on-device render suite for $kind WHILE a question is pending ──────────
run_ondevice_render() {
  local kind="$1" out="$GATE_DIR/log/live-render-$kind.out"
  log "running the on-device live render suite ($GATE_LIVE_TEST_PACKAGE, kind=$kind, sessionN=$LIVE_N)"
  # Header contract: -e liveSessionN <N> + -e liveQuestionKind <single|multi> (else the
  # suite Assume-SKIPs → a green no-op). Pass both to actually exercise the in-app render.
  "$ADB" -s "$DEV" shell "am instrument -w \
    -e package $GATE_LIVE_TEST_PACKAGE \
    -e liveSessionN $LIVE_N \
    -e liveQuestionKind $kind \
    com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out"
  if grep -q 'FAILURES!!!' "$out"; then
    fail "on-device live render suite ($kind): test failures — see $out"
  fi
  if grep -qE 'OK \([1-9][0-9]* test' "$out"; then
    log "$kind: on-device render suite passed ✅"
  else
    fail "on-device live render suite ($kind) ran zero/failed tests (a green gate with no tests is a failure — check that -e liveSessionN/-e liveQuestionKind match the suite's header). See $out"
  fi
}

# ── dismiss the currently-pending question (interrupt the turn) + settle ───────────
dismiss_pending() {
  dexec tmux send-keys -t "$PANE" Escape 2>/dev/null || true
  sleep 2
}

# ── per shape: raise → assert pane → render WHILE pending → dismiss ────────────────
# Prompt wording confirmed live on 2.1.169 (QA A0) to reliably make Claude call
# AskUserQuestion and BLOCK, without doing anything else first (which would eat the turn).
# `|`-delimited (the prompts contain no `|`); first field = kind, remainder = prompt.
for spec in \
  "single|Use the AskUserQuestion tool right now to ask me a single question: do I prefer the color red or the color blue? Provide exactly those two options. Do not do anything else first." \
  "multi|Use the AskUserQuestion tool to ask me TWO questions at once in a single call: (1) favorite color: Red or Blue; (2) favorite size: Small or Large. Ask both together now."; do
  kind="${spec%%|*}"
  prompt="${spec#*|}"
  raise_and_assert_pane "$kind" "$prompt"
  [ "$ONDEVICE" = 1 ] && run_ondevice_render "$kind" # runs while the question is still live
  dismiss_pending
done

log "PANE DETECTION (single + multi) PASSED ✅"
[ "$ONDEVICE" = 1 ] && log "ON-DEVICE RENDER (single + multi) PASSED ✅"
log "UC-97 LIVE DRIFT-GUARD LEG COMPLETE ✅"
