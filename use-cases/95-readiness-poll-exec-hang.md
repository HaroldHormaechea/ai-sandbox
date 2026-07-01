# Use Case 95: Spawn readiness poll must not wedge on a hung `docker compose exec` probe

## Summary
When devtools are enabled, `spawn.sh` runs a post-`compose up` readiness loop that polls the session container for `/tmp/aisandbox-ready` via `ai_sandbox_compose -p <project> exec -T claude-sandbox test -f /tmp/aisandbox-ready`, sleeping 2s between iterations for up to 600 iterations (~20 min). Each probe has **no per-call timeout**, so if a single `docker compose exec` invocation hangs (an observed failure mode when the probe fires against a container that is still early/busy in provisioning), the loop blocks on that one call **indefinitely** — it never advances `tries`, never re-probes, and the 600-iteration cap never applies. The observed symptom: the container finishes provisioning and writes `/tmp/aisandbox-ready`, the rootless DinD daemon comes up, and a fresh identical probe returns `0` instantly — yet `spawn.sh` stays stuck on `waiting for in-container provisioning to finish…` forever (a hung `docker compose exec` was seen alive for 59+ minutes). The fix is to bound each probe with a per-call timeout so a hung `exec` is killed and the loop continues, and to bound the whole poll by a real wall-clock deadline instead of an iteration count (which is meaningless once a probe can hang). This is a defect in the UC-27 eager-provisioning readiness wait in `spawn.sh`; it does not touch the container, the mounts, or UC-94's ownership fix.

## Acceptance Criteria
1. Each readiness probe (`ai_sandbox_compose … exec -T claude-sandbox test -f /tmp/aisandbox-ready`) is bounded by a per-probe timeout; if one probe hangs, it is killed and the poll continues to the next iteration rather than blocking forever.
2. The overall readiness wait is bounded by a **wall-clock deadline** (≈ the current ~20-minute budget) computed from the actual clock, so the total wait is bounded regardless of how many individual probes hang or how long each takes.
3. When `/tmp/aisandbox-ready` exists, the poll detects it and reports the session ready within roughly one probe interval — even if one or more earlier probes hung and were timed out.
4. When the deadline is reached without the marker, `spawn.sh` prints the existing "readiness marker was not seen within ~N min" warning and returns (the container is left running), preserving today's timeout behavior.
5. No behavior change when devtools are disabled (the readiness loop is not entered) or when provisioning completes promptly (fast path stays effectively identical — a successful probe still breaks out immediately).
6. A killed/timed-out probe must not leak a stray `docker compose exec` process that outlives the poll (the per-probe timeout terminates the child, and any spawned client is cleaned up).
7. **Repro-first (mandatory before/after):** demonstrate a wedged poll before the fix (a probe that hangs blocks the loop indefinitely while the marker is present / detectable) and demonstrate the fixed poll advancing past a hung/slow probe and reporting ready after.

## Potential Pitfalls & Open Questions
- **Assumption** — `timeout` (coreutils) is available in the environment that runs `spawn.sh` (the ai-sandbox-server host); confirm and, if it might be absent, degrade gracefully rather than breaking the poll.
- **Edge case** — a killed `docker compose exec` can leave a client/daemon-side exec; use `timeout` with a kill-after grace and verify no stray process/exec session accumulates across iterations (AC#6).
- **Edge case** — the per-probe timeout must be long enough not to false-negative a healthy-but-briefly-slow exec, and short enough that a hung probe doesn't eat the whole budget in one iteration. Pick a sensible per-probe timeout (e.g. ~10–15s) and derive the iteration/deadline math from the wall-clock budget.
- **Testability** — a genuinely hung `docker compose exec` is a nondeterministic race; QA should prove the loop-advances-past-a-hung-probe behavior deterministically by injecting a fake/stub probe (e.g. shadowing `ai_sandbox_compose` or the probe command with one that sleeps beyond the per-probe timeout), plus reason about the real case. Avoid a test that depends on reproducing the real docker-compose hang.
- **Risk** — keep the fast path (successful probe → immediate ready) byte-for-byte behaviorally identical; the change is only in how a slow/hung probe and the overall bound are handled.
- **Note** — scope is `spawn.sh`'s readiness loop only. Do not change the eager-provisioning content, the marker contract, or anything UC-94 touched. (Separately, the Android provisioning `aisandbox-android: line 1: tmp: unbound variable` crash and the `java` warn-after-success are pre-existing devtool-provisioning quirks — out of scope here, candidates for their own use cases.)

## Original Description
Discovered while validating UC-94: a `sudo -u ai-sandbox-server … bash /opt/ai-sandbox-server/host/spawn.sh --non-interactive …` (dind+android+java enabled) sat on `waiting for in-container provisioning to finish…` seemingly forever. Investigation showed the container was healthy (Up, `docker info` → rootless Docker 27.3.1), `/tmp/aisandbox-ready` had been written (provisioning completed), and a fresh, identical `docker compose -p ai-sandbox-11 … exec -T claude-sandbox test -f /tmp/aisandbox-ready` returned exit 0 immediately. Yet `ps` showed a `docker compose … -p ai-sandbox-11 exec -T claude-sandbox test -f /tmp/aisandbox-ready` process alive for 59+ minutes — a single readiness probe from the poll that hung and never returned. The poll loop in `spawn.sh`:

```
while [ "$tries" -lt 600 ]; do
    if ai_sandbox_compose -p "$PROJECT" exec -T claude-sandbox test -f /tmp/aisandbox-ready >/dev/null 2>&1; then
        ready=1; break
    fi
    tries=$((tries + 1)); sleep 2
done
```

blocks on that one hung `exec`, so `tries` never increments, the loop never re-probes (where it would see the marker and finish), and the 600×2s cap never triggers. Fix: wrap each probe in `timeout` so a hung exec is killed and the loop advances, and bound the whole wait by wall-clock time rather than iteration count.

## Clarifications
- Q: Where should the fix live and what shape should it take?
  A: In `spawn.sh`'s readiness loop only — add a per-probe `timeout` around the `exec` probe AND convert the overall bound to a wall-clock deadline (~20 min budget preserved). Keep the fast path (successful probe → immediate ready) and the deadline-warning behavior unchanged.
- Q: Is this part of UC-94?
  A: No — UC-94 (bind-source ownership / subuid mount) is a separate, already-fixed concern. UC-95 is the pre-existing readiness-poll hang in the UC-27 eager-provisioning wait, discovered during UC-94 validation.
- Q: How should QA prove it given a real hung exec is nondeterministic?
  A: Deterministically simulate a hung/slow probe (stub the probe command to sleep past the per-probe timeout) to show the loop advances and still detects the marker; the before/after uses the same simulation to show wedge → no-wedge.
