# `/v1/mux` — the single multiplexed WebSocket (UC-100)

All realtime traffic between the Android client and the management server now
flows over **one** long-lived, process-wide WebSocket at `/v1/mux`. This
replaces the three legacy endpoints (`/v1/sessions/{n}/stream`,
`/v1/sessions/{n}/conversation`, `/v1/sessions/events`), which are **removed** —
a hard cut with matched client/server versions.

The motivation: on a delete-session→create-session flow the four legacy sockets
(and their uncoordinated reconnect loops) re-dialled inside the same per-IP
window and tripped the TLS-layer `PerIpRateLimiter` (10 new/10 s, 10 concurrent,
keyed on source IP). Collapsing to one connection removes the churn; the
rate limiter stays **enabled and unchanged** as defense-in-depth.

## Handshake

| Header                   | Value                    |
|--------------------------|--------------------------|
| Path                     | `/v1/mux`                |
| `Sec-WebSocket-Protocol` | `ai-sandbox.mux.v1`      |

Identity is resolved **once** per connection (mTLS → Netty channel-id →
`ActiveConnectionRegistry`). An anonymous / unauthenticated connection is closed
with `1008` (policy violation), exactly as the legacy endpoints did.

New clients SHOULD first probe `GET /v1/capabilities` → `{"ws_protocol":"mux.v1"}`
to detect an old server before connecting (a new-client↔old-server mismatch).

## Envelope

Every **text** frame is a JSON envelope:

```json
{ "channel": "conversation", "sessionId": 3, "type": "assistant-text", "seq": 42, "payload": { "type": "assistant-text", "text": "…" } }
```

| Field       | Meaning |
|-------------|---------|
| `channel`   | `control` \| `stream` \| `conversation` \| `events` |
| `sessionId` | session number `n` for per-session channels (`stream`, `conversation`); omitted for `control` / `events` |
| `type`      | the discriminator *within* the channel (mirrors the nested payload's own `type`) |
| `seq`       | **per-subscription** monotonic counter — advisory within a channel (one ordered TCP stream can't reorder); used by the client for **cross-reconnect gap detection** → on a gap it triggers that channel's authoritative resync (conversation backfill / events snapshot) |
| `payload`   | the existing typed model, nested **verbatim** (`ControlMessage`, `StreamServerMessage`, `ConversationServerMessage`/`ConversationClientMessage`, `SessionEventMessage`, `MuxControlMessage`) |

The `stream` channel's PTY stdout/stdin use a **compact binary envelope**
(a WS binary frame), not JSON — no base64 on the hot path:

```
[channel:1 byte][sessionId:unsigned LEB128 varint][seq:8 bytes big-endian][raw PTY bytes …]
```

`channel` byte: `control`=0, `stream`=1, `conversation`=2, `events`=3. PTY output
is an order-preserving, boundary-less byte stream, so a large payload is chunked
across envelopes (≤ ~32 KiB each) transparently — no client-side reassembly.

## Control channel

`channel:"control"` payloads (`MuxControlMessage`):

| `type`         | Direction | Purpose |
|----------------|-----------|---------|
| `hello`        | C → S     | first frame; `{protocol, caps}` — client protocol + requested caps |
| `welcome`      | S → C     | `{protocol:"mux.v1", caps}` — negotiated per-channel caps (from `ServerProperties.Streams`) |
| `subscribe`    | C → S     | `{channel, sessionId?}` — open a logical channel |
| `unsubscribe`  | C → S     | `{channel, sessionId?}` — close a logical channel (idempotent) |
| `subscribed`   | S → C     | subscribe accepted |
| `unsubscribed` | S → C     | unsubscribe completed (after the channel's queued frames flush) |
| `sub-error`    | S → C     | subscribe refused — see taxonomy below (only that channel; socket stays up) |
| `error`        | S → C     | connection-level error (e.g. `upgrade_required` before a `4426` close) |

### `sub-error` taxonomy (per-subscribe authorization)

`subscribe(stream,n)` / `subscribe(conversation,n)` run
`StreamFacade.authorizeOpen(n, identity)` at subscribe-time. The four results
map to the existing `ErrorCode` vocabulary (same UI states the old HTTP statuses
drove):

| `authorizeOpen` result | `sub-error{code}`        | legacy HTTP |
|------------------------|--------------------------|-------------|
| `SessionNotFound`      | `session_not_found`      | 404 |
| `NotRunning`           | `session_not_running` (+ state) | 409 |
| `CapExceeded`          | `stream_cap_exceeded` (+ scope) | 503 |
| `Draining`             | `draining`               | 503 |
| `Allowed`              | `subscribed` ack         | — |

`subscribe(events)` uses `SessionEventFacade.authorizeSubscribe` →
`draining` / `cap_exceeded` sub-errors, or `subscribed`.

## Lifecycle & guarantees

- **Open a session** → `subscribe`; **close/delete** → `unsubscribe`. Neither
  opens nor closes a TCP connection (AC1/AC4).
- **Idempotent**: re-subscribing a live channel just re-acks `subscribed`
  (dedupe); unsubscribing an absent channel is a no-op that still acks
  `unsubscribed` — this makes reconnect races safe (AC6).
- **Per-channel fairness (AC7)**: the single outbound writer holds one bounded
  FIFO queue per channel and drains them **round-robin**, so a large terminal
  burst (chunked) interleaves with — never stalls — conversation/events frames.
  Strict FIFO is preserved **within** each channel.
- **Backpressure**: the writer only emits while the socket has downstream
  demand; a per-channel queue overflow closes **that channel** with a
  `sub-error` (`stream_overflow`), never the whole socket — bounding
  server-side buffering.
- **Connection-scoped completion**: the merged outbound stream completes only
  when the connection closes, never on a per-channel EOF.
- **Keepalive**: a connection-level entry (touched on any channel I/O) is pinged
  on staleness, so a conversation- or events-only client — which holds no PTY
  `ActiveStream` — is still kept alive.
- **Cap accounting is unchanged**: each `subscribe(stream,n)` registers its own
  `ActiveStream` (sharing the one mux session), so `countFor(fingerprint)`
  counts stream **subscriptions** per identity = the same per-client cap as
  before.

## Close codes

| Code | Meaning |
|------|---------|
| `1000` | normal |
| `1008` | anonymous / policy violation (no mTLS identity) |
| `4401` | client cert revoked (UC04) — client shows the cert-revoked dialog |
| `4426` | protocol version mismatch (`hello` disagreed) — client routes to the update-required screen |

## Hard cut (AC8)

The three legacy paths are re-claimed as **plain HTTP routes** returning
**HTTP 426 Upgrade Required** + `application/problem+json`
(`client_upgrade_required`) — never a WS upgrade. An old client's
`Upgrade: websocket` therefore falls through to a non-101 response →
okhttp `onFailure` → a fast, explicit failure rather than a silent hang. A new
client detects an old server via `GET /v1/capabilities` (missing / different
`ws_protocol`) and routes to the update screen. Client and server must be
released together on both tracks.
