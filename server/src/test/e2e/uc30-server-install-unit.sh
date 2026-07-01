#!/usr/bin/env bash
# uc30-server-install-unit.sh — UC-30 plain-sh unit harness for the server-side
# per-capability install stage (idempotent, hard-gated) + the dind subuid/subgid
# provisioning hook.
#
# Shell-side coverage for the new THIRD capability lifecycle stage introduced by
# UC-30: a manifest's optional `devtool_server_install()` hook, run by `setup.sh`
# in the privileged setup context (writable host /etc-equivalent files) AFTER
# capability selection and BEFORE any in-container `devtool_provision`. Every
# check is hermetic: it sources the libraries and asserts pure-function
# behaviour against TEMP files / a SYNTHETIC catalog — no Docker, no real /etc
# write, no network.
#
#   devtools.d/lib/server-install.sh
#     aisb_subuid_ensure_line      AC#3/#6/#1  idempotent ensure-or-append of one
#                                              `owner:start:count` delegation line
#     aisb_server_install_note     AC#5        post-requisite notice channel
#     aisb_server_install_reason   AC#4        hard-gate reason channel
#   devtools.d/dind/manifest.sh
#     _aisb_dind_ensure_subid_files AC#3/#6    both names in BOTH files, disjoint,
#                                              idempotent from setup AND spawn path
#     devtool_server_install       AC#5/#7     respawn note on success / fuse gate
#   lib.sh
#     run_devtool_server_install   AC#1/#4/#5  dispatcher: hard-gate a failing hook
#                                              (id removed from ledger, sibling +
#                                              no-hook caps survive, rc 0), notes
#                                              aggregated incl. from a failed hook
#
# Environment-gated legs (flagged SKIP, NOT failures):
#   AC#8  live `aisandbox-dind doctor/start/selftest` against real Docker + a real
#         subuid /etc write — operator-sign-off gate (cf. uc26-dind-busybox-gate.sh).
#   AC#9  shellcheck lint — shellcheck is not installed on this host; we fall back
#         to `bash -n` (syntax-clean) on every changed shell file instead.
#   AC#7  the /dev/fuse ABSENT hard-gate branch — /dev/fuse is present on this host,
#         so the live failure path cannot be exercised; asserted structurally.
#
# Plain bash — no bats (per the brief / team-lead decision). Prints PASS/FAIL/SKIP
# per check + a summary; exits non-zero if any check fails (SKIPs never fail).
#
# UC-30. POSIX-bash; Linux-only.
set -uo pipefail

# ── locate the scripts under test ────────────────────────────────────────────
# server-install.sh + lib.sh + the dind manifest live at the repo ROOT tree
# (setup.sh's siblings): lib.sh, devtools.d/lib/server-install.sh,
# devtools.d/dind/manifest.sh, setup.sh, container-bin/aisandbox-dind.
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HARNESS_DIR/../../../.." && pwd)"
DEVTOOLS_D="$REPO_ROOT/devtools.d"
SERVER_INSTALL_LIB="$DEVTOOLS_D/lib/server-install.sh"
DIND_MANIFEST="$DEVTOOLS_D/dind/manifest.sh"
SETUP_SH="$REPO_ROOT/setup.sh"
DIND_BIN="$REPO_ROOT/container-bin/aisandbox-dind"
COMPOSE_DIND="$REPO_ROOT/docker-compose.dind.yml"

# ── tiny assertion framework (t_ prefix: lib.sh defines its own ok/info/warn) ─
_pass=0
_fail=0
_skip=0
t_ok()   { printf '\033[1;32mPASS\033[0m %s\n' "$*"; _pass=$((_pass + 1)); }
t_bad()  { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; _fail=$((_fail + 1)); }
t_skip() { printf '\033[1;33mSKIP\033[0m %s\n' "$*"; _skip=$((_skip + 1)); }

