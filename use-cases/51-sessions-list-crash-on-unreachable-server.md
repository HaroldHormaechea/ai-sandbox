# Use Case 51: App crashes when the management server is unreachable (uncaught `ConnectException` on `refresh()`/`spawn()`)

## Summary
When the management server is unreachable — cold-starting the app while the server is down, foreground-resuming after the server stopped, dropping the network, or tapping a control while offline — the Android app **hard-crashes** ("ai-sandbox keeps stopping") instead of degrading gracefully. Reproduced live against the emulator: with the server stopped, launching the app produces `FATAL EXCEPTION: main — java.net.ConnectException: Failed to connect to /<host>:<port>` on a `Dispatchers.Main.immediate` `StandaloneCoroutine`.

Root cause: in `SessionsCoordinator`, the `refresh()` and `spawn()` coroutines call `apiFactory(profile).list()` / `.spawn(label)` with **no `try/catch`**. The `SessionsApi` calls return `ApiResult` for HTTP-status failures but **throw** for transport failures (connection refused, timeout, unknown host, TLS). Because `refresh()`/`spawn()` launch on `viewModelScope` (the Main dispatcher) and don't catch the throw, the transport exception escapes uncaught and crashes the process. `refresh()` is invoked on `SessionsViewModel.init`, on every foreground `ON_RESUME`, and after a successful `spawn`/`delete`/`lifecycle`, so the crash is easy to hit.

This is the exact bug class already fixed for the sibling methods: `delete()` and `lifecycle()` wrap their API calls in `try/catch` (their in-code comments explicitly note "delete() previously had no try/catch, so a transport throw … escaped uncaught on viewModelScope (crash risk)"). `refresh()` and `spawn()` were **missed** by that hardening. The fix extends the same protection to `refresh()` and `spawn()` so a transport throw becomes a surfaced, recoverable error state — never a crash.

## Acceptance Criteria
1. Cold-starting the app while the server is unreachable does **not** crash; the sessions screen renders (empty/last-known list) with a surfaced "can't reach server" error state, and recovers on the next successful `refresh()` once the server is reachable.
2. Foreground-resuming the app (`ON_RESUME` → `refresh()`) while the server is unreachable does not crash.
3. Tapping **Spawn** ("New session") while the server is unreachable does not crash; the optimistic "starting" row is rolled back and an error is surfaced (mirroring the existing HTTP-failure rollback), and `spawning` is reset to `false`.
4. A transport throw from `refresh()`/`spawn()` (`ConnectException`, `SocketTimeoutException`, `UnknownHostException`, generic `IOException`/`SSLException`) is caught and converted to a `lastError` (or equivalent surfaced state) on the same `state` flow — `loading`/`spawning` are always cleared, so no stuck spinner/disabled FAB.
5. Behavior matches the already-hardened `delete()`/`lifecycle()` contract: do not double-surface an error the `AiSandboxHttpClient` interceptor already surfaced (use the same `TlsFailureTranslation.translate(...) == null` guard before raising a snackbar) — see UC-52 for the related interceptor-classification change; this UC's scope is solely preventing the uncaught crash in `SessionsCoordinator`.
6. No regression to the happy path (successful list/spawn), the UC-28 optimistic-terminating reconcile, the UC-32 push-apply paths (`applySnapshot`/`applyDelta` must remain untouched and never drive `loading`), or the UC-46 lifecycle pending flags.
7. A pure-JVM unit test (the coordinator is Robolectric-free by design) injects an `apiFactory` whose `list()`/`spawn()` throws a `ConnectException` and asserts: no exception propagates out of the launched coroutine, `loading`/`spawning` are cleared, and the error is surfaced — for both `refresh()` and `spawn()`.

## Potential Pitfalls & Open Questions
- **Coroutine-scope semantics** — The crash is an uncaught throwable on `viewModelScope` (Main). A `try/catch` inside the `launch` block is the surgical fix; do not swap the scope to a `SupervisorJob`-only swallow that would hide real bugs. Catch `Throwable` (as `delete()`/`lifecycle()` do) and re-surface, don't silently drop.
- **Don't double-surface** — The `AiSandboxHttpClient` interceptor may already emit a global `NetworkEvent` (full-screen routing) for SSL/IO failures before the call re-throws. Reuse the `delete()`/`lifecycle()` pattern: only set `lastError` for throwables `TlsFailureTranslation.translate(...)` returns `null` for, to avoid a snackbar + full-screen double surface. (The desirability of that full-screen routing for a plain `ConnectException` is UC-52's concern, not this UC's.)
- **Spawn rollback** — `spawn()` inserts an optimistic "starting" row before the call; on a transport throw it must roll that row back (like the existing `HttpFailure` branch) so a phantom session can't persist, and clear `spawning`.
- **Edge case** — A transport throw during the `spawn()`-triggered `refresh()` must also be safe (the nested `refresh()` is itself now guarded), so a spawn that succeeds server-side but whose follow-up refresh fails does not crash.

## Original Description
Functional check against a real backend + emulator, connection-drop behavior: with the server stopped, cold-starting the app crashes ("ai-sandbox keeps stopping"). Logcat shows `FATAL EXCEPTION: main — java.net.ConnectException` on a `Dispatchers.Main.immediate` coroutine. Traced to `SessionsCoordinator.refresh()`/`spawn()` calling the API without try/catch, unlike the already-fixed `delete()`/`lifecycle()`.

## Clarifications
- Q: Is it reproducible? A: Yes — force-stop app, ensure server down, launch → consistent crash with the same FATAL ConnectException on the main thread.
- Q: Which entry points trigger it? A: `refresh()` (init / ON_RESUME / post-spawn-delete-lifecycle) and `spawn()` — both lack try/catch; `delete()`/`lifecycle()` are already guarded.
- Q: Crash or just an error? A: Hard crash (process killed) — `FATAL EXCEPTION: main`, not a handled error.
- Q: Scope? A: Add the same transport-throw try/catch hardening to `refresh()` and `spawn()` (surface, don't crash), with a pure-JVM regression test. Reclassifying the connectivity error itself (so it isn't routed to the TLS-identity screen) is tracked separately in UC-52.
