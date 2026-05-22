# Use Case 18: Android sessions-screen cards do not respond to taps

## Summary
On the released **android-v0.3.3** build of the ai-sandbox Android client, the session cards on the sessions screen are unresponsive to taps: tapping a card does not navigate to / connect to that session's tmux stream. The screen otherwise renders roughly correctly and other screens (onboarding, enrollment) behave normally, so the defect is isolated to **touch/click handling on the sessions list** rather than layout, data loading, or navigation infrastructure. The likely cause is a Jetpack Compose interaction-wiring problem — a misplaced or missing `clickable`/`onClick` on the card, a transparent overlay or sibling composable consuming pointer events (z-order), a `LazyColumn` item whose click target doesn't receive events, or click-gating state leaving cards disabled. **Implementation is currently blocked**: this development host has no confirmed way to build, run, or tap-test the Android app (no verified Android SDK/emulator/device, and the Compose instrumented `androidTest` suite is not run in CI), so a fix cannot be verified. Standing up an Android testing environment is a prerequisite to be handled separately before this is implemented.

## Acceptance Criteria
1. Tapping a session card on the sessions screen opens/connects to that session's tmux stream (the documented connect action), on a phone running a build derived from current `main`.
2. The tap target covers the full visible card area and shows standard Compose press feedback (ripple/state) when touched.
3. Other interactive controls on the sessions screen (refresh, create-session, overflow/menu, pull-to-refresh if present) remain clickable and unaffected.
4. No regression to the other screens (onboarding, enrollment), which currently work.
5. An automated Compose UI / instrumented test exercises a card tap and asserts the connect/navigation action fires (added once the test environment exists).
6. Behavior is verified on an actual app build (emulator or device) before the change is considered done.

## Potential Pitfalls & Open Questions
- **Risk** — Blocked until an Android testing environment (SDK + emulator/device + a way to run Compose instrumented tests) exists on the dev host; without it any fix is unverifiable. Gating prerequisite, to be set up separately first.
- **Missing input** — Regression status unknown: unclear whether card taps worked in v0.3.0–v0.3.2 and broke at v0.3.3, or never worked. A git-history/bisect of `android/`'s sessions screen will help the implementer.
- **Ambiguity** — Exact intended card-tap action ("connect to tmux") should be confirmed against the UC04/UC16 sessions flow (navigate to a stream/terminal view vs. open the WebSocket session view).
- **Edge case** — Tablet form factor not assessed (user reported phone, sessions screen only); verify the fix on tablet too, since Compose interaction can differ by window size class.
- **Assumption** — Assumed the layout is fine and the defect is purely interaction (per the "taps don't register" report); an invisible overlay could straddle both layout and touch.

## Original Description
"The UI of the phone app is VERY wonky. E.g. I can not click on any of the cards to connect to the tmux..."

## Clarifications
- Q: Beyond the cards not responding, what does the wonkiness look like?
  A: Taps don't register — the layout looks roughly right; it's purely an interaction/click problem.
- Q: Which build of the app are you seeing this on, and did it work before?
  A: The installed android-v0.3.3 APK.
- Q: Where does the wonkiness show up?
  A: The sessions screen only; other screens behave fine.
