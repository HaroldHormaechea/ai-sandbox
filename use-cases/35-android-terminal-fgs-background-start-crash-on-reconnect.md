# Use Case 35: Android terminal — crash starting the foreground service from the background when a stream reconnects while backgrounded (Android 12+)

## Summary
The start-side sibling of UC-34. When a terminal stream drops and the process-scoped reconnect loop (`TerminalStreamController`) later **succeeds** in reconnecting, the stream state transitions back to `TerminalState.Open`. The terminal screen's `LaunchedEffect(state, …)` in `TerminalScreen.kt` reacts to `Open` by (re)asserting the dataSync foreground service via `TerminalForegroundService.start(context, …)` → `TerminalForegroundService.kt` `start()` → `context.startForegroundService(intent)`. Because the reconnect loop is process-scoped and fully decoupled from the UI lifecycle (`TerminalStreamController.startConnectLoop`), an `Open` transition can land while the app is **backgrounded** — e.g. the user backgrounds the app during a `Reconnecting` state and the server then comes back. On **Android 12+ (target API ~36)** a background `startForegroundService(...)` throws `ForegroundServiceStartNotAllowedException` unless a documented exemption applies; a WS reconnect succeeding in the background qualifies for none — so the app crashes. This is lower-frequency than UC-34 (it requires a reconnect to land precisely while backgrounded) but is a concrete, distinct mechanism with a distinct fix surface (start vs. teardown).

(Source: QA disconnect-path audit, 2026-06-05.)

## Acceptance Criteria

1. Backgrounding the app while the terminal is in a `Reconnecting` state, then restoring the server so the reconnect **succeeds** (→ `Open`) while still backgrounded, must **not** crash.
2. The dataSync FGS is (re)asserted only from a context where a foreground service start is **legal** (foreground), or via a path exempt from the Android 12+ background-start restriction — while still satisfying UC-04 AC21 (stream protected from low-memory kill across a locked screen).
3. The first-ever `Open` (initial connect) continuing to start the FGS correctly while the screen is foreground is preserved (no regression to the normal connect path).
4. A regression test drives an `Open` transition with the host in a **non-`RESUMED`** lifecycle state and asserts no `ForegroundServiceStartNotAllowedException` is thrown.
5. Handled together with UC-34: the single shared `LaunchedEffect` must not issue an illegal background `start*Service` on **either** the `Open` (start) or `GaveUp`/`Revoked` (stop) edge.

## Potential Pitfalls & Open Questions
- **Don't just try/catch the start.** Swallowing `ForegroundServiceStartNotAllowedException` leaves the FGS **not started**, so the reconnected stream loses its low-memory protection in the background — defeating UC-04 AC21. Prefer asserting the FGS **once while foreground** and letting the running service persist across the background window, rather than re-issuing `startForegroundService` on every `Open` transition.
- **Exemptions are fragile.** The Android 12+ background-FGS-start exemption list (e.g. recent notification action, high-priority FCM) is narrow and version-dependent; do not rely on an incidental exemption — design the start to happen from a legal context.
- **Interaction with UC-34.** A combined fix where the service owns its own lifecycle (started once on first foreground `Open`, self-stopping on terminal states by observing `controller.state`) resolves both this UC and UC-34 without any background `start*Service`/`stopService` from the UI.
- **Repro timing.** The reconnect must complete while backgrounded; to make this deterministic, drop the server during `Open`, confirm `Reconnecting`, background the app, then bring the server back within a backoff window. Watch `adb logcat | grep -E "AndroidRuntime|ForegroundServiceStartNotAllowed"`.

## Original Description (user, 2026-06-05)
> Generate one UC per crash QA sees on the Android app, especially related to server disconnections.

(Identified by the QA disconnect-path audit; fires when a stream that dropped on a server disconnection reconnects while the app is backgrounded.)

## Notes
- repro_status: **static-medium-confidence** (live repro blocked this run — see UC-32/33 notes). severity: crash-on-edge-disconnect.
- Files most likely to change: `android/src/main/kotlin/com/aisandbox/android/ui/screens/TerminalScreen.kt`, `android/src/main/kotlin/com/aisandbox/android/terminal/service/TerminalForegroundService.kt`, possibly `TerminalStreamController.kt`. Tests: new `android/src/test/**` or `androidTest`.
- Related: UC-34 (the teardown-side sibling — fix both together), UC-21 (process-scoped controller + FGS).
