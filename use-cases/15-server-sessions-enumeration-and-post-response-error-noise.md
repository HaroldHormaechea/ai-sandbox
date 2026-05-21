# Use Case 15: Server `/v1/sessions` enumeration broken — `docker compose ls --all` rejected, `.docker/config.json` unreadable, and downstream `handleAny` log noise

## Summary

Production logs on `potato-server` (2026-05-21 evening, post-`server-v0.0.13` deploy with UC-14 changes) surface two correlated failures on the `GET /v1/sessions` path, plus one unrelated success-path log-noise issue on `POST /v1/enrollment`:

1. **Docker config unreadable.** `docker compose ls` invoked by the server's session-enumeration helper logs `WARNING: Error loading config file: open /home/ai-sandbox-server/.docker/config.json: permission denied`. The systemd unit's user (`ai-sandbox-server`, per UC-06 packaging) does not own / cannot read `~/.docker/config.json` — either the directory was never created with the right ownership at install time, or the systemd sandbox's filesystem narrowing excludes it. Result: docker invocations log a warning even when they succeed; this is the smaller half.

2. **`--all` flag rejected.** `docker compose ls` is invoked with `--all` (added by UC-02 to surface stopped containers in the session list). The runner's docker-compose binary returns `unknown flag: --all` and `exit=125`. The flag was renamed / removed in a docker-compose major version that ships in the production runner's Ubuntu 24.04 packages. The session enumeration therefore errors on every call. The controller treats this as an exception (caught somewhere up the stack) and ends up returning HTTP 200 with an empty list, OR HTTP 500 — both ways the user gets a useless answer.

3. **Post-response `handleAny` log noise (will be silenced by UC-12).** Every failed enumeration triggers `ProblemDetailsAdvice.handleAny(Throwable)` after the response is already committed. `handleAny` attempts `setStatusCode(...)` on the committed `ServerHttpResponse` → `UnsupportedOperationException` → `HttpWebHandlerAdapter` logs `Error [...] for HTTP GET "/v1/sessions", but ServerHttpResponse already committed (200 OK)` and the symmetric line on the `/v1/enrollment` success path (`201 CREATED`). UC-12 already removes `handleAny` and adds `GenericProblemFallbackHandler` with the isCommitted-guard early return, so this third symptom should *already* be silenced by the time UC-15 lands on top of `server-v0.0.14`. UC-15's job here is the empirical verification, not new code.

This UC ships as `server-v0.0.15` (UC-12 takes v0.0.14; UC-15 follows directly after). No Android changes.

## Acceptance Criteria

1. **`/v1/sessions` returns the real list.** A `GET /v1/sessions` against a server with one or more running ai-sandbox containers returns HTTP 200 with a JSON array that contains those containers. Tested against the actual docker-compose binary version installed on the runner (Ubuntu 24.04 default) — NOT only against the dev-env docker.

2. **`docker compose ls` invocation works on the runner's binary.** The session-enumeration helper's argv produces a successful (`exit=0`) docker-compose invocation on the runner. Concretely: the analyst's proposal MUST verify the runner's `docker compose version`, then either (a) drop `--all` if the modern binary lists stopped containers by default, (b) substitute the supported flag (e.g. `--status=stopped` or whatever the modern binary expects), or (c) detect the binary's flag set at runtime and pick the right one. The decision must be empirical — running the candidate command in a UC-04-runner-equivalent shell — not docs-reading.

3. **`.docker/config.json` permission resolved.** One of:
   - The `.deb` postinst creates `/home/ai-sandbox-server/.docker/` (mode 0700, ownership `ai-sandbox-server:ai-sandbox-server`) and an empty `config.json` (mode 0600, same ownership). Re-running `pki init` / `secrets seed` does NOT clobber it.
   - OR the server's docker invocations pass `DOCKER_CONFIG=/var/lib/ai-sandbox-server/docker-config` (or equivalent state path that's already in the systemd unit's `StateDirectory=`) so docker doesn't reach for `$HOME/.docker`.
   - OR the systemd unit's `ReadWritePaths=` / `BindReadOnlyPaths=` get an explicit entry for `/home/ai-sandbox-server/.docker`.
   The analyst picks ONE based on a brief tradeoff comparison; the chosen approach is documented in the PR body.

