#!/usr/bin/env bash
# uc95-readiness-poll-unit.sh — UC-95 plain-bash unit harness for the bounded
# spawn-readiness poll (per-probe timeout + wall-clock deadline).
#
# UC-95 fixes a wedge: spawn.sh's post-`compose up` readiness loop polled for
# /tmp/aisandbox-ready via `docker compose exec` with NO per-probe timeout, so a
# single hung `exec` blocked the loop forever (the 600-iteration cap never
# applied). The fix (lib.sh) factors compose-flag assembly into a shared helper
# `ai_sandbox_compose_flags`, wraps each probe's `docker` binary in
# `timeout --kill-after` (`ai_sandbox_ready_probe`), and bounds the whole poll by
# a $SECONDS wall-clock deadline (`ai_sandbox_wait_devtools_ready`). spawn.sh's
# call site collapsed to an `if`.
#
# Deterministic strategy (per the plan / team-lead brief): a PATH-shadowing fake
# `docker` with a call-counter file — the first K calls `exec sleep <SENTINEL>`
# (a distinctive duration so a stray process is pgrep-findable, and `exec` so the
# real `timeout` signals its DIRECT child — a bash-wrapper that spawns sleep as a
# grandchild would show a spurious AC#6 leak), and from call K+1 it behaves as
# marker-present (rc 0). Env-tuned tiny timeouts keep the whole run to a few
# seconds. NOTE: the host `sleep` is a uutils multi-call binary, so the process
# is identified by a distinctive sleep DURATION (SENTINEL_SECS), not by a renamed
# binary (renaming breaks the multi-call dispatch).
#
# Per-AC coverage (see the per-case banners + the final summary map):
#   AC#7 BEFORE — reconstruct the OLD no-timeout loop → outer `timeout 6` = rc 124 (wedged)
#   AC#7 AFTER  — fixed wait fn vs the SAME fake → returns 0 within the deadline (no wedge)
#   AC#1        — K killed probes then success; loop advanced (elapsed ≈ K×probe_timeout)
#   AC#2        — always-hang fake → returns 1 bounded by the wall-clock deadline (not iteration count)
#   AC#3        — first probe rc 0 → immediate success, ~0 sleeps (fast path)
#   AC#6        — after killed probes, no stray sleep survives the poll (pgrep sentinel)
#   timeout-absent — degrades to an unbounded direct probe + one-time warn (NOT a true bound; honest)
#   flag-parity — probe's reconstructed `docker compose` flags == ai_sandbox_compose's (drift guard)
#
# Plain bash — no bats. Prints PASS/FAIL/SKIP per check + a summary; exits
# non-zero if any check fails (SKIPs never fail). Operator/QA-gated; NOT wired
# into server-ci.yml.
#
# UC-95. POSIX-bash-ish; Linux-only.
set -uo pipefail

# ── locate lib.sh (repo root = four levels up from server/src/test/e2e) ───────
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HARNESS_DIR/../../../.." && pwd)"
LIB_SH="$REPO_ROOT/lib.sh"
SPAWN_SH="$REPO_ROOT/spawn.sh"

# ── tiny assertion framework (t_ prefix: lib.sh defines its own ok/warn/info) ─
_pass=0
_fail=0
_skip=0
t_ok()   { printf '\033[1;32mPASS\033[0m %s\n' "$*"; _pass=$((_pass + 1)); }
t_fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; _fail=$((_fail + 1)); }
t_skip() { printf '\033[1;33mSKIP\033[0m %s\n' "$*"; _skip=$((_skip + 1)); }

assert_eq() { # <desc> <expected> <actual>
  if [ "$2" = "$3" ]; then t_ok "$1"; else t_fail "$1 — expected [$2], got [$3]"; fi
}
assert_rc() { # <desc> <expected-rc> <actual-rc>
  if [ "$2" -eq "$3" ]; then t_ok "$1"; else t_fail "$1 — expected rc $2, got $3"; fi
}
assert_contains() { # <desc> <haystack> <needle>
  case "$2" in *"$3"*) t_ok "$1" ;; *) t_fail "$1 — [$2] does not contain [$3]" ;; esac
}
assert_le() { # <desc> <actual> <max>
  if [ "$2" -le "$3" ]; then t_ok "$1 ($2 ≤ $3)"; else t_fail "$1 — $2 exceeds max $3"; fi
}
assert_ge() { # <desc> <actual> <min>
  if [ "$2" -ge "$3" ]; then t_ok "$1 ($2 ≥ $3)"; else t_fail "$1 — $2 below min $3"; fi
}

