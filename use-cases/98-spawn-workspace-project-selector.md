# Use Case 98: Workspace/project pre-selection on session spawn

## Summary
When spawning a session, the Android `NewSessionSheet` (`SessionsScreen.kt`, today label-only) gains a **drop-down** to pick a workspace project alongside the session label. The drop-down is populated by a **new mTLS-protected server endpoint** that lists the top-level folders under the server's workspace root (list-all now, with room to filter by config later). The drop-down is offered **in both workspace modes** (shared and isolated) and defaults to a **"None"** option that preserves today's behavior exactly (no prompt). When a real project is chosen, the server passes the selection through spawn and, once the session's readiness marker is up, **auto-injects and submits** a single fixed prompt into that session's Claude — **`We will work in the project {folder}.`** — via the existing `InputInjectionService` `tmux send-keys` path, *before* the user attaches. Selection is non-exclusive (Claude may still work elsewhere) and non-limiting (multiple sessions may target the same project). Touched components: `SpawnRequest`/`SpawnCommand` DTOs, `SessionController`/`SessionFacade`, the new workspace-listing endpoint, `ScriptExecutorService`/`spawn.sh` (only if the choice must reach the container), the injection service, and the Android spawn UI + view model.

## Acceptance Criteria
1. A new mTLS-protected endpoint (e.g. `GET /v1/workspace/projects`) returns the selectable projects — each top-level folder under the server workspace root — with a stable id and a display name; the listing rule is implemented so a folder filter (e.g. only git repos, or only folders with a `PROJECT_BRIEF.md`) can be added later via configuration without an API change.
2. `NewSessionSheet` renders a drop-down of the returned projects (plus a "None" entry) in addition to the existing session-label field, populated from that endpoint.
3. "None" is the pre-selected default; spawning with "None" selected yields byte-for-byte today's behavior — **no** prompt is injected.
4. Choosing a real project injects **exactly one** prompt, `We will work in the project {folder}.`, into that session's Claude and **submits it** (presses Enter), with no manual step required from the user.
5. The `{folder}` substituted into the injected prompt is the selected project's folder name.
6. Injection happens **after** the session readiness marker is up (cf. UC-95) and **before/independently of** the user attaching; attaching later shows the prompt already present and already submitted.
7. The drop-down is available in **both** shared and isolated workspace modes.
8. Selecting a project does **not** restrict the session to that project, and does **not** prevent spawning additional sessions targeting the same project (N sessions → same project is allowed).
9. The selected project (or its absence) is carried end-to-end through the spawn request; a "none" selection is a first-class, valid request.
10. A stale/invalid project id (e.g. the folder was deleted between listing and spawn) degrades gracefully to "no project" (no prompt injected) rather than failing the spawn.

## Potential Pitfalls & Open Questions
- **Edge case** — Isolated mode gets a fresh empty `workspace-<N>`, so the chosen folder may not physically exist in that session; the prompt still references it by name (informational only). Seeding/copying the project into an isolated workspace is **out of scope** for this use case.
- **Risk** — The auto-submit choreography (`send-keys` + Enter) is pinned to the Claude Code TUI version (cf. UC-97 pinning); a TUI/spec change could break clean submission and must be guarded accordingly.
- **Assumption** — Reuses `InputInjectionService.injectComposer()` (`tmux send-keys -l`) rather than adding a new spawn-time prompt channel on the spawn endpoint.
- **Assumption** — The "workspace root" used for listing is the server's configured host workspace root (the shared `workspace/` directory), regardless of the spawning session's workspace mode.

## Original Description
When requesting to spawn a new session, other than the name of the session you should be promoted to select what workspace folder you will work on. This will pre -setup the container sending Claude a prompt to tell it you want to work in that project. It should not prevent later working in others. We should not limit to one session per project. The selector should be a drop-down. It should include an option to not choose one by default in the selector which will omit the setup Claude prompt. We need an endpoint to get the list of workspace projects in the server, and to modify the necessary ones to create the session. And also generate the prompt and be input automatically to Claude before the user attaches to it

## Clarifications
- Q: What should count as a listable "workspace project" the drop-down offers?
  A: All top-level folders now (list everything), implemented so a folder filter can be added later via configuration.
- Q: How does project selection relate to workspace mode (shared vs isolated)?
  A: Offer the drop-down in both modes.
- Q: Should the injected setup prompt be auto-submitted or just pre-filled?
  A: Auto-submit (press Enter).
- Q: What should the generated setup prompt tell Claude to do?
  A: The prompt will be exactly: "We will work in the project {folder}."
