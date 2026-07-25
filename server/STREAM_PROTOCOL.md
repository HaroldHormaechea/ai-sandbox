> **⚠️ SUPERSEDED (UC-100).** The standalone `/v1/sessions/{n}/stream`
> WebSocket endpoint has been **removed**. Terminal streaming is now the
> `stream` channel of the single multiplexed connection at `/v1/mux` — see
> [`MUX_PROTOCOL.md`](MUX_PROTOCOL.md). The framing below is retained for
> historical reference and because the per-channel *payload* models
> (`ControlMessage`, `StreamServerMessage`, binary PTY bytes) are carried
> **unchanged** inside the mux envelope. A request to the old path now returns
> **HTTP 426 Upgrade Required** (`client_upgrade_required`).

# `/v1/sessions/{n}/stream` — WebSocket framing

## Subprotocol

| Header                   | Required value                |
|--------------------------|-------------------------------|
| `Sec-WebSocket-Protocol` | `ai-sandbox.v1`               |

Missing or unrecognised → HTTP 400 (no WebSocket established).

The version suffix is reserved for future bumps. When the wire format
changes incompatibly, the server will negotiate the new subprotocol
(e.g. `ai-sandbox.v2`) and reject clients that only advertise `ai-sandbox.v1`.

## Frame types

| Frame    | Carries                                                    |
|----------|------------------------------------------------------------|
| Binary   | Raw tty bytes. Client→server frames go to PTY stdin; server→client frames carry PTY stdout. |
| Text     | JSON control messages (resize, mouse, error, close).       |

Maximum sizes (configurable; defaults shown):

- Binary frame: **256 KiB** — exceeded ⇒ WebSocket close 1009.
- Text frame: **16 KiB** — exceeded ⇒ WebSocket close 1009.

Server-side per-stream output buffer is **256 KiB**; PTY output that
would exceed the buffer triggers a server-emitted ERROR text frame
(`stream_overflow`, see below) and a close with code 1009.

## Text-frame schemas

Every text frame is a JSON object with a `type` discriminator. The
following shapes are valid:

### `resize`

```json
{ "type": "resize", "cols": 120, "rows": 40 }
```

Server calls `ptyProcess.setWinSize(cols, rows)`.

### `mouse`

```json
{ "type": "mouse", "x": 42, "y": 12, "button": 0, "modifiers": 0, "action": "press" }
```

| Field      | Meaning                                                          |
|------------|------------------------------------------------------------------|
| `x`, `y`   | 1-indexed tmux cell coordinates.                                 |
| `button`   | 0=left, 1=middle, 2=right, 3=wheel-up, 4=wheel-down.             |
| `modifiers`| bitmask: 1=shift, 2=alt, 4=ctrl.                                  |
| `action`   | `"press"`, `"release"`, `"drag"`.                                |

Server translates to xterm-SGR escape and writes into the PTY's stdin.
This is the only mouse path; raw xterm-SGR bytes sent as binary frames
are forwarded to the PTY but **not** interpreted by the server.

### `close`

```json
{ "type": "close", "reason": "tab-closed" }
```

Client-initiated clean close. Server responds with WebSocket close 1000.

### `error` (server → client)

```json
{ "type": "error", "code": "stream_overflow", "title": "Service Unavailable", "detail": "output buffer full" }
```

Mirrors the RFC 9457 problem-details shape. Emitted by the server when
something the WebSocket cannot recover from happens. Typically followed
by a WebSocket close.

## Agent-team switcher frames (UC-21)

A single stream can switch which tmux target it bridges — the **main**
session or one **agent-team member** — without reconnecting. Agent-team
teammates are Claude Code's own agent-team feature, rendered as tmux
**panes** in a window on a separate `claude-swarm-<pid>` socket; the main
session lives on the container's default socket. The server enumerates
targets and re-bridges in place; the client mirrors the selection.

### `enumerate-targets` (client → server)

```json
{ "type": "enumerate-targets" }
```

Asks the server to list the targets for this session. The server replies
with a `targets` frame. Discovery is dynamic (no hard-coded pid): the
server scans the container's tmux sockets and reads pane metadata.

