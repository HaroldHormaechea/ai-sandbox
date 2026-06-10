# Use Case 54: Tri-state server-connectivity dot on the Sessions screen

## Summary
On the main `SessionsScreen`, the connectivity dot next to the server URL is hardcoded green (`SessionsScreen.kt:171-176`, bound to `Success`) and never reflects real connectivity. UC-52 added a `SessionsUiState.unreachable` flag surfaced as an intrusive banner (`SessionsScreen.kt:210-216`) when a server call hits `NetworkEvent.ServerUnreachable`. This use case replaces that banner-based signaling with a tri-state colored dot driven by connectivity state: **green** when the last server interaction succeeded, **yellow** while a connectivity check/refresh is in flight or state is not yet known, **red** when the last interaction failed (unreachable). The UC-52 banner is removed so a server outage degrades gracefully via the dot alone, without a disruptive surface. Connectivity is detected exactly as today via `TlsFailureTranslation`/`SessionsCoordinator`; only the presentation changes. The onboarding/enrollment flow (and its `FailurePanel`) is out of scope and unchanged.

## Acceptance Criteria
1. The connectivity dot on `SessionsScreen` is bound to connectivity state in `SessionsUiState`, not hardcoded to `Success`.
2. The dot is **green** when the last server interaction succeeded (server reachable).
3. The dot is **yellow** while a connectivity check / refresh / spawn is in flight, or before the first interaction has completed (state unknown).
4. The dot is **red** when the last server interaction failed (`unreachable = true`).
5. The UC-52 `unreachable` banner is removed; the dot is the single connectivity signal on the Sessions screen.
6. When connectivity is restored by any successful operation, the dot automatically returns to green (no manual retry needed).
7. The color-state derivation is covered by JVM-testable unit tests on `SessionsCoordinator`/state.
8. The onboarding `FailurePanel` behavior is unchanged.

## Potential Pitfalls & Open Questions
- **Assumption** — Initial/loading state (before the first call resolves) maps to yellow per AC #3.
- **Assumption** — Yellow/red use existing theme palette colors (a `Warning`/`Error` equivalent alongside `Success`); if no `Warning` color exists, one is added to the theme.
- **Risk** — Color is the only signal; a `contentDescription` should be set on the dot for accessibility (green/yellow/red → reachable/checking/unreachable).

## Original Description
When server is unavailable we are showing the same screen as when the onboarding by qr fails. We need to not show a blocking screen Just modify the green dot next to the server IP from green to yellow to red depending on the connectivity situation

## Clarifications
- Q: Where should this apply (the dot lives on the Sessions screen; the blocking FailurePanel is in the onboarding flow)?
  A: Sessions screen only — leave the onboarding flow as-is.
- Q: What should yellow vs. red mean?
  A: Green = last call succeeded; yellow = a connectivity check is in flight / unknown; red = last call failed (server down).
- Q: How should the new dot coexist with the UC-52 unreachable banner?
  A: The dot replaces the banner — remove the banner so the dot is the single connectivity signal.
- Q: During onboarding, what should replace the blocking screen for server-unavailable?
  A: Defer — leave onboarding behavior unchanged; this UC only touches the Sessions-screen dot.
