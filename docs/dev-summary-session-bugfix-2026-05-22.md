# Development Summary — Session create/delete bug fix

**Date:** 2026-05-22
**Branch:** `feat/session-create-delete-fix` → merged to `main` as `b14b207` (PR [#23](https://github.com/HaroldHormaechea/ai-sandbox/pull/23), squash)
**Releases:** `server-v0.0.18`, `android-v0.3.3`
**Method:** dev-team workflow (analyst → challenger → developer → QA), autonomous run.

## Problem reported

After opening the Android app on the same Wi-Fi as the server: tapping **New Session** added a row stuck at `starting`, but no `session_create` ever reached the server (no audit line). Deleting one of those phantom rows returned **500** (`SessionsVM: Delete N failed: internal_error (500) clean.sh exited non-zero`); the server logged `session_kill outcome:fail`.

These turned out to be **two independent defects** sharing one scenario — fixing create does not fix delete.

## Root causes & fixes

### BUG 1 — silent create (Android client) → `android-v0.3.3`
Two stacked sub-bugs:

- **Fix B — `SessionsApi.list()`** (`91c8072`). `list()` decoded envelope-first. The server returns a **bare JSON array**, and decoding a bare array with the object serializer **throws** (`JsonDecodingException`) *before* the `?:` bare-list fallback — so the fallback was dead code and `list()` always returned `HttpFailure(decode_error)`. Now parses the body to a `JsonElement` and branches: `JsonArray` → bare list, `JsonObject` → envelope. (Empirically verified the pre-fix decode throws rather than returning null — stable across kotlinx 1.6–1.9.)
- **Fix A — `SessionsViewModel` / new `SessionsCoordinator`** (`13f1e86`). `refresh()` set the profile only on list-*success* (which never happened due to Fix B), and `spawn()` alone read the profile from UI state → it short-circuited **before** the POST and leaked the optimistic `starting` row. Now sources the profile from the store, rolls back the optimistic row on no-profile, and sets the profile independent of list success. Orchestration was extracted into a plain-JVM `SessionsCoordinator` so it is unit-testable without Robolectric.

### BUG 2 — delete 500 (server) → `server-v0.0.18`
- **Fix C — `SessionFacade.deleteSession` / `SessionController.delete`** (`6f84ae8`). Delete ran `clean.sh` with no existence check, so deleting any non-existent N → non-zero exit → 500. Now `force=false` consults `registry.exists(n)` **inside the per-N lock**: absent ⇒ `NoSuchElementException` → **404 `session_not_found`** (clean.sh not run); present ⇒ clean ⇒ 204/500; an enumeration `IOException` propagates as **5xx, never masked as 404**. `force=true` keeps the unconditional-clean operator escape hatch. `openapi.yaml` regenerated (DELETE now documents 204/404/500). Layer-compliant (Controller→Facade→same-domain Service; no `@Transactional`; no entity/DTO leak).

## Test infrastructure (regression prevention)

The explicit goal was to validate the full create→list→delete flow so these can't regress. Covered on **both wire sides**:

- **Server e2e — `SessionsRestRoundTripTest`** (`deb198c`): real `@SpringBootTest` + Netty + TLS create→list→delete, mocking **only** the `ProcessExecutor` subprocess seam so the entire real stack runs. Named `*Test` (not `*IT`) so it runs **ungated** in `:server:test` — the `*IT` lane is excluded and DIND-gated, which would have silently skipped it.
- **Client dispatch guard — `SessionsCoordinatorTest`** (`858f8c7`): drives the real coordinator and asserts an **outbound `POST /v1/sessions` actually left the client** (`takeRequest`), not merely that a response was handled — the assertion that catches BUG 1. Plus no-profile rollback and a create→delete round-trip. Uses a real `Dispatchers.IO` scope with `takeRequest` barriers because `SessionsApi` hops to a non-injectable `withContext(Dispatchers.IO)` that virtual-time schedulers can't observe.
- **Decode contract — `SessionsApiTest`** (`858f8c7`): bare array → Success (RED on pre-Fix-B), envelope, empty array, problem+json → `HttpFailure(code)`.
- **Controller mapping — `SessionDeleteControllerTest`** (`deb198c`): 404 / 500 / 5xx-not-404 / 204 over real Spring/WebFlux.
- **Facade pins — `SessionFacadeTest`** (`deb198c`): existence-gate cases + the IOException-vs-NoSuchElement distinction.
- **fail-on-zero-tests guard — `android/build.gradle.kts`** (`b024adf`): a `TestListener` fails the build if the JUnit-5 unit-test task discovers zero tests (durable guard against the silently-skipped-test class of regression). NO-SOURCE-safe.

### Results
- **Server: green locally** — `:server:test` = 366 tests / 0 failures; `:server:spotlessCheck` green.
- **Android: CI-executed** — the dev environment has no Android SDK, so `:android:test` runs only in CI; android-ci executed and passed the new tests on the merged head.

### Honest limitation
No device-level Compose→server→docker test is CI-feasible. The two JVM halves above cover the full create→list→delete contract on both wire sides; only literal on-device Compose rendering remains manual.

## CI / merge / release record

| Stage | Outcome |
|---|---|
| PR #23 first CI run | android-ci **failed** — Kotlin syntax error in `SessionsCoordinatorTest` (a `/*` inside a KDoc opened a *nested* block comment, which Kotlin allows, swallowing the rest of the file). Server CI green. |
| Fix-back (1 round) | `86483bb` — reworded `/v1/sessions/*` → `/v1/sessions/{n}`; verified syntax-clean with a standalone Kotlin compiler pass. |
| PR #23 second CI run | All green — server-ci (build, integration, real-docker-integration, release-install-smoke) + android-ci (build, with `:android:test` executing). |
| Merge | Squash → `main` `b14b207`. |
| Releases | `server-v0.0.18` (assets: `.zip`, `_amd64.deb`) and `android-v0.3.3` (assets: `.aab`, `.apk`) — both workflows succeeded. |

## java-class-call-scanning — usefulness review

Requested as part of this run. **Verdict: low-to-modest value for this task.**

- **Provisioning is broken in v0.2.0.** The daemon `--project` CLI fallback documented in the profile does not work: query subcommands hash the *project dir* (e.g. `ca8dc24…`) while `daemon start` registers under a *classpath* hash (e.g. `fc82bd4…`), with no flag to bridge them, and the daemon enforces a wire-level ping hash-check that defeats a manual discovery-file bridge. The generated `.mcp.json` also uses `serve`, which is not a valid v0.2.0 subcommand.
- **What works:** the one-shot daemonless surface — `--compiled <paths> --print-hierarchy <FQN>` (caller/callee trees) and `--diff-stdin --export-format json` (impact). All four agents used this fallback.
- **Did it help?** Mildly. It confirmed `SessionController#delete` is the sole prod caller of `deleteSession` and classified the impacted `SessionFacadeTest` methods (prompting the developer to pre-flag the test that Fix C turned red). It did **not** surface the e2e round-trip test, because that reaches the changed methods over HTTP (WebTestClient) — bytecode-static analysis can't see HTTP/reflection dispatch. Plain `grep` + reading source reached every conclusion at least as fast.
- **Hard limit:** server Java only. ~70% of this change is the Android Kotlin client, which the tool cannot see at all.
- **Foot-guns recorded:** diff paths must be relative to the `--sources` root (default repo-relative paths silently return empty impact); RTK's `git diff` compaction can corrupt a piped diff (use a raw file).
- **Fix-forward (separate from this run):** correct the profile SKILL.md CLI-fallback section and `develop` Step 3b (`.mcp.json` `serve` → `daemon start --foreground`) to register the daemon under the `--project`-canonical key, or document the one-shot mode as the supported path.

## Out of scope

The older "app auto-closes when not on the same Wi-Fi" symptom was not pursued — it did not share a root cause with the silent-create bug. Flagged for a separate investigation if it recurs.