assert_eq() { # <desc> <expected> <actual>
  if [ "$2" = "$3" ]; then t_ok "$1"; else t_bad "$1 — expected [$2], got [$3]"; fi
}
assert_contains() { # <desc> <haystack> <needle>
  case "$2" in *"$3"*) t_ok "$1" ;; *) t_bad "$1 — [$2] does not contain [$3]" ;; esac
}
assert_not_contains() { # <desc> <haystack> <needle>
  case "$2" in *"$3"*) t_bad "$1 — [$2] unexpectedly contains [$3]" ;; *) t_ok "$1" ;; esac
}
assert_rc() { # <desc> <expected-rc> <actual-rc>
  if [ "$2" -eq "$3" ]; then t_ok "$1"; else t_bad "$1 — expected rc $2, got $3"; fi
}
assert_file_has() { # <desc> <file> <exact-line>
  if grep -qxF -- "$3" "$2" 2>/dev/null; then t_ok "$1"; else t_bad "$1 — [$2] missing exact line [$3]"; fi
}
assert_grep() { # <desc> <pattern> <file>  (extended-regex grep -E)
  if grep -Eq -- "$2" "$3" 2>/dev/null; then t_ok "$1"; else t_bad "$1 — pattern [$2] not found in $3"; fi
}
count_lines() { grep -cxF -- "$2" "$1" 2>/dev/null || true; }  # exact-line count

# ── preconditions ────────────────────────────────────────────────────────────
[ -f "$REPO_ROOT/lib.sh" ]   || { t_bad "lib.sh missing";                 exit 1; }
[ -f "$SERVER_INSTALL_LIB" ] || { t_bad "devtools.d/lib/server-install.sh missing"; exit 1; }
[ -f "$DIND_MANIFEST" ]      || { t_bad "devtools.d/dind/manifest.sh missing";      exit 1; }
[ -f "$SETUP_SH" ]           || { t_bad "setup.sh missing";               exit 1; }
[ -f "$DIND_BIN" ]           || { t_bad "container-bin/aisandbox-dind missing";     exit 1; }
[ -f "$COMPOSE_DIND" ]       || { t_bad "docker-compose.dind.yml missing";          exit 1; }

# Source from the repo root so lib.sh resolves AISB_DEVTOOLS_DIR / AISB_LIB_DIR.
cd "$REPO_ROOT"
# shellcheck source=/dev/null
. "$REPO_ROOT/lib.sh"
# shellcheck source=/dev/null
. "$SERVER_INSTALL_LIB"

# ═════════════════════════════════════════════════════════════════════════════
# A — aisb_subuid_ensure_line: the unit-testable core of the dind hook.
#     append-when-absent (creates dir+file), idempotent no-op + no-dup (AC#3),
#     preserve a pre-existing owner while adding another (AC#6), disjoint ranges.
# ═════════════════════════════════════════════════════════════════════════════
_A="$(mktemp -d)"
_f="$_A/nested/subuid"   # parent dir intentionally absent → must be created

# append-when-absent: creates parent dir + file, writes exactly the line.
aisb_subuid_ensure_line "$_f" claude 100000 65536; _rc=$?
assert_rc "AC#3 ensure_line creates a missing file+dir and returns 0" 0 "$_rc"
assert_file_has "AC#3 ensure_line appends the claude delegation line" "$_f" "claude:100000:65536"
assert_eq "AC#3 ensure_line wrote exactly one line" "1" "$(wc -l <"$_f" | tr -d ' ')"

# idempotent no-op: re-running the SAME line is byte-identical (no duplicate).
_before="$(cat "$_f")"
aisb_subuid_ensure_line "$_f" claude 100000 65536; _rc=$?
assert_rc "AC#3 ensure_line re-run of the same line returns 0 (idempotent)" 0 "$_rc"
assert_eq "AC#3 ensure_line re-run is byte-identical (no duplicate)" "$_before" "$(cat "$_f")"
assert_eq "AC#3 the claude line appears exactly once after re-run" "1" "$(count_lines "$_f" "claude:100000:65536")"

# AC#6 — adding a SECOND owner preserves the pre-existing one (no clobber).
aisb_subuid_ensure_line "$_f" sandbox 165536 65536
assert_file_has "AC#6 pre-existing claude entry is preserved when adding sandbox" "$_f" "claude:100000:65536"
assert_file_has "AC#6 sandbox delegation is appended alongside claude" "$_f" "sandbox:165536:65536"
assert_eq "AC#6 both delegations present, exactly two lines" "2" "$(wc -l <"$_f" | tr -d ' ')"
# Disjoint ranges: claude 100000..165535, sandbox 165536..231071 — no overlap.
assert_eq "AC#6 the two ranges are disjoint (claude ends at 165535, sandbox starts 165536)" \
  "165535 165536" "$((100000 + 65536 - 1)) $(awk -F: '/^sandbox:/{print $2}' "$_f")"

