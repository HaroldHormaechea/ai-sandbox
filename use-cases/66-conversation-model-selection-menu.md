# Use Case 66: Conversation menu — model selection popup for the conversation or selected agent

## Summary
The conversation overflow menu (`ConversationScreen.kt`) gains a **Model** item that opens a popup/dialog listing the Claude models available on the server (e.g. Opus 4.8, Opus 4.7, Sonnet, Haiku — whatever the server is configured to offer). Selecting a model sends the corresponding model-change command (Claude Code's `/model <id>`) to the active conversation, applying it to the currently selected target — the main conversation **or** the selected agent/teammate chosen in the `AgentSwitcherBar` (input already routes to the selected target's pane). The available-model list is sourced from the server, not hardcoded in the app: a new server endpoint returns the catalogue of model ids + human labels so the app stays in sync as models change. The popup indicates the current/selected model and is dismissable without changing anything.

## Acceptance Criteria
1. The conversation overflow menu shows a **Model** item.
2. Tapping **Model** opens a popup/dialog listing the models the server reports as available (each with a human-readable label such as "Opus 4.8", "Opus 4.7", "Sonnet").
3. The model list is fetched from a server endpoint (not a hardcoded client list); adding/removing a model server-side changes what the popup shows without an app change.
4. Selecting a model sends the appropriate model-change command (`/model <id>`) to the currently selected target — the main conversation when no agent is selected, or the selected agent/teammate when one is selected in the `AgentSwitcherBar`.
5. The popup reflects which model is currently active/selected (e.g. a checkmark or highlighted row) where that information is available.
6. Dismissing the popup without choosing leaves the model unchanged.
7. After a model change, the session remains usable: subsequent messages are answered (by the newly selected model, where observable).
8. QA verifies end-to-end against a live server + emulator: open popup, see the server's model list, pick a model, confirm the command reaches the session (and, where observable, that the model actually switched).

## Potential Pitfalls & Open Questions
- **Missing input** — There is no existing server model endpoint or any model-selection plumbing today (confirmed by exploration). The server must expose the available-model catalogue; the source of truth could be a static configured list of Claude Code model aliases/ids the harness accepts. The dev-team decides where the list lives (config vs. derived).
- **Ambiguity** — "Models available in the server": resolved as a server-configured list of Claude model ids/aliases acceptable to the embedded Claude Code (`opus`, `sonnet`, `haiku`, plus pinned ids like `claude-opus-4-8`). Exact catalogue is a server config detail, not fixed by this use case.
- **Risk / Edge case** — Per-agent model change: Claude Code's `/model` switches the model of the Claude instance in that pane. For a *teammate/subagent* target, sending `/model` to its pane is only meaningful if that teammate is itself a Claude Code instance accepting `/model`. Resolved decision: route `/model` to the selected target's pane via the existing target-injection mechanism; if the dev-team finds subagent panes don't accept `/model`, scope model-change to the main session and disable/grey the option for non-main targets (document the limitation).
- **Assumption** — Delivery reuses the conversation input path (a `composer-input` `/model <id>` frame) unless a dedicated control frame proves cleaner.
- **Edge case** — Reflecting the *current* model: Claude Code may not surface the active model over the existing frames. If current-model state isn't available, the popup may omit the active-model indicator (criterion 5 is best-effort) — document if so.

## Original Description
"I want in that same menu a new button that shows a popup with the model selection for this conversation OR SELECTED AGENT, that shows the models available in the server (opus 4.8, opus 47, sonnet, whatever). It should be able to send the required command to Claude."

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- Model catalogue comes from a new server endpoint; app does not hardcode it.
- Model change applies to the selected target (main or AgentSwitcherBar selection), reusing existing input routing.
- Active-model indicator is best-effort, contingent on the harness exposing the current model.
