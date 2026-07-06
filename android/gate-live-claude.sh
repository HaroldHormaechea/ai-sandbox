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
# Env contract (exported by gate.sh):
#   GATE_DIR, REPO, GATE_CLAUDE_VERSION           (required)
#   DEV, ADB, ANDROID_HOME, GATE_LIVE_TEST_PACKAGE (for the on-device render leg)
# Optional knobs:
#   GATE_LIVE_SESSION   compose project of a RUNNING sandbox (default: first running
#                       ai-sandbox-* session discovered via `docker compose ls`).
#   GATE_LIVE_PANE      tmux pane/target for the main Claude session (default: main).
#   GATE_LIVE_TIMEOUT   seconds to wait for the pane pending-question signal (default 90).
#   GATE_LIVE_SKIP_ONDEVICE  set 1 to assert pane detection only (skip the render leg).
#
# ⚠️ LIVE-VALIDATED BY QA (task #3): the three env-specific bits below are marked
#    `# @qa-tune` — the exact question-raising prompt, the pane target, and the
#    settle/timeout values are confirmed against the real emulator+server+Claude in
#    the A0 repro. The orchestration, version gate, and pass/fail semantics are fixed.
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

# ── resolve a running sandbox session ────────────────────────────────────────────
SESSION="${GATE_LIVE_SESSION:-}"
if [ -z "$SESSION" ]; then
  # Discover the first running ai-sandbox-* compose project (mirrors lib.sh enumeration).
  SESSION="$(docker compose ls --format json 2>/dev/null \
    | grep -oE '"Name":"ai-sandbox-[^"]+"' | head -1 | cut -d'"' -f4 || true)"
fi
[ -n "$SESSION" ] || skip_or_fail "no running ai-sandbox-* session found (spawn one, or set GATE_LIVE_SESSION). The live leg needs a live pinned Claude to drive."
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

# ── drive one AskUserQuestion and assert the PANE signal fires ────────────────────
# Raises a question in the live session, then tails the pane via the streaming
# helper for up to $TIMEOUT and asserts `__ctrl__<TAB>pending-question` appears.
assert_pane_pending() {
  local kind="$1" prompt="$2" cap="$GATE_DIR/log/live-$kind.tail"

  log "raising a live $kind AskUserQuestion"
  # Prompts below are QA-live-validated on Claude Code 2.1.169 (the sheet renders
  # ~20-24s after send; the default GATE_LIVE_TIMEOUT=90 is comfortable).
  dexec tmux send-keys -t "$PANE" -l "$prompt"
  dexec tmux send-keys -t "$PANE" Enter

  # Stream the pane via the helper and wait (bounded) for the pane pending signal.
  # `timeout` bounds the tail; grep -q returns as soon as the control line appears.
  if dexec timeout "$TIMEOUT" "$HELPER" --pane "$PANE" 2>/dev/null \
       | tee "$cap" | grep -q "$(printf 'pending-question')"; then
    log "$kind: pane pending-question signal detected ✅"
  else
    dexec tmux send-keys -t "$PANE" Escape 2>/dev/null || true
    fail "$kind: pane pending-question signal NOT detected within ${TIMEOUT}s — Claude Code $LIVE_VER may have drifted the TUI shape out from under PENDING_QUESTION_CHROME. Retune the scraper for this version or fall back to the next-newest gate-passing pin. (capture: $cap)"
  fi

  # Clear the pending state before the next case (interrupt the turn).
  dexec tmux send-keys -t "$PANE" Escape 2>/dev/null || true
  sleep 2
}

# Prompt wording confirmed live on 2.1.169 (QA A0) to reliably make Claude call
# AskUserQuestion and BLOCK, without doing anything else first (which would eat the turn).
assert_pane_pending "single" \
  "Use the AskUserQuestion tool right now to ask me a single question: do I prefer the color red or the color blue? Provide exactly those two options. Do not do anything else first."
assert_pane_pending "multi" \
  "Use the AskUserQuestion tool to ask me TWO questions at once in a single call: (1) favorite color: Red or Blue; (2) favorite size: Small or Large. Ask both together now."

log "PANE DETECTION (single + multi) PASSED ✅"

# ── on-device in-view render assertion (QA-owned live instrumented suite) ─────────
if [ "${GATE_LIVE_SKIP_ONDEVICE:-0}" = "1" ]; then
  log "on-device render assertion: SKIPPED (GATE_LIVE_SKIP_ONDEVICE=1) — pane detection only"
  exit 0
fi
: "${DEV:?DEV unset (needed for the on-device render leg)}"
: "${ADB:?ADB unset}"
: "${GATE_LIVE_TEST_PACKAGE:?GATE_LIVE_TEST_PACKAGE unset}"

log "running the on-device live render suite ($GATE_LIVE_TEST_PACKAGE)"
OUT="$GATE_DIR/log/live-render.out"
"$ADB" -s "$DEV" shell "am instrument -w \
  -e package $GATE_LIVE_TEST_PACKAGE \
  com.aisandbox.android.debug.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT"
# Reuse gate.sh's zero-tests-is-a-failure discipline inline (this script may run standalone).
if grep -q "FAILURES!!!" "$OUT"; then fail "on-device live render suite: test failures — see $OUT"; fi
if grep -qE 'OK \([1-9][0-9]* test' "$OUT"; then
  log "on-device live render suite passed ✅"
else
  fail "on-device live render suite ran zero/failed tests (a green gate with no tests is a failure). See $OUT"
fi

log "UC-97 LIVE DRIFT-GUARD LEG COMPLETE ✅"
