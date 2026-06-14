# Use Case 74: Bugfix — `systemctl restart`/`stop` of the server takes over a minute (graceful-shutdown timeouts pin the JVM)

## Summary
Restarting the management server with `systemctl restart ai-sandbox-server` (or `stop`) appears to "refuse" and takes **over a minute** to complete. It is not a true refusal: the unit `server/systemd/ai-sandbox-server.service` runs the JVM as a **direct** child of systemd (`Type=simple`, `KillSignal=SIGTERM`), so the classic "SIGTERM hits a shell wrapper, not the JVM" cause does not apply. Instead, `TimeoutStopSec=70` (with `SuccessExitStatus=143`) is systemd's backstop, and the JVM does not exit promptly when a client (the Android app) holds a live WebSocket at shutdown time: `application.yaml` sets `server.shutdown: graceful` with no explicit `spring.lifecycle.timeout-per-shutdown-phase` (defaults to 30s), a `shutdown.total-grace-seconds: 60` budget consumed by `GracefulShutdownHandler`, and a netty `idle-timeout: 60s`. A lingering WebSocket therefore drags graceful shutdown toward ~60s and bumps the 70s ceiling. Additionally `GracefulShutdownHandler.stop()` issues per-stream closes as fire-and-forget `.subscribe()` rather than actually draining them, so persistent WebSocket connections are not promptly disposed. This bugfix makes a normal restart fast (a few seconds) even with a client connected, while preserving the UC-44/UC-52/UC-61 graceful-WebSocket-close behaviour.

## Acceptance Criteria
1. With an Android client (or any `wss://` client) holding a live conversation/binary WebSocket, `systemctl restart ai-sandbox-server` completes well under the systemd stop timeout — target a few seconds, and in all cases comfortably below `TimeoutStopSec` — rather than dragging to ~60–70s.
2. Internal shutdown budgets are bounded and ordered so their sum stays strictly less than `TimeoutStopSec`: set an explicit `spring.lifecycle.timeout-per-shutdown-phase` (e.g. ~10–15s) and reduce `ai-sandbox.server.shutdown.total-grace-seconds` (and the netty `idle-timeout` for the shutdown path) so a stuck WebSocket cannot pin shutdown for ~60s. `TimeoutStopSec` remains greater than the total internal grace.
3. `GracefulShutdownHandler.stop()` actually drains each stream close with a small bounded timeout (instead of fire-and-forget `.subscribe()`), and the underlying reactor-netty connections are disposed within a bounded duration, so persistent WebSockets are dropped promptly.
4. Any PTY child processes spawned for streams (pty4j via the tmux bridge) are terminated on shutdown so they cannot keep handles/connections alive and delay exit.
5. The restart still exits **gracefully** (clean WS close, exit status honoured by `SuccessExitStatus=143`); systemd does not have to SIGKILL, and `journalctl -u ai-sandbox-server` shows an orderly shutdown, not a kill after timeout. Clients see a normal close (so they do not route to the destructive re-scan-QR path), not an abrupt drop.
6. Restart with **no** client connected remains fast (no regression), and the server comes back up healthy and serving after the restart.
7. QA verifies on a live systemd-managed server, measuring restart wall-clock both with and without a live WebSocket client, and confirming the JVM exits before the systemd backstop.

## Potential Pitfalls & Open Questions
- **Risk** — Keep `TimeoutStopSec` strictly greater than the sum of internal grace budgets; otherwise systemd SIGKILLs mid-shutdown, the graceful WS-close ceremony is lost, and the exit code stops matching `SuccessExitStatus=143` (restarts log as failed).
- **Risk** — Do NOT switch to `KillMode=mixed`/`SIGKILL` to "speed it up": that defeats the UC-44 graceful WebSocket close, and clients seeing abrupt drops route to the UC-52/UC-61 re-scan-QR identity screen. Fix the **timeouts/drain**, not the kill semantics.
- **Edge case** — Daemon watcher threads (`ServerCertWatcher`, `AllowlistWatcher`) and the `ProcessExecutor`/`ConversationNameService` pools are already daemon + interrupt-on-stop; they are not the hold-up. The lever is the WebSocket/graceful-drain budgets, not those threads.
- **Edge case** — Reducing the netty `idle-timeout` is shutdown-sensitive: ensure lowering it for prompt disposal does not prematurely drop healthy long-lived streams during normal operation. If the single `idle-timeout` value serves both purposes, prefer an explicit dispose-on-shutdown path over globally shrinking the idle timeout.
- **Assumption** — The dominant cause is a persistent WebSocket pinning the reactor-netty graceful drain; confirm by reproducing with vs without a connected client and comparing restart timings.
- **Key files** — `server/systemd/ai-sandbox-server.service`, `server/src/main/resources/application.yaml` (+ `server/sample-config.yaml` operator override), `server/.../shutdown/GracefulShutdownHandler.java`, `server/.../tls/NettyServerCustomizer.java`, and the stream/PTY teardown (`server/.../stream/...`, tmux-bridge service).

## Original Description
"Also server refuses to be restarted with systemctl and it takes a while for it to do so, like over a minute."

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- QA investigation confirmed this is actionable: not a refusal, but systemd waiting out `TimeoutStopSec=70` because a lingering WebSocket pins the JVM's graceful shutdown (~60s of internal grace + netty idle-timeout).
- The JVM is already a direct systemd child with `KillSignal=SIGTERM`; the wrapper-eats-SIGTERM cause is ruled out.
- Fix the timeout budgets and make `GracefulShutdownHandler.stop()` actually drain/dispose connections; preserve graceful close so clients don't fall into the re-scan-QR path.