# ── preconditions ────────────────────────────────────────────────────────────
[ -f "$LIB_SH" ]   || { t_fail "lib.sh missing at $LIB_SH"; exit 1; }
[ -f "$SPAWN_SH" ] || { t_fail "spawn.sh missing at $SPAWN_SH"; exit 1; }

cd "$REPO_ROOT"
# shellcheck source=/dev/null
. "$LIB_SH"

for _fn in ai_sandbox_compose_flags ai_sandbox_ready_probe ai_sandbox_wait_devtools_ready; do
  if declare -F "$_fn" >/dev/null 2>&1; then
    t_ok "precondition: lib.sh defines $_fn"
  else
    t_fail "precondition: lib.sh is missing $_fn — cannot exercise UC-95"; exit 1
  fi
done

# `timeout` is load-bearing for every bounded leg; the plan verified it is
# present on the host. If it is somehow absent, SKIP the timeout-dependent legs
# (they would genuinely hang) rather than wedge the harness.
HAVE_TIMEOUT=0
if command -v timeout >/dev/null 2>&1; then HAVE_TIMEOUT=1; fi

# ── fake docker: PATH-shadowing stub with a call counter + argv log ───────────
WORK="$(mktemp -d)"
FAKEBIN="$WORK/bin"
mkdir -p "$FAKEBIN"
SENTINEL_SECS=31337   # distinctive sleep duration → pgrep-findable stray process
export FAKE_LOG="$WORK/docker.argv.log"
export FAKE_COUNT_FILE="$WORK/docker.count"
export FAKE_HANG_COUNT=0
export FAKE_SLEEP_SECS="$SENTINEL_SECS"

cat > "$FAKEBIN/docker" <<'FAKE'
#!/usr/bin/env bash
# Fake `docker` for the UC-95 poll harness. Logs its full argv, counts calls,
# and — for the first FAKE_HANG_COUNT calls — `exec sleep <SENTINEL>` so `timeout`
# signals THIS process directly (exec, not a spawned grandchild). From call
# FAKE_HANG_COUNT+1 onward it behaves as marker-present (rc 0). No `set -u`, no
# external commands, so it also runs under a stripped PATH (timeout-absent leg).
printf '%s\n' "$*" >> "$FAKE_LOG"
cur=0
[ -f "$FAKE_COUNT_FILE" ] && cur=$(<"$FAKE_COUNT_FILE")
n=$((cur + 1))
printf '%s' "$n" > "$FAKE_COUNT_FILE"
if [ "$n" -le "${FAKE_HANG_COUNT:-0}" ]; then
    exec sleep "${FAKE_SLEEP_SECS:-31337}"
fi
exit 0
FAKE
chmod +x "$FAKEBIN/docker"

# Make the fake the `docker` on PATH; real timeout/sleep/pgrep still resolve
# normally (the fake dir holds only `docker`).
ORIG_PATH="$PATH"
export PATH="$FAKEBIN:$ORIG_PATH"

reset_fake() { : > "$FAKE_LOG"; : > "$FAKE_COUNT_FILE"; }

# ── tiny env-tuned timeouts so the whole run is a few seconds ─────────────────
export AISB_READY_PROBE_TIMEOUT=2s
export AISB_READY_PROBE_KILL_GRACE=1s
export AISB_READY_PROBE_INTERVAL=1
export AISB_READY_DEADLINE_SECS=8

fake_calls() { local c=0; [ -f "$FAKE_COUNT_FILE" ] && c=$(<"$FAKE_COUNT_FILE"); printf '%s' "$c"; }

# ═════════════════════════════════════════════════════════════════════════════
# AC#3 — fast path: first probe returns 0 → immediate success, ~0 sleeps.
# ═════════════════════════════════════════════════════════════════════════════
printf '\n── AC#3 fast path (marker present on first probe) ──\n'
if [ "$HAVE_TIMEOUT" -eq 1 ]; then
  reset_fake
  export FAKE_HANG_COUNT=0
  _t0=$SECONDS
  ai_sandbox_wait_devtools_ready ac3-proj; _rc=$?
  _elapsed=$((SECONDS - _t0))
  assert_rc "AC#3 fixed wait returns 0 when the first probe succeeds" 0 "$_rc"
  assert_eq "AC#3 exactly one probe issued (no re-poll)" "1" "$(fake_calls)"
  assert_le "AC#3 returns within one probe interval (no sleep on the fast path)" "$_elapsed" 1
else
  t_skip "AC#3 fast path — timeout(1) absent (bounded legs skipped)"
