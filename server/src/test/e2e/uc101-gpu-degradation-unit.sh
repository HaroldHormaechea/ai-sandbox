#!/usr/bin/env bash
# uc101-gpu-degradation-unit.sh — UC-101 plain-sh unit harness for the host
# NVIDIA GPU (CUDA) passthrough SHELL logic (no Docker, no real GPU required).
#
# Complement to the PRIMARY CI gate (the JUnit fake-shim test
# HostScriptGpuPassthroughTest, run under :server:test). This harness is a
# standalone bash complement in the UC-26/27/30/95 e2e tradition — it is NOT
# auto-wired into CI; run it manually:
#
#   bash server/src/test/e2e/uc101-gpu-degradation-unit.sh
#
# It exercises, hermetically (sourcing the real lib.sh + running the real
# container-bin/aisandbox-gpu with env knobs, fake nvidia-smi / fake CDI spec /
# fake uname shims):
#
#   devtools.d/gpu/manifest.sh (via lib.sh):
#     devtool_catalog_ids       AC#2/#4  gpu is an auto-discovered capability
#     devtool_label gpu         AC#9     version-bearing CUDA label (no drift)
#     devtool_arch  gpu         AC#4/#11 amd64-only gate (CUDA toolkit x86_64)
#     devtool_warning gpu       AC#10    trust-boundary WARNING present
#   lib.sh host detection + injector:
#     host_gpu_disabled         AC#10    env + sentinel-file kill switch
#     host_gpu_available        AC#1/#9  Linux + driver + CDI gate
#     inject_host_gpu_passthrough AC#1/#5/#9/#10  status + override layering
#   container-bin/aisandbox-gpu:
#     doctor                    AC#5     graceful degradation — names each
#                                        missing prerequisite, never crashes
#
# NOT a live-GPU gate: AC#1 (nvidia-smi), AC#2 (CUDA compute), AC#3 (nested
# docker --gpus) need real NVIDIA hardware and are owner-verify-on-a-GPU-host.
# Prints PASS/FAIL per check + a summary; exits non-zero if any check fails.
#
# UC-101. POSIX-bash; Linux-only.
set -uo pipefail

# ── locate the scripts under test ────────────────────────────────────────────
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HARNESS_DIR/../../../.." && pwd)"
GPU_HELPER="$REPO_ROOT/container-bin/aisandbox-gpu"
GPU_OVERRIDE="$REPO_ROOT/docker-compose.gpu.yml"

# ── tiny assertion framework (t_-prefixed; lib.sh defines ok/info/warn) ───────
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
[ -f "$REPO_ROOT/lib.sh" ] || { t_bad "lib.sh missing"; exit 1; }
[ -f "$GPU_HELPER" ]       || { t_bad "container-bin/aisandbox-gpu missing"; exit 1; }
[ -f "$GPU_OVERRIDE" ]     || { t_bad "docker-compose.gpu.yml missing"; exit 1; }

# Source from the repo root so lib.sh resolves AISB_DEVTOOLS_DIR to the real
# devtools.d (derived from its own location) and the injector finds the override
# in cwd when AI_SANDBOX_COMPOSE_FILE is unset.
cd "$REPO_ROOT"
# shellcheck source=/dev/null
. "$REPO_ROOT/lib.sh"

# ── shared fakes ─────────────────────────────────────────────────────────────
# NOTE: the Darwin `uname` lives in its OWN dir (_MAC_BIN), NOT in _FAKE_BIN —
# otherwise prepending _FAKE_BIN for the GPU-present cases would shadow the real
# uname and make every host look non-Linux (host_gpu_available's first gate).
_FAKE_BIN="$(mktemp -d)"   # fake nvidia-smi only → flips host_gpu_driver_present true.
printf '#!/bin/sh\nexit 0\n' > "$_FAKE_BIN/nvidia-smi"; chmod 0755 "$_FAKE_BIN/nvidia-smi"
_MAC_BIN="$(mktemp -d)"    # fake uname printing Darwin, honouring -s; delegates otherwise.
printf '#!/bin/sh\nif [ "$1" = "-s" ]; then echo Darwin; exit 0; fi\nexec /usr/bin/uname "$@"\n' \
  > "$_MAC_BIN/uname"; chmod 0755 "$_MAC_BIN/uname"
