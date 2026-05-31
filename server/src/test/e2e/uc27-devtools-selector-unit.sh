#!/usr/bin/env bash
# uc27-devtools-selector-unit.sh — UC-27 plain-sh unit harness for the dev-tools
# capability selector's pure logic (no Docker, no real raw-mode TTY required).
#
# Shell-side complement to the JUnit coverage of DevToolsConfig / DevToolsStep.
# It exercises the lib.sh + devtools-ui.sh functions that make the SHELL the
# single source of truth (AC#4) and that the Java side only delegates to. Every
# check is hermetic: it sources the libraries and asserts function behaviour, or
# runs the standalone controller with stdin redirected from /dev/null.
#
#   lib.sh:
#     devtool_catalog_ids       AC#2/#6  manifest auto-discovery, sorted order
#     devtool_label             AC#9     version-bearing labels (no drift)
#     devtool_warning           AC#3     dind inline trust-boundary warning
#     devtool_depends_on        AC#6     android DEPENDS_ON java
#     devtool_deps_transitive   AC#5     transitive dependency closure
#     devtool_dependents_among  AC#5     cascade detection (who depends on X)
#     devtool_is_available      AC#11    Android amd64-only arch gate
#     write/read_enabled_devtools AC#7   single ledger, byte-stable catalog order
#   devtools-ui.sh:
#     _dtui_confirm             AC#5     cascade-deselect confirm / decline
#     _dtui_term_restore        pitfall  raw-mode terminal-state restore
#     (trap registration)       pitfall  restore on every exit path
#   devtools-select.sh:
#     non-TTY refusal           AC#1     pure raw-mode needs a terminal (exit 3)
#
# NOT a live-Docker gate (those are G1/G2, run separately). Plain bash — no bats
# (per the brief / team-lead decision). Prints PASS/FAIL per check + a summary;
# exits non-zero if any check fails.
#
# UC-27. POSIX-bash; Linux-only.
set -uo pipefail

# ── locate the scripts under test ────────────────────────────────────────────
# The selector + libs live at the repo ROOT (setup.sh's siblings): lib.sh,
# devtools-ui.sh, devtools-select.sh, devtools.d/.
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HARNESS_DIR/../../../.." && pwd)"
DEVTOOLS_D="$REPO_ROOT/devtools.d"
SELECTOR="$REPO_ROOT/devtools-select.sh"

# ── tiny assertion framework ─────────────────────────────────────────────────
# NOTE: helpers are prefixed `t_` because lib.sh (sourced below) defines its own
# `ok`/`info`/`warn`; an unprefixed `ok()` here would be silently overridden by
# lib.sh's, making every assertion a no-op (0 passed / 0 failed).
_pass=0
_fail=0
t_ok()  { printf '\033[1;32mPASS\033[0m %s\n' "$*"; _pass=$((_pass + 1)); }
t_bad() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; _fail=$((_fail + 1)); }

assert_eq() { # <desc> <expected> <actual>
  if [ "$2" = "$3" ]; then t_ok "$1"; else t_bad "$1 — expected [$2], got [$3]"; fi
}
assert_contains() { # <desc> <haystack> <needle>
  case "$2" in *"$3"*) t_ok "$1" ;; *) t_bad "$1 — [$2] does not contain [$3]" ;; esac
}
assert_rc() { # <desc> <expected-rc> <actual-rc>
  if [ "$2" -eq "$3" ]; then t_ok "$1"; else t_bad "$1 — expected rc $2, got $3"; fi
}

# ── preconditions ────────────────────────────────────────────────────────────
[ -f "$REPO_ROOT/lib.sh" ]         || { t_bad "lib.sh missing";          exit 1; }
[ -f "$REPO_ROOT/devtools-ui.sh" ] || { t_bad "devtools-ui.sh missing";  exit 1; }
[ -f "$SELECTOR" ]                 || { t_bad "devtools-select.sh missing"; exit 1; }
[ -d "$DEVTOOLS_D" ]               || { t_bad "devtools.d missing";        exit 1; }