# AC#6 — symmetric direction: a hand-written sandbox-first file keeps sandbox
# untouched when the claude entry is later ensured.
_g="$_A/subuid-sandbox-first"
printf 'sandbox:165536:65536\n' > "$_g"
aisb_subuid_ensure_line "$_g" claude 100000 65536
assert_file_has "AC#6 hand-written sandbox entry preserved when claude is added" "$_g" "sandbox:165536:65536"
assert_file_has "AC#6 claude appended after a pre-existing sandbox" "$_g" "claude:100000:65536"

# ── UC-94 AC#3 self-heal — a path that EXISTS but is NOT a regular file (the
#    root-created-DIRECTORY trap, plus a stray symlink / fifo) is removed and
#    recreated as an empty regular file, provided the parent is writable. The
#    `rm -rf` blast radius is bounded to the exact target path.

# (i) pre-existing DIRECTORY where a regular file must live → rm + recreate.
_heal_dir="$_A/heal-dir/subuid"
mkdir -p "$_heal_dir"   # the target path is itself a directory (docker root-create trap)
aisb_subuid_ensure_line "$_heal_dir" claude 100000 65536; _rc=$?
assert_rc "AC#3 self-heal: a pre-existing DIRECTORY at the target is healed (rc 0)" 0 "$_rc"
if [ -f "$_heal_dir" ]; then
  t_ok "AC#3 self-heal: the healed target is now a regular file (was a directory)"
else
  t_bad "AC#3 self-heal: target is still not a regular file after healing a directory"
fi
assert_file_has "AC#3 self-heal: the delegation line is written into the healed file" "$_heal_dir" "claude:100000:65536"
assert_eq "AC#3 self-heal: the healed file has exactly one line" "1" "$(wc -l <"$_heal_dir" | tr -d ' ')"

# (ii-a) pre-existing FIFO → rm + recreate as a regular file.
if command -v mkfifo >/dev/null 2>&1; then
  _heal_fifo="$_A/heal-fifo/subuid"
  mkdir -p "$(dirname -- "$_heal_fifo")"
  mkfifo "$_heal_fifo"
  aisb_subuid_ensure_line "$_heal_fifo" sandbox 165536 65536; _rc=$?
  assert_rc "AC#3 self-heal: a pre-existing FIFO at the target is healed (rc 0)" 0 "$_rc"
  if [ -f "$_heal_fifo" ] && [ ! -p "$_heal_fifo" ]; then
    t_ok "AC#3 self-heal: the healed target is now a regular file (was a fifo)"
  else
    t_bad "AC#3 self-heal: target is not a regular file after healing a fifo"
  fi
  assert_file_has "AC#3 self-heal: delegation written into the healed fifo path" "$_heal_fifo" "sandbox:165536:65536"
else
  t_skip "AC#3 self-heal: mkfifo unavailable — fifo self-heal case skipped"
fi

# (ii-b) DANGLING SYMLINK (missed by `-e`, caught by `-L`) → rm + recreate.
_heal_link="$_A/heal-link/subuid"
mkdir -p "$(dirname -- "$_heal_link")"
ln -s "$_A/heal-link/no-such-target-$$" "$_heal_link"   # dangling on purpose
aisb_subuid_ensure_line "$_heal_link" claude 100000 65536; _rc=$?
assert_rc "AC#3 self-heal: a DANGLING SYMLINK at the target is healed (rc 0)" 0 "$_rc"
if [ -f "$_heal_link" ] && [ ! -L "$_heal_link" ]; then
  t_ok "AC#3 self-heal: the healed target is a regular file, not a symlink"
else
  t_bad "AC#3 self-heal: target is still a symlink / not a regular file after healing"
fi
assert_file_has "AC#3 self-heal: delegation written into the healed symlink path" "$_heal_link" "claude:100000:65536"

# Failure path → non-zero (caller hard-gates): an unwritable parent dir.
_ro="$_A/readonly"
mkdir -p "$_ro"
chmod 0500 "$_ro"
aisb_subuid_ensure_line "$_ro/sub/x" claude 100000 65536 >/dev/null 2>&1; _rc=$?
if [ "$_rc" -ne 0 ]; then
  t_ok "AC#4 ensure_line returns non-zero when the target dir is unwritable"
else
  # Running as root would defeat the perm check; only meaningful as non-root.
  t_skip "AC#4 ensure_line unwritable-dir check inconclusive (running as root?)"
fi
chmod 0700 "$_ro" 2>/dev/null || true
rm -rf "$_A"

