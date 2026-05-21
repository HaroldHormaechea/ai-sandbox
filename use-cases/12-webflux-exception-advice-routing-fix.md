# Use Case 12: Fix UC-11 regression — WebFlux exception chain bypasses EnrollmentWebExceptionHandler

## Summary

UC-11's release `server-v0.0.12` still exhibits the bug it claimed to fix. Production logs on potato-server (2026-05-21 09:50 +02:00) show that for every enrollment exception, `ProblemDetailsAdvice.handleAny` (the catch-all `@ExceptionHandler(Throwable.class)` on a `@RestControllerAdvice`) still wins, then attempts to write to an already-committed `ServerHttpResponse`, and the request ends as HTTP 500 with `UnsupportedOperationException`. The new `EnrollmentWebExceptionHandler` bean (introduced by UC-11) never sees the exception because the controller-advice chain runs at the `RequestMappingHandlerAdapter` → `InvocableHandlerMethod` layer, **before** the exception reaches the `ExceptionHandlingWebHandler` layer where `WebExceptionHandler` beans run. UC-11's integration test passed because it used `MockServerWebExchange` bound directly to the handler bean — `ProblemDetailsAdvice` was never registered in the test context, so the test proved the handler works in isolation but not that the handler ever receives the exception in production. This UC chooses a real fix shape, lands it, applies the same pattern to the sibling `StreamProblemDetailsAdvice`, and closes the test gap with a real-context integration test that boots both advices in the same application context. Ships paired with the PKCS#12 / Android crypto UC as `server-v0.0.13`; no Android changes.

## Acceptance Criteria

1. **Production symptom gone.** `TokenInvalidException` thrown by `EnrollmentFacade.redeem` produces HTTP 401 `enrollment_token_invalid` with `application/problem+json` body. No `Unmapped exception in REST flow` WARN line from `ProblemDetailsAdvice`. No `ServerHttpResponse already committed` ERROR line from `HttpWebHandlerAdapter`. The other four enrollment exceptions (`RateLimitedException`, `TokenExpiredException`, `TokenRedeemedException`, `CertAlreadyExistsException`) get the same treatment with their documented codes (429/401/401/409). **Same coverage for every exception currently mapped by `StreamProblemDetailsAdvice`** — its documented status codes must appear instead of HTTP 500.