# Fake CDI dir holding an nvidia.com/gpu spec (what detection greps for).
_CDI_DIR="$(mktemp -d)"
printf 'cdiVersion: "0.6.0"\nkind: "nvidia.com/gpu"\n' > "$_CDI_DIR/nvidia.yaml"
_EMPTY_CDI="$(mktemp -d)"   # exists but holds no spec
_NO_SENTINEL="$_FAKE_BIN/does-not-exist-kill-switch"

cleanup() { rm -rf "$_FAKE_BIN" "$_MAC_BIN" "$_CDI_DIR" "$_EMPTY_CDI"; }
trap cleanup EXIT

# ─────────────────────────────────────────────────────────────────────────────
# AC#2 / AC#4 — the `gpu` capability is auto-discovered in the catalog.
# ─────────────────────────────────────────────────────────────────────────────
assert_contains "AC#2/#4 catalog auto-discovers the gpu capability" \
  " $(devtool_catalog_ids | tr '\n' ' ')" " gpu "

# AC#9 — version-bearing label (sourced from AISB_CUDA_VERSION → no drift).
_label="$(devtool_label gpu)"
assert_contains "AC#9 gpu label mentions CUDA" "$_label" "CUDA"
if printf '%s' "$_label" | grep -Eq '[0-9]+\.[0-9]+'; then
  t_ok "AC#9 gpu label carries a version number (no drift from versions.sh)"
else
  t_bad "AC#9 gpu label is not version-bearing — got [$_label]"
fi

# AC#4 / AC#11 — amd64-only gate (CUDA toolkit is x86_64-only).
assert_eq "AC#4/#11 gpu capability declares ARCH=amd64" "amd64" "$(devtool_arch gpu)"

# AC#10 — trust-boundary WARNING present and names the host-wide tradeoff.
_warn="$(devtool_warning gpu)"
if [ -n "$_warn" ]; then
  t_ok "AC#10 gpu capability ships a trust-boundary WARNING"
else
  t_bad "AC#10 gpu capability has an empty WARNING"
fi
assert_contains "AC#10 WARNING names the host-wide exposure tradeoff" "$_warn" "host-wide"

# ─────────────────────────────────────────────────────────────────────────────
# AC#10 — host kill switch: engaged by EITHER the env override OR a sentinel.
# ─────────────────────────────────────────────────────────────────────────────
# Run each predicate inside a subshell that inherits the sourced functions so the
# exported env never leaks into the next check.
if ( export AISB_GPU_KILL_SWITCH_FILE="$_NO_SENTINEL" AI_SANDBOX_GPU_DISABLED=1; host_gpu_disabled ); then
  t_ok "AC#10 host_gpu_disabled true via env AI_SANDBOX_GPU_DISABLED=1"
else
  t_bad "AC#10 host_gpu_disabled should be true with AI_SANDBOX_GPU_DISABLED=1"
fi

_sentinel="$(mktemp)"
if ( export AISB_GPU_KILL_SWITCH_FILE="$_sentinel"; unset AI_SANDBOX_GPU_DISABLED; host_gpu_disabled ); then
  t_ok "AC#10 host_gpu_disabled true via sentinel file existence"
else
  t_bad "AC#10 host_gpu_disabled should be true when the sentinel file exists"
fi
rm -f "$_sentinel"

if ( export AISB_GPU_KILL_SWITCH_FILE="$_NO_SENTINEL"; unset AI_SANDBOX_GPU_DISABLED; host_gpu_disabled ); then
  t_bad "AC#10 host_gpu_disabled should be FALSE with no env + no sentinel"
else
  t_ok "AC#10 host_gpu_disabled false with switch off (no env, no sentinel)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# AC#1 / AC#9 — host_gpu_available: Linux AND driver AND CDI spec.