# Source from the repo root so lib.sh resolves AISB_DEVTOOLS_DIR to the real
# devtools.d (it derives it from its own location).
cd "$REPO_ROOT"
# shellcheck source=/dev/null
. "$REPO_ROOT/lib.sh"
# shellcheck source=/dev/null
. "$REPO_ROOT/devtools-ui.sh"

# ─────────────────────────────────────────────────────────────────────────────
# AC#2 / AC#6 — manifest auto-discovery: the catalog is exactly the three
# shipped capabilities, emitted in byte-stable (sorted) order, populated from
# the directory (no hardcoded list).
# ─────────────────────────────────────────────────────────────────────────────
assert_eq "AC#2/#6 catalog discovers exactly the 3 shipped capabilities, sorted" \
  "android dind java" "$(devtool_catalog_ids | tr '\n' ' ' | sed 's/ $//')"

# AC#2 — discovery is data-driven: a drop-in manifest dir is picked up with no
# code edits. Point AISB_DEVTOOLS_DIR at a synthetic catalog and re-query.
_tmpcat="$(mktemp -d)"
mkdir -p "$_tmpcat/zzz"
cat >"$_tmpcat/zzz/manifest.sh" <<'EOF'
ID="zzz"
LABEL="Synthetic capability"
DEPENDS_ON=()
APPLY_AT="session-spawn"
ARCH="any"
EOF
assert_eq "AC#2 a drop-in manifest dir is auto-discovered with no code edits" \
  "zzz" "$(AISB_DEVTOOLS_DIR="$_tmpcat" devtool_catalog_ids | tr '\n' ' ' | sed 's/ $//')"
rm -rf "$_tmpcat"

# ─────────────────────────────────────────────────────────────────────────────
# AC#9 — version-bearing labels (label sourced from the same constants the
# install uses, so label and install can never drift).
# ─────────────────────────────────────────────────────────────────────────────
assert_contains "AC#9 java label is version-bearing"            "$(devtool_label java)"    "Java 21"
assert_contains "AC#9 android label carries build-tools version" "$(devtool_label android)" "36.0.0"
assert_contains "AC#9 android label carries the platform"        "$(devtool_label android)" "android-36"
assert_contains "AC#2 dind label is self-describing"             "$(devtool_label dind)"    "rootless"

# ─────────────────────────────────────────────────────────────────────────────
# AC#3 — dind carries the inline trust-boundary warning.
# ─────────────────────────────────────────────────────────────────────────────
assert_contains "AC#3 dind warning names the trust boundary" "$(devtool_warning dind)" "trust boundary"

# ─────────────────────────────────────────────────────────────────────────────
# AC#6 — dependency wiring: android DEPENDS_ON java; java/dind standalone.
# ─────────────────────────────────────────────────────────────────────────────
assert_eq "AC#6 android DEPENDS_ON java" "java" "$(devtool_depends_on android | tr '\n' ' ' | sed 's/ $//')"
assert_eq "AC#6 java has no deps"        ""     "$(devtool_depends_on java | tr -d '[:space:]')"
assert_eq "AC#6 dind has no deps"        ""     "$(devtool_depends_on dind | tr -d '[:space:]')"

# ─────────────────────────────────────────────────────────────────────────────
# AC#5 — dependency resolution: selecting android pulls in java transitively;
# resolving a standalone capability adds nothing.
# ─────────────────────────────────────────────────────────────────────────────
assert_eq "AC#5 selecting android auto-selects its java dependency" \
  "java" "$(devtool_deps_transitive android | tr '\n' ' ' | sed 's/ $//')"
assert_eq "AC#5 java resolves to no extra deps" "" "$(devtool_deps_transitive java | tr -d '[:space:]')"

