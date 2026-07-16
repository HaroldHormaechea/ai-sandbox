---
plan_for: use-cases/98-spawn-workspace-project-selector.md
work_branch: feat/uc-98-spawn-workspace-project-selector
team: ai-sandbox-uc-98
approved: 2026-07-16
---

# UC-98 — Workspace/project pre-selection on session spawn — FINAL APPROVED PROPOSAL

TARGET_DIR = /workspace/ai-sandbox-uc-98-spawn-workspace-project-selector. Complies with profile-java-server-architecture (Controller→Facade→Service, API-vs-internal DTO split, no @Transactional — project has no DB) and keeps the ArchUnit `LayeringTest` cycle scan green.

## Analysis

**Problem / task (UC Summary + ACs).** When spawning a session, the Android `NewSessionSheet` (today label-only) gains a **drop-down** to pick a workspace project alongside the label. The drop-down is populated by a **new mTLS-protected server endpoint** listing the top-level folders under the server's workspace root. It is offered in **both** workspace modes (shared/isolated) and defaults to a **"None"** option that preserves today's behavior exactly. When a real project is chosen, the server passes the selection through spawn and, once the session's readiness marker is up, **auto-injects and submits** exactly `We will work in the project {folder}.` into that session's Claude via the existing `InputInjectionService` `tmux send-keys` path, **before the user attaches**. Selection is non-exclusive and non-limiting (N sessions → same project allowed).