4. **No `permission denied` warning in normal operation.** After the fix, a `GET /v1/sessions` triggers a docker invocation that logs no warning lines about config files; the response is 200 + the expected JSON array.

5. **UC-12 carryover verified.** After UC-12 (`server-v0.0.14`) is deployed AND this UC's changes land, the production logs no longer contain `Error [java.lang.UnsupportedOperationException] for HTTP ... "/v1/...", but ServerHttpResponse already committed` lines on the success path. Verification: AC9 below or equivalent runtime test.

6. **Pre-fix log signature captured in tests.** QA writes a server-side test against an `/v1/sessions` controller wired to a mock docker-compose invoker that returns `exit=125 + stderr containing "unknown flag: --all"`. The test asserts (a) HTTP status + body shape the controller returns under that failure mode today (pre-fix), then (b) after the developer's fix is in, the same controller wired to a SUCCESS docker-compose invoker returns the expected populated list.

7. **`.docker/config.json` invariant test.** A test or `.deb` postinst script section asserts the file's existence + mode + ownership after a fresh install. UC-06 already has install-smoke checks; add to that surface.

8. **No drift on UC-12 contracts.** All UC-12 tests (`EnrollmentExceptionRoutingTest`, `StreamExceptionRoutingTest`, `GenericProblemFallbackHandlerTest`, plus the 8 named UC-11 contract tests) stay green.

9. **CI green.** `:server:test`, `:server:spotlessCheck`, `release-install-smoke` (the long-broken systemd leg that has class-version skew — orthogonal to UC-15 but worth a re-check at the same time; if still broken, document and defer), `android-ci` all pass.

10. **Ship as `server-v0.0.15`.** Standard release-notes; explicitly call out the two enumeration fixes and a one-liner that UC-12's defensive handleAny removal already silenced the success-path "already committed" log noise (no additional code change for that here).

## Original Description

(Reported by the user on 2026-05-21 after deploying `server-v0.0.13` to potato-server.)

```
[52413b06/1-1] Error [java.lang.UnsupportedOperationException] for HTTP POST "/v1/enrollment", but ServerHttpResponse already committed (201 CREATED)
…
docker compose ls failed (exit=125): WARNING: Error loading config file: open /home/ai-sandbox-server/.docker/config.json: permission denied
unknown flag: --all
…
[e5614801/1-2] Error [java.lang.UnsupportedOperationException] for HTTP GET "/v1/sessions", but ServerHttpResponse already committed (200 OK)
```

## Potential Pitfalls & Open Questions

- **Which docker-compose version actually ships in Ubuntu 24.04?** The fix has to work against the binary the runner actually uses, not the latest upstream. The analyst must verify on a Ubuntu 24.04 box (or the GH Actions ubuntu-24.04 runner image) before committing to a flag-set.
- **systemd sandbox vs. `$HOME`.** Adding `ReadWritePaths=/home/ai-sandbox-server/.docker` is the smallest blast radius; rerouting via `DOCKER_CONFIG=` to a `StateDirectory=`-managed path is cleaner but touches more code (the docker-compose invocation needs the env var consistently). Pick one with a documented rationale.
- **Empty / corrupted docker config.** If the `.docker/config.json` is empty or doesn't exist, docker logs a different warning. The fix needs to handle both "directory missing" and "file present but unreadable" idempotently.
- **Stopped-container reporting.** UC-02's choice to include stopped containers was deliberate. If we drop `--all`, the new behavior must still return stopped containers — possibly via two calls (`docker compose ls` + `docker compose ls --status=stopped`) and a merge, or via the docker-compose binary's modern default behavior (does it include stopped by default in the new version?).
- **The UC-12 carryover (AC5).** UC-12 must land first (it owns the handleAny removal). If for any reason UC-12 ships *without* the GenericProblemFallbackHandler isCommitted-guard, the third symptom would not be silenced; verify the guard is in place before declaring AC5 satisfied.
