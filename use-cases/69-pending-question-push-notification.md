# Use Case 69: Push notification when a session raises a question, deep-linking to the conversation

## Summary
When any session raises a pending AskUserQuestion, the phone posts a system notification so the user notices even when the app is backgrounded or on another screen. The notification's title identifies which session is asking (e.g. "ai-sandbox-3", ideally with the conversation name), and its body contains the initial part of the question text — the first question when several are asked together in the same conversation. Tapping the notification opens the app directly on that session's conversation screen. There is no FCM/Firebase today; the app already learns of pending questions from the server's tmux-pane signal (the `pending-question` frames and the sessions-events feed used by UC-49/UC-50/UC-55). This use case adds a local-notification path driven by that existing signal: a component that stays subscribed to session events (e.g. via the existing foreground service) detects a session transitioning into the pending-question state and posts/updates a notification, with a deep-link `PendingIntent` into the corresponding conversation.

## Acceptance Criteria
1. When a session enters the pending-question state, a system notification is posted (subject to the `POST_NOTIFICATIONS` runtime permission already declared in the manifest).
2. The notification title identifies the session (the session label such as "ai-sandbox-<n>", using the conversation name when available).
3. The notification body shows the initial portion of the question text; when multiple questions are raised together in one conversation, it uses the first question's text (truncated as needed).
4. Tapping the notification opens the app and navigates directly to that session's conversation screen (deep link by session id), not merely the sessions list.
5. The notification fires when the app is backgrounded as well as foregrounded — i.e. detection does not depend on the conversation screen being open (it rides the always-on session-events subscription / foreground service).
6. Duplicate suppression: a single pending-question episode produces one notification (not repeated every poll/delta); a new distinct question may update or replace it.
7. The notification is dismissed/cancelled once the question is answered or the pending state clears (so stale "answer me" notifications don't linger).
8. A dedicated notification channel is created so the user can manage these notifications; behaviour degrades gracefully if notification permission is denied (no crash; in-app indicators from UC-49 still work).
9. QA verifies on a live server + emulator: trigger an AskUserQuestion in a session with the app backgrounded, observe the notification with correct session + first-question text, tap it, and land on the right conversation; then answer and confirm the notification clears.

## Potential Pitfalls & Open Questions
- **Missing input** — The question *text* must reach the notifier. The conversation WebSocket's `pending-question` frame carries the prompt, but that channel is only open on the conversation screen. The sessions-events feed (UC-49) currently carries a pending *flag*, not necessarily the question text. Likely server change: include the first question's text (truncated) in the session-events pending signal so a backgrounded device can build the notification body without opening each conversation.
- **Risk** — Background execution: detecting pending questions while backgrounded requires the session-events subscription to stay alive in the background. The existing `TerminalForegroundService` is the natural host, but it may only run for the active session; this UC may need a service that keeps the `/v1/sessions/events` feed connected app-wide. Scope the background-liveness mechanism carefully (battery, Android background limits).
- **Edge case** — Deep link: navigating directly to a conversation from a cold start (process not running) must reconstruct nav state to land on the conversation, not crash or land on an empty backstack.
- **Edge case** — De-duplication keying: a question that is re-signalled on every events delta must map to one notification (key by session id + question identity/epoch).
- **Edge case** — Permission denied / Android 13+ runtime `POST_NOTIFICATIONS`: handle gracefully; the app must still function with in-app UC-49 indicators.
- **Assumption** — Notifications are local (built on-device from server signals); no FCM/Firebase is introduced (consistent with current architecture).
- **Edge case** — Multiple sessions pending at once: each session gets its own notification (distinct notification ids), each deep-linking to its own conversation.

## Original Description
"New thing too. When a session prompts for a question, the phone should get a notification stating which session, and in the notification text put the initial part of the question, or first question if multiple ones are in the same conversation. Clicking in the notification must navigate directly to the relevant conversation"

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- Notifications are local, driven by the existing pending-question signal; no FCM introduced.
- Server likely needs to include the first-question text in the app-wide session-events signal so a backgrounded device can render the body.
- Body uses the first question when multiple are asked together; title identifies the session.
- Tapping deep-links straight to the conversation; notification clears on answer.