# ═════════════════════════════════════════════════════════════════════════════
# B — note / reason channels (the live-shell globals the dispatcher consumes).
# ═════════════════════════════════════════════════════════════════════════════
AISB_SERVER_INSTALL_NOTES=()
aisb_server_install_note "first note"
assert_eq "AC#5 note helper appends the first post-requisite notice" "1" "${#AISB_SERVER_INSTALL_NOTES[@]}"
aisb_server_install_note "second note"
assert_eq "AC#5 note helper accumulates a second notice" "2" "${#AISB_SERVER_INSTALL_NOTES[@]}"
assert_eq "AC#5 notes preserve insertion order/content" "first note" "${AISB_SERVER_INSTALL_NOTES[0]}"

_AISB_SI_REASON=""
aisb_server_install_reason "because of X"
assert_eq "AC#4 reason helper records the hard-gate reason" "because of X" "$_AISB_SI_REASON"

# ═════════════════════════════════════════════════════════════════════════════
# C — dind manifest: _aisb_dind_ensure_subid_files writes BOTH names into BOTH
#     files, idempotently, from either the setup-time OR the spawn-time caller
#     (challenger carry-forward). devtool_server_install registers the respawn
#     post-requisite on success; records a reason on a write failure (AC#4).
# ═════════════════════════════════════════════════════════════════════════════
_C="$(mktemp -d)"
export AI_SANDBOX_DIND_SUBUID_HOST_PATH="$_C/subuid"
export AI_SANDBOX_DIND_SUBGID_HOST_PATH="$_C/subgid"
# shellcheck source=/dev/null
. "$DIND_MANIFEST"

AISB_SERVER_INSTALL_NOTES=()
_AISB_SI_REASON=""
_aisb_dind_ensure_subid_files; _rc=$?
assert_rc "AC#3 _aisb_dind_ensure_subid_files succeeds on first run" 0 "$_rc"
for _which in subuid subgid; do
  _ff="$_C/$_which"
  assert_file_has "AC#6 dind delegates claude into $_which" "$_ff" "claude:100000:65536"
  assert_file_has "AC#6 dind delegates sandbox (non-1000 uid case) into $_which" "$_ff" "sandbox:165536:65536"
  assert_eq "AC#6 $_which carries exactly the two disjoint ranges" "2" "$(wc -l <"$_ff" | tr -d ' ')"
done

# Challenger carry-forward: the SAME helper backs setup-time write AND spawn-time
# ensure. Driving it twice (≙ setup then a warm spawn) must be byte-identical.
_sub_before="$(cat "$_C/subuid")"; _sgid_before="$(cat "$_C/subgid")"
_aisb_dind_ensure_subid_files; _rc=$?
assert_rc "AC#3 second drive (spawn-time path) returns 0" 0 "$_rc"
assert_eq "AC#3 subuid byte-identical when driven setup→spawn (no dup)" "$_sub_before" "$(cat "$_C/subuid")"
assert_eq "AC#3 subgid byte-identical when driven setup→spawn (no dup)" "$_sgid_before" "$(cat "$_C/subgid")"
# Both lifecycle hooks funnel through the one shared helper (single source of truth).
assert_grep "AC#3 devtool_server_install routes through _aisb_dind_ensure_subid_files" \
  'devtool_server_install\(\)' "$DIND_MANIFEST"
assert_eq "AC#3 both server-install and spawn_env call the shared ensure helper" "2" \
  "$(grep -c '_aisb_dind_ensure_subid_files *||' "$DIND_MANIFEST" | tr -d ' ')"

# devtool_server_install end-to-end. /dev/fuse gates the success path (AC#7).
AISB_SERVER_INSTALL_NOTES=()
_AISB_SI_REASON=""
if [ -e /dev/fuse ]; then
  devtool_server_install; _rc=$?
  assert_rc "AC#5 devtool_server_install succeeds (fuse present) and returns 0" 0 "$_rc"
  assert_eq "AC#5 success registers exactly one respawn post-requisite note" "1" "${#AISB_SERVER_INSTALL_NOTES[@]}"
  assert_contains "AC#5 the post-requisite note tells the operator to respawn" \
    "${AISB_SERVER_INSTALL_NOTES[0]:-}" "respawn"
else
  devtool_server_install; _rc=$?
  assert_rc "AC#7 devtool_server_install hard-gates (rc!=0) when /dev/fuse is absent" 1 "$_rc"
  assert_contains "AC#7 the hard-gate reason names /dev/fuse exactly" "$_AISB_SI_REASON" "/dev/fuse"
