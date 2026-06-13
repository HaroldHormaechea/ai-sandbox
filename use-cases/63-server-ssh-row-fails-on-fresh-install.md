# Use Case 63: SERVER SSH SESSION row fails with a terminal error on a fresh server (missing socket dir + tmux exit-code lie)

## Summary
Opening the UC-62 **SERVER SSH SESSION** row fails with a terminal error on a freshly-installed management server when the operator taps the shell icon **before any Claude session has ever been spawned**. The host shell never comes up, the pinned row never lists, and the terminal WebSocket closes with an error.

Root cause is two compounding **server-side** defects:

1. **`HostShellSessionService.ensureCreated()` trusts tmux's exit code, which lies.** `tmux -S <socket> new-session …` prints `error creating <socket> (No such file or directory)` to stderr but **exits 0** when the socket's parent directory is missing. `ensureCreated()` only throws on a non-zero exit, so it logs *"Created server-ssh host tmux …"*, audits `session_spawn ok`, and `POST /v1/sessions/server-ssh` returns a **false 200** — while no tmux actually exists. `exists()` (a real `has-session` probe) is then `false`, so `SessionRegistryService.list()` never prepends the row, `StreamFacade` rejects the stream open, and the Android client surfaces the WebSocket close (observed: **1011**, server log `NoSuchElementException: session 0 disappeared during open`) as a terminal error.

2. **The default socket directory is not created on a fresh install.** The default socket is `<sessions.hostStateRoot>/server-ssh.sock` (= `/var/lib/ai-sandbox-server/sessions/server-ssh.sock`). The deb `postinst` creates `/var/lib/ai-sandbox-server`, `…/docker-config`, and `…/log` but **not `…/sessions`** — that subdir is otherwise created lazily by `spawn.sh` only when the first Docker session is spawned. So on a fresh server where the operator opens the SSH row first, the parent dir is absent, defect 1 masks the failure, and the feature is silently broken.

The fix is server-only: make `ensureCreated()` create the socket's parent directory before `new-session`, and stop trusting tmux's exit code (verify the session actually exists / inspect stderr and throw a real `IOException` on genuine failure so `POST` returns 5xx rather than a false 200). Ensure the deb install also provisions the `sessions/` directory so the dir exists independently of the lazy `spawn.sh` path.

## Acceptance Criteria
1. `HostShellSessionService.ensureCreated()` creates the socket's parent directory (idempotently) before invoking `tmux new-session`, so a server that has never spawned a Claude session can still bring up the host shell. The directory creation is confined to the writable host-state tree (works under the systemd unit's `ReadWritePaths`).
2. `ensureCreated()` no longer trusts a `0` exit code alone: after `new-session` it confirms the session is actually present (e.g. via the existing `has-session` probe) and/or detects tmux's "error creating …" stderr, and throws an `IOException` when the host tmux was **not** actually created.
3. As a result, `POST /v1/sessions/server-ssh` returns a **5xx** (not a false `200`) when the host tmux genuinely cannot be created, and returns `200` with `type=server-ssh` only when `exists()` would subsequently be true.
4. On a fresh install (zero prior Docker sessions, `sessions/` dir absent at start), tapping the shell icon creates a reachable host tmux: `GET /v1/sessions` then lists the pinned `server-ssh` row and the terminal WebSocket attaches to the real host login shell.
5. The deb packaging (`server/debian/postinst`) provisions the `sessions/` directory under the host-state root at install time, with the same ownership/permissions as the sibling state dirs, so the directory exists before first use regardless of whether a Docker session was ever spawned.
6. Idempotency and the UC-62 singleton guarantees are preserved: a second create still focuses the existing session (no second tmux), `kill()` still tears it down, and the `createLock` serialization is unchanged.
7. No regression to existing UC-62 behavior: the happy path (socket dir present) still creates, lists, streams, and removes the host shell exactly as before; normal Claude-session enumeration/streaming/lifecycle is unaffected.
8. New automated tests cover the previously-missing path: (a) `ensureCreated()` against a non-existent socket-parent dir brings up a reachable tmux (dir auto-created), and (b) when `new-session` genuinely fails, `ensureCreated()` throws and `exists()` stays false (no false-positive 200). The existing `HostShellSessionLiveTmuxTest` used an always-present `@TempDir`, which is why the gap shipped — the new test must NOT pre-create the parent dir.

## Potential Pitfalls & Open Questions
- **tmux exit-code foot-gun** — confirmed on tmux 3.3a: `new-session` with a missing socket-parent dir writes `error creating … (No such file or directory)` to stderr yet exits `0`. Do not rely on exit code alone anywhere host tmux is created.
- **Writable tree under systemd hardening** — `/var/lib/ai-sandbox-server` is the unit's writable root (`ReadWritePaths`), so `Files.createDirectories(socketPath.getParent())` succeeds at runtime; verify the chosen dir stays inside that tree and don't widen the sandbox.
- **Don't double-own the dir** — `spawn.sh` may also create `sessions/`. Both paths must be idempotent and agree on ownership/mode so neither clobbers the other (deb runs as root at install; the server runs as its service user at runtime).
- **Stream error mapping (optional polish)** — a missing reserved-id row currently surfaces as a generic `1011`; mapping it to a clean `session_not_found` (1008-style) close would make the client-side failure clearer, but the primary fix is preventing the missing row in the first place.
- **Environment vs product bug** — this is a genuine product bug, not an environment artifact: tmux is present and the host shell works once the directory exists; the defect is that the server fails to provision its own socket dir and hides the failure behind a false success.

## Original Description
(Triage-derived.) Reported as: "the SSH connection to server functionality is failing with a terminal error." Reproduced live against the running management server over real mTLS: with the host tmux absent, `POST /v1/sessions/server-ssh` returns `200` but `GET /v1/sessions` returns `[]` and the terminal WebSocket closes `1011` (server log: `NoSuchElementException: session 0 disappeared during open`). Direct exercise of the shipped `HostShellSessionService.ensureCreated()` against a missing socket-parent directory returned normally while `exists()` stayed `false`, confirming the exit-code-trust defect; the deb `postinst` was confirmed not to create the `sessions/` subdir.

## Clarifications
- Q: Is this a server bug or an environment artifact on the dev host?
  A: A real product bug. tmux is installed and the feature works once the socket dir exists; the server fails to create its own socket directory and then masks the failure behind a false `200`.
- Q: Server-side fix only, or does the Android client change too?
  A: Server-only. The client correctly surfaces whatever the server returns; fixing the server (real socket dir + honest failure) makes the row list and the terminal attach. Optional client-irrelevant polish: map a missing reserved-id stream open to a clean not-found close.
- Q: Which release track?
  A: `server-v*` (changes are confined to `server/`). No `android/` changes expected.