**Relevant context found in the codebase (verified):**
- Spawn chain: `SessionController.spawn` (server/src/main/java/com/aisandbox/server/api/SessionController.java:82-96) → `ApiMappers.toSpawnCommand` (server/src/main/java/com/aisandbox/server/api/mapper/ApiMappers.java:45-51) → `SessionFacade.spawnSession` (server/src/main/java/com/aisandbox/server/sessions/facade/SessionFacade.java, ~156-243) → `ScriptExecutorService.spawn` (server/src/main/java/com/aisandbox/server/sessions/service/ScriptExecutorService.java:88-100) which builds the spawn.sh argv. Internal DTO `SpawnCommand` (server/src/main/java/com/aisandbox/server/sessions/dto/SpawnCommand.java); API DTO `ApiDtos.SpawnRequest` (server/src/main/java/com/aisandbox/server/api/dto/ApiDtos.java:99-105) = {label, workspaceMode, claudeConfigMode}.
- Injection primitive: `InputInjectionService.injectComposer(int n, InjectTarget target, String text)` (server/src/main/java/com/aisandbox/server/stream/service/InputInjectionService.java:118) types the text (newline-safe) then sends `Enter` — exactly "inject + submit" (AC4). `InjectTarget.main()` (:90) addresses the session's main Claude pane. Runs `docker compose -p ai-sandbox-<n> exec -T claude-sandbox tmux send-keys …`.
- Server-initiated main-pane inject precedent: `ConversationFacade.openMcpMenu(int n, ClientIdentity)` (server/src/main/java/com/aisandbox/server/stream/facade/ConversationFacade.java:333) and `injectComposer(...)`:303; cross-domain callers reach it facade-to-facade (McpFacade→ConversationFacade).
- Readiness marker: entrypoint.sh:294 `touch "$READY_MARKER"` (=/tmp/aisandbox-ready) is **unconditional**, fired right after tmux/claude is up. spawn.sh only *waits* on it under devtools (spawn.sh:279-291), so **for a normal spawn, spawn.sh returns before the marker exists** → the server must poll it independently (correct AC6 mechanism). Probe shape at DockerEnumerationService.java:634 (currently private): `docker compose -p <project> exec -T claude-sandbox test -f /tmp/aisandbox-ready`, exit 0 = ready.
- Workspace root: `ServerProperties.Sessions.hostStateRoot()` (server/src/main/java/com/aisandbox/server/config/ServerProperties.java:271; default `/var/lib/ai-sandbox-server/sessions`). Shared workspace = `<hostStateRoot>/workspace` (confirmed lib.sh:719). Listing uses the shared root regardless of the spawning session's mode (UC assumption).
- Layered-endpoint template: `ModelController`→`ModelCatalogFacade`→`ModelCatalogService` (config-backed, read-only facade, no @Transactional).
- **ArchUnit cycle rule** `LayeringTest.no_cycles_between_top_level_feature_packages` (server/src/test/java/com/aisandbox/server/archunit/LayeringTest.java:114) treats each top-level package (`sessions`, `stream`, `mcp`, `workspace`, …) as a slice and forbids cycles. `stream → sessions` already exists (InputInjectionService imports sessions.service.ProcessExecutor); `sessions` has zero imports of `stream`. **A direct `SessionFacade→ConversationFacade` call would create a `sessions↔stream` cycle → build failure.** Resolved via the `mcp.McpLoginInitiator` port-inversion precedent (interface in the caller's package, implemented by the callee).
- Android: `NewSessionSheet` (android/src/main/kotlin/com/aisandbox/android/ui/screens/SessionsScreen.kt:986, currently private; onSpawn wiring :300-308) → `SessionsViewModel.spawn` (:88) → `SessionsCoordinator.spawn(label)` (SessionsCoordinator.kt:252, optimistic-row logic) → `SessionsApi.spawn(label)` (net/SessionsApi.kt:69). Client GET template: `ModelsApi` (net/ModelsApi.kt); model list fetched via `container.modelsApi(...).list()` (ConversationViewModel.kt:191). API factories in `AppContainer` (:111 sessionsApi, :114 modelsApi). Strings at res/values/strings.xml:64-67.

## Proposed Solution

### Design decision (b) — does the choice reach the container/spawn.sh?
**No — the selection is purely server-side post-spawn injection.** spawn.sh gets no new flag; the container spawn stays byte-identical (making AC3 "None = today" trivially true, and even a real selection leaves the spawn argv unchanged). Rationale: the folder name is used only to compose the injected prompt (informational — pitfall: the folder may not physically exist in an isolated `workspace-<N>`). `SpawnCommand` carries the selection end-to-end (AC9) but `ScriptExecutorService.spawn` never appends it to argv.

### Layering approach (resolves the cycle)
Orchestration lives in `SessionFacade` (the `sessions` slice legitimately owns spawn + readiness; the `SandboxImageService.warmAsync` daemon-thread precedent is also in `sessions`). The raw readiness probe is a `sessions` **service**. Folder lookup is `sessions → workspace` facade-to-facade. The inject step is behind a **sessions-owned port** implemented by `ConversationFacade`. Resulting slice edges — all acyclic: `sessions → workspace` (new, one-directional; workspace depends only on `config`, which is excluded from the cycle scan), `sessions → sessions.service` (same-domain), `stream → sessions` (port impl, pre-existing).

### Server — new read-only endpoint (mirrors ModelController)
- **CREATE** server/src/main/java/com/aisandbox/server/workspace/dto/WorkspaceProject.java — internal DTO `record WorkspaceProject(String id, String name)`. No API-shaping annotations (profile rule 5).
- **CREATE** server/src/main/java/com/aisandbox/server/workspace/service/WorkspaceProjectService.java — `@Service`, no @Transactional. Lists the immediate subdirectories of `props.sessions().hostStateRoot().resolve("workspace")` (skips non-dirs, `.gitkeep`, dangling symlinks; sorted, stable order). Applies a **pluggable filter predicate** defaulting to accept-all so AC1's later config-driven filter (git-repos-only / has-PROJECT_BRIEF.md) drops in with no API change. Missing/absent root → empty list (never throws). id = folder name; name = folder name (AC5).
- **CREATE** server/src/main/java/com/aisandbox/server/workspace/facade/WorkspaceProjectFacade.java — `@Component` read-only facade (no @Transactional; precedent ModelCatalogFacade). `List<WorkspaceProject> listProjects()` and `Optional<WorkspaceProject> find(String id)` (spawn-time validation, AC10). Pure list/find — **no orchestrator**.
- **CREATE** server/src/main/java/com/aisandbox/server/api/WorkspaceController.java — `@RestController @RequestMapping("/v1/workspace")`, `@GetMapping("/projects")` → `facade.listProjects()` mapped to the API DTO. mTLS applies automatically (same security chain as every non-enrollment path).
- **ADD** to server/src/main/java/com/aisandbox/server/api/dto/ApiDtos.java — API DTO `record WorkspaceProjectSummary(String id, String displayName)`.
- **ADD** to server/src/main/java/com/aisandbox/server/api/mapper/ApiMappers.java — `toWorkspaceProjectSummary(WorkspaceProject)` + a list variant (profile rule 5: internal DTO never crosses the API boundary).

### Server — carry the selection end-to-end + post-readiness injection
- **MODIFY** api/dto/ApiDtos.java — `SpawnRequest` (:99-105) gains an optional `String workspaceProject` (folder id; null/absent = "None", a first-class valid request — AC9). @Schema documents it.
- **MODIFY** sessions/dto/SpawnCommand.java — add nullable `String workspaceProject`; compact-ctor validates it against a conservative folder-safe regex **only when non-null** — a null bypasses validation entirely (AC9), matching the existing `label`-null handling. Keep an overloaded 3-arg constructor defaulting the new field to null so existing QA fixtures compile.
- **MODIFY** api/mapper/ApiMappers.java — `toSpawnCommand` (:45-51) threads `req.workspaceProject()` into `SpawnCommand`.
- **CREATE** sessions/service/SessionReadinessService.java — `@Service` (sessions slice, same as `ProcessExecutor`). Wraps the readiness probe (extracted from DockerEnumerationService.java:634's shape) via `ProcessExecutor`. Exposes `boolean awaitReady(int n, Duration deadline, Duration probeInterval)` (bounded wall-clock loop, per-probe timeout; conservative — any exec error / non-zero exit = not-ready, keep polling to deadline) and `boolean isReady(int n)`. Raw subprocess work lives here in the **service** tier, never in a facade. Unit-testable with a mocked `ProcessExecutor`.
- **CREATE** sessions/SpawnPromptInjector.java — **interface at the `sessions` root package**, mirroring `com.aisandbox.server.mcp.McpLoginInitiator`'s placement. `void inject(int n, String text) throws IOException`. The sessions-owned port; `SessionFacade` depends only on this.
- **ADD** to stream/facade/ConversationFacade.java — `implements SpawnPromptInjector`; `inject(int n, String text)` = `injection.injectComposer(n, InjectTarget.main(), text)` + a server-actor audit event (no ClientIdentity — server-initiated). This is the McpLoginInitiator shape exactly; the added edge `stream → sessions` already exists, so no new slice edge and no cycle.
- **MODIFY** sessions/facade/SessionFacade.java — after the successful-spawn branch (~:229, right after `registry.invalidate()`/audit-ok), if `cmd.workspaceProject() != null`:
  1. Resolve the folder via `workspaceProjectFacade.find(id)` (facade-to-facade, `sessions → workspace`). Absent → log/audit + return, **no injection** (AC10).
  2. Spawn a daemon thread (`new Thread(..., "spawn-prompt-inject-" + n)`, mirroring SandboxImageService.java:173) that: calls `sessionReadinessService.awaitReady(n, …)` (same-domain facade→service); **re-validates** the folder via `workspaceProjectFacade.find(id)` immediately before injecting (covers the vanish-during-wait race → absent = skip, AC10); then calls `spawnPromptInjector.inject(n, "We will work in the project " + folderName + ".")` **exactly once**. Guarded try/catch: readiness-timeout or inject failure logs + audits and **never retries, never re-injects, never crashes the session** (AC4 single-shot; AC6 fire-and-forget independence — the CREATED response is never blocked).
  3. The three collaborators (`workspaceProjectFacade`, `sessionReadinessService`, `spawnPromptInjector`) are injected via late-bound `@Autowired(required=false)` setters — same pattern as `setHostShell` (:90) / `setSandboxImage` (:97) — so existing 7-arg-ctor unit constructions compile and behave identically (all unset → block skipped, spawn byte-identical). **Null-guard each before the daemon thread dereferences them** (challenger note): any unset → skip scheduling + log once, exactly like the "no project selected" path.

Prompt text is built with the literal folder name; `send-keys -l --` sends it verbatim, so a hostile folder name cannot smuggle keystrokes, and the id is additionally membership-checked against the live listing.

### Android — dropdown + wiring
- **CREATE** android/src/main/kotlin/com/aisandbox/android/net/WorkspaceProjectsApi.kt — mirrors `ModelsApi`: `suspend fun list(): ApiResult<List<WorkspaceProjectInfo>>` GET `/v1/workspace/projects` (bare JSON array). `@Serializable data class WorkspaceProjectInfo(val id: String, val displayName: String = "")` mirroring the server API DTO field-for-field.
- **MODIFY** android/…/AppContainer.kt — add `fun workspaceProjectsApi(client) = WorkspaceProjectsApi(client)` (mirror :114).
- **MODIFY** android/…/net/SessionsApi.kt — `SpawnRequest` gains `workspaceProject: String? = null`; `spawn(label, workspaceProject)` passes it through.
- **MODIFY** android/…/ui/screens/SessionsCoordinator.kt — `spawn` (:252) → `spawn(label, workspaceProject: String?)`, passed through to `api.spawn(...)`; optimistic-row logic unchanged (`workspaceProject == null` ≡ "None").
- **MODIFY** android/…/ui/screens/SessionsViewModel.kt (:88) — `spawn(label, workspaceProject)` delegate; expose the fetched project list + a fetch on sheet-open (pattern per ConversationViewModel.kt:191).
- **MODIFY** android/…/ui/screens/SessionsScreen.kt — make `NewSessionSheet` (:986) **`internal`** (testable seam) and add an M3 `ExposedDropdownMenuBox` alongside the label field: first item **"None"** (preselected default, AC3), then one item per returned project. `onSpawn` becomes `(label, projectId)` where "None" → null. Dropdown shown unconditionally (AC7). **Stable testTags:** `new_session_project_dropdown` (anchor), `new_session_project_dropdown_field` (read-only field), `new_session_project_option_none`, `new_session_project_option_<id>` per project.
- **ADD** to android/…/res/values/strings.xml (near :64-67) — `new_session_project_label` ("Workspace project"), `new_session_project_none` ("None").

## Files Affected

### Production code (developer)
Server — NEW:
- server/src/main/java/com/aisandbox/server/workspace/dto/WorkspaceProject.java
- server/src/main/java/com/aisandbox/server/workspace/service/WorkspaceProjectService.java
- server/src/main/java/com/aisandbox/server/workspace/facade/WorkspaceProjectFacade.java
- server/src/main/java/com/aisandbox/server/api/WorkspaceController.java
- server/src/main/java/com/aisandbox/server/sessions/service/SessionReadinessService.java
- server/src/main/java/com/aisandbox/server/sessions/SpawnPromptInjector.java (interface, sessions root pkg)

Server — MODIFY:
- server/src/main/java/com/aisandbox/server/api/dto/ApiDtos.java
- server/src/main/java/com/aisandbox/server/api/mapper/ApiMappers.java
- server/src/main/java/com/aisandbox/server/sessions/dto/SpawnCommand.java
- server/src/main/java/com/aisandbox/server/sessions/facade/SessionFacade.java
- server/src/main/java/com/aisandbox/server/stream/facade/ConversationFacade.java

Android — NEW:
- android/src/main/kotlin/com/aisandbox/android/net/WorkspaceProjectsApi.kt

Android — MODIFY:
- android/src/main/kotlin/com/aisandbox/android/AppContainer.kt
- android/src/main/kotlin/com/aisandbox/android/net/SessionsApi.kt
- android/src/main/kotlin/com/aisandbox/android/ui/screens/SessionsScreen.kt
- android/src/main/kotlin/com/aisandbox/android/ui/screens/SessionsViewModel.kt
- android/src/main/kotlin/com/aisandbox/android/ui/screens/SessionsCoordinator.kt
- android/src/main/res/values/strings.xml

### Test code (qa)
Server JVM (`./gradlew :server:test`) under server/src/test/java/com/aisandbox/server/…:
- WorkspaceProjectServiceTest (temp-dir root: lists dirs; skips files/.gitkeep/dangling symlinks; empty when root absent; filter-predicate hook).
- WorkspaceController + REST round-trip test (GET returns API DTOs; mTLS).
- ApiMappers test (toWorkspaceProjectSummary; toSpawnCommand threads workspaceProject).
- SessionReadinessService test (mocked ProcessExecutor: ready / not-ready / bounded-timeout).
- SessionFacade orchestration test: valid id → **exactly-one** `inject(n, "We will work in the project <folder>.")` only after `awaitReady` true (AC4/AC5/AC6); null project → zero probes, zero inject, spawn byte-identical (AC3); stale/absent id at schedule OR pre-inject re-validate → zero inject, spawn still CREATED (AC10).
- ConversationFacade.inject unit test asserting exact `send-keys -l -- <text>` + `Enter` argv.
- LayeringTest stays green + new belt-and-suspenders rule: `..workspace..` ↛ `..sessions..`/`..stream..`.

Android instrumented (`./gradlew :android:connectedDebugAndroidTest`) — MANDATORY on-device UI functional test:
- PRIMARY (mandated, server-free, UC-85-style, stable testTags): NEW android/src/androidTest/kotlin/com/aisandbox/android/ui/screens/NewSessionSheetProjectDropdownInstrumentationTest.kt driving the now-`internal` `NewSessionSheet` seam with a seeded in-memory project list and a captured `onSpawn(label, projectId)` callback:
  - **None path:** sheet opens, dropdown "None" preselected → tap `new_session_spawn` → assert `onSpawn` fires with `projectId == null` (AC2/AC3).
  - **Real-project path:** open `new_session_project_dropdown` → tap `new_session_project_option_<id>` → tap spawn → assert `onSpawn` carries that id (AC2/AC4).
  - Assert the dropdown lists returned projects + "None" and renders regardless of mode (AC7). Zero dependency on any server-side replay change.
- SECONDARY (optional, decoupled): gate-style test wiring the real container `WorkspaceProjectsApi` against the replay-profile server asserting the dropdown populates from the live wire — flagged optional-if-costly. tmux injection isn't wire-observable on a synthetic replay session, so AC4/AC5/AC6/AC10 stay covered by the server JVM tests.

## Risks & Considerations
- Readiness→TUI-paint race / TUI pin 2.1.169: a small post-ready settle advisable; injection failure must be non-fatal.
- AC10 stale id: re-validate at schedule AND immediately before inject; absent → skip silently (audit), never fail spawn.
- Isolated mode: prompt references folder by name even if it doesn't physically exist in `workspace-<N>` (informational; seeding out of scope).
- Security: folder name → tmux `-l` literal (no shell) + live-listing membership check; SpawnCommand regex is defense-in-depth; endpoint mTLS.
- Filter extensibility (AC1): service takes a predicate now (default accept-all) so a config filter lands later with no API/DTO change.
- Back-compat: overloaded ctor + optional setters → existing tests/behaviour unchanged when no project selected.
- No version discovery/upgrades (brief pins Java 21 / Spring Boot / Gradle).

## Challenger verdict
APPROVED (round 2). Slice graph `{sessions→workspace, sessions→sessions.service, stream→sessions}` verified acyclic; all 10 ACs + 4 pitfalls covered; mandatory server-free instrumented Compose UI test present (None + real-project paths, stable testTags). Non-blocking developer note: null-guard the `@Autowired(required=false)` collaborators before the daemon thread dereferences them.
