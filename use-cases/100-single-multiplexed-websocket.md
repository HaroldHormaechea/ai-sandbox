# Use Case 100: Single multiplexed WebSocket connection between the Android client and the server

## Summary
The Android client currently opens **three separate long-lived WebSockets** to the management server — a per-session terminal `stream` (`StreamClient`), a per-session `conversation` (`ConversationClient`), and one global `sessions/events` feed (`SessionEventsClient`) — plus REST calls with OkHttp's `retryOnConnectionFailure(true)`. The server defends itself with a **per-IP connection limiter at the TLS layer** (`PerIpRateLimiter` + `RateLimitingChannelHandler`), capped at **10 new connections / 10s** and **10 concurrent** per source IP, evaluated *before* the TLS handshake, so it keys on IP and never on mTLS identity — the whole device shares one budget. When the user deletes a session and creates a new one, all three sockets tear down and re-dial inside the same window while three uncoordinated reconnect loops converge, bursting past the cap; the storm then self-sustains ("fully rate limited"). Confirmed in the server log: **1102 `Rate-limit reject ip=… identity=harold-phone`** lines, clustered several-per-second across distinct source ports, each trailed by `Connection has been closed`, immediate `stream_open`/`conversation_open` reconnects, and `Failed to select the application-level protocol` (socket closed pre-TLS/ALPN). The fix collapses all realtime traffic onto **one multiplexed WebSocket** using a **fresh, typed envelope** — `{channel, sessionId, type, seq, payload}` — that multiplexes logical channels (per-session stream, per-session conversation, global session-events, control) over a single connection; the existing typed payload models are carried unchanged inside the envelope, and per-channel capability negotiation happens inside the envelope handshake. The client opens/closes sessions via **subscribe/unsubscribe frames** instead of new TCP connections. This is a **hard cut with matched versions**: the three legacy WebSocket endpoints are removed and a new client requires a new server (and vice-versa), released together on both tracks. **REST stays as-is** (HTTP/OkHttp keep-alive), since the WebSocket storm is the culprit. The per-IP limiter remains enabled and unchanged as defense-in-depth.

## Acceptance Criteria
1. A normal **delete-session-then-create-session** flow from a single device opens **no more than one** realtime TCP connection (the multiplexed WebSocket) and produces **zero `Rate-limit reject`** server-log entries under normal interactive use.
2. Terminal streaming, structured conversation, and the global session-events feed all function over the single connection with **correct, discriminated typing** — envelope payloads deserialize to the existing typed models (`StreamServerMessage`, `ConversationServerMessage`/`ConversationClientMessage`, `SessionEventMessage`, `ControlMessage`) with no behavioral regression.
3. The envelope uses a **fresh framing** `{channel, sessionId, type, seq, payload}`; per-channel capability/caps negotiation occurs inside the envelope handshake at connect time (not via separate per-endpoint subprotocols).
4. **Opening** a session sends a subscribe frame and **closing/deleting** it sends an unsubscribe frame over the existing connection; neither opens nor closes a TCP connection. The swarm/multi-session view subscribes to multiple session channels over the one connection.
5. On session **delete**, the server promptly tears down that session's stream/conversation tails in response to the unsubscribe — no leaked tails, no `conversation tail start failed`-style dangling-tail errors.
6. Reconnect of the single connection is **coordinated by one backoff controller**; on reconnect the client **re-subscribes** to exactly the channels active before the drop, with no duplicate subscriptions.
7. **Per-channel fairness is required and tested**: a large terminal-output burst on one channel does **not stall** conversation or session-events frames on the shared connection (e.g. via chunk interleaving / per-channel credit), and per-channel message ordering is preserved.
8. **Hard cut**: the three legacy WebSocket endpoints (`stream`, `conversation`, `sessions/events`) are removed from the server. A version mismatch (old client ↔ new server, or new client ↔ old server) fails with an **explicit, actionable "upgrade required" signal**, never a silent hang or generic error.
9. The per-IP TLS rate limiter remains **enabled and unchanged** in configuration; the improvement comes from reduced connection count, not from raising the cap.
10. **REST endpoints are unchanged** and continue to work over HTTP; this use case does not move list/spawn/delete/refresh onto the WebSocket.
11. The **UC-85 deterministic replay gate** and the existing server + Android test suites pass, including new tests covering multiplex routing, subscribe/unsubscribe lifecycle, reconnect re-subscription, and per-channel fairness.

## Potential Pitfalls & Open Questions
- **Risk** — Because this is a hard cut, the `android-vX.Y.Z` and `server-vX.Y.Z` releases must be **coordinated**: a device that updates one but not the other is broken until both land. The "upgrade required" signal (AC8) is what keeps that failure legible.
- **Edge case** — Reconnect races: a subscribe/unsubscribe in flight when the socket drops must not leave the server with a leaked or missing subscription after re-subscription (AC5/AC6 interaction).
- **Edge case** — Backpressure semantics for the fresh envelope: per-channel credit/windowing must handle a slow client without unbounded server-side buffering (ties into AC7).
- **Assumption** — mTLS/auth is unchanged: the single connection authenticates once at connect exactly as today, and per-channel frames inherit that identity (no per-channel auth).
- **Risk** — Scope spans the server realtime layer and the Android networking layer simultaneously; the `replay` profile sources must be rewired to the multiplexed endpoint without regressing the UC-85 gate.
- **Open question** — `seq` scope: is the sequence number per-channel or per-connection? (Recommendation: per-channel, to support ordering/gap-detection independently — the dev-team can confirm during design.)

## Original Description
> I want to fix the rate limiting issue I have. It triggers specially when I remove sessions - once I do it and I try to create new ones I usually get fully rate limited. Evaluate why and define a possible fix as an use case that I'll have to approve. If you need logs I can provide them

> I need D. We should have a SINGLE websocket connection through which all messages go through with correct types.

(The fix direction and root cause were confirmed via a code + server-log investigation delegated to the assistant; the server log — 1102 `Rate-limit reject … identity=harold-phone` entries — validated the per-IP-limiter storm on delete→create churn.)

## Clarifications
- Q: Which fix direction should the use case specify (server retune / client pacing / single multiplexed connection / defer to dev-team)?
  A: Direction D — a single WebSocket connection through which all messages go, with correct types.
- Q: Migration strategy for the new multiplexed endpoint vs. the 3 existing WebSocket endpoints?
  A: Hard cut, matched versions — remove the legacy endpoints; new client requires new server (and vice-versa), released together.
- Q: Envelope wire format / how much of the existing handshake to reuse?
  A: Fresh typed envelope — `{channel, sessionId, type, seq, payload}` wrapping the existing typed payload models; per-channel caps negotiated inside the envelope.
- Q: Should REST calls (list/spawn/delete/refresh) also move onto the single connection?
  A: Keep REST as-is; only the three WebSockets are multiplexed.
- Q: Head-of-line blocking / fairness — how firmly to require it?
  A: Required acceptance criterion — a busy terminal channel must not starve conversation/events.