### `targets` (server → client)

```json
{
  "type": "targets",
  "selectedId": "main",
  "targets": [
    { "id": "main", "kind": "main", "title": "main",
      "agentName": null, "agentType": null, "agentColor": null, "teamName": null,
      "socket": null, "session": "main", "window": null, "pane": null },
    { "id": "swarm:claude-swarm-15713:0.0", "kind": "orchestrator", "title": "✳ general-purpose",
      "agentName": null, "agentType": "general-purpose", "agentColor": null, "teamName": "pingpong-functest",
      "socket": "/tmp/tmux-997/claude-swarm-15713", "session": "claude-swarm", "window": "0", "pane": "0" },
    { "id": "swarm:claude-swarm-15713:0.1", "kind": "swarm", "title": "✳ ping",
      "agentName": "ping", "agentType": "general-purpose", "agentColor": "blue", "teamName": "pingpong-functest",
      "socket": "/tmp/tmux-997/claude-swarm-15713", "session": "claude-swarm", "window": "0", "pane": "1" }
  ]
}
```

| Field        | Meaning                                                                 |
|--------------|-------------------------------------------------------------------------|
| `selectedId` | `id` of the target this stream is currently bridged to.                 |
| `id`         | Opaque, stable id the client echoes back in `select-target`.            |
| `kind`       | `main` \| `swarm` (a teammate) \| `orchestrator` (pane without `--agent-name`). |
| `title`      | tmux pane title (e.g. the agent's display label).                       |
| `agentName` / `agentType` / `agentColor` / `teamName` | Agent-team metadata from the pane process argv; `null` when unreadable. |
| `socket` / `session` / `window` / `pane` | tmux coordinates the server re-bridges to; `null` fields ⇒ the default-socket main session. |

The **main** target is always present and always first, even when no team
is running (or when the default socket has no server yet — a placeholder).

### `select-target` (client → server)

```json
{ "type": "select-target", "targetId": "swarm:claude-swarm-15713:0.1" }
```

Switch the stream to `targetId` **mid-stream, on the same WebSocket**. The
server starts a fresh bridge to the new target, atomically swaps the live
bridge, then tears the old one down (so the long-lived output pump never
sees a gap), and replies with `target-selected`. On failure (unknown or
vanished target, tmux error) it replies with an `error` frame and the
stream stays on its current target. The client should re-send a `resize`
after a switch so the new PTY matches the rendered geometry.

### `target-selected` (server → client)

```json
{ "type": "target-selected", "targetId": "swarm:claude-swarm-15713:0.1" }
```

Acknowledges a successful switch. Subsequent binary frames carry the new
target's PTY stdout, and binary stdin is routed to it.

## WebSocket close-code matrix

| Close code | When                                                              |
|------------|-------------------------------------------------------------------|
| 1000       | Client `close` control frame, or normal end-of-stream.            |
| 1001       | Server shutting down, OR keepalive pong timeout.                  |
| 1003       | Unsupported (subprotocol absent at handshake).                    |
| 1008       | Policy violation (revoked cert tore connection down).             |
| 1009       | Frame too big OR per-stream output buffer overflow.               |
| 1011       | Server-side error (PTY died, tmux unreachable, etc.).             |

## Streams are not resumable

A network drop ends the WebSocket. The client must reconnect to start a
fresh attach. The Claude tmux session inside the container is unaffected;
the per-client tmux session created at stream open (`tmux new-session -t main`)
is killed when the WebSocket closes.

## Caps

| Cap                           | Default | Configurable |
|-------------------------------|---------|--------------|
| Per-client concurrent streams | 10      | yes          |
| Server-wide concurrent streams| 100     | yes          |
| Per-stream idle timeout       | 2 h     | yes          |
| Binary frame max              | 256 KiB | yes          |
| Text frame max                | 16 KiB  | yes          |

Cap exceeded at upgrade time → HTTP 503 (`stream_cap_exceeded`) with a
problem-details body; the WebSocket never opens.