2. **Fix-shape decision recorded.** The analyst's proposal explicitly states which of options (a)/(b)/(c) was chosen and why each rejected option was rejected:
   - **(a)** Remove `@ExceptionHandler(Throwable.class)` from `ProblemDetailsAdvice` entirely; everything falls through to `WebExceptionHandler` beans; generic fallback becomes a separate `WebExceptionHandler` at lowest precedence. Cleanest, biggest blast radius.
   - **(b)** Carve out the 5 enrollment exceptions (and the stream advice's exceptions) in `ProblemDetailsAdvice.handleAny` by rethrowing them so they escape the advice chain and reach the `WebExceptionHandler` beans. Smallest diff, maintenance hazard.
   - **(c)** Revert to `@ExceptionHandler` per exception in a new `EnrollmentExceptionHandler` (and `StreamExceptionHandler`) `@RestControllerAdvice` with explicit `@Order(Ordered.HIGHEST_PRECEDENCE)` so they win over `ProblemDetailsAdvice` at the controller-advice level. UC-11 explicitly moved away from this; the move was based on a wrong premise about layer ordering.

3. **Real-context integration test (the gap-closer).** A new test under `server/src/test/java/com/aisandbox/server/enrollment/api/` (and a sibling under `server/src/test/java/com/aisandbox/server/stream/api/`) uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` (or equivalent full-context bootstrap) to register BOTH `ProblemDetailsAdvice` AND the enrollment/stream handlers in the same context. The test wires a real `EnrollmentFacade` (or stream-facade) stub that throws each documented exception, sends a real HTTP request via `WebTestClient`, and asserts: (a) HTTP status matches the documented mapping; (b) body is `application/problem+json` with the documented top-level `code` (per UC-11's RFC-9457 flattening fix); (c) `ProblemDetailsAdvice.LOG` did NOT emit the "Unmapped exception in REST flow" line for any of the mapped exceptions; (d) the application logs do NOT contain "ServerHttpResponse already committed" for any of those cases. **`MockServerWebExchange` / `bindToController` / `WebFilter`-adapter shortcuts are explicitly prohibited** — those are exactly what masked the bug in UC-11.

4. **Fallback advice still catches truly-unmapped exceptions.** A regression test that throws a deliberately-unmapped exception (e.g., a custom `RuntimeException("not in any specific handler")`) from a non-enrollment, non-stream endpoint confirms the generic fallback still returns HTTP 500 with `code: internal_error` and the "Unmapped exception in REST flow" WARN line still fires for that case.

5. **`StreamProblemDetailsAdvice` migrated to the same pattern.** The analyst's empirical step is mandatory: confirm under the real bean stack whether the stream advice exhibits the same routing bug (almost certainly it does). If yes, migrate using the chosen fix shape (option a/b/c). If empirical testing shows it does NOT exhibit the bug, drop the migration but record the empirical check and the differential explanation ("why does it work when the enrollment one didn't?") in the PR body. The empirical check itself is not optional.

6. **No drift on UC-11's other contracts.** `EnrollmentControllerTest`, `EnrollmentWebExceptionHandlerIntegrationTest`, `EnrollmentFacadeTest`, `EnrollmentTokenStoreTest`, `EnrollmentTokenServiceTest`, `ProblemDetailsAdviceTest`, `UnitFileContractTest`, `ClientInviteCommandTest` all stay green. The Problem+JSON body shape stays flat (top-level `code`, no nested `properties.code`). The token rollback ordering (`rate → verify → mint → addClient → markRedeemed`) is unchanged. `AllowlistDirectory.write`'s `Files.move(ATOMIC_MOVE)` contract is unchanged.

7. **CI green.** `:server:test`, `:server:spotlessCheck`, `release-install-smoke` (including UC-11's systemd-driven enrollment-write leg), `android-ci` all pass.

8. **Release.** Ships paired with the PKCS#12 / Android crypto UC as `server-v0.0.13`. Standard version-to-version release notes — no special "superseded" callout for v0.0.12, just normal "fixed in v0.0.13" language. Single PR squash-merged to `main`, per-slice commits within the branch are fine.

9. **No Android changes.** The Android client already handles 429/401/409 correctly per `EnrollmentClient.kt:58-72` — the existing switch was correct; UC-11 just didn't deliver the codes to it. Confirm via `git diff main...HEAD` that this UC's PR touches no `android/` paths.

10. **Test-first cascade.** QA writes the failing AC3 integration test against current `main` (v0.0.12) BEFORE the developer's production change. Pre-fix failure pattern (HTTP 500 + "Unmapped exception in REST flow" WARN + "ServerHttpResponse already committed" ERROR) captured in the test summary; developer's production change flips them green. Mirrors UC-09 § AC4 / UC-10 § AC7 / UC-11 § AC11 orchestration.

11. **Operational continuity.** Hosts running v0.0.12 today will see the bug fixed only after upgrading to v0.0.13 — there is no operator drop-in for this fix (unlike UC-11's systemd drop-in option). Release notes mention the upgrade as the resolution path.

## Potential Pitfalls & Open Questions

- **Risk** — Fix shape (a) removes `ProblemDetailsAdvice.handleAny`'s catch-all entirely. Every endpoint that today relies on that catch-all to produce a `problem+json` 500 body would fall through to Spring's default error response unless a replacement `WebExceptionHandler` is registered at lowest precedence. Analyst MUST verify all non-enrollment, non-stream endpoints still produce the documented `internal_error` body shape after the change.

- **Risk** — Fix shape (b)'s carve-out list (5 enrollment exceptions + the stream advice's exception types today, growing) becomes a documentation contract that's easy to drift on. If the project adds a sixth enrollment exception later and forgets the carve-out, the new exception silently regresses to HTTP 500. Mitigation: parameterized test that asserts every public exception type declared on `EnrollmentFacade.redeem` (and the stream-facade equivalent) is on the carve-out list.

- **Risk** — Fix shape (c) brings back `@ExceptionHandler` advice that "worked" pre-UC11 but actually didn't (UC-11's own evidence section says "the enrollment-specific advice does not fire in the running Spring WebFlux reactive flow"). If (c) is chosen, the analyst must explain WHY it works now when it didn't before — most likely `@Order(Ordered.HIGHEST_PRECEDENCE)` was missing pre-UC11, but this has to be confirmed empirically against the resolved Spring Boot 4.0.6 / Spring 7.0.7 jar bytecode, not assumed.

- **Edge case** — Sibling `StreamProblemDetailsAdvice` empirically tests as NOT-broken (contrary to expectation). The analyst's proposal must explain the differential — what does the stream advice do differently that the enrollment advice doesn't? — and either lift that pattern back to enrollment as a fourth fix-shape option, or record the divergence as a project-specific oddity.

- **Ambiguity** — Operational hotfix for current v0.0.12 deployments. UC-11 had a drop-in (`/etc/systemd/system/ai-sandbox-server.service.d/uc11-clients-writable.conf`). This UC's fix is code-only — no drop-in is possible. The user accepts "upgrade to v0.0.13" as the only path; release notes call this out so operators don't search for a non-existent drop-in.

- **Assumption** — Spring WebFlux's exception-handling layer ordering as described (controller-advice consumes first at `RequestMappingHandlerAdapter` / `InvocableHandlerMethod`; `WebExceptionHandler` second at `ExceptionHandlingWebHandler`) holds across the Spring Boot 4.0.6 / Spring 7.0.7 versions actually resolved by this project. Analyst verifies via the actual `ExceptionHandlingWebHandler` / `RequestMappingHandlerAdapter` source on the resolved jar versions, not via general Spring documentation.

## Original Description

UC-11's release `server-v0.0.12` still exhibits the bug it claimed to fix. Production logs from potato-server (2026-05-21 09:50:34 +02:00): for a `TokenInvalidException`, the facade emits its audit `client_enroll_reject outcome=token-invalid`, then `ProblemDetailsAdvice.handleAny` WARN fires ("Unmapped exception in REST flow: ...TokenInvalidException..."), then `HttpWebHandlerAdapter` ERROR fires ("ServerHttpResponse already committed (500 INTERNAL_SERVER_ERROR)"). This means UC-11's v0.0.12 release still exhibits the exact pathology UC-11 claimed to fix — every documented-error path for the 5 enrollment exceptions still returns HTTP 500 with the wrapped `UnsupportedOperationException`.

Root cause: in Spring WebFlux the `@ExceptionHandler` chain on `@RestControllerAdvice` runs at the controller-method-invocation level (`RequestMappingHandlerAdapter` → `InvocableHandlerMethod`), **before** the exception ever reaches the `ExceptionHandlingWebHandler` where `WebExceptionHandler` beans run. So `ProblemDetailsAdvice.handleAny`'s `Throwable` catch-all consumes the enrollment exception first, tries to render the response, hits the already-committed write path, and we land at HTTP 500 — exactly the symptom UC-11 was supposed to fix.

The UC-11 integration test (`EnrollmentWebExceptionHandlerIntegrationTest`) passed because it used a `MockServerWebExchange` bound directly to the new handler (or a `WebFilter`-adapter binding) — `ProblemDetailsAdvice` was never registered in that test context, so the exception flowed straight to the handler. The test proved the handler works in isolation but not that the handler gets the exception in the real bean stack.

Three fix-shape options for the analyst to evaluate (do not pre-decide):
(a) Remove `@ExceptionHandler(Throwable.class)` from `ProblemDetailsAdvice` entirely; everything falls through to `WebExceptionHandler` beans; generic fallback becomes a separate `WebExceptionHandler` at lowest precedence. Cleanest but biggest blast radius (`StreamProblemDetailsAdvice` and any sibling that depends on the catch-all has to change too).
(b) Carve out the 5 enrollment exceptions (`RateLimited`/`TokenInvalid`/`TokenExpired`/`TokenRedeemed`/`CertAlreadyExists`) in `ProblemDetailsAdvice.handleAny` by rethrowing them so they escape the advice chain and reach `EnrollmentWebExceptionHandler`. Smallest diff but the carve-out list becomes a maintenance hazard.
(c) Revert to `@ExceptionHandler` per exception in a new `EnrollmentExceptionHandler` `@RestControllerAdvice` with explicit `@Order(Ordered.HIGHEST_PRECEDENCE)` so it wins over `ProblemDetailsAdvice` at the controller-advice level. This is what UC-11 explicitly moved away from — but the move was based on a wrong premise about which layer `@ExceptionHandler` runs at.

Mandatory test-coverage gap to close: the next integration test MUST boot `ProblemDetailsAdvice` AND the enrollment handler in the same context — a real `@SpringBootTest` with a real controller throwing real enrollment exceptions, NOT a `MockServerWebExchange` shortcut, NOT a `WebTestClient.bindToController()` that registers handlers manually. Without this, the same class of bug will slip through again.

Out of scope: the crypto/PKCS#12 compatibility issue surfaced on the same potato-server smoke (Android client gets `import_failed Cannot import client cert: exception unwrapping private key - java.security.NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available`) — that gets its own UC (next).

Ships as `server-v0.0.13` (paired with the crypto UC). No Android changes for this UC.

## Clarifications

- Q: Does this UC also fix the sibling `StreamProblemDetailsAdvice` (which UC-11 deferred with the same likely bug, no empirical proof yet)?
  A: Yes — same UC, same pattern. Analyst empirically tests whether the stream advice exhibits the same bug; if yes, migrates it using the same chosen fix shape in the same PR.
- Q: How should v0.0.12 be marked in the v0.0.13 release notes?
  A: Just upgrade. Release notes mention the fix but don't call out v0.0.12 as broken — normal version-to-version language.
