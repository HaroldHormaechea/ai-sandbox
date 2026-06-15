# Use Case 82: Add and remove MCP servers via the MCP popup (with injection-safe exec + least-privilege user)

## Summary
The UC-67 conversation MCP management screen currently lists a session's MCP servers and lets the user operate them (login, etc.). This use case extends it so the user can **add** a new MCP server and **remove** an existing one directly from that MCP popup/screen. Adding collects the server's identifying fields (name, transport/type, command or URL, and any args/env as applicable) and registers it for the session; removing deregisters and stops it. Two **security requirements are mandatory** for the process-execution path that backs MCP servers:

1. **Injection-safe execution** — any user-supplied input (notably an MCP server URL, command, and arguments) MUST be passed to process execution as **discrete argv arguments / properly escaped**, never concatenated into a shell command string. It must be impossible for a crafted MCP URL or argument to break out and run arbitrary commands via `process.exec`/shell. Use a no-shell `execFile`/argv-array spawn (or the platform equivalent) and validate/escape inputs.
2. **Least-privilege execution user** — MCP server processes (and the Claude process generally) MUST run under a dedicated OS user that has permission to execute **only** Claude (and what it strictly needs), not a privileged/general account. A compromised or malicious MCP server should be confined by that user's minimal permissions.

The feature must keep the UC-67 listing/operate behavior intact and reflect add/remove live in the popup.

## Acceptance Criteria
1. From the MCP popup/screen (UC-67), the user can **add** a new MCP server by providing its required fields (name + transport/type + command-or-URL [+ args/env if applicable]); on submit the server is registered for the session and appears in the list.
2. From the MCP popup/screen, the user can **remove** an existing MCP server; on confirm it is deregistered, its process stopped (if running), and it disappears from the list.
3. The add/remove changes are reflected **live** in the popup (list updates without needing to fully reopen), consistent with how UC-67 surfaces state.
4. **Injection-safe exec (security):** user-supplied MCP fields (URL, command, args) are never passed as a raw shell string to process execution. Execution uses an argv-array / no-shell mechanism with validation/escaping. A test demonstrates that a malicious value (e.g. a URL/arg containing `;`, `$(…)`, backticks, `&&`, newlines) does NOT result in extra command execution — it is treated as inert data or rejected.
5. **Least-privilege user (security):** MCP server / Claude process execution runs under a dedicated OS user restricted to executing only Claude (and strict dependencies), not root or a broadly-privileged account. The mechanism is documented and verifiable (the spawned process's uid/permissions are the restricted user's).
6. Input validation: malformed/empty required fields are rejected with clear feedback; duplicate server names are handled (reject or disambiguate) without corrupting the config.
7. No regression to UC-67 (list + operate/login), the conversation screen, or session lifecycle. Removing a server does not disrupt other running MCP servers or the session.
8. CI gates pass: `:server:test` + `:server:spotlessCheck` (server process/exec + config changes), `:android:test` + `:android:lint` (popup add/remove UI). New endpoints/enums → regenerate the OpenAPI doc (`:server:generateOpenApiDocs`) per project convention.

## Potential Pitfalls & Open Questions
- **Ambiguity (where MCP config lives)** — The analyst must find how MCP servers are registered today (the session's `.mcp.json` inside the per-session container, a server-side registry, or both — see the `profile-java-call-graph-tool` `.mcp.json` precedent and UC-67's management surface) and how add/remove should mutate it, then how the running process set is reconciled.
- **Security — exec path (CRITICAL):** locate every place an MCP server command/URL reaches process execution. Confirm the language/runtime call used (`ProcessBuilder` argv list in Java avoids shell; Node `execFile`/`spawn` with an args array avoids shell; `exec`/`sh -c` does NOT). The fix must guarantee no `sh -c`-style interpolation of user input. AC4's malicious-input test is the proof.
- **Security — least-privilege user:** determine the current execution user model (Docker container user, systemd unit `User=`, or a dedicated service account). Define/confirm the restricted user that can only execute Claude, and how MCP child processes inherit that confinement. Don't broaden privileges to make the feature work; if the current model already runs Claude as a confined user, document and verify it covers MCP children.
- **Edge case** — Removing a server mid-operation (e.g. while it's mid-login or actively used) — define safe teardown so it doesn't wedge the session.
- **Edge case** — stdio vs HTTP/SSE MCP transports: a "URL" applies to remote transports while a "command + args" applies to stdio; the add form and the exec/escaping concerns differ per transport. Cover both or scope explicitly.
- **Risk** — Persisting secrets/env for an MCP server: avoid logging or echoing sensitive fields; keep them out of transcripts and error messages.
- **Relationship** — UC-67 (MCP management screen — the host for add/remove), the `profile-java-call-graph-tool` `.mcp.json` registration pattern, and the server session-lifecycle/process-spawn code.

## Original Description
New use case, be able to add and remove MCP servers via the MCP pop up. In case we need to do process execution, make sure we escape the user input like mcp url so it is not passed as raw commands to process.exec. Run it under an user that only has permission to execute Claude in the OS.

## Clarifications
- Status: **Captured during the autonomous UC-58→60 run (2026-06-15) at the user's request.** Interactive clarification loop skipped (autonomous capture). The two security requirements (injection-safe argv exec + least-privilege execution user) are first-class acceptance criteria (AC4/AC5), each with a concrete verification, not soft suggestions. Architecture specifics (MCP config locus, current exec call sites, current execution-user model) are left for the analyst to resolve against the server + container code. To be implemented in this same autonomous batch; release deferred until all queued UCs are merged.