fi

# AC#4 — a write failure inside the hook records a reason (the dispatcher surfaces
# it). Point the subuid target at an unwritable dir; subgid stays writable.
_Cro="$_C/ro"
mkdir -p "$_Cro"; chmod 0500 "$_Cro"
AI_SANDBOX_DIND_SUBUID_HOST_PATH="$_Cro/sub/subuid"
AI_SANDBOX_DIND_SUBGID_HOST_PATH="$_C/subgid"
_AISB_SI_REASON=""
_aisb_dind_ensure_subid_files >/dev/null 2>&1; _rc=$?
if [ "$_rc" -ne 0 ]; then
  t_ok "AC#4 dind ensure returns non-zero on an unwritable target"
  assert_contains "AC#4 the recorded reason names the unwritable target path" "$_AISB_SI_REASON" "subuid"
else
  t_skip "AC#4 dind ensure unwritable-target check inconclusive (running as root?)"
fi
chmod 0700 "$_Cro" 2>/dev/null || true
unset AI_SANDBOX_DIND_SUBUID_HOST_PATH AI_SANDBOX_DIND_SUBGID_HOST_PATH
rm -rf "$_C"

# ═════════════════════════════════════════════════════════════════════════════
# D — run_devtool_server_install dispatcher against a SYNTHETIC catalog:
#     a passing hook, a failing hook, and a NO-hook capability.
#     AC#1 no-hook = byte-for-byte no-op; AC#4 hard-gate removes only the failed
#     id (rc 0, no abort, sibling + no-hook survive); AC#5 notes aggregated incl.
#     from the failed hook.
# ═════════════════════════════════════════════════════════════════════════════
_mk_cap() { # <catalog-dir> <id> <hook-body|"">
  local dir="$1/$2"; mkdir -p "$dir"
  {
    printf 'ID="%s"\n' "$2"
    printf 'LABEL="synthetic %s"\n' "$2"
    printf 'DEPENDS_ON=()\n'
    printf 'APPLY_AT="session-spawn"\n'
    printf 'ARCH="any"\n'
    [ -n "$3" ] && printf 'devtool_server_install() { %s }\n' "$3"
  } > "$dir/manifest.sh"
}

# ── D1: mixed catalog — pass + fail + no-hook ────────────────────────────────
_D="$(mktemp -d)"
_mk_cap "$_D" cap_pass   'aisb_server_install_note "pass-note"; return 0;'
# A FAILING hook still registers a note BEFORE it returns non-zero → that note
# must survive aggregation (AC#5 "incl. from failed hooks").
_mk_cap "$_D" cap_fail   'aisb_server_install_note "fail-note-still-aggregated"; aisb_server_install_reason "synthetic failure"; return 1;'
_mk_cap "$_D" cap_nohook ''   # no devtool_server_install → AC#1 path

AISB_DEVTOOLS_DIR="$_D"
export AISB_DEVTOOLS_FILE="$_D/.ai-sandbox-devtools"
write_enabled_devtools cap_pass cap_fail cap_nohook   # persisted in catalog order
assert_eq "dispatcher precondition: all three capabilities enabled, sorted" \
  "cap_fail cap_nohook cap_pass" "$(read_enabled_devtools | tr '\n' ' ' | sed 's/ $//')"

run_devtool_server_install; _rc=$?
assert_rc "AC#4 dispatcher returns 0 even though a hook hard-gated (no abort)" 0 "$_rc"
assert_eq "AC#4/#1 ledger after run keeps the passing + no-hook caps, drops only the failed one" \
  "cap_nohook cap_pass" "$(read_enabled_devtools | tr '\n' ' ' | sed 's/ $//')"
assert_eq "AC#4 exactly one capability recorded as DISABLED" "1" "${#AISB_SERVER_INSTALL_DISABLED[@]}"
assert_contains "AC#4 the DISABLED record names the failed capability" "${AISB_SERVER_INSTALL_DISABLED[0]:-}" "cap_fail"
assert_contains "AC#4 the DISABLED record carries the hook's reason" "${AISB_SERVER_INSTALL_DISABLED[0]:-}" "synthetic failure"
# AC#5 — notes aggregated across ALL hooks, including the one that then failed.
_notes_joined="$(printf '%s\n' "${AISB_SERVER_INSTALL_NOTES[@]}")"
assert_contains "AC#5 note from the passing hook is aggregated" "$_notes_joined" "pass-note"
assert_contains "AC#5 note from the FAILED hook is still aggregated" "$_notes_joined" "fail-note-still-aggregated"
rm -rf "$_D"

