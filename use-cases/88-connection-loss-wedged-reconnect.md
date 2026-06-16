# Use Case 88: Frequent connection loss requiring a full app restart (chat → list back-navigation)

## Summary
On the ai-sandbox **Android client**, the live connection to the management server is frequently lost and does **not** auto-recover — the user must fully kill and relaunch the app to get back online. It reproduces especially when navigating **back from a conversation/chat to the sessions list**. This sits directly on top of three prior fixes that should already cover it: UC-52 (transient connectivity reclassified away from the destructive TLS/identity screen, with auto-recovery), UC-56 (no re-scan-QR/Quit *loop* on returning to the list), and UC-70/71/72 (unlimited reconnect retries with a ≤10s backoff and a tri-state status light). Despite these, the connection still ends up **wedged** on this transition: the app looks/behaves as disconnected and the auto-reconnect machinery never recovers it until the process is restarted. The likely mechanism is a lifecycle/ownership bug rather than a connectivity-classification bug: the live connection (WebSocket / event stream and its coordinator/`CoroutineScope`) is owned by, or cancelled when leaving, the conversation screen, and on returning to the sessions list it is neither re-subscribed nor handed back to a list-level owner — leaving a dead socket the reconnect logic either never re-arms or considers "healthy". The fix must make the app transparently re-establish (or retain) the live connection across the chat→list transition and auto-recover whenever the server is reachable, with **no manual restart**, while preserving the UC-52/UC-54/UC-56 routing behaviour. The reported scope is the chat→list back-navigation specifically (not primarily the backgrounding path covered by UC-34/35).

## Acceptance Criteria
1. Navigating back from a conversation to the sessions list keeps (or transparently re-establishes) the live server connection — when the server is reachable, **no manual app restart is ever required** to recover.
2. A reproduction of the chat→list back-navigation under realistic conditions shows the sessions list returning to a healthy live-connected state (live session statuses updating per UC-32) within a bounded time after return, without restarting the app.
3. If the connection does momentarily drop on/after the transition, the existing auto-reconnect (UC-70/71/72: unlimited retries, ≤10s max backoff) **actually engages and recovers**, and the UC-72 status light correctly reflects retrying (yellow) / backoff (red) / healthy states throughout.
4. The wedged state is eliminated: there is no condition reachable via chat→list where the app stays disconnected indefinitely while the server is reachable (i.e. no leaked-cancel of the connection scope and no reconnect loop that believes a dead socket is alive).
5. No regression to UC-56 (no re-scan-QR/Quit loop on return to list), UC-52 (connectivity-vs-TLS classification), UC-54 (tri-state dot, no blocking banner), or UC-32 (server-push live status).
6. A regression test reproduces the chat→list transition and asserts the connection/subscription is alive afterward (the event-stream subscription is re-owned by the list and not left cancelled, and a dropped socket re-arms the reconnect path).
7. CI gates pass: `:android:test` and `:android:lint`; and `:server:test` + `:server:spotlessCheck` if any server code is touched.

## Potential Pitfalls & Open Questions
- **Assumption** — The root cause is a connection-lifecycle/ownership bug (a scope tied to the conversation screen that is cancelled and never re-owned by the list), not a connectivity-classification regression. The analyst MUST isolate the precise mechanism on a live emulator repro before the developer changes the connection/coordinator wiring — it could instead be a reconnect state machine that latches "connected" against a dead socket.
- **Edge case** — Distinguish "wedged forever until restart" (this UC) from a transient drop that *does* recover via UC-70/71/72 backoff; the test must assert eventual healthy recovery, not merely that a retry was scheduled.
- **Risk** — The fix must not weaken UC-52's genuine TLS/identity routing or re-introduce the UC-56 loop; over-eagerly re-subscribing on every recomposition could reignite the flicker loop UC-56 closed.
- **Edge case** — Although the reported trigger is chat→list, the same wedged-connection mechanism may also surface after backgrounding (UC-34/35 FGS reconnect paths); the analyst should note whether the root cause is shared, even though backgrounding is out of the primary reported scope.
- **Ambiguity** — "Restart the app" is the user's recovery today; whether a foregrounded reconnect tick, an explicit pull-to-refresh, or only a cold process start currently recovers it is a useful diagnostic the analyst should capture during repro.

## Original Description
We lose connection to the server very often and I have to restart the web app to recover it. It specially happens when going back from one chat to the list view.

## Clarifications
- Q: Which client is it, and what is the exact symptom when it happens?
  A: The **Android app**; after chat→list it gets **wedged** (looks disconnected / dead) and never auto-recovers until the app is killed and relaunched. ("Web app" in the original description refers to the Android client.)
- Q: Does it also happen after backgrounding the app, or only on the chat→list back-navigation?
  A: Mainly on the chat→list back-navigation — scope the fix there (note any shared root cause with the backgrounding/FGS paths, but backgrounding is not the primary scope).
- Note: Closely related prior art — UC-52 (connectivity vs TLS reclassification + auto-recovery), UC-56 (no re-scan-QR/Quit loop on returning to the list), UC-70/71/72 (unlimited reconnect retries, ≤10s backoff, tri-state status light). This UC is the still-manifesting **wedged, no-auto-recovery** gap on the chat→list transition beyond those fixes.