# ─────────────────────────────────────────────────────────────────────────────
# AC#5 — cascade detection: among the selected set {java, android}, disabling
# java surfaces android as a dependent; nothing depends on android.
# ─────────────────────────────────────────────────────────────────────────────
assert_eq "AC#5 dependents_among java {java android} = android" \
  "android" "$(devtool_dependents_among java java android | tr '\n' ' ' | sed 's/ $//')"
assert_eq "AC#5 dependents_among android {java android} = (none)" \
  "" "$(devtool_dependents_among android java android | tr -d '[:space:]')"

# ─────────────────────────────────────────────────────────────────────────────
# AC#5 — cascade CONFIRM / DECLINE: _dtui_confirm returns 0 only on y/Y; a
# decline (n / bare Enter) returns non-zero so the toggle is a no-op. The prompt
# reads ONE key from stdin.
# ─────────────────────────────────────────────────────────────────────────────
printf 'y' | _dtui_confirm "Disable? [y/N]" >/dev/null 2>&1; assert_rc "AC#5 cascade confirm: 'y' CONFIRMS (rc==0)" 0 "$?"
printf 'Y' | _dtui_confirm "Disable? [y/N]" >/dev/null 2>&1; assert_rc "AC#5 cascade confirm: 'Y' CONFIRMS (rc==0)" 0 "$?"
printf 'n' | _dtui_confirm "Disable? [y/N]" >/dev/null 2>&1; assert_rc "AC#5 cascade confirm: 'n' DECLINES (rc!=0)" 1 "$?"
printf '\n' | _dtui_confirm "Disable? [y/N]" >/dev/null 2>&1; assert_rc "AC#5 cascade confirm: bare Enter DECLINES (rc!=0)" 1 "$?"

# ─────────────────────────────────────────────────────────────────────────────
# AC#11 — Android is amd64-only. devtool_is_available compares the manifest ARCH
# to aisb_host_arch; override the host-arch probe deterministically so the result
# does not depend on the CI runner's real architecture.
# ─────────────────────────────────────────────────────────────────────────────
aisb_host_arch() { printf 'amd64'; }
devtool_is_available android; assert_rc "AC#11 android available on amd64"            0 "$?"
devtool_is_available java;    assert_rc "AC#11 java (ARCH=any) available on amd64"    0 "$?"
aisb_host_arch() { printf 'arm64'; }
devtool_is_available android; assert_rc "AC#11 android UNAVAILABLE on arm64 (amd64-only)" 1 "$?"
devtool_is_available java;    assert_rc "AC#11 java (ARCH=any) still available on arm64"  0 "$?"
# Restore the real probe for any later checks.
aisb_host_arch() {
    case "$(uname -m)" in
        x86_64|amd64)  printf 'amd64' ;;
        aarch64|arm64) printf 'arm64' ;;
        *)             uname -m ;;
    esac
}

# ─────────────────────────────────────────────────────────────────────────────
# AC#7 — single ledger: write/read round-trips; output is `<id>\t<apply_at>`,
# byte-stable in CATALOG (sorted) order regardless of argument order. This is
# the "catalog-dump format stability" guarantee.
# ─────────────────────────────────────────────────────────────────────────────
_leddir="$(mktemp -d)"
export AISB_DEVTOOLS_FILE="$_leddir/.ai-sandbox-devtools"

# Input order scrambled; output MUST be catalog (sorted) order: android,dind,java.
write_enabled_devtools java android dind
_expected="$(printf 'android\tsession-spawn\ndind\tsession-spawn\njava\tsession-spawn')"
assert_eq "AC#7 ledger written in byte-stable catalog order with apply_at column" \
  "$_expected" "$(cat "$AISB_DEVTOOLS_FILE")"

# Round-trip: read_enabled emits the ids (first column), file order.
assert_eq "AC#7 read_enabled round-trips the persisted ids" \
  "android dind java" "$(read_enabled_devtools | tr '\n' ' ' | sed 's/ $//')"