# ── D2: no-hook-only catalog — AC#1 byte-for-byte no-op (no ledger rewrite) ───
_D2="$(mktemp -d)"
_mk_cap "$_D2" cap_only ''
AISB_DEVTOOLS_DIR="$_D2"
AISB_DEVTOOLS_FILE="$_D2/.ai-sandbox-devtools"
write_enabled_devtools cap_only
_ledger_before="$(cat "$AISB_DEVTOOLS_FILE")"
run_devtool_server_install; _rc=$?
assert_rc "AC#1 dispatcher over a no-hook-only catalog returns 0" 0 "$_rc"
assert_eq "AC#1 no-hook capability stays enabled, ledger byte-identical (no-op)" \
  "$_ledger_before" "$(cat "$AISB_DEVTOOLS_FILE")"
assert_eq "AC#1 no DISABLED capabilities for a hook-less catalog" "0" "${#AISB_SERVER_INSTALL_DISABLED[@]}"
assert_eq "AC#1 no post-requisite notes for a hook-less catalog" "0" "${#AISB_SERVER_INSTALL_NOTES[@]}"
rm -rf "$_D2"

# ── D3: all-passing catalog — dispatcher-level idempotency across two runs ────
_D3="$(mktemp -d)"
_mk_cap "$_D3" cap_a 'aisb_server_install_note "a"; return 0;'
_mk_cap "$_D3" cap_b 'aisb_server_install_note "b"; return 0;'
AISB_DEVTOOLS_DIR="$_D3"
AISB_DEVTOOLS_FILE="$_D3/.ai-sandbox-devtools"
write_enabled_devtools cap_a cap_b
run_devtool_server_install >/dev/null 2>&1; _rc1=$?
_after1="$(cat "$AISB_DEVTOOLS_FILE")"
run_devtool_server_install >/dev/null 2>&1; _rc2=$?
_after2="$(cat "$AISB_DEVTOOLS_FILE")"
assert_eq "AC#3 dispatcher idempotent: both runs return 0" "0 0" "$_rc1 $_rc2"
assert_eq "AC#3 dispatcher idempotent: ledger byte-identical across two runs" "$_after1" "$_after2"
assert_eq "AC#3 dispatcher resets notes each run (no cross-run accumulation)" "2" "${#AISB_SERVER_INSTALL_NOTES[@]}"
unset AISB_DEVTOOLS_FILE
rm -rf "$_D3"

# ═════════════════════════════════════════════════════════════════════════════
# E — structural wiring (grep-asserted, like uc27's trap checks) + env-gated.
# ═════════════════════════════════════════════════════════════════════════════

# AC#2 — setup.sh runs the new stage after the selector and in --reconfigure,
# and it runs BEFORE the Step 7 first-spawn (which triggers in-container
# devtool_provision). Two call sites: the main flow and the reconfigure branch.
_si_calls="$(grep -c '^[[:space:]]*run_devtool_server_install$' "$SETUP_SH" | tr -d ' ')"
if [ "${_si_calls:-0}" -ge 2 ]; then
  t_ok "AC#2 setup.sh wires the server-install stage in both the main and --reconfigure flows"
else
  t_bad "AC#2 setup.sh expected ≥2 run_devtool_server_install call sites, found ${_si_calls:-0}"
fi
_ln_si="$(grep -n '^[[:space:]]*run_devtool_server_install$' "$SETUP_SH" | tail -1 | cut -d: -f1)"
_ln_step7="$(grep -n 'Step 7:' "$SETUP_SH" | head -1 | cut -d: -f1)"
if [ -n "$_ln_si" ] && [ -n "$_ln_step7" ] && [ "$_ln_si" -lt "$_ln_step7" ]; then
  t_ok "AC#2 the server-install stage runs BEFORE Step 7 (first spawn / devtool_provision)"
else
  t_bad "AC#2 server-install stage is not ordered before Step 7 (si@${_ln_si:-?} step7@${_ln_step7:-?})"
fi

# AC#5 — the end-of-run summary consumes BOTH aggregated globals.
assert_grep "AC#5 print_server_install_summary prints the aggregated notes" \
  'AISB_SERVER_INSTALL_NOTES' "$SETUP_SH"
assert_grep "AC#5 print_server_install_summary prints the DISABLED block" \
  'AISB_SERVER_INSTALL_DISABLED' "$SETUP_SH"