# ─────────────────────────────────────────────────────────────────────────────
if ( export PATH="$_FAKE_BIN:$PATH" AISB_GPU_CDI_DIRS="$_CDI_DIR"; host_gpu_available ); then
  t_ok "AC#1 host_gpu_available true with fake driver + CDI spec on Linux"
else
  t_bad "AC#1 host_gpu_available should be true with driver + CDI present"
fi

if ( export PATH="$_FAKE_BIN:$PATH" AISB_GPU_CDI_DIRS="$_EMPTY_CDI"; host_gpu_available ); then
  t_bad "AC#5 host_gpu_available should be FALSE with driver but no CDI spec"
else
  t_ok "AC#5 host_gpu_available false with driver present but no CDI spec"
fi

# No driver at all (minimal PATH without the fake nvidia-smi) → unavailable.
if ( export PATH="/usr/bin:/bin" AISB_GPU_CDI_DIRS="$_CDI_DIR"; host_gpu_available ); then
  t_bad "AC#5 host_gpu_available should be FALSE with no driver"
else
  t_ok "AC#5 host_gpu_available false with no NVIDIA driver present"
fi

# ─────────────────────────────────────────────────────────────────────────────
# AC#1/#5/#9/#10 — inject_host_gpu_passthrough status machine + override layering.
# Each scenario runs in a subshell so the exported env/override never leaks.
# ─────────────────────────────────────────────────────────────────────────────
# GPU present + switch off → status on, AI_SANDBOX_GPU=1, override layered.
_on="$(
  export PATH="$_FAKE_BIN:$PATH" AISB_GPU_CDI_DIRS="$_CDI_DIR" AISB_GPU_KILL_SWITCH_FILE="$_NO_SENTINEL"
  unset AI_SANDBOX_COMPOSE_FILE AI_SANDBOX_GPU AI_SANDBOX_EXTRA_COMPOSE_FILES
  inject_host_gpu_passthrough
  printf '%s|%s|%s' "${AI_SANDBOX_GPU_STATUS:-}" "${AI_SANDBOX_GPU:-}" "${AI_SANDBOX_EXTRA_COMPOSE_FILES:-}"
)"
assert_eq "AC#1 injector status=on when GPU present + switch off" "on" "${_on%%|*}"
_on_rest="${_on#*|}"
assert_eq "AC#1 injector exports AI_SANDBOX_GPU=1" "1" "${_on_rest%%|*}"
assert_contains "AC#1 injector layers docker-compose.gpu.yml" "${_on##*|}" "docker-compose.gpu.yml"

# Kill switch engaged (env) on a GPU host → status disabled, no override.
_dis="$(
  export PATH="$_FAKE_BIN:$PATH" AISB_GPU_CDI_DIRS="$_CDI_DIR" AI_SANDBOX_GPU_DISABLED=1
  unset AI_SANDBOX_COMPOSE_FILE AI_SANDBOX_GPU AI_SANDBOX_EXTRA_COMPOSE_FILES
  inject_host_gpu_passthrough
  printf '%s|%s|%s' "${AI_SANDBOX_GPU_STATUS:-}" "${AI_SANDBOX_GPU:-}" "${AI_SANDBOX_EXTRA_COMPOSE_FILES:-}"
)"
assert_eq "AC#10 injector status=disabled when kill switch engaged on a GPU host" "disabled" "${_dis%%|*}"
_dis_rest="${_dis#*|}"
assert_eq "AC#10 injector exports no AI_SANDBOX_GPU when disabled" "" "${_dis_rest%%|*}"
assert_eq "AC#10 injector layers no override when disabled" "" "${_dis##*|}"

