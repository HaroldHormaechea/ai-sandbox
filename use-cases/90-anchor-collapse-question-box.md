# Use Case 90: Anchor and collapse the question box to read conversation context

## Summary
In the Android app's chat/conversation view, when an `AskUserQuestion` prompt (a single question or a multi-question group) is displayed, the user currently cannot scroll the conversation to re-read the context that led to the question. This use case **anchors the question box at the top of the chat screen** while leaving the conversation scrollable beneath it, and adds a **collapse/expand control** (an icon on the question box) that temporarily collapses the box to a compact header so the user can read and scroll the messages underneath, then re-expand it to answer. The box appears **expanded by default**; collapsing it shows a compact header bar carrying the expand icon plus a **short label** (the first question's title, or "Question pending"). For a very tall question group, **collapse is the intended way to free up screen space** — the box is not internally height-capped. The question remains pending and answerable throughout — collapsing, expanding, or scrolling never dismisses it or loses entered/selected input. The new jump-to-bottom affordance from UC-89 **coexists** with this: it still functions while a question is anchored. This is confined to the Android Compose chat UI and its question-rendering/scroll-state handling; it touches no server code, the question protocol, or message transport, and must not regress the single/multi-question + selected-option-sent invariants asserted by UC-85's functional gate.

## Acceptance Criteria
1. While an `AskUserQuestion` (single or multi-question group) is pending, the conversation message list **remains scrollable** so the user can read messages that preceded the question.
2. The question box is **pinned/anchored at the top** of the chat screen (stays visible while the conversation scrolls underneath it), rather than covering the whole view or being locked in place with the list.
3. The question box appears **expanded by default**, showing the full question(s) and options as it does today.
4. The question box shows a **collapse control** (icon). Activating it collapses the box to a compact header bar that displays the expand icon plus a **short label** — the first question's title, or "Question pending" when no concise title is available.
5. While collapsed, the **expand control** restores the full question box with all questions/options exactly as before; no entered text or selected options are lost across collapse/expand cycles.
6. Collapsing, expanding, or scrolling **never dismisses or auto-answers** the pending question; it stays pending until the user explicitly submits.
7. For a multi-question group, the anchor + collapse behavior applies to the whole group as one unit (all questions collapse/expand together under one header), consistent with how the group is presented today.
8. A very tall expanded question group is **not internally height-capped**; the user collapses the box to read context beneath it. (The box may extend down the screen when expanded; collapse is the mitigation.)
9. Submitting the answer works identically whether the box was collapsed at any point or not: the selected option(s)/text are sent correctly and the question UI is then removed.
10. The UC-89 jump-to-bottom button **continues to work** while a question is anchored at top (the two affordances coexist); answering or expanding the question does not break it.
11. The anchored question box and its collapse control do not obscure the message composer/input or other interactive controls, and respect safe-area/insets.
12. Behavior is covered by an instrumented/Compose UI test (testTag-based, consistent with UC-85's deterministic gate) verifying: list scrollability while a question is pending, collapse→read→expand preserving state, correct submission after a collapse/expand cycle, and coexistence with the UC-89 button.

## Potential Pitfalls & Open Questions
- **Edge case** — New messages arriving while a question is pending and the box is anchored: placement/scroll behavior must stay coherent with UC-89's follow rules.
- **Risk** — This restructures how `AskUserQuestion` is laid out in the chat; it must not regress the single/multi-question + selected-option-sent invariants that UC-85's functional gate and the release skill assert.
- **Assumption** — The question UI is a Compose component rendered within (or over) the `LazyColumn` chat list; anchoring means lifting it into a top slot of the chat screen scaffold. To be confirmed against the actual composable by the dev-team.

## Original Description
In the chat when a question or group of questions is shown i want it to be possible to scroll the conversation so you can see the context of the question. I guess the question box should anchor at the top of the screen and have some icon to temporarily collapse it so you can read under it and scroll

## Clarifications
- Q: When a question first appears, should it start expanded or collapsed?
  A: Expanded by default — shows the full question/options immediately; user taps the icon to collapse when they want to read context.
- Q: What should the collapsed question header show?
  A: Icon + short label — a bar with the expand icon and a short label (the first question's title, or "Question pending").
- Q: If an expanded question group is very tall (many questions / long options), how should it fit on screen while anchored at top?
  A: Collapse is the only mitigation — the box can grow tall; the user collapses it to read context. No internal height cap.
- Q: How should this interact with UC-89's jump-to-bottom button while a question is anchored and the user scrolls up?
  A: Both coexist — the jump-to-bottom button still works while a question is anchored at top; answering/expanding doesn't disable it.