# AC#7 — host-side /dev/fuse hard-gate logic + an actionable reason naming it.
assert_grep "AC#7 dind hook checks host /dev/fuse" '! -e /dev/fuse' "$DIND_MANIFEST"
assert_grep "AC#7 the /dev/fuse hard-gate reason names /dev/fuse" \
  'aisb_server_install_reason.*/dev/fuse' "$DIND_MANIFEST"

# AC#7 — compose override masks /etc/subuid + /etc/subgid with the host files (:ro).
assert_grep "AC#7 compose binds the host subuid file over /etc/subuid (read-only)" \
  '/etc/subuid:ro' "$COMPOSE_DIND"
assert_grep "AC#7 compose binds the host subgid file over /etc/subgid (read-only)" \
  '/etc/subgid:ro' "$COMPOSE_DIND"

# AC#8 (compensating control, structural) — `aisandbox-dind doctor` reports the
# subuid/subgid delegation, matching by resolved NAME *or* numeric uid, with
# present / MISSING / unreadable branches. The nested helper is not independently
# sourceable, so this is a structural assertion; the LIVE doctor/start/selftest
# leg is the operator-sign-off gate below.
assert_grep "AC#8 doctor matches subid by resolved name OR numeric uid" \
  '\^\(\$\{name[^)]*\}\|\$\{uid\}\):' "$DIND_BIN"
assert_grep "AC#8 doctor reports a MISSING delegation"   'MISSING — no range' "$DIND_BIN"
assert_grep "AC#8 doctor reports an unreadable map file" 'unreadable' "$DIND_BIN"
assert_grep "AC#8 doctor reports /dev/fuse accessibility" '/dev/fuse' "$DIND_BIN"

# AC#9 — shellcheck is NOT installed on this host → fall back to `bash -n`
# (syntax-clean) on every changed shell file; flag the lint itself as env-gated.
_synfail=0
for _f in "$REPO_ROOT/lib.sh" "$SERVER_INSTALL_LIB" "$DIND_MANIFEST" "$SETUP_SH" \
          "$REPO_ROOT/entrypoint.sh" "$DIND_BIN" \
          "$DEVTOOLS_D/dind/ensure-host-subid.sh" "$DEVTOOLS_D/dind/repair-state-root.sh"; do
  if bash -n "$_f" 2>/dev/null; then
    t_ok "AC#9 bash -n syntax-clean: ${_f#"$REPO_ROOT"/}"
  else
    t_bad "AC#9 bash -n FAILED: ${_f#"$REPO_ROOT"/}"; _synfail=1
  fi
done
if command -v shellcheck >/dev/null 2>&1; then
  t_skip "AC#9 shellcheck available — run it in CI for the full lint (this harness only did bash -n)"
else
  t_skip "AC#9 shellcheck NOT installed on this host — lint is environment-gated (bash -n used as fallback)"
fi

# AC#8 — LIVE doctor/start/selftest against real Docker + a real /etc subuid
# write requires privilege this unprivileged session user lacks. Operator gate.
t_skip "AC#8 live 'aisandbox-dind doctor/start/selftest' — operator-sign-off gate (needs real Docker + privileged /etc write)"

# ═════════════════════════════════════════════════════════════════════════════
# F — UC-94 repair-state-root.sh: re-owns ONLY the known per-session bind-source
#     name-set (never a blanket sessions/* glob), leaves the UC-62 host-shell
#     siblings + other state dirs untouched, removes the legacy root-owned
#     secrets/dind debris (keeping a non-empty secrets/), and is idempotent +
#     never fatal. chown-to-another-owner needs root, so a PATH-shadowing fake
#     `chown` records WHICH entries the script targets (observable without root).
# ═════════════════════════════════════════════════════════════════════════════
REPAIR_SCRIPT="$DEVTOOLS_D/dind/repair-state-root.sh"
if [ ! -f "$REPAIR_SCRIPT" ]; then
  t_bad "UC-94 repair-state-root.sh missing at $REPAIR_SCRIPT"
