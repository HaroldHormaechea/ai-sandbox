# Use Case 11: Enrollment-flow latent bug fixes — systemd ReadWritePaths + WebFlux exception advice + token rollback

## Summary

UC10's success closed the chain-cleaning bug and the Android client successfully reached `/v1/enrollment` end-to-end on potato-server for the first time, immediately surfacing three pre-existing latent bugs masked by UC04-UC10. **Bug A**: `server/systemd/ai-sandbox-server.service:47` declares `ReadOnlyPaths=/etc/ai-sandbox-server …` combined with `ProtectSystem=strict`, which makes the `/etc/ai-sandbox-server/clients/` allowlist directory **read-only inside the service's mount namespace** even though the underlying filesystem is rw — `EnrollmentFacade.enroll(...)`'s atomic-rename write of `<name>.crt` fails with `EROFS`. The CLI path (`aisandboxctl client mint`) is unaffected because it runs outside the service's sandbox. **Bug B**: `EnrollmentProblemDetailsAdvice.java:34-38`'s `@ExceptionHandler` for `EnrollmentFacade.RateLimitedException` (and sibling token/payload exceptions) does NOT fire in the running Spring WebFlux reactive flow — every documented-error path returns HTTP 500 with a wrapped `UnsupportedOperationException: ServerHttpResponse already committed` instead of the documented 429 / 401 / 413 codes. **Bug C**: enrollment token state is marked redeemed BEFORE the cert is written; when the cert write fails (e.g., Bug A's FS error, or any future failure), the token is consumed but no cert exists, forcing the operator to re-mint a fresh QR — UC11 adds transactional rollback so a failed cert write restores the token to its pre-redeemed state. All three fixes ship together on one feature branch / one PR (parallel slices per UC10's playbook); UC11 ships as `server-v0.0.12`; no Android changes needed (the existing client already handles 429/401/413 per `EnrollmentClient.kt:58-72`).

## Acceptance Criteria

### Bug A — systemd ReadWritePaths fix

1. **Unit file update.** `server/systemd/ai-sandbox-server.service` line 42's `ReadWritePaths=…` is extended to include `/etc/ai-sandbox-server/clients`. The existing entries (`/var/log/ai-sandbox-server /var/lib/ai-sandbox-server`) stay. The unit's `ReadOnlyPaths=` line is unchanged so other `/etc/ai-sandbox-server/` contents (cert, key, config.yaml, secrets) remain read-only to the service. Security model is intentional: config files immutable, dynamic state (allowlist) mutable.

2. **Test — unit file parsed-content assertion.** A new test (suggested location: `server/src/test/java/com/aisandbox/server/systemd/UnitFileContractTest.java`) reads `server/systemd/ai-sandbox-server.service` from the repo, parses the `[Service]` section's `ReadWritePaths=` line, and asserts both `/etc/ai-sandbox-server/clients` AND the existing `/var/log/ai-sandbox-server` + `/var/lib/ai-sandbox-server` are present. Pre-fix: fails (clients path absent). Post-fix: passes. Catches regressions if anyone edits the unit file again.

3. **Test — end-to-end packaged service write.** `release-install-smoke` is extended to actually exercise the enrollment write path: install the .deb in the smoke container, mint an enrollment token via `aisandboxctl client invite`, hit `POST /v1/enrollment` via curl (the mTLS-exempt endpoint) with the token, and assert a real `<name>.crt` file lands in `/etc/ai-sandbox-server/clients/`. Pre-fix on a stock packaged service: the curl POST returns 500 with the FS-error trace. Post-fix: returns 201 with a valid p12 body and the cert file is on disk. Only test layer that actually exercises the systemd ReadWritePaths in effect.

### Bug B — WebFlux exception advice routing (WebExceptionHandler bean approach)

4. **`EnrollmentProblemDetailsAdvice` converted to a `WebExceptionHandler` bean.** The existing `@RestControllerAdvice` is replaced with a `@Bean WebExceptionHandler` (Spring WebFlux's reactive-aware exception handling primitive). The bean is registered with sufficient `@Order` precedence that it fires before the generic fallback `com.aisandbox.server.api.error.ProblemDetailsAdvice` for the enrollment exceptions. All declared enrollment exceptions map to their documented HTTP codes (per `EnrollmentController.java:34-44`):
   - `EnrollmentFacade.RateLimitedException` → 429 `enrollment_rate_limited`
   - `EnrollmentFacade.TokenInvalidException` → 401 `enrollment_token_invalid`
   - `EnrollmentFacade.TokenExpiredException` → 401 `enrollment_token_expired`
   - `EnrollmentFacade.TokenRedeemedException` → 401 `enrollment_token_redeemed`
   - `EnrollmentFacade.PayloadTooLargeException` → 413 `payload_too_large`

   Response bodies are valid `application/problem+json` with the documented `code` / `detail` / `title` / `instance` fields.

5. **Test — `ServerHttpResponse already committed` regression.** A WebFlux integration test triggers each of the 5 enrollment exceptions in turn and asserts:
   - HTTP response code matches the documented mapping (429/401/401/401/413).
   - Response body is a valid `application/problem+json` document with the documented `code` field.
   - The `UnsupportedOperationException: ServerHttpResponse already committed` does NOT appear in the captured server logs for any of the 5 cases.

   Pre-fix: tests fail (500 returned, exception in logs). Post-fix: all pass.

6. **Test — generic fallback advice still catches truly-unmapped exceptions.** Regression test confirms that a deliberately-thrown unmapped runtime exception (e.g., a custom `RuntimeException("not in any specific handler")`) on a non-enrollment endpoint still produces a 500 response with the documented fallback shape (`code: internal_error`). The fallback's "Unmapped exception in REST flow" log line MUST NOT fire for any of the 5 enrollment exceptions in (4).

### Bug C — Transactional token rollback on cert-write failure

7. **Token redemption is atomic with cert write.** `EnrollmentFacade.enroll(...)` is restructured so the token is NOT marked redeemed until the client cert is successfully written to disk. Order of operations (post-fix):
   - Step 1: rate-limit check (in-memory; unchanged).
   - Step 2: token validation — verify the token exists and is not expired or redeemed. **Do NOT mark redeemed yet.**
   - Step 3: client cert mint — generate the cert + key, write to `/etc/ai-sandbox-server/clients/<name>.crt` via the existing atomic-rename pattern.
   - Step 4: AFTER successful cert write, mark the token redeemed (and only then).
   - Step 5: return the p12 to the caller.

   If step 3 fails (FS error, key-gen error, atomic-rename failure), the token remains in its pre-redeemed state and the operator can retry the same QR. If step 4 fails (extremely rare — the redeem-mark itself is a file write to `/var/lib/...`), the cert is on disk but the token is still valid — next retry would write a duplicate cert, which the existing client-name-collision check in step 3 catches.

   Concurrency note: two simultaneous redemptions of the same token would both pass step 2's "not redeemed" check, both reach step 3, and one would lose to the cert-file-already-exists collision (assuming the same `<name>`). The first to mark in step 4 wins; the second's cert write is the loss. Acceptable for the threat model (operator-driven single-shot enrollment); the rate limiter already provides per-IP serialization at the second level.

8. **Test — token survives cert-write failure.** A unit test (or facade-level integration test) injects a mock `EnrollmentTokenStore` and a mock `ClientCertWriter` (or whatever name the dev-team picks). Configures the cert writer to throw `IOException("simulated FS failure")` during the cert mint step. Asserts:
   - `EnrollmentFacade.enroll(...)` propagates the failure (callers see the exception).
   - The token store's redeemed mark is NOT set after the call.
   - A subsequent call with the same token succeeds (cert writer's failure is one-shot in the test setup).
   - Audit log emits `client_enroll_reject` with a specific outcome (e.g. `cert-write-failed`) rather than `success`.

9. **Test — happy path still single-shot.** Regression test: a successful enrollment marks the token redeemed and a subsequent retry with the same token fails with `TokenRedeemedException` (which AC4 now correctly maps to 401). Confirms UC11's rollback fix doesn't accidentally allow multi-use tokens on the happy path.

### Cross-cutting

10. **Both/all fixes on one PR, parallel slices.** Bug A, B, C land on the same feature branch (`feat/uc-11-enrollment-runtime-fixes`) with commits cleanly split per slice. Single PR to `main`, squash-merged to match the project's release-commit style.

11. **Test-first cascade.** QA writes the failing tests in AC2/AC3/AC5/AC6/AC8/AC9 against the current branch state BEFORE the developer's production change. Pre-fix failure pattern captured in the test summary; developer's production change flips them green; cascade honored end-to-end per UC09 § AC4 / UC10 § AC7 / § AC9 orchestration.

12. **No Android changes.** The Android client already switches on 429/401/413 per `EnrollmentClient.kt:58-72`. UC11 makes the server-side contract real; the Android side is already correct. If the analyst discovers any Android-side gap during investigation, surface as UC12 candidate, not in-scope here.

13. **Release.** Ships as `server-v0.0.12`. No `android-v0.2.1` tag. Release notes call out:
    *"Closes UC11. Fixes three latent enrollment-flow bugs surfaced by UC10's smoke gate: (a) systemd sandbox blocked the service from writing the allowlist cert; (b) Spring WebFlux exception advice wasn't catching enrollment exceptions reactively, so all error paths returned 500; (c) enrollment tokens were marked redeemed before the cert write, leaving operators with a half-burned token on any failure. Operators who applied the operational drop-in (`/etc/systemd/system/ai-sandbox-server.service.d/uc11-clients-writable.conf`) can leave it in place after upgrading — v0.0.12 includes the same path in the packaged unit file and systemd's `ReadWritePaths` is additive, so the drop-in becomes a no-op."*

14. **Documentation audit.** `server/README.md` and the operator-facing systemd/install sections describe the unit file's read-only intent; UC11 updates these to note that `/etc/ai-sandbox-server/clients` is the one operator-visible exception (allowlist is dynamic). PR body records audited-and-clean for any file that didn't need a change.

15. **CI green, single PR.** `:server:test`, `:server:spotlessCheck`, `release-install-smoke` (with the new AC3 enrollment-write step), `android-ci` all pass.

### Operational continuity

16. **Drop-in coexists with the fix.** Operators who already applied the `/etc/systemd/system/ai-sandbox-server.service.d/uc11-clients-writable.conf` workaround on a v0.0.11 host don't need to remove it — `ReadWritePaths` is additive in systemd, so the packaged fix + the drop-in resolves to the same effective state. Release notes call this out explicitly.

17. **Manual smoke gate (UC10 § AC15) extended.** v0.0.12's release runbook adds a fourth condition: (d) re-running the enrollment flow with a deliberately-tripped rate limit returns 429 to the phone (verified via the in-app error screen showing the rate-limit copy, not the generic `<bootstrap>`-era diagnostic block). The expander still shows the raw response. Also (e) deliberately failing the cert write (e.g., temporarily removing the drop-in on a v0.0.12 host that doesn't yet have the unit fix applied) returns the proper error code AND the token remains valid for retry once writability is restored.

## Potential Pitfalls & Open Questions

- **Risk** — `profile-java-server-architecture` boundary. The fix touches the WebFlux exception advice (API layer), the systemd unit file (deployment infra), and the EnrollmentFacade's transactional ordering. None of this changes the Controller → Facade → Service → Repository chain; the exception types are already declared on the facade. No layering violation.
- **Risk** — `profile-java-call-graph-tool` active. Dev-team should use it to locate callers of `EnrollmentTokenStore.markRedeemed` (or the equivalent), `EnrollmentFacade.enroll`, and the existing exception-mapping advice to confirm the surface area before refactoring.
- **Edge case** — Drop-in compatibility on upgrade. If the operator's drop-in contains different `ReadWritePaths` entries (or accidentally got `ReadWritePaths=` empty to clear the list), the post-fix behaviour may differ. Release notes call out "the drop-in becomes a no-op only if it contains exactly `ReadWritePaths=/etc/ai-sandbox-server/clients`; any other content should be reviewed before upgrade."
- **Edge case** — Concurrent enrollment race with rollback. Two simultaneous redemptions of the same token both pass the "not redeemed" check (step 2 in AC7); one wins the cert-write race at step 3 (the loser sees `FileAlreadyExistsException` on `<name>.crt`); the winner marks redeemed at step 4. Threat-model-acceptable per AC7's own note, but worth surfacing.
- **Edge case** — Token state file write fails at step 4 (AC7's "rare but possible"). Cert is on disk but token still valid. Next retry would attempt to write `<name>.crt` again and hit `FileAlreadyExistsException`. The facade should treat that as success-effective (the operator's identity is provisioned) and emit the same p12 payload — OR fail with a specific "cert already exists" error code so the operator knows the previous attempt actually succeeded. Decision for the analyst.
- **Assumption** — The "Unmapped exception in REST flow" log line comes from `com.aisandbox.server.api.error.ProblemDetailsAdvice`. That class exists (referenced in the empirical log line) and is the catch-all fallback. UC11's investigation should confirm.
- **Assumption** — The enrollment endpoint is the only WebFlux endpoint with this exception-mapping problem. The mTLS-protected endpoints (sessions, terminal) may have the same issue if they share the advice infrastructure. Out of scope for UC11 (no empirical proof); flag for the analyst to mention in the implementation notes if any sibling exposure surfaces during investigation.

## Original Description

UC10 closed the chain-cleaning bug and the Android client successfully reaches `/v1/enrollment` end-to-end for the first time. Smoke-testing v0.0.11 / android-v0.2.0 against potato-server immediately surfaced two **pre-existing latent bugs** in the enrollment flow — both masked by UC04-UC10 because no real Android client had ever reached the enrollment endpoint successfully.

### Bug A — systemd unit makes /etc/ai-sandbox-server read-only to the service

`server/systemd/ai-sandbox-server.service:47` declares `ReadOnlyPaths=/etc/ai-sandbox-server /opt/ai-sandbox-server/host` combined with `ProtectSystem=strict` (line 27). This mounts the entire `/etc/ai-sandbox-server` tree read-only inside the service's mount namespace — including `/etc/ai-sandbox-server/clients/`, which is exactly where `EnrollmentFacade.enroll(...)` writes the newly-minted client cert (`<name>.crt` via the `.<name>.crt.tmp` atomic-rename pattern).

Empirical proof (2026-05-20, post-v0.0.11 deploy on potato-server):
```
java.nio.file.FileSystemException: /etc/ai-sandbox-server/clients/.harold-phone.crt.tmp: Read-only file system
```
Disk `df -h` shows 19% used / 110GB free; `dmesg` shows zero ext4/remount/read-only events; the dir itself is `700 ai-sandbox-server:ai-sandbox-server`. The fs is rw at the kernel level — the EROFS is purely systemd's mount-namespace sandbox.

`bootstrap.crt` + `laptop.crt` exist on disk because they were created via `aisandboxctl client mint` — a separate CLI process running as the operator's sudo'd shell, NOT subject to the service's `ReadOnlyPaths` sandbox. The Android-driven path that goes through the running service hits the RO mount.

Fix: add `/etc/ai-sandbox-server/clients` to the unit's `ReadWritePaths`. Existing RW paths (`/var/log/ai-sandbox-server /var/lib/ai-sandbox-server`) stay. Other config under `/etc/ai-sandbox-server/` (the cert, key, config.yaml, secrets) MUST remain read-only — only the allowlist subdir needs write access. This matches the security intent: config files immutable, dynamic state mutable.

Operational workaround already in place on potato-server via a `/etc/systemd/system/ai-sandbox-server.service.d/uc11-clients-writable.conf` drop-in; UC11 lands the proper fix in the packaged unit file so fresh installs work out of the box.

### Bug B — Spring WebFlux exception handler doesn't catch enrollment exceptions

`server/src/main/java/com/aisandbox/server/enrollment/api/EnrollmentProblemDetailsAdvice.java:34-38` declares a `@ExceptionHandler(EnrollmentFacade.RateLimitedException.class)` mapped to `429 enrollment_rate_limited`. Empirically this handler **does NOT fire** in the running WebFlux reactive flow.

Empirical proof from production logs:

```
Unmapped exception in REST flow: com.aisandbox.server.enrollment.facade.EnrollmentFacade$RateLimitedException: Enrollment rate limit tripped for 192.168.0.25
Error [java.lang.UnsupportedOperationException] for HTTP POST "/v1/enrollment", but ServerHttpResponse already committed (500 INTERNAL_SERVER_ERROR)
```

Same shape for the FileSystemException above — "Unmapped exception" + `ServerHttpResponse already committed`. The fallback `ProblemDetailsAdvice` (in `com.aisandbox.server.api.error`) catches everything as "unmapped" and tries to write a 500 response, but the response is already committed, triggering `UnsupportedOperationException`. End result: the phone gets a 500 instead of the documented 429 (rate-limit) / 401 (token invalid) / 413 (payload too large) error codes.

This affects:
- `RateLimitedException` → should be 429 (observed: 500)
- Wrapped `FileSystemException` (from cert write failure) → should be a specific server-error code (observed: 500 with `UnsupportedOperationException` wrapping)
- Probably also `TokenInvalidException` / `TokenExpiredException` / `TokenRedeemedException` (401) and `PayloadTooLargeException` (413) — all declared in EnrollmentFacade and mapped in the advice but unlikely to fire correctly given the symptom pattern

Root cause hypothesis: `@ExceptionHandler` annotated handlers on a `@RestControllerAdvice` work cleanly in Spring MVC but require specific setup for Spring WebFlux reactive controllers. The controller returns `Mono<…>` which signals errors via `Mono.error(...)`; the exception advice needs to catch via the reactive-aware path. The current advice may be set up MVC-style and only the generic `ProblemDetailsAdvice` fallback is actually running.

### Why both fixes ship together

Both are pre-existing latent bugs in the enrollment flow that UC10's success on the chain-cleaning side has now exposed. Either alone is insufficient:
- Fix A without B: enrollment now writes the cert file but the phone still gets 500 on rate-limit, token-already-redeemed, expired-token, and any other documented-error path → Android client can't surface specific error messages to the operator.
- Fix B without A: error responses are now correct (429 / 401 / 413) but the happy path STILL fails with FS write blocked → phone never enrolls.

Single PR, parallel slices per UC10's playbook.

### Test-first cascade

Same orchestration as UC09 § AC4 / UC10 § AC7 / § AC9.

### Release

Ships as `server-v0.0.12`. No Android changes — the Android client already handles 429 / 401 / 413 correctly per `EnrollmentClient.kt:58-72`; it just needs the server to return the right codes. So no `android-v0.2.1` tag needed.

## Clarifications

- Q: Bug B fix approach — which WebFlux exception-mapping strategy?
  A: Convert the advice to a `WebExceptionHandler` bean. Proper WebFlux idiom; sidesteps the `ServerHttpResponse already committed` issue at the right reactive lifecycle point; reusable for future reactive endpoints.

- Q: Bug A test scope — which tests should be in scope for UC11?
  A: Both AC2 (parsed unit-file test, cheap regression protection) and AC3 (end-to-end smoke through the packaged service, only test that exercises the systemd ReadWritePaths in effect).

- Q: Token state on cert-mint failure — should UC11 add transactional rollback?
  A: Yes — roll back token state on cert-write failure. Atomic ordering so the token isn't marked redeemed until the cert is on disk. Improves operator UX on transient errors. Added as AC7-AC9 (Bug C).

- Q: After UC11 is saved — same flow as UC10 (chain into /develop, dev-team implements, autonomous merge + tag)?
  A: Save UC11 — don't run /develop yet. Operator wants to review the use case file (and likely run the dev-team in a fresh session).
