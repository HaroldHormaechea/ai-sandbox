# Use Case 71: Unlimited reconnection retries with a 10-second maximum backoff

## Summary
The reconnection policy (`ReconnectController`) is changed so the client retries a dropped connection **indefinitely** rather than giving up after the current 5-minute cumulative budget, and the exponential backoff is **capped at 10 seconds** instead of the current 30-second cap. Concretely: remove (or neutralise) the `shouldGiveUp()`/`GIVE_UP_AFTER_MS` give-up behaviour so reconnection never terminates on its own, and change the backoff schedule's ceiling so successive delays grow toward and then hold at 10 s (e.g. 1, 2, 4, 8, 10, 10, …). All consumers of the controller (the terminal/conversation streams, and the sessions-events feed if it uses the same machinery) inherit the new policy, and any UI that previously surfaced a "disconnected — tap to reconnect / gave up" terminal state must be reconciled with "always retrying" (see UC-70 and UC-72, which present this state).

## Acceptance Criteria
1. Reconnection retries are unlimited: the controller never enters a permanent give-up state on its own; `shouldGiveUp()` (or its replacement) returns false indefinitely while disconnected.
2. The backoff delay never exceeds 10 seconds: the schedule grows from its initial small delays and caps at 10 s for all subsequent attempts.
3. The early backoff progression remains sensible (small initial delays growing exponentially) up to the 10 s cap — it does not jump straight to 10 s on the first retry.
4. Existing UI/flows that depended on the old give-up (e.g. a "Disconnected — tap to reconnect" terminal affordance after 5 minutes) are reconciled: either removed or made consistent with perpetual retrying, with no dead/unreachable state and no crash.
5. The `attemptCount` (and next-retry timing used by UC-70/UC-72) continues to be available and keeps incrementing across the unlimited retries.
6. Unit tests cover: backoff caps at 10 s; the controller does not give up after the old 5-minute budget; attempt counting continues past the former cap.
7. QA verifies on the emulator: stop the server, leave the app disconnected well past 5 minutes, and confirm it is still retrying (no permanent give-up), with inter-attempt waits never exceeding ~10 s; restart the server and confirm it reconnects.

## Potential Pitfalls & Open Questions
- **Edge case** — `ReconnectController` currently has a 6-entry schedule ending at 30 s and a 5-minute give-up. Both must change consistently; ensure the new cap value (10 s) is the schedule's last/held value and that index clamping still works.
- **Risk** — Removing give-up means a perpetually unreachable server keeps the app retrying forever. Confirm this is desired (it is, per the request) and that it does not pin a wakelock/foreground service indefinitely in a battery-hostile way; reuse existing lifecycle gating so retries pause when appropriate.
- **Dependency** — Tightly coupled to **UC-70** (retrying background copy: with no limit, omit "remaining attempts") and **UC-72** (status light yellow/red). These should read the policy from the controller, not hardcode old constants. Land/merge order should keep them consistent.
- **Edge case** — Any code branching on `shouldGiveUp()` (foreground-service notification dismissal, toolbar "gave up" text) must be updated, not left dangling.
- **Assumption** — "Max back off 10 seconds" means the ceiling of the backoff schedule is 10 s; initial faster retries are retained.

## Original Description
"New use case, make the retries unlimited. Max back off 10 seconds"

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- Unlimited retries = remove the 5-minute cumulative give-up; controller never self-terminates.
- 10 s is the backoff ceiling; early exponential steps retained.
- UC-70 and UC-72 consume the controller's state and must stay consistent with "always retrying".
