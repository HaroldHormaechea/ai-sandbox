# `/v1/sessions/events` — WebSocket framing (live sessions-list status push)

UC-32. A one-way **server→client** WebSocket that pushes session-status
changes to a subscribed client so the sessions list updates itself live —
a session going `provisioning → running`, `running → stopped`,
`running → terminating`, a new spawn appearing, or a session being removed —
without the client polling or leaving and re-entering the screen.

This is a **separate** channel from the per-session terminal stream
(`/v1/sessions/{n}/stream`, see [STREAM_PROTOCOL.md](STREAM_PROTOCOL.md)),
which carries PTY bytes for one session and says nothing about other
sessions' lifecycle. The two endpoints are disjoint and share no server
code.

## Subprotocol

| Header                   | Value           |
|--------------------------|-----------------|
| `Sec-WebSocket-Protocol` | `ai-sandbox.v1` |

The same `ai-sandbox.v1` handshake as the terminal stream. The client sends
it for consistency; the events channel does not require the server to echo
it (the channel carries no negotiated capabilities).

## Transport gating

Identical to the REST + stream surface: **mTLS, same client-cert allowlist**.
Identity is resolved from the TLS connection; a connection with no valid
client certificate (or the `anonymous` sentinel) is closed immediately with
**1008** (policy violation). A subscription is also indexed against the
client fingerprint so a **cert revocation** tears the feed down with close
code **4401** (`revoked`) — exactly like the terminal stream — and a revoked
client stops receiving pushes.

## Frame types

| Frame  | Direction      | Carries                                              |
|--------|----------------|------------------------------------------------------|
| Text   | server→client  | JSON status frames (`snapshot`, `delta`).            |
| —      | client→server  | Nothing. Inbound frames are read and ignored.        |

Every text frame is a JSON object with a `type` discriminator.

### `snapshot` (server → client)

The authoritative full session list. Sent **once immediately on subscribe**
and **again on every reconnect**. The client applies it as a full resync
that replaces its working set (so a reconnect can never leave drift from a
missed `delta`).

```json
{
  "type": "snapshot",
  "sessions": [
    { "n": 1, "label": "api", "tmuxTitle": "(idle)", "state": "running",
      "uptimeSec": 0, "activeStreams": 0, "startedAt": null },
    { "n": 2, "label": "", "tmuxTitle": "", "state": "provisioning",
      "uptimeSec": 0, "activeStreams": 0, "startedAt": null }
  ]
}
```

`sessions` may be empty (no sessions, e.g. Docker not running, or a
subscribe before any session exists) — the empty-state is a valid snapshot.

### `delta` (server → client)

A **coalesced** incremental update. The server diffs the current session
list against the previous tick and emits **one** frame per change batch
(never N frames for N simultaneous spawns).

```json
{
  "type": "delta",
  "upserts": [
    { "n": 2, "label": "", "tmuxTitle": "", "state": "running",
      "uptimeSec": 0, "activeStreams": 0, "startedAt": null }
  ],
  "removed": [3]
}
```

| Field     | Meaning                                                              |
|-----------|---------------------------------------------------------------------|
| `upserts` | Rows to **insert-or-replace**, keyed by `n`. A changed `state` (or any other field) on an existing `n` is an upsert; a new `n` is an insert. |
| `removed` | The `n`s that disappeared from the server (deleted / fully torn down). |

The client applies both **idempotently keyed by `n`**: re-applying a delta
is a no-op, and the list (sorted by `n`) plus its filter-chip counts
recompute automatically. The server never sends a `delta` where both
`upserts` and `removed` are empty.

### Row schema

`snapshot.sessions[*]` and `delta.upserts[*]` use the same row shape, which
mirrors the REST `GET /v1/sessions` summary (`ApiDtos.SessionSummary`)
field-for-field:

| Field           | Type            | Notes                                                       |
|-----------------|-----------------|-------------------------------------------------------------|
| `n`             | int             | Session number — the stable key for upsert/remove.          |
| `label`         | string          | Free-form label, may be empty.                              |
| `tmuxTitle`     | string          | tmux window title, or `(idle)` / `(unavailable)`.           |
| `state`         | string          | `running` \| `starting` \| `provisioning` \| `terminating` \| `stopped`. The live field that drives the status pill + counts. |
| `uptimeSec`     | long            | Seconds since container start, or 0 when unknown.           |
| `activeStreams` | int             | Currently-attached terminal-stream count.                   |
| `startedAt`     | ISO-8601 \| null| Container start time, or null when unknown.                 |

> `uptimeSec` / `activeStreams` / `startedAt` are carried for parity with
> REST but are currently hardcoded to `0` / `null` by the enumerator (the
> same as the REST list); `state` is the field that actually changes live.

## Where the changes come from

The server runs a **subscriber-gated periodic reconcile**: a scheduled job
(default every 1 s) re-enumerates the session list, diffs it against the
retained previous snapshot, and broadcasts one coalesced `delta`. It runs
**only while at least one client is subscribed** (zero enumeration churn on
an idle server) and resets its baseline when the last subscriber leaves.

Because it diffs the same enumeration the REST list uses, it observes **all**
transitions — not just client-initiated ones — including a container exiting
on its own, OOM, host reboot, or a `provisioning → running` readiness flip.
Combined with the registry's ~1 s read cache, the worst-case
observe→push latency is roughly **two seconds**.

## Disconnect resilience & reconnect

A network drop ends the WebSocket. The client backs off and reconnects; each
(re)connect delivers a fresh `snapshot` (a full server-authoritative
resync), so missed deltas can never accumulate into drift. The client also
keeps its existing REST refresh (on screen entry / resume) as a fallback, so
a feed that is down or has exhausted its reconnect budget still shows
correct (if not live) state — no crash, no stuck spinner.

## Lifecycle / battery

The sessions screen's subscription is **foreground-bound**: the client opens it
when the sessions screen is started and closes it when the screen stops, so that
socket is not held open in the background. UC-69 added a **second**, app-wide
subscription owned by a `dataSync` foreground service (the pending-question
watcher), which is intentionally long-lived so a backgrounded device can still
notice a session asking a question — analogous to the terminal stream's
foreground-service socket. A single device therefore holds up to two feeds.

## WebSocket close-code matrix

| Close code | When                                                          |
|------------|---------------------------------------------------------------|
| 1000       | Client closed normally.                                       |
| 1001       | Server draining for shutdown (new subscriptions also refused).|
| 1008       | No / anonymous client identity (policy violation).            |
| 1013       | Per-client subscription cap reached (service overload).       |
| 4401       | Client cert revoked (`revoked`) — routes to the cert-revoked dialog. |

## Caps

| Cap                                   | Default | Notes                                            |
|---------------------------------------|---------|--------------------------------------------------|
| Per-client (fingerprint) subscriptions| 20      | A status feed is cheap; this is a light guard, **not** the terminal-stream per-client cap (10). Raised from 4 in UC-69 because a single device now holds two feeds (sessions screen + pending-question watcher). Cap exceeded → close 1013. |
| Reconcile interval                    | 1 s     | Subscriber-gated; no enumeration when nobody is listening. |

> The cap and interval are constants today; they may move to
> `ai-sandbox.server` config in a later change.