fi

# ═════════════════════════════════════════════════════════════════════════════
# AC#1 — loop ADVANCES past killed probes: K hangs then success. Elapsed ≈
#        K×probe_timeout (the loop did not wedge on the first hung probe).
#        AC#6 rides on the same aftermath (no stray sleep survives the poll).
# ═════════════════════════════════════════════════════════════════════════════
printf '\n── AC#1 loop advances past K killed probes + AC#6 no-leak ──\n'
if [ "$HAVE_TIMEOUT" -eq 1 ]; then
  reset_fake
  export FAKE_HANG_COUNT=2   # calls 1,2 hang (killed); call 3 = marker present
  # Ensure a clean slate for the leak assertion.
  pkill -f "$SENTINEL_SECS" 2>/dev/null || true
  _t0=$SECONDS
  ai_sandbox_wait_devtools_ready ac1-proj; _rc=$?
  _elapsed=$((SECONDS - _t0))
  assert_rc "AC#1 fixed wait returns 0 after advancing past 2 killed probes" 0 "$_rc"
  assert_eq "AC#1 exactly 3 probes issued (2 killed + 1 success)" "3" "$(fake_calls)"
  # 2 killed probes × 2s per-probe timeout ⇒ elapsed must reflect real advance,
  # not an instant wedge-break, and stay well under the 8s deadline.
  assert_ge "AC#1 elapsed reflects K×probe_timeout (loop truly advanced)" "$_elapsed" 3
  assert_le "AC#1 elapsed bounded below the wall-clock deadline" "$_elapsed" 7
  # AC#6 — the two killed probes must leave no stray sleep behind.
  if pgrep -f "$SENTINEL_SECS" >/dev/null 2>&1; then
    t_fail "AC#6 a killed probe leaked a stray sleep (pgrep '$SENTINEL_SECS' found a survivor)"
    pkill -f "$SENTINEL_SECS" 2>/dev/null || true
  else
    t_ok "AC#6 no stray probe process outlives the poll after 2 killed probes"
  fi
else
  t_skip "AC#1 loop-advances — timeout(1) absent (bounded legs skipped)"
  t_skip "AC#6 no-leak — timeout(1) absent (bounded legs skipped)"
fi

# ═════════════════════════════════════════════════════════════════════════════
# AC#2 — always-hang fake: the wait is bounded by the WALL-CLOCK deadline (not
#        an iteration count). Returns 1 within ~deadline (+ one probe/grace).
# ═════════════════════════════════════════════════════════════════════════════
printf '\n── AC#2 always-hang → bounded by wall-clock deadline ──\n'
if [ "$HAVE_TIMEOUT" -eq 1 ]; then
  reset_fake
  export FAKE_HANG_COUNT=100000   # never succeeds
  pkill -f "$SENTINEL_SECS" 2>/dev/null || true
  _t0=$SECONDS
  ai_sandbox_wait_devtools_ready ac2-proj; _rc=$?
  _elapsed=$((SECONDS - _t0))
  assert_rc "AC#2 always-hang wait returns 1 (deadline reached, no marker)" 1 "$_rc"
  # Bounded by wall-clock: ~deadline (8s) + at most one in-flight probe (2s) +
  # kill grace (1s) + interval slack. NOT 600 iterations.
  assert_ge "AC#2 used ~the full deadline (proves a real wall-clock bound)" "$_elapsed" 7
  assert_le "AC#2 total wait bounded by deadline + one probe/grace" "$_elapsed" 13
  # A handful of iterations, nowhere near the retired 600-iteration cap — the
  # bound is wall-clock, not iteration count.
  _c="$(fake_calls)"
  assert_le "AC#2 iteration count is deadline-driven, far below the old 600 cap" "$_c" 10
  # Clean up the last in-flight killed probe, if any.
  pkill -f "$SENTINEL_SECS" 2>/dev/null || true
  if pgrep -f "$SENTINEL_SECS" >/dev/null 2>&1; then
    t_fail "AC#6/AC#2 stray probe survived the bounded always-hang wait"
    pkill -f "$SENTINEL_SECS" 2>/dev/null || true
  else
    t_ok "AC#6/AC#2 no stray probe survives the bounded always-hang wait"
  fi
else
  t_skip "AC#2 always-hang bound — timeout(1) absent (bounded legs skipped)"
fi

