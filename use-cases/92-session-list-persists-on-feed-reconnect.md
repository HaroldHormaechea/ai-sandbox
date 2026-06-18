# Use Case 92: Session list stays visible (and recovers fast) when the events feed reconnects after a delete

## Summary
On the Android client's Sessions list, deleting a session causes the live events-feed WebSocket (`/v1/sessions/events`) to drop transiently — the events socket and the blocking `DELETE` share one `OkHttpClient`, and `clean.sh` teardown churns the connection. When the feed enters `RECONNECTING`, `SessionsScreen` replaces the entire list region with a full-screen "retrying…" background (`RetryingBackground`), even though the last-known session rows are still held in state (`SessionsCoordinator.applyFeedStatus` deliberately preserves them). The result is a blank list. Recovery is then slow because `ReconnectController` backoff climbs to a 10s cap (reset only on a successful open) and the controller does not proactively REST-refresh during backoff, so the user waits up to ~10s per cycle — or force-quits, which resets backoff and repaints from REST instantly. This is a **client-only** fix; the server delete path (`SessionController.delete` → `SessionFacade.deleteSession`) and the events feed are correct and unchanged. The fix makes the reconnect indicator non-destructive (keep known rows visible, with a slim "Reconnecting…" banner above the list) and shortens recovery (on a transient drop, immediately re-fetch the list over REST and reset reconnect backoff so the socket reopens quickly).

**The same blank-list-then-reconnect symptom also occurs on back-navigation** from a session screen (terminal or conversation) to the Sessions list — not only on delete. The Sessions list binds its events feed to `repeatOnLifecycle(Lifecycle.State.STARTED)`: entering a session STOPs the list screen and calls `disconnectEvents()` (closing the events socket); navigating back re-STARTs it and calls `refresh()` + `connectEvents()`. During that reconnect window the same `RetryingBackground` precedence blanks the still-in-memory rows. The fix below (non-destructive indicator + fast recovery) must cover this trigger as well as the delete trigger.

## Acceptance Criteria
1. Deleting a session never blanks the list while previously-known rows exist in state: the deleted row disappears (or shows a terminating state) and all other rows remain visible throughout the events-feed reconnect.
2. While the feed is `RECONNECTING`/`STOPPED` **and** at least one known session row exists, the list shows those rows plus a slim non-destructive "Reconnecting…" banner/header above the list — not the full-screen `RetryingBackground`.
3. The full-screen `RetryingBackground` is shown only when there are genuinely zero known session rows to display.
4. After a delete-induced transient drop, the visible list returns to an accurate, connected state within a short bound (target ≤ ~3s under a normal local server), without requiring an app restart/force-quit.
5. On a transient events-socket drop, the client immediately triggers a REST refresh of the session list **and** resets/shortens the reconnect backoff, rather than waiting out the full exponential backoff delay.
6. Genuine offline/extended-outage behavior is preserved: when the server is actually unreachable and no rows are known, the user still sees the full-screen retrying state and the controller keeps retrying (unlimited retries, capped backoff) as before.
7. If the deleted session was the only session, the list shows the normal empty state — not the retrying background.
8. No server-side change is required; existing server tests remain green.
9. An Android test reproduces the regression: "delete a session → events feed reconnects → the remaining rows stay visible (no blank list) and a reconnect indicator is shown", and a test covers the fast-recovery path (transient drop triggers REST refresh + backoff reset).
10. Returning to the Sessions list from a session screen (terminal or conversation) does not show a blank list while previously-known rows exist: on re-entry the list shows the last-known rows immediately (repainted from REST) with at most the slim reconnect indicator while the events socket re-opens — never the full-screen `RetryingBackground` when rows are known. A test covers the "enter session → back to list → rows still visible" path.

## Potential Pitfalls & Open Questions
- **Edge case** — Distinguishing a *transient* drop (refresh + reset backoff quickly) from a *real* outage (don't hammer the server). Needs a heuristic, e.g. fast-reset only for the first quick reconnect after a clean/non-error close, or only within a short window after a local mutation (delete) — leave the exact heuristic to the dev-team, but it must not turn a genuine outage into a tight retry loop (AC 6).
- **Risk** — The slim banner must not cause layout jank or push rows around on every flap; prefer an overlay/sticky header that does not reflow the list content.

## Original Description
When I remove a session, the server list shows then no servers for a while and begins reconnecting. I have to either wait a lot, or force quit the app to restore normal behavior.

(Added by the user during analysis:) It may also sometimes happen when going back to the list from within a session.

## QA Root-Cause Investigation (provided as input)
- **Server-side is CLEAN:** `DELETE /v1/sessions/{n}` (`SessionController.delete` → `SessionFacade.deleteSession`) never closes the `/v1/sessions/events` WebSocket, never revokes identity (no 4401), never touches `SessionEventBroadcaster`. `SessionEventWatcher` correctly emits a `delta` with `removed:[n]`, not an empty snapshot. No server change is required.
- **PRIMARY client bug (guarantees the "no sessions" symptom):** `SessionsScreen.kt:340-346` renders `RetryingBackground` (which replaces the whole list region) whenever `state.showRetryingBackground` is true, i.e. `feedStatus` phase == `RECONNECTING` or `STOPPED` (`SessionsViewModel.kt:274-275`, `SessionsFeedStatus.kt:81`). But `applyFeedStatus` intentionally **preserves** the last-known rows (`SessionsCoordinator.kt:225-227`). So a reconnecting feed wipes the visible list even though the rows are still in memory.
- **WHY the feed drops on delete:** the events WS and the blocking `DELETE` share one `OkHttpClient` (`AiSandboxHttpClient.kt:52`, `SessionsViewModel.kt:62-64`); `clean.sh` teardown churns the connection and the half-open events socket drops, pushing `SessionEventsController.startConnectLoop` (`SessionEventsController.kt:195-257`) into `RECONNECTING`.
- **SLOW-RECOVERY tail ("wait a lot / force-quit"):** `ReconnectController` backoff is 1,2,4,8,10,10… capped at 10s, `attempt` only reset on a successful `Open` (`ReconnectController.kt:49-82`, `SessionEventsController.kt:175`), retries unlimited by default. The controller does not proactively REST-refresh during backoff, so the wiped list persists for the full backoff delay. Force-quit "fixes" it only because a fresh process resets backoff and repaints from REST.

## Clarifications
- Q: How should the reconnecting state look while known session rows still exist?
  A: Slim "Reconnecting…" banner/header above the list, keeping all known rows visible. Full-screen retrying state only when zero rows are known.
- Q: How should recovery be sped up after a delete-induced transient drop?
  A: On a transient drop, immediately re-fetch the list over REST so rows repaint at once, AND reset/shorten reconnect backoff so the socket reopens quickly (~1s).
- Q: Also isolate the events feed onto its own OkHttpClient so a blocking DELETE can't churn it offline?
  A: Keep as a follow-up. Scope this UC to the UI-precedence fix + fast recovery; dedicated-connection isolation is a separate future UC.
