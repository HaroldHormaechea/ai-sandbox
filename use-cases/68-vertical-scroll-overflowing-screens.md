# Use Case 68: Bugfix — add vertical scrolling to screens that can overflow (Settings is cut off)

## Summary
The general **Settings** screen (`SettingsScreen.kt`) renders its content in a plain `Column` with no `verticalScroll` modifier, so on devices where the content is taller than the viewport the bottom of the screen (e.g. footer/diagnostics) is cut off and unreachable. This bugfix makes Settings vertically scrollable and audits every other top-level screen for the same defect, adding vertical scrolling wherever content can exceed the viewport. Screens already backed by a `LazyColumn` (the Sessions list `SessionsScreen.kt`, and the conversation transcript) are inherently scrollable and only need confirmation, not changes. The fix must not break existing scroll behaviour, fixed top/bottom bars (Scaffold app bars, the conversation composer), or `imePadding`/`navigationBarsPadding` insets.

## Acceptance Criteria
1. The Settings screen scrolls vertically: on a short viewport every section, including the last/footer/diagnostics content, can be scrolled into view and is no longer clipped at the bottom.
2. Every top-level screen is audited; any that renders potentially-overflowing content in a non-scrollable container is made vertically scrollable. At minimum Settings is fixed; the audit explicitly covers Sessions list, Conversation, Terminal, and Enrollment screens, recording each as already-scrollable or fixed.
3. Screens already scrollable via `LazyColumn` (Sessions list, conversation transcript) continue to scroll and are not regressed.
4. Fixed chrome is preserved: Scaffold top app bars stay pinned, the conversation composer/bottom bar stays pinned, and the new scroll regions sit between them (scrolling does not move the app bar or composer).
5. Window insets remain correct after the change: keyboard (`imePadding`) and system bars (`navigationBarsPadding`/status bar) padding still apply, with no double-padding or content hidden behind bars.
6. QA verifies on the emulator that the Settings screen can be scrolled to its bottom item, and spot-checks the other screens for correct scroll/no-regression, including with the soft keyboard open where relevant.

## Potential Pitfalls & Open Questions
- **Edge case** — Nesting a `verticalScroll` Column inside a Scaffold: the scroll modifier must be applied to the content Column (inside `innerPadding`), not the Scaffold, so app bars stay fixed.
- **Risk** — Never wrap a `LazyColumn` in a parent `verticalScroll` (infinite-height measurement crash). The audit must distinguish list-backed screens (leave alone) from `Column`-backed screens (add scroll).
- **Edge case** — `verticalArrangement = spacedBy(...)` on the Settings Column interacts with scroll; confirm spacing is preserved and there is bottom content padding so the last item isn't flush against the nav bar.
- **Edge case** — Keyboard interplay on screens with text fields (Enrollment, composer): ensure adding scroll doesn't fight `imePadding` or cause jumpiness.
- **Assumption** — "All windows that may require it" is scoped to the app's top-level Compose screens; transient dialogs/popups (and the new UC-66 model popup / UC-67 MCP screen) should also be scrollable if their content can overflow, but the audit's primary target is the existing screen set.

## Original Description
"Bugfix: the general settings screen doesn't have a scroll and is cut at the bottom as it is longer than normal screens. Add vertical scrolling to all windows that may require it, e.g. the list of sessions which I don't know if has it."

## Clarifications
Captured in autonomous mode (maintainer pre-authorized full autonomy):
- The Sessions list is already a `LazyColumn` (scrollable) — confirmed by exploration; the audit verifies it and focuses the fix on the non-scrollable `Column`-based Settings screen.
- Scope is the top-level Compose screens plus any overflow-prone dialogs; fixed chrome and insets must be preserved.
