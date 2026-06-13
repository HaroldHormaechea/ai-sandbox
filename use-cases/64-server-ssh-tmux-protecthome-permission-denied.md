# Use Case 64: SERVER SSH SESSION shell shows "Permission denied" reading tmux config (ProtectHome hides $HOME)

## Summary
After UC-63 made the **SERVER SSH SESSION** row reachable, opening it now shows tmux config errors in the terminal:

```
/home/ai-sandbox-server/.tmux.conf: Permission denied
/home/ai-sandbox-server/.config/tmux/tmux.conf: Permission denied
```

Root cause is the server's deliberate systemd hardening colliding with the host shell's inherited environment. `server/systemd/ai-sandbox-server.service` sets `ProtectHome=true`, which overmounts `/home` as inaccessible for the service. But the host tmux is created (`HostShellSessionService.ensureCreated()` → `tmux new-session`) and attached (`TmuxBridgeService`) **inheriting `HOME=/home/ai-sandbox-server`** from the systemd-provided environment (`ProcessExecutor.run` overlays its env map onto the inherited `ProcessBuilder` environment rather than replacing it, and `baseEnv()` does not set `HOME`). tmux then resolves its config paths under the now-untraversable `/home` and every lookup returns `EACCES` → the "Permission denied" lines the operator sees. The login shell (`$SHELL -l`) hits the same hidden `$HOME` for its startup files.

This is the **same class of problem** the unit already solves for Docker: a comment on the `Environment=DOCKER_CONFIG=/var/lib/ai-sandbox-server/docker-config` line explains it redirects Docker's `$HOME/.docker` lookup away from the ProtectHome-hidden home into the already-writable `/var/lib/ai-sandbox-server` tree. The host shell needs the equivalent `HOME` redirection.

The fix is server-only: give the host shell an **accessible, writable `HOME`** (a directory under `/var/lib/ai-sandbox-server`, which is in the unit's `ReadWritePaths`) for both tmux-server creation and the PTY attach, pre-create it in packaging like `docker-config`, and make it configurable. tmux and the login shell then read their (absent) config from the accessible home and start cleanly — no `Permission denied`.

## Acceptance Criteria
1. Opening the SERVER SSH SESSION row on a server running with `ProtectHome=true` no longer prints any `Permission denied` line for `~/.tmux.conf` or `~/.config/tmux/tmux.conf`; the host shell starts cleanly.
2. The host tmux server is created (`HostShellSessionService.ensureCreated()`/`new-session`) with `HOME` set to an **accessible, writable** directory (under `/var/lib/ai-sandbox-server`, i.e. inside the unit's `ReadWritePaths`), not the ProtectHome-hidden `/home/ai-sandbox-server`.
3. The interactive PTY attach for the host shell (`TmuxBridgeService`, the `onHost` branch) uses the same accessible `HOME`, so the attaching client and the login shell inside the pane do not hit the hidden home.
4. The `HOME` directory is configurable via `ServerProperties.ServerSsh` (a new optional field), resolving to a sensible runtime default under the host-state/writable tree when unset, consistent with how the socket path and shell are already resolved.
5. `ensureCreated()` ensures the `HOME` directory exists (idempotently, like it already does for the socket parent dir under UC-63), so it works even before packaging has created it.
6. The deb packaging (`server/debian/postinst`) pre-creates the host-shell `HOME` directory with restrictive ownership/permissions consistent with the sibling state dirs (mirroring how `docker-config` is pre-created), so it exists on a fresh install.
7. No regression to UC-62/UC-63 behavior: the singleton/idempotency/`kill()` semantics, the socket-dir provisioning + post-create verification, listing the pinned row, and streaming all continue to work; normal Claude-session enumeration/streaming/lifecycle is unaffected.
8. The server's security posture is unchanged: `ProtectHome=true` and the other hardening directives stay as-is; the fix only redirects the host shell's `HOME` to an already-writable path (it does NOT relax `ProtectHome`, expose `/home`, or widen the sandbox).
9. Automated tests cover the fix: (a) the env used to create the host tmux includes `HOME` pointing at the accessible directory (not `/home/...`); (b) the host-mode attach env includes the same `HOME`; (c) a live test that creating the host tmux with `HOME` redirected to an accessible dir starts a reachable session with no config-permission error; (d) a postinst contract assertion that the `HOME` dir is provisioned.

## Potential Pitfalls & Open Questions
- **EACCES vs ENOENT** — under `ProtectHome=true`, `/home` is overmounted *untraversable*, so config lookups fail with `EACCES` ("Permission denied"), not "No such file". A fix that only creates files in the real home would not help (the path is unreachable); the home must be *redirected*.
- **Two call sites** — the tmux **server** env is set at `new-session` (`HostShellSessionService.baseEnv()`); the **client/login-shell** env is set at PTY attach (`TmuxBridgeService` host branch, currently `new HashMap<>(System.getenv())` which carries the real `HOME`). Both must use the accessible `HOME`, or the config error / hidden-home startup files reappear on one path.
- **XDG_CONFIG_HOME** — tmux uses `$XDG_CONFIG_HOME/tmux/tmux.conf` falling back to `$HOME/.config/tmux/tmux.conf`. Setting `HOME` alone is sufficient (the fallback follows `HOME`); only set `XDG_CONFIG_HOME` if the runtime environment already exports it to a hidden path.
- **Don't double-own the dir** — like the UC-63 `sessions/` dir, both `postinst` (root at install) and `ensureCreated()` (service user at runtime) may create the `HOME` dir; both must be idempotent and agree on ownership/mode (follow the `docker-config` precedent: mode 0700, owner ai-sandbox-server).
- **Operator expectation** — under `ProtectHome=true` the operator's real `~/.bashrc`/`~/.tmux.conf` are fundamentally inaccessible to the sandboxed service; this UC delivers a *clean* shell with a writable redirected home, not the operator's personal dotfiles. That is the accepted trade-off of the hardening (consistent with UC-62's operator-convenience framing). The nice in-app terminal UX (mouse, single-pane, status off) is already applied per-client by `TmuxBridgeService`, independent of any user tmux.conf.
- **Scope** — server-only, single `server-v*` track. No Android changes.

## Original Description
User report after updating to server-v0.0.47: opening the SSH-into-server session shows
`/home/ai-sandbox-server/.tmux.conf: Permission denied` and `/home/ai-sandbox-server/.config/tmux/tmux.conf: Permission denied`. (The host shell is now reachable thanks to UC-63; this config-permission error was previously masked by the 1011 failure.)

## Clarifications
- Q: Is this a server bug or operator misconfiguration?
  A: Server bug. The host shell inherits the ProtectHome-hidden `$HOME`; the feature must redirect `HOME` to an accessible writable path, exactly as the unit already does for Docker via `DOCKER_CONFIG`.
- Q: Should we relax `ProtectHome` so tmux can read the real config?
  A: No. Keep the hardening intact. Redirect the host shell's `HOME` to a writable dir under `/var/lib/ai-sandbox-server` instead.
- Q: Which release track?
  A: `server-v*` (changes confined to `server/`). No `android/` changes.
