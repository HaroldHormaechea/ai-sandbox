# Use Case 53: Android client — Settings screen with appearance preferences (current settings demoted to an "Info" subsection)

## Summary
Restructure the Android client's existing `SettingsScreen` into a proper preferences surface. The screen's current read-only content — the Server, Identity (mTLS cert), WebSocket, and Diagnostics sections plus the version footer — is demoted into an **Info** subsection at the bottom, while a new **Appearance** group is added above it. The new preferences are: (1) a **font size** preference (discrete steps S/M/L/XL) that rescales text in the conversation/agent view only; and (2) a **"use agent color in bubbles" toggle** (default OFF) that subtly tints conversation assistant message bubbles toward each message's agent color, reusing the existing `agentColor` name→`Color` mapping. The existing UC-36 keyboard conversational toggle is a genuine behavior preference and stays as a top-level preference (not buried in Info). All new preferences persist across app restarts via a new DataStore-backed store mirroring `KeyboardSettingsStore`, and the conversation view reads them reactively so changes apply live. **Theme/light-mode is explicitly out of scope for this use case** — the app remains dark-only per UC04 AC13; a theme selector is deferred to its own future use case.

## Acceptance Criteria
1. The Settings screen presents a top **Appearance** group and, below it, an **Info** group; the previously top-level Server, Identity, WebSocket, and Diagnostics sections and the version footer now live under **Info**.
2. A **font-size** preference with discrete steps (S/M/L/XL) is available in Appearance; changing it visibly rescales text in the conversation/agent view (and only that view — terminal and other screens are unaffected), and the chosen size survives app restart.
3. A **"use agent color in bubbles"** toggle is available in Appearance and defaults **OFF**. When ON, conversation assistant bubbles render with a subtle background tint derived from the message's agent color; when OFF, bubbles keep today's neutral `SurfaceLow` background.
4. The tint reuses the existing color-name→`Color` mapping (`agentColor`) — no second, divergent palette. Messages with no agent color (e.g. the main session) keep the neutral background regardless of the toggle. User (right-aligned) bubbles are unaffected.
5. The existing UC-36 keyboard conversational toggle remains functional, reachable as a top-level Appearance/Behavior preference, and continues to persist.
6. All new preferences are persisted in DataStore (no in-memory-only state) and read reactively, so a change applies without restarting the app.
7. Very large font sizes do not break bubble layout (80%-of-viewport max width) or clip text.
8. New/changed Kotlin passes `:android:lint` and unit tests; the new settings store has unit-test coverage following the `KeyboardSettingsStoreTest` pattern.

## Potential Pitfalls & Open Questions
- **Assumption** — The new Settings is a restructure of the existing `SettingsScreen`, not a brand-new screen/route.
- **Edge case** — Font scaling must apply to the bubble body text style currently hardcoded as `MaterialTheme.typography.bodyMedium`; the implementer must thread the scale through the conversation view's text styles without affecting unrelated screens.
- **Edge case** — The agent-color background tint must stay subtle enough to keep `OnSurface` body text readable (low-alpha blend over `SurfaceLow`, not the full saturated dot color used in the switcher).
- **Note (deferred)** — Theme/light-mode selector was requested but is split out to a separate future use case because the app is intentionally dark-only (UC04 AC13) and a real light theme requires authoring a full token palette.

## Original Description
> Project ai sandbox. I want you to create new settings. The current settings should go to an info subsection of those. The new settings should be things like font size, a toggle to use the agent 'color' in the agent view bubbles, and such.

## Clarifications
- Q: The app is dark-only by explicit UC04 design. How should the theme selector be handled?
  A: Drop theme this UC — keep dark-only (respect UC04 AC13); defer a theme selector to its own future use case.
- Q: Which currently-visible settings move into the new "Info" subsection, and where does the UC-36 keyboard conversational toggle go?
  A: Read-only sections (Server, Identity, WebSocket, Diagnostics) + version footer go under Info. The UC-36 keyboard toggle stays as a top-level preference.
- Q: How should the font-size preference work, and what should it affect?
  A: Discrete steps (S/M/L/XL); scales only the conversation/agent view text.
- Q: When the agent-color toggle is ON, how should an assistant bubble show its agent color, and what's the default?
  A: Subtle tinted bubble background; toggle defaults OFF.
