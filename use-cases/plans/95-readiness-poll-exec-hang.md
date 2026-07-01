---
plan_for: use-cases/95-readiness-poll-exec-hang.md
work_branch: feat/uc-95-readiness-poll-exec-hang
team: ai-sandbox-uc-95
approved: 2026-07-01
---

# UC-95 — Approved Implementation Plan: readiness poll must not wedge on a hung `docker compose exec`

> Analyst↔challenger approved (challenger verdict: **Approve** with 3 should-fix recommendations, folded in below). Pure-Bash fix to `spawn.sh` + `lib.sh`; Java profiles N/A.

## Root cause (verified)
`spawn.sh` lines 279–297 poll for `/tmp/aisandbox-ready` via `ai_sandbox_compose -p "$PROJECT" exec -T claude-sandbox test -f /tmp/aisandbox-ready` inside `while [ "$tries" -lt 600 ]; … sleep 2`. The probe has **no per-call timeout**, so a single hung `docker compose exec` blocks the `if`-condition forever — `tries` never increments, the loop never re-probes, and the 600 cap never applies. `ai_sandbox_compose` (lib.sh 272–294) is a shell **function** ending in `docker compose "${flags[@]}" "$@"`.

## Solution

**A. `lib.sh` — per-probe helper `ai_sandbox_ready_probe <project>`.** CRITICAL: `timeout` execs a binary and CANNOT wrap the `ai_sandbox_compose` function — it must wrap the `docker` binary directly. Run:
`timeout --kill-after="${AISB_READY_PROBE_KILL_GRACE:-3s}" "${AISB_READY_PROBE_TIMEOUT:-12s}" docker compose <flags> -p "$PROJECT" exec -T claude-sandbox test -f /tmp/aisandbox-ready >/dev/null 2>&1`.
Return the probe rc (0 = ready; any non-zero incl. 124 = keep polling). `--kill-after` guarantees SIGKILL so no stray `docker` client outlives the poll (AC#6). Per-probe timeout env-tunable (default ~12s, in the 10–15s window).
- **Flag assembly (challenger rec #2):** factor `ai_sandbox_compose`'s flag-building (base `-f`, extra `-f` overrides, `--project-directory`) into a SHARED helper that BOTH `ai_sandbox_compose` and `ai_sandbox_ready_probe` call, so `ai_sandbox_compose` stays byte-identical for its 4 other callers (clean.sh, lifecycle.sh, attach.sh, spawn.sh:265). Prefer shared helper over duplication.
- **`timeout`-absent fallback (challenger rec #1 — honest wording):** if `command -v timeout` fails, fall back to a direct `docker compose … exec …` (today's behavior) with a **one-time warn**. State plainly in the comment that this degrades to **today's behavior — NOT a guaranteed bound** (a truly-hung probe still blocks, since the wall-clock deadline is only checked between iterations). `timeout` IS present on the install host (uutils coreutils 0.8.0, verified), so this path is defensive-only.

**B. `lib.sh` — wall-clock wait `ai_sandbox_wait_devtools_ready <project>`.** `local start=$SECONDS; local deadline=$((start + ${AISB_READY_DEADLINE_SECS:-1200}))` (1200s preserves today's ~20-min bound). Loop `while [ "$SECONDS" -lt "$deadline" ]; do if ai_sandbox_ready_probe "$PROJECT"; then return 0; fi; sleep "${AISB_READY_PROBE_INTERVAL:-2}"; done; return 1`. Fast path preserved byte-for-byte: a successful first probe returns 0 immediately with no sleep (AC#3/#5). Probe stays inside the `if` so rc 124 never trips `set -e`. The `600`/`tries` counter is removed entirely.

**C. `spawn.sh` call site (lines ~281–296).** Replace the inline `ready=0; tries=0; while … done` block with:
```
if ai_sandbox_wait_devtools_ready "$PROJECT"; then
    ok "Devtools provisioned; session ready."
else
    warn "Session started but the readiness marker was not seen within ~20 min — provisioning may still be running or may have failed."
    warn "Check inside the session: ./attach.sh --session $N  then run \`aisandbox-<capability> doctor\`."
fi
```
The `info "  devtools      : waiting for in-container provisioning to finish…"` (line 280) and both `ok`/`warn` texts stay EXACTLY as today (AC#4); the enclosing `if [ -n "${AI_SANDBOX_DEVTOOLS:-}" ]` guard is untouched (AC#5 — devtools-disabled never enters the loop).

## Files Affected
**Production (developer):** `lib.sh` (shared flag-assembly helper + `ai_sandbox_ready_probe` + `ai_sandbox_wait_devtools_ready`), `spawn.sh` (call-site replacement; surrounding text unchanged). Run `shellcheck` on both.
**Test (QA):** NEW `server/src/test/e2e/uc95-readiness-poll-unit.sh` — plain-bash harness matching uc30 conventions (`set -uo pipefail`, BASH_SOURCE-relative root, t_ok/t_fail/t_skip, PASS/FAIL summary, non-zero exit on fail); operator/QA-gated, NOT wired into server-ci.yml. Deterministic strategy: PATH-shadow a fake `docker` with a call-counter (first K calls `sleep 30` → real `timeout` kills → rc 124; from call K → marker-present rc 0) + tiny env timeouts (`AISB_READY_PROBE_TIMEOUT=2s`, `_KILL_GRACE=1s`, `_INTERVAL=1`, `_DEADLINE_SECS=8`). Cases: AC#7 BEFORE (reconstruct the OLD no-timeout `if`-blocks loop — keep it **structurally faithful**, challenger rec #3 — vs the same fake, outer `timeout 6 bash -c` → rc 124 = wedged) / AFTER (fixed fn returns 0); AC#1 loop-advances (elapsed ≈ K×timeout); AC#2 always-hang fake → returns 1 within ~deadline; AC#3 fast path (first probe 0, ~0 sleeps); AC#6 no-leak (`pgrep` sentinel finds nothing post-run); `timeout`-absent degradation; and (rec #2) an assertion that the probe's reconstructed `docker compose` flags match `ai_sandbox_compose`'s (drift guard for the 4 other callers).

## Risks & Considerations
- `timeout` wraps `docker`, never the `ai_sandbox_compose` function (the #1 correctness trap).
- Shared flag-assembly helper must keep `ai_sandbox_compose` byte-identical for clean.sh/lifecycle.sh/attach.sh/spawn.sh:265.
- `timeout`-absent fallback = today's behavior, not a guaranteed bound (honest wording).
- `set -euo pipefail`: probe must stay inside an `if`; `$SECONDS` arithmetic is safe.
- Sibling `attach.sh:70` has a similar unbounded probe — OUT OF SCOPE for UC-95 (spawn.sh only); candidate for a future UC.
- Scope: spawn.sh readiness loop + lib.sh helpers only; no change to eager-provisioning content, the marker contract, or anything UC-94 touched.
