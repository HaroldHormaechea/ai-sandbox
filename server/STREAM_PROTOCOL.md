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
