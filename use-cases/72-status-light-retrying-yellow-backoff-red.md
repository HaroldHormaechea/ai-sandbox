# Use Case 72: Connectivity status light — yellow while actively retrying, red while waiting in backoff

## Summary
The tri-state server-connectivity light introduced in UC-54 (green/yellow/red on the Sessions screen) gains precise reconnection semantics. While the client is **actively making a reconnect attempt** (a connection attempt is in flight) the light is **yellow**; while the client is **waiting between attempts** (sitting in the backoff delay before the next attempt) the light is **red**. Green continues to mean connected/healthy. This makes the dot reflect the moment-to-moment reconnect cycle driven by `ReconnectController` (attempt-in-flight vs. backoff-wait), in concert with UC-70's retrying background and UC-71's unlimited-retry policy. The light therefore oscillates yellow→red→yellow as the reconnect loop attempts, fails, waits, and re-attempts, settling on green when a connection is established.

## Acceptance Criteria
1. When connected/healthy, the status light is green (unchanged from UC-54).
2. When a reconnect attempt is actively in flight (the client is trying to open the connection), the status light is yellow.
3. When the client is waiting out the backoff delay between attempts (no attempt currently in flight, next attempt scheduled), the status light is red.
4. The light transitions correctly across the reconnect cycle: on disconnect → yellow during the attempt → red during the ensuing backoff wait → yellow on the next attempt → green once connected.
5. The semantics are derived from the reconnect machinery's actual state (attempt-in-flight vs. backoff-wait), not a fixed timer, so they stay correct under UC-71's unlimited-retry / 10 s-cap policy.
6. The change is consistent with UC-70's retrying background and does not conflict with it (both reflect the same underlying reconnect state).
7. QA verifies on the emulator: stop the server and observe the light show yellow during each attempt and red during each backoff wait, cycling between them; restart the server and confirm it goes green.

## Potential Pitfalls & Open Questions
- **Missing input** — The reconnect state machine must expose a distinct "attempting now" vs "waiting for backoff" signal to the UI. `ReconnectController` models the delay schedule and attempt counter, but the *in-flight vs waiting* distinction may need to be surfaced explicitly (e.g. the stream client emitting an "attempting" event when it starts dialing and a "waiting" event when it begins `delay(nextDelayMs())`).
- **Edge case** — Fast attempts: a connection attempt may succeed or fail very quickly; the yellow phase could be brief. Ensure the light still reflects reality and doesn't get stuck on a stale state if events arrive out of order.
- **Dependency** — Builds on **UC-54** (the tri-state dot exists) and shares reconnect state with **UC-70/UC-71**. Reuse the same observable reconnect state to avoid divergence between the dot and the background message.
- **Ambiguity** — Existing UC-54 "yellow" may currently mean a broader "degraded/connecting" state. Resolved decision: narrow/define yellow = attempt-in-flight and red = backoff-wait for the reconnect cycle; preserve green = connected. If UC-54 used yellow for another condition (e.g. partial health), reconcile so the reconnect cycle uses yellow/red as specified without breaking the connected/green case.
- **Edge case** — Initial connect (app start) vs reconnect: decide whether the first-ever connection attempt also shows yellow (reasonable) — keep consistent with the reconnect attempt rendering.

## Original Description
"New UC, while actively retrying put the status light as yellow. When waiting for back off, red"

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- Yellow = a reconnect attempt is actively in flight; Red = waiting in the backoff delay between attempts; Green = connected (unchanged).
- Derive from the reconnect state machine (attempt-in-flight vs backoff-wait), not a timer, so it stays correct under UC-71.
- Reconcile with UC-54's existing yellow usage and share state with UC-70/UC-71.
