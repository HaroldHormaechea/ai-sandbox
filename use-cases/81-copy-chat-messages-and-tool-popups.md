# Use Case 81: Copy chat messages (both sides) and tool-popup information

## Summary
The ai-sandbox Android conversation view does not let the user **copy message text**. This use case adds a copy affordance so the user can copy the content of chat messages **from both sides** — their own (user) messages and the assistant's (and, consistently, teammate/subagent bubbles) — as well as the **information shown in tool/skill popups** (the UC-41 tool/skill detail popup/expanded content). Copying places the message's (or popup's) text on the system clipboard so it can be pasted elsewhere. The interaction should follow a familiar chat-app convention (e.g. long-press a bubble to copy, or a copy button/menu), be available for both message sides, and copy the **full** text (consistent with UC-80's un-cropped messages).

## Acceptance Criteria
1. The user can copy the text of an **assistant** message to the system clipboard via a discoverable affordance (long-press or an explicit copy action).
2. The user can copy the text of their **own (user)** message to the clipboard via the same affordance — copy works on **both sides** of the chat.
3. The user can copy the information shown in a **tool/skill popup** (the UC-41 tool/skill detail/expanded content) to the clipboard.
4. Copied text is the **full** message/popup content (not a cropped version) and lands on the Android system clipboard correctly (verifiable by pasting).
5. A brief confirmation of the copy (e.g. a toast/snackbar or the platform's default clipboard feedback) is shown, following platform convention.
6. The copy affordance is consistent across bubble types (user, assistant, teammate/subagent) and does not interfere with existing interactions (scrolling, the UC-41 expand/collapse, tapping pills, answering questions).
7. No regression to UC-37/40/41/47/49/50/58 conversation behavior. CI gates pass: `:android:test` + `:android:lint` (server gates only if server code changes — not expected).

## Potential Pitfalls & Open Questions
- **Ambiguity (interaction model)** — Long-press-to-copy vs an explicit copy icon/overflow action per bubble. The analyst should pick the most idiomatic, least-intrusive option for Compose and confirm it doesn't collide with existing gestures (e.g. the UC-41 tap-to-expand on tool/skill bubbles, or any selection behavior).
- **Edge case** — Tool/skill bubbles have both a collapsed summary and an expanded popup (UC-41). Define exactly what "copy the tool popup information" copies (the expanded/detail text), and make copy available in the popup context.
- **Edge case** — Very long messages (UC-80): ensure the full text is copied, and that placing a large string on the clipboard works without truncation.
- **Edge case** — Non-plain content: if a message contains structured/teammate-envelope-derived content (UC-58) or formatting, copy the human-readable rendered text, not raw markup.
- **Risk** — Accessibility/selection conflicts: adding long-press copy must not break TalkBack or any existing text-selection; verify the gesture layering.
- **Relationship** — UC-41 (tool/skill detail popup — the copy source for AC3), UC-80 (full text must be present to be fully copyable), UC-58 (teammate bubbles included in "both sides" consistency).

## Original Description
Allow copying messages in the chat from both sides, and tool popups information.

## Clarifications
- Status: **Captured during the autonomous UC-58→60 run (2026-06-15) at the user's request.** Interactive clarification loop skipped (autonomous capture); the exact copy interaction (long-press vs button) and the precise tool-popup copy target are left for the analyst to resolve against the Android conversation/UC-41 popup code. To be implemented in this same autonomous batch; release deferred until all queued UCs (58–60 + 78–81) are merged.