# ═════════════════════════════════════════════════════════════════════════════
# AC#7 BEFORE/AFTER — the mandatory wedge → no-wedge repro against the SAME fake.
#   BEFORE: reconstruct the OLD no-timeout loop shape (a direct `docker exec`
#           inside an `if`, no per-probe timeout) → wrap the whole loop in an
#           outer `timeout 6`; a hung first probe blocks forever ⇒ outer rc 124.
#   AFTER:  the FIXED ai_sandbox_wait_devtools_ready vs the same hanging fake
#           advances past the hung probe and returns 0 within the deadline.
# ═════════════════════════════════════════════════════════════════════════════
printf '\n── AC#7 wedge (BEFORE) → no-wedge (AFTER) ──\n'
if [ "$HAVE_TIMEOUT" -eq 1 ]; then
  # Structurally-faithful reconstruction of the pre-fix loop (spawn.sh lines
  # 279–297 as quoted in the use case): direct probe, no timeout, sleep 2, cap
  # 600. Kept in its own script so it runs in a fresh process we can wrap.
  OLDLOOP="$WORK/old-loop.sh"
  cat > "$OLDLOOP" <<'OLD'
#!/usr/bin/env bash
set -uo pipefail
PROJECT="${1:-proj}"
ready=0; tries=0
while [ "$tries" -lt 600 ]; do
    # OLD SHAPE: direct `docker compose exec` probe with NO per-probe timeout.
    if docker compose -p "$PROJECT" exec -T claude-sandbox test -f /tmp/aisandbox-ready >/dev/null 2>&1; then
        ready=1; break
    fi
    tries=$((tries + 1))
    sleep 2
done
printf 'ready=%s tries=%s\n' "$ready" "$tries"
OLD
  chmod +x "$OLDLOOP"

  pkill -f "$SENTINEL_SECS" 2>/dev/null || true
  _bt0=$SECONDS
  # Always-hang fake; the OLD loop wedges on the first probe. Outer timeout 6s
  # must fire (rc 124) — proving the pre-fix bug.
  timeout 6 env \
      PATH="$FAKEBIN:$ORIG_PATH" \
      FAKE_LOG="$WORK/before.log" \
      FAKE_COUNT_FILE="$WORK/before.count" \
      FAKE_HANG_COUNT=100000 \
      FAKE_SLEEP_SECS="$SENTINEL_SECS" \
      bash "$OLDLOOP" before-proj >/dev/null 2>&1
  _before_rc=$?
  _before_elapsed=$((SECONDS - _bt0))
  assert_rc "AC#7 BEFORE: the OLD no-timeout loop wedges (outer timeout fires, rc 124)" 124 "$_before_rc"
  assert_ge "AC#7 BEFORE: the loop was genuinely stuck for the whole outer window" "$_before_elapsed" 5
  # The wedge means the loop never advanced past the first probe.
  _before_calls=0
  [ -f "$WORK/before.count" ] && _before_calls=$(<"$WORK/before.count")
  assert_eq "AC#7 BEFORE: loop never advanced past the first (hung) probe" "1" "$_before_calls"
  # Reap the leaked (unbounded) sleep the OLD loop left behind — the fix's whole point.
  pkill -f "$SENTINEL_SECS" 2>/dev/null || true

  # AFTER: same hanging-fake shape (one hung probe, then marker present); the
  # FIXED wait advances past the hung probe and reports ready within the deadline.
  reset_fake
  export FAKE_HANG_COUNT=1
  _at0=$SECONDS
  ai_sandbox_wait_devtools_ready after-proj; _after_rc=$?
  _after_elapsed=$((SECONDS - _at0))
  assert_rc "AC#7 AFTER: the FIXED wait advances past the hung probe → returns 0" 0 "$_after_rc"
  assert_le "AC#7 AFTER: ready reported within the wall-clock deadline" "$_after_elapsed" 7
  assert_ge "AC#7 AFTER: advance reflects the killed probe (not an instant no-op)" "$_after_elapsed" 1
  pkill -f "$SENTINEL_SECS" 2>/dev/null || true
else
  t_skip "AC#7 BEFORE/AFTER wedge repro — timeout(1) absent (bounded legs skipped)"
fi

