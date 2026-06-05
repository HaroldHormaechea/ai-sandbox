# Use Case 34: Android terminal — crash tearing down the foreground service from the background on reconnect give-up / cert-revoke

## Summary
When a terminal stream loses the server, the process-scoped reconnect loop (`TerminalStreamController`, `android/src/main/kotlin/com/aisandbox/android/terminal/TerminalStreamController.kt`) keeps retrying independently of the UI lifecycle. If the server stays unreachable until the **5-minute cumulative cap** (`ReconnectController.GIVE_UP_AFTER_MS`) elapses, the state flips to `TerminalState.GaveUp`; if the server tears the WS down with code 4401 it flips to `Revoked`. The terminal screen reacts to these states by tearing down the dataSync foreground service: `TerminalScreen.kt` (`LaunchedEffect(state, …)`) calls `TerminalForegroundService.stop(context)`, which (`TerminalForegroundService.kt`, `stop()` helper) issues `context.startService(Intent(...).setAction(ACTION_STOP))`. The whole point of the dataSync FGS is to keep the stream alive across a **locked screen / backgrounded app**, so the cap routinely elapses (and the notification's "Disconnect" action routinely fires) while the Activity is **not foreground**. On Android 8+ (this app is `minSdk 29` / `compileSdk` latest, so the restriction is fully active) calling `Context.startService(...)` from a background app throws `IllegalStateException` / `BackgroundServiceStartNotAllowedException` — crashing the app. This is the **dominant server-disconnection crash class**: it is the designed-for path (disconnect while backgrounded), not an edge case. The same `stop()` call site is also reached from `TerminalViewModel.disconnect()` and `deleteSession()` and from the notification "Disconnect" action — all of which run while the UI is not in the foreground.

(Source: QA disconnect-path audit, 2026-06-05. The off-main-thread emulator-feed hypothesis was investigated and **disproved** — the vendored `terminal-emulator/.../TerminalSession.java` marshals `appendToEmulator` to the main-thread handler, so `WsTerminalSession.feed` is thread-safe. This UC is the real crash.)

## Acceptance Criteria

1. With the terminal `Open` and the app **backgrounded** (screen locked / in recents / another app foreground), killing the server and letting the 5-minute reconnect cap elapse (→ `GaveUp`) must **not** crash; the foreground-service notification is dismissed cleanly and the stream resources are released.
2. Tapping the ongoing notification's **"Disconnect"** action while the app is backgrounded must **not** crash and must actually stop the service + dismiss the notification.
3. A server-driven **4401 revoke** received while the app is backgrounded must **not** crash; the cert-revoked dialog (UC04-7) still shows on next foreground.
4. The FGS teardown uses a mechanism that is **legal from the background** — e.g. `stopService(...)`, a bound-service stop, or `NotificationManager.cancel(NOTIFICATION_ID)` + the running service self-stopping — rather than `startService(ACTION_STOP)`.
5. No notification leak: after any of the above teardown paths, no zombie "FOREGROUND · dataSync" notification remains, and `TerminalForegroundService` is actually stopped (`stopSelf` reached), verified on the next app foreground.
6. A regression test exists (Robolectric or instrumented) that drives the `GaveUp`/`Revoked`/explicit-disconnect transitions with the host in a **non-`RESUMED`** lifecycle state and asserts no exception is thrown and the service is stopped. (No `TerminalForegroundService` test exists today — that gap is why this is unguarded.)
7. No regression to UC-04 AC21 (the stream survives a locked screen while still connected) or UC-21 AC#8 (stream/emulator survive back-navigation).

## Potential Pitfalls & Open Questions
- **Do NOT just gate the effect on `Lifecycle.RESUMED`.** That would skip the teardown when give-up happens in the background and **leak** the FGS notification forever. The teardown must still run — it must only use a background-legal API.
- **`dismissNotification()` exists but is unwired.** `TerminalForegroundService.dismissNotification(context)` cancels the notification but does not `stopSelf()` the running service; using it alone leaves the service running. Pair it with an actual service stop.
- **Verify `stopService` on the target API.** `stopService()` is subject to fewer restrictions than `startService()`, but confirm on `compileSdk` (target API ~36). The most robust design is to have the **already-running service observe `controller.state`** and self-stop on `GaveUp`/`Revoked`/explicit-disconnect, so the UI never needs to issue any `start*Service`/`stopService` command for teardown.
- **Shared `LaunchedEffect`.** The same effect also handles the `Open` edge (which starts the FGS — see UC-35); a fix should treat the start and stop edges together so neither fires an illegal background `start*Service`.
- **Reducing repro time.** To live-verify without waiting 5 minutes, temporarily lower `ReconnectController.GIVE_UP_AFTER_MS`; watch `adb logcat | grep -E "AndroidRuntime|BackgroundServiceStartNotAllowed"`.

## Original Description (user, 2026-06-05)
> Generate one UC per crash QA sees on the Android app, especially related to server disconnections.

(This crash class was identified by the QA disconnect-path audit; it fires specifically on a server disconnection that outlasts the reconnect cap, or a server-driven revoke, while the app is backgrounded.)

## Notes
- repro_status: **static-high-confidence** (live repro blocked this run by the enrollment/camera/spawn.sh constraints — see UC-32/33 notes). severity: crash-on-common-disconnect.
- Files most likely to change: `android/src/main/kotlin/com/aisandbox/android/terminal/service/TerminalForegroundService.kt`, `android/src/main/kotlin/com/aisandbox/android/ui/screens/TerminalScreen.kt`, possibly `TerminalStreamController.kt` (to expose state to a self-managing service). Tests: new `android/src/test/**` (Robolectric) or `androidTest`.
- Related: UC-35 (the start-side sibling crash), UC-21 (process-scoped controller + FGS), UC-28 (delete/terminate path that also calls `stopForegroundService`).