# Re-writing the same set is byte-identical (deterministic dump).
_first="$(cat "$AISB_DEVTOOLS_FILE")"
write_enabled_devtools dind java android
assert_eq "AC#7 re-writing the same set is byte-identical" "$_first" "$(cat "$AISB_DEVTOOLS_FILE")"

# Empty selection truncates to zero bytes (no-cap sessions byte-identical today).
write_enabled_devtools
assert_eq "AC#12 empty selection truncates the ledger to zero bytes" "0" "$(wc -c <"$AISB_DEVTOOLS_FILE" | tr -d ' ')"
assert_eq "AC#12 read of an empty ledger yields nothing" "" "$(read_enabled_devtools | tr -d '[:space:]')"

# read tolerates comments / blank lines / surrounding whitespace.
printf '# header\n\n  android  session-spawn  \n# tail\n' >"$AISB_DEVTOOLS_FILE"
assert_eq "AC#7 read tolerates comments + blank lines + whitespace" \
  "android" "$(read_enabled_devtools | tr -d '[:space:]')"
unset AISB_DEVTOOLS_FILE
rm -rf "$_leddir"

# ─────────────────────────────────────────────────────────────────────────────
# AC#1 / pitfall — non-TTY refusal: the raw-mode selector cannot run without a
# terminal. Running the controller with stdin from /dev/null MUST exit 3 and
# leave the ledger untouched (no silent write on a headless invocation).
# ─────────────────────────────────────────────────────────────────────────────
_nttydir="$(mktemp -d)"
_nttyled="$_nttydir/.ai-sandbox-devtools"
bash "$SELECTOR" "$_nttyled" </dev/null >/dev/null 2>&1
assert_rc "AC#1 non-TTY invocation exits 3 (cannot run raw-mode picker)" 3 "$?"
if [ -e "$_nttyled" ]; then
  t_bad "AC#1 non-TTY invocation MUST NOT write the ledger"
else
  t_ok "AC#1 non-TTY invocation leaves the ledger untouched"
fi
rm -rf "$_nttydir"

# ─────────────────────────────────────────────────────────────────────────────
# Pitfall (raw-mode robustness) — terminal-state restore is idempotent and
# structurally wired (EXIT/INT/TERM trap + stty save/restore), so the cursor UI
# cannot leak a broken terminal on any exit path.
# ─────────────────────────────────────────────────────────────────────────────
_DTUI_STTY_SAVE=""
_dtui_term_restore >/dev/null 2>&1; assert_rc "restore is a safe no-op with no saved stty" 0 "$?"
_dtui_term_restore >/dev/null 2>&1; assert_rc "restore is idempotent (second call also safe)" 0 "$?"

# The selector traps INT/TERM to restore on signal, and calls _dtui_term_restore
# unconditionally after the input loop (covering commit / cancel / EOF). Both
# halves together guarantee restore on every exit path.
if grep -Eq "trap '_dtui_term_restore;? *exit 130' INT TERM" "$REPO_ROOT/devtools-ui.sh"; then
  t_ok "pitfall: UI traps INT/TERM to restore the terminal on signal"
else
  t_bad "pitfall: UI is missing the INT/TERM restore trap"
fi
if grep -q 'stty -g' "$REPO_ROOT/devtools-ui.sh" && grep -q 'stty sane' "$REPO_ROOT/devtools-ui.sh"; then
  t_ok "pitfall: UI saves (stty -g) and restores (stty / stty sane) the prior terminal state"
else
  t_bad "pitfall: UI does not save/restore stty state"
fi

# ── summary ──────────────────────────────────────────────────────────────────
printf '\n──────────────────────────────────────────────\n'
printf 'uc27-devtools-selector-unit: %d passed, %d failed\n' "$_pass" "$_fail"
[ "$_fail" -eq 0 ] || exit 1
exit 0
