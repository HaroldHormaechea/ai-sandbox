# Use Case 20: Android client — swipe-to-delete a session (reveal red + black-outlined trash, confirm, and actually delete)

## Summary
On the Android sessions list, deleting a session is broken: the current path is **long-press → `DeleteSessionDialog`**, and confirming "does nothing" — the operator's "clicking prompts to remove it but nothing happens." Under the hood, confirm calls `SessionsViewModel.delete` → `SessionsCoordinator.delete` → `SessionsApi.delete` (`DELETE /v1/sessions/{n}`, treated as success only on HTTP 204, with optional `?force=true`); on failure the coordinator only sets `state.lastError`, which `SessionsScreen.kt` never renders, so a non-204 response — or a 204 that doesn't actually tear the container down (cf. UC-15's docker-compose issues) — looks like a silent no-op. UC-20 replaces the delete affordance with a **swipe-to-dismiss gesture**: swiping a row to the left reveals a red background with a **black-outlined trash-can** icon; releasing past the activation threshold raises a delete confirmation (still offering the existing **force** toggle for sessions with attached streams); confirming **actually deletes** the session — container torn down server-side, row removed and not reappearing — and any failure is surfaced to the user rather than swallowed. The long-press delete is removed in favor of swipe. The fix spans the Compose UI (a `SwipeToDismissBox` whose threshold release opens the confirm dialog instead of auto-dismissing), the client delete/error-surfacing path, and the server's `DELETE /v1/sessions/{n}` handler if diagnosis points there. It must not regress UC-18's tap-to-open behavior and must stay within the dark-theme M3 Expressive design system.

## Acceptance Criteria
1. Swiping a session row to the left reveals a red/destructive background with a black-outlined trash-can icon behind the row, scaling with the drag.
2. Releasing past the activation threshold opens a delete-confirmation prompt; releasing before the threshold snaps the row back with no prompt and no deletion.
3. Cancelling the confirmation leaves the session intact and returns the row to its resting position.
4. Confirming deletes for real: the server tears down the container and the row disappears after the post-delete refresh and does not reappear.
5. A failed delete (any non-success response, or a network/pin error) is surfaced to the user (e.g. snackbar/toast with the error code + status) — never a silent no-op — and the row returns to its resting position.
6. For a running session with active streams, the confirm step still presents the existing `force` toggle, and force-delete works (no regression to UC-04 behavior).
7. Long-press no longer triggers delete (the long-press → dialog path is removed); swipe-left is the sole delete affordance.
8. Tapping a row still opens the terminal (no UC-18 regression); the swipe and tap gestures do not interfere with each other.
9. End-to-end correctness across layers: if diagnosis finds a server defect, `DELETE /v1/sessions/{n}` reliably tears the container down and returns 204, and the client correctly treats 204 as success and refreshes.
10. Coverage: instrumented Compose tests (swipe reveals the affordance; threshold release opens the dialog; confirm deletes and the row disappears; cancel restores; force toggle shown when attached) runnable via the `android-testing` skill; unit tests on `SessionsCoordinator.delete` for the success and failure-surfacing paths; a server-side test if a server fix lands.

## Potential Pitfalls & Open Questions
- **Edge case (Compose)** — `SwipeToDismissBox` normally auto-settles to *dismissed* on threshold release; here release must open the confirm dialog **without** removing the row, then delete only on confirm (use `confirmValueChange` to fire the dialog and return `false`, and reset the dismiss state on cancel). Mis-handling makes the row vanish before confirmation or the dialog never fire.
- **Risk (root cause — diagnose first)** — "does nothing" may be a server 204-without-teardown (cf. UC-15's `docker compose` teardown/enumeration issues) or a non-204 response the UI swallows; these need different fixes. The analyst should reproduce against a real session + local management server (the `android-testing` skill connects the emulator to a local server) and identify the failing layer before implementing.
- **Risk** — `lastError` is produced by `SessionsCoordinator` but never rendered by `SessionsScreen.kt`; the AC5 surfacing likely needs wiring into the global `NetworkEvents`/snackbar host — confirm the surfacing mechanism and the relevant string resources.
- **Assumption (styling)** — the red background maps to the M3 `error` color; the user explicitly asked for a **black-outlined** trash icon. Honor that literally (outlined `Delete` icon, black tint) even though M3 would normally pair `error` with `onError`; flag if it clashes with the dark-theme palette or contrast guidance.
- **Edge case** — an in-flight list refresh during a swipe (rows are keyed by `n`) must not desync the dismiss-anchor state or resurrect a just-deleted row.

## Original Description
> This is for the Android client. The delete functionality does not work. Clicking on a session prompts to download it, but it does nothing (i don't know if it is a server or client failure).
> The actual behaviour should be to slide to the left the session to remove it -> Sliding to the left should show a red background with a black outlined thrash can, and releasing the slide at that point should prompt the delete confirmation, and actually do the deletion properly.

## Clarifications
- Q: You said clicking a session "prompts to download it but does nothing." In the current code, tapping a row opens the terminal screen (not a delete/download). Which best matches what you actually see?
  A: "download" was a mistype for **remove** — the delete path is the whole issue; there is no separate tap/terminal bug.
- Q: Today delete is triggered by long-pressing a row. Once swipe-to-delete exists, what should happen to long-press?
  A: Swipe replaces long-press — remove the long-press → delete-dialog path entirely.
- Q: You're unsure whether the broken deletion is client- or server-side. How should UC-20 scope the fix?
  A: Both in scope — diagnose and fix wherever the failure is (Android client and/or the server's `DELETE /v1/sessions/{n}` handler + container teardown).
- Q: A running session can have attached terminal streams; the current dialog offers a "force" toggle. In the new swipe → confirm flow, how should attached sessions be handled?
  A: Keep the force toggle in the confirm step (preserve the existing safeguard).
