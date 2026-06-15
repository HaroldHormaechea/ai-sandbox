# Use Case 58: Teammate/subagent messages render as the user's own messages in the conversation view

## Summary
In the ai-sandbox Android conversation view, when the displayed Claude Code session is a **team-lead session** (one that has spawned a teammate/subagent team — analyst/challenger/developer/qa, or any `Agent`/`TeamCreate` swarm), the messages authored by those teammates are rendered as the **user's own messages** instead of as distinct non-user messages. The user reported seeing teammate content (e.g. a literal `<teammate-message teammate_id="…" color="…">…</teammate-message>` envelope, including idle-notification JSON) attributed to themselves in the chat. The underlying reason is structural: the Claude Code harness delivers inbound teammate messages to the team lead **as user-role turns** in the lead's transcript ("messages appear automatically as new conversation turns, like user messages"), so any attribution logic that keys purely on the transcript role will mis-bucket them as the user. The fix must detect the teammate-message envelope/marker (the `<teammate-message …>` wrapper and/or an equivalent metadata signal) and render those turns as a distinct, non-user speaker — ideally attributed to the sending teammate by name/colour — while leaving the user's genuine messages and the lead's assistant messages correctly attributed. The change most likely lives in the structured-conversation transcript-tail parser (the UC-37 `container-bin/aisandbox-conversation-tail` Node helper) and/or the Android conversation renderer; the analyst must confirm where speaker attribution is decided.

## Acceptance Criteria
1. In a team-lead session, a message authored by a teammate/subagent is rendered as a **non-user** message in the Android conversation view — visually distinct from the user's own messages.
2. Where the teammate-message envelope carries sender identity (`teammate_id` / name / `color`), the message is attributed to that teammate (name and/or colour), not shown anonymously and not shown as the user.
3. The user's **genuine** messages remain correctly attributed to the user (no over-correction that flips real user turns to teammate turns).
4. The lead session's own **assistant** messages remain correctly attributed to the assistant.
5. The fix covers the transcript-derived conversation path (UC-37 tail helper) and, if applicable, any pane-derived path — the analyst determines which surface(s) decide attribution.
6. Robust parsing: teammate-message envelopes that contain nested JSON (e.g. `{"type":"idle_notification",…}`) or multi-line content are handled without leaking raw envelope markup into the rendered bubble.
7. No regression to single-agent conversation rendering (UC-37/UC-40/UC-47/UC-49/UC-50 and related), and their tests still pass.
8. CI gates pass: `:android:test` + `:android:lint`, and `:server:test` + `:server:spotlessCheck` if any server/Node helper code changes.

## Potential Pitfalls & Open Questions
- **Assumption** — The harness injects teammate messages into the lead transcript as **user-role** turns; that is why role-only attribution fails. The analyst must confirm this against an actual team-lead transcript and find the exact, reliable marker that distinguishes a teammate turn from a genuine user turn (the `<teammate-message …>` wrapper is the visible candidate; check whether a structured metadata field also exists).
- **Ambiguity** — Where is speaker attribution actually decided: in the `aisandbox-conversation-tail` Node helper (UC-37) that emits structured turns, in the server's conversation event mapping, or in the Android renderer? The fix belongs wherever the role is assigned; identify it before proposing.
- **Edge case** — Idle-notification envelopes and peer-DM summaries are also teammate-origin turns; decide whether to render, collapse, or suppress them rather than showing raw JSON as a user bubble.
- **Edge case** — A genuine user message that literally contains the text `<teammate-message` must not be misclassified; rely on the authoritative envelope/metadata, not a naive substring match on user-typed content.
- **Risk** — Over-correction: flipping real user turns to non-user would break the conversation's readability worse than the original bug. Criterion 3 guards this.
- **Relationship** — This is specific to team-lead / multi-agent sessions (a niche but real mode — e.g. the dev-team orchestrator session itself). It does not affect ordinary single-agent sessions, which already render user vs assistant correctly.

## Original Description
New bug for later. Teammate messages show as my own messages in the chat. <Teammate message....>

## Clarifications
- Status: **Captured for later — not yet dispatched.** Filed during an autonomous UC-56/UC-57 run at the user's request ("new bug for later"); no dev-team run started for it. When dispatched, it will get its own worktree + ledger row off the then-current `main`, and an analyst should resolve the open attribution-locus question above before implementing.
- Note: The interactive clarification loop was skipped (autonomous capture). The leading hypothesis — teammate turns arrive as user-role transcript entries and need envelope-based re-attribution in the UC-37 transcript-tail parser and/or the Android renderer — is recorded above for the analyst to confirm against a real team-lead transcript.