else
  _F="$(mktemp -d)"
  _sr="$_F/sessions"
  # Known per-session bind sources (the EXACT repair name-set).
  mkdir -p "$_sr/workspace" "$_sr/workspace-1" "$_sr/claude-config" \
           "$_sr/claude-config-99" "$_sr/claude-projects-5"
  # Siblings that MUST be left untouched (UC-62 host-shell + other state dirs).
  mkdir -p "$_sr/server-ssh-home" "$_sr/docker-config" "$_sr/update-trigger" "$_sr/enrollment"
  : > "$_sr/server-ssh.sock"
  # Legacy root-owned dind debris to be removed; a sibling keeps secrets/ alive.
  mkdir -p "$_sr/secrets/dind"
  : > "$_sr/secrets/other"

  # Fake chown: logs the args it is asked to apply, always succeeds.
  _fakebin="$_F/bin"
  mkdir -p "$_fakebin"
  CHOWN_LOG="$_F/chown.log"; : > "$CHOWN_LOG"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'printf "%%s\\n" "$*" >> "%s"\n' "$CHOWN_LOG"
    printf 'exit 0\n'
  } > "$_fakebin/chown"
  chmod +x "$_fakebin/chown"

  PATH="$_fakebin:$PATH" bash "$REPAIR_SCRIPT" \
      --state-root "$_sr" --owner "testowner:testgroup"; _rc=$?
  assert_rc "AC#5 repair-state-root.sh returns 0 (idempotent, never fatal)" 0 "$_rc"

  _chowned="$(cat "$CHOWN_LOG")"
  # The exact name-set is re-owned.
  assert_contains "AC#5 workspace is re-owned"        "$_chowned" "$_sr/workspace"
  assert_contains "AC#5 workspace-* is re-owned"      "$_chowned" "$_sr/workspace-1"
  assert_contains "AC#5 claude-config is re-owned"    "$_chowned" "$_sr/claude-config"
  assert_contains "AC#5 claude-config-* is re-owned"  "$_chowned" "$_sr/claude-config-99"
  assert_contains "AC#5 claude-projects-* is re-owned" "$_chowned" "$_sr/claude-projects-5"
  # Siblings MUST NOT be touched (narrow scope — never a sessions/* glob).
  assert_not_contains "AC#5 server-ssh.sock left untouched" "$_chowned" "server-ssh.sock"
  assert_not_contains "AC#5 server-ssh-home left untouched" "$_chowned" "server-ssh-home"
  assert_not_contains "AC#5 docker-config left untouched"   "$_chowned" "docker-config"
  assert_not_contains "AC#5 update-trigger left untouched"  "$_chowned" "update-trigger"
  assert_not_contains "AC#5 enrollment left untouched"      "$_chowned" "enrollment"
  # Legacy dind debris removed; non-empty secrets/ preserved (rmdir refuses it).
  if [ ! -e "$_sr/secrets/dind" ]; then
    t_ok "AC#5 legacy secrets/dind debris removed"
  else
    t_bad "AC#5 legacy secrets/dind debris NOT removed"
  fi
  if [ -e "$_sr/secrets/other" ]; then
    t_ok "AC#5 non-empty secrets/ preserved (unrelated sibling content kept)"
  else
    t_bad "AC#5 secrets/ wrongly removed while it still held unrelated content"
  fi
  # Repair re-owns, never DELETES a matched entry.
  if [ -d "$_sr/workspace" ] && [ -d "$_sr/claude-projects-5" ]; then
    t_ok "AC#5 matched entries still present after repair (re-own, not delete)"
  else
    t_bad "AC#5 a matched per-session entry was deleted by repair"
  fi

  # Never fatal: missing --state-root, or an absent state-root dir → exit 0.
  bash "$REPAIR_SCRIPT" --owner "x:y"; assert_rc "AC#5 no --state-root arg → exit 0 (never fatal)" 0 $?
  bash "$REPAIR_SCRIPT" --state-root "$_F/does-not-exist" --owner "x:y"
  assert_rc "AC#5 absent state-root dir → exit 0 (never fatal)" 0 $?

  # Idempotent: a second run over an already-repaired tree still returns 0 and
  # leaves the siblings + preserved secrets/ intact.
  : > "$CHOWN_LOG"
  PATH="$_fakebin:$PATH" bash "$REPAIR_SCRIPT" \
      --state-root "$_sr" --owner "testowner:testgroup"; _rc=$?
  assert_rc "AC#5 second repair run is idempotent (rc 0)" 0 "$_rc"

  rm -rf "$_F"
fi

# ── summary ──────────────────────────────────────────────────────────────────
printf '\n──────────────────────────────────────────────\n'
printf 'uc30-server-install-unit: %d passed, %d failed, %d skipped\n' "$_pass" "$_fail" "$_skip"
[ "$_fail" -eq 0 ] || exit 1
exit 0
