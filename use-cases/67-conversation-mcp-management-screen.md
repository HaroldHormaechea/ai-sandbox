# Use Case 67: Conversation menu — full-screen MCP management screen for the session

## Summary
The conversation overflow menu (`ConversationScreen.kt`) gains an **MCP** item that opens a new **full-screen** screen showing the MCP servers configured for this session and their current state (e.g. connected, needs-auth/login, failed/disconnected). For each MCP server the screen offers the controls that server requires to operate — most importantly authentication (login) for OAuth-style servers, plus reconnect/refresh where applicable. The list and states are read from the server (the embedded Claude Code knows its MCP servers via `claude mcp` / the session's MCP config), and the operations are driven through the server back to the session's Claude. The existing dev-environment MCP servers (e.g. the project's `java-class-call-scanning` MCP and an Atlassian MCP) serve as concrete examples for QA. QA must assess from the UI that the listing reflects reality and that the controls (notably login) actually operate as a human would expect.

## Acceptance Criteria
1. The conversation overflow menu shows an **MCP** item.
2. Tapping **MCP** opens a dedicated full-screen screen (not a small popup), with a way to navigate back to the conversation.
3. The screen lists the MCP servers configured for the current session, each with a name/identifier and a visible state (e.g. connected / needs-auth / failed).
4. The list/states are sourced from the server for this session (reflecting the session's actual MCP configuration), not a static placeholder.
5. Each MCP server exposes the controls it needs to operate — at minimum an authenticate/login control for servers that require it, and a reconnect/refresh affordance; controls are enabled/disabled appropriately for the server's state.
6. Invoking a control (e.g. login) triggers the corresponding operation against that MCP server through the session's Claude, and the screen reflects the resulting state change (e.g. needs-auth → connected, or surfaces the login URL/flow the server returns).
7. The screen handles the empty case gracefully (a session with no MCP servers shows a clear "no MCP servers" state).
8. **QA assessment from the UI is mandatory**: QA boots a live server + emulator session that has real MCP servers configured (e.g. the call-graph MCP and an Atlassian MCP), opens the MCP screen, confirms the listing matches the session's real MCP set and states, and exercises a control (login/authenticate) to confirm it operates as a human would expect.

## Potential Pitfalls & Open Questions
- **Missing input** — No MCP-related server endpoint or client code exists today (confirmed). The server must expose: (a) a per-session MCP listing (name + state), and (b) action endpoints to drive operations (login/auth, reconnect). Source of truth is the embedded Claude Code's MCP knowledge (`claude mcp list` / `/mcp`).
- **Risk (significant)** — MCP authentication for OAuth servers (e.g. Atlassian) is an interactive flow: Claude Code's `/mcp` surfaces a login URL the user must open and approve. The control likely cannot complete auth fully headlessly; the realistic deliverable is to *initiate* the flow and surface the URL/next-step to the user, then reflect the post-auth state on refresh. The dev-team must scope what "login operates" means concretely and QA must validate the actual achievable behaviour.
- **Ambiguity** — "Operations like login or whatever they require": resolved as a state-driven control set — login/authenticate for needs-auth servers, reconnect/refresh otherwise. Exact per-server operation set is discovered from what Claude Code's MCP surface offers.
- **Edge case** — State freshness: MCP state can change out-of-band (a server crashes, a token expires). The screen needs a refresh path (manual pull-to-refresh or on-resume re-fetch); decide whether to live-stream state or fetch-on-open + manual refresh.
- **Assumption** — Scope is the **current session's** MCP servers (per the request "for this session"), not a global MCP admin surface.
- **Edge case** — Driving `/mcp` (an interactive TUI command) through the server's pane-injection path may be brittle; a non-interactive `claude mcp` CLI surface (if available in the harness) is preferable for listing/state. The dev-team chooses the most reliable mechanism.

## Original Description
"I want in that menu a new button showing MCP, that opens a new window, full screen, with the list of MCP and their states for this session. Offer controls to execute their operations like login or whatever they require.. you can use current mcp and the atlassian mcp as examples. QA must assess from the UI this works and the controls operate as expected by an human."

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- Scope is the current session's MCP servers.
- Server exposes both a listing and action endpoints; client renders a full-screen state-driven controls list.
- "Login operates" is interpreted realistically: initiate the auth flow / surface its URL and reflect post-auth state; full headless OAuth completion is not assumed achievable and is a documented constraint for QA to validate.
- QA must verify from the UI with real MCP servers (call-graph + Atlassian) present in the session.