# Driver present but no CDI spec → distinct driver-no-cdi status, no override.
_nocdi="$(
  export PATH="$_FAKE_BIN:$PATH" AISB_GPU_CDI_DIRS="$_EMPTY_CDI" AISB_GPU_KILL_SWITCH_FILE="$_NO_SENTINEL"
  unset AI_SANDBOX_COMPOSE_FILE AI_SANDBOX_GPU AI_SANDBOX_EXTRA_COMPOSE_FILES
  inject_host_gpu_passthrough
  printf '%s|%s' "${AI_SANDBOX_GPU_STATUS:-}" "${AI_SANDBOX_EXTRA_COMPOSE_FILES:-}"
)"
assert_eq "AC#5/#7 injector status=driver-no-cdi when driver present but no spec" "driver-no-cdi" "${_nocdi%%|*}"
assert_eq "AC#5 injector layers no override without a CDI spec" "" "${_nocdi##*|}"

# No GPU at all → status none, no override (byte-identical-to-today no-op).
_none="$(
  export PATH="/usr/bin:/bin" AISB_GPU_CDI_DIRS="$_EMPTY_CDI" AISB_GPU_KILL_SWITCH_FILE="$_NO_SENTINEL"
  unset AI_SANDBOX_COMPOSE_FILE AI_SANDBOX_GPU AI_SANDBOX_EXTRA_COMPOSE_FILES
  inject_host_gpu_passthrough
  printf '%s|%s' "${AI_SANDBOX_GPU_STATUS:-}" "${AI_SANDBOX_EXTRA_COMPOSE_FILES:-}"
)"
assert_eq "AC#5 injector status=none with no GPU" "none" "${_none%%|*}"
assert_eq "AC#5 injector is a no-op (no override) with no GPU" "" "${_none##*|}"

# AC#9 — non-Linux host (fake uname → Darwin) → status none, no override.
_mac="$(
  export PATH="$_MAC_BIN:$_FAKE_BIN:$PATH" AISB_GPU_CDI_DIRS="$_CDI_DIR" AISB_GPU_KILL_SWITCH_FILE="$_NO_SENTINEL"
  unset AI_SANDBOX_COMPOSE_FILE AI_SANDBOX_GPU AI_SANDBOX_EXTRA_COMPOSE_FILES
  inject_host_gpu_passthrough
  printf '%s|%s' "${AI_SANDBOX_GPU_STATUS:-}" "${AI_SANDBOX_EXTRA_COMPOSE_FILES:-}"
)"
assert_eq "AC#9 injector status=none on a non-Linux host (fake uname Darwin)" "none" "${_mac%%|*}"
assert_eq "AC#9 injector lays down no override on a non-Linux host" "" "${_mac##*|}"

# ─────────────────────────────────────────────────────────────────────────────
# AC#5 — aisandbox-gpu doctor: graceful degradation reporter. On a non-GPU host
# it MUST name each missing prerequisite and exit 1 (never crash).
# ─────────────────────────────────────────────────────────────────────────────
_cache="$(mktemp -d)"
_doc_out="$( AISB_GPU_CACHE="$_cache" PATH="/usr/bin:/bin" "$GPU_HELPER" doctor 2>&1 )"
_doc_rc=$?
assert_rc "AC#5 doctor exits 1 (degraded) on a non-GPU host" 1 "$_doc_rc"
assert_contains "AC#5 doctor names the missing CUDA userspace" "$_doc_out" "CUDA userspace NOT installed"
assert_contains "AC#5 doctor names the missing GPU device nodes" "$_doc_out" "no GPU device nodes"
assert_contains "AC#5 doctor names the missing host-wide passthrough signal" "$_doc_out" "AI_SANDBOX_GPU not set"
# Degradation is reported, not a crash: the tail summary line is present.
assert_contains "AC#5 doctor ends with an actionable NOT-usable summary (no crash)" "$_doc_out" "NOT fully usable"
rm -rf "$_cache"

# ── summary ──────────────────────────────────────────────────────────────────
printf '\n──────────────────────────────────────────────\n'
printf 'uc101-gpu-degradation-unit: %d passed, %d failed\n' "$_pass" "$_fail"
[ "$_fail" -eq 0 ] || exit 1
exit 0
