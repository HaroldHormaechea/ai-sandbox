# Use Case 70: Sessions list — "Not connected, retrying…" background while disconnected/reconnecting

## Summary
On the Sessions screen (`SessionsScreen.kt`), when the live session-events connection is down and the app is retrying (disconnected/reconnecting), the list is emptied as it is today, but the empty background now shows an informative, unobtrusive status message instead of a blank/empty area. The message reads "Not connected, retrying…" in **light grey**, and surfaces the retry progress derived from the reconnect controller: the current attempt count, the time until the next retry attempt (a live countdown), and — only if a retry limit actually exists — how many attempts remain / the give-up budget. Once the connection is (re)established, the message disappears and the list repopulates from the live feed. This is a presentation-only state tied to the existing reconnect/backoff machinery (`ReconnectController`: attempt counter, backoff schedule, and — until UC-71 — the 5-minute cumulative give-up).

## Acceptance Criteria
1. While the sessions-events connection is disconnected and retrying, the Sessions screen shows a centered background message in light grey reading "Not connected, retrying…".
2. The list area is empty during this state (no stale rows), consistent with current behaviour.
3. The message shows the current reconnect attempt count.
4. The message shows a live countdown to the next retry attempt (derived from the backoff schedule), updating as time passes.
5. If — and only if — a retry limit exists (e.g. the cumulative give-up budget), the message communicates the remaining budget / attempts; if retries are unlimited (see UC-71) the message omits any limit/remaining wording and just shows attempt + next-retry.
6. When the connection is restored, the message is removed and the sessions list repopulates from the live feed without requiring a manual action.
7. The message styling is light grey and visually subordinate (a background/empty-state treatment, not an alarming error banner) — consistent with the app's existing connectivity treatments (e.g. the UC-54 tri-state dot) and not re-introducing the dismissed UC-52 blocking screen.
8. QA verifies on the emulator: kill/stop the server, observe the empty list show the light-grey retrying message with attempt count and next-retry countdown, then restart the server and confirm the message clears and rows return.

## Potential Pitfalls & Open Questions
- **Edge case** — Distinguish "connected but genuinely zero sessions" from "disconnected/retrying": the empty-state copy must only show the retrying message when actually disconnected/reconnecting, and show the normal empty state when connected with no sessions.
- **Missing input** — The Sessions feed path (`SessionEventsClient`/controller) must expose attempt count + next-retry timing to the UI. `ReconnectController` already tracks `attemptCount` and the backoff schedule, but the next-retry *timestamp* (for a live countdown) may need to be surfaced/observable. Confirm the sessions feed uses `ReconnectController` (the terminal/conversation streams do) or wire equivalent state.
- **Dependency** — Interacts with **UC-71** (unlimited retries, 10s max backoff): criterion 5's "limit" branch must reflect whichever give-up policy is in effect after UC-71 lands. Implementations should read the policy from the controller rather than hardcoding "5 min" or "no limit".
- **Edge case** — Countdown accuracy: the countdown should track the actual scheduled next attempt; if an attempt fires early (e.g. network event) the message should resync rather than show a stale timer.
- **Assumption** — Scope is the Sessions list background only (per the request). Per-conversation connection banners are out of scope here.
- **Risk** — Avoid reintroducing the connection-error flicker loop fixed in UC-56: the retrying state must be stable, not flicker between empty/error/list.

## Original Description
"New use case... When disconnected from the server or retrying to connect, replace the server list with an empty one as now, but the background should say not connected, retrying ... With a counter or whatever if we actually have a limit. And when the next Retry is going to be. Light grey."

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- Message shows attempt counter + next-retry countdown always; limit/remaining wording only if a retry limit exists (and after UC-71 lands, retries are unlimited so that branch is omitted — read the policy from the controller).
- Light-grey, subordinate background empty-state treatment, not an error banner; do not reintroduce the UC-52 blocking screen or UC-56 flicker.