# ═════════════════════════════════════════════════════════════════════════════
# timeout-absent degradation — with `timeout` off PATH, the wall-clock loop
# still runs and detects the marker on a fast fake, and emits the one-time
# degradation warn. HONEST NOTE: this path does NOT bound a truly-hung probe
# (the deadline is only checked between iterations), so we only exercise the
# fast (non-hanging) fake here — asserting a hung probe would (correctly) hang.
# ═════════════════════════════════════════════════════════════════════════════
printf '\n── timeout-absent degradation (fast fake only; NOT a true bound) ──\n'
NOTO="$WORK/noto"
mkdir -p "$NOTO"
ln -s "$FAKEBIN/docker" "$NOTO/docker"           # fake docker only, no timeout
ln -s "$(command -v bash)"  "$NOTO/bash"          # shebang resolution
ln -s "$(command -v sleep)" "$NOTO/sleep"         # defensive; fast path won't sleep
reset_fake
export FAKE_HANG_COUNT=0
_saved_path="$PATH"
_warned_before="${_AISB_READY_TIMEOUT_WARNED:-<unset>}"
unset _AISB_READY_TIMEOUT_WARNED
PATH="$NOTO"
# Sanity: timeout really is not resolvable under this PATH.
if command -v timeout >/dev/null 2>&1; then _to_visible=1; else _to_visible=0; fi
ai_sandbox_wait_devtools_ready noto-proj 2>"$WORK/noto.err"; _noto_rc=$?
PATH="$_saved_path"
assert_eq "timeout-absent: timeout(1) is genuinely off PATH for this leg" "0" "$_to_visible"
assert_rc "timeout-absent: the wall-clock loop still detects the marker (fast fake)" 0 "$_noto_rc"
assert_contains "timeout-absent: one-time degradation warn is emitted" \
  "$(cat "$WORK/noto.err")" "timeout(1) not found"
t_skip "timeout-absent: a TRULY-hung probe is NOT bounded on this path (honest — deadline only checked between iterations); not exercised to avoid a real hang"

# ═════════════════════════════════════════════════════════════════════════════
# Flag-parity (drift guard, plan rec #2) — ai_sandbox_ready_probe rebuilds the
# `docker compose` flags via the SAME shared helper as ai_sandbox_compose, so the
# -f / --project-directory flags must be byte-identical. Capture the fake docker's
# argv from each path and compare the flag span between `compose` and `-p`.
# ═════════════════════════════════════════════════════════════════════════════
printf '\n── flag-parity: probe flags == ai_sandbox_compose flags ──\n'
# Distinctive, non-empty compose env so the flag span is meaningful.
export AI_SANDBOX_COMPOSE_FILE="$WORK/compose/docker-compose.yml"
export AI_SANDBOX_EXTRA_COMPOSE_FILES="$WORK/compose/docker-compose.dind.yml"
export AI_SANDBOX_HOST_STATE_ROOT="$WORK/compose/state"
export FAKE_HANG_COUNT=0

reset_fake
if [ "$HAVE_TIMEOUT" -eq 1 ]; then
  ai_sandbox_ready_probe parity-proj >/dev/null 2>&1 || true
else
  # timeout-absent probe path still shapes flags identically (else-branch).
  ai_sandbox_ready_probe parity-proj >/dev/null 2>&1 || true
fi
_probe_argv="$(head -n1 "$FAKE_LOG")"

reset_fake
ai_sandbox_compose -p parity-proj __PARITY_MARKER__ >/dev/null 2>&1 || true
_compose_argv="$(head -n1 "$FAKE_LOG")"

# Flags = the span between the leading `compose ` and ` -p ` (neither flag value
# contains ` -p `). Extracted identically from both argv lines.
_probe_flags="$(printf '%s' "$_probe_argv"   | sed -E 's/^compose (.*) -p .*/\1/')"
_compose_flags="$(printf '%s' "$_compose_argv" | sed -E 's/^compose (.*) -p .*/\1/')"
_expected_flags="-f $AI_SANDBOX_COMPOSE_FILE -f $AI_SANDBOX_EXTRA_COMPOSE_FILES --project-directory $AI_SANDBOX_HOST_STATE_ROOT"

assert_eq "flag-parity: probe flags == ai_sandbox_compose flags (no drift)" "$_compose_flags" "$_probe_flags"
assert_eq "flag-parity: both carry the expected -f / --project-directory span" "$_expected_flags" "$_probe_flags"

unset AI_SANDBOX_COMPOSE_FILE AI_SANDBOX_EXTRA_COMPOSE_FILES AI_SANDBOX_HOST_STATE_ROOT

# ── final cleanup: never leave a stray sentinel process on the host ───────────
pkill -f "$SENTINEL_SECS" 2>/dev/null || true
rm -rf "$WORK"

# ── summary ──────────────────────────────────────────────────────────────────
printf '\n──────────────────────────────────────────────\n'
printf 'uc95-readiness-poll-unit: %d passed, %d failed, %d skipped\n' "$_pass" "$_fail" "$_skip"
[ "$_fail" -eq 0 ] || exit 1
exit 0
