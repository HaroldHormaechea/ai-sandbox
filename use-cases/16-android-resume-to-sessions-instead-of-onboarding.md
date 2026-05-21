# Use Case 16: Android client — cold-start after enrollment should resume to the sessions list, not re-prompt for QR

## Summary

After a successful onboarding flow on `android-v0.3.1` (QR scanned, enrollment redeemed, client cert imported into AndroidKeyStore, sessions list rendered), backgrounding the app and reopening it returns to the QR-scanner screen instead of the sessions list. The persisted state IS on disk — both the `ServerProfile` (DataStore) and the client cert (AndroidKeyStore under alias `ai-sandbox-client-cert`) survive the kill — but the navigation graph's start destination unconditionally points at onboarding rather than checking for "do we already have a usable identity?" at process start.

The fix is Android-only: the start-destination decision needs to consult both `ServerProfileStore` and `KeyStoreIdentityManager` synchronously (or via a single-shot suspending check before the first composition) and route to either the sessions screen (when both present + cert not expired) or the QR scanner (when either is missing). Ships as `android-v0.3.2` (patch bump — no new dependency surface, no new APIs).

## Acceptance Criteria

1. **Cold-start with valid identity resumes to sessions.** A device that has previously completed enrollment (a `ServerProfile` is persisted AND the `ai-sandbox-client-cert` alias exists in AndroidKeyStore AND the cert is not expired) shows the sessions screen as its first visible screen after a cold launch. No flash of the QR scanner; no extra tap.

2. **Cold-start with no identity routes to onboarding.** A first-install device (no `ServerProfile`, no cert in AndroidKeyStore) shows the QR scanner as its first visible screen. Existing behavior.

3. **Cold-start with profile but missing cert routes to onboarding.** If the cert entry has been wiped (operator deleted via Settings, or AndroidKeyStore was reset out-of-band) but the profile remains, the start destination is the QR scanner and the stale profile is cleared. The user re-enrolls cleanly.

4. **Cold-start with expired cert routes to onboarding.** If the cert's `notAfter` is in the past, the start destination is the QR scanner. The expired cert + profile are wiped before navigation so the operator can scan a fresh QR without UI-state confusion.

5. **Warm-resume after background does not reset destination.** Backgrounding the app from the sessions screen and resuming returns to the sessions screen, not the QR scanner. This is the headline regression from the user's report.

6. **Re-scanning QR while a valid identity exists replaces it cleanly.** The existing "replace identity" flow (re-scan from Settings) is unaffected — UC-04 already wipes + re-imports atomically; AC1's "consult the persisted state" check doesn't accidentally block this flow.

7. **Decision is empirical, not heuristic.** The start-destination decision asks the actual `KeyStoreIdentityManager` whether the cert exists (no time-of-check / time-of-use race — the same call site that later uses the cert). It does NOT cache an in-memory boolean across process kills.

8. **Test coverage.** A test (Robolectric is acceptable if it works; pure JUnit 5 against the start-destination decider function if Robolectric is brittle in this project — see UC-14's lessons) covers each of the four state combinations: (profile, cert) ∈ {(absent, absent), (present, absent), (absent, present), (present, present)} and the "present + expired" edge.

9. **No drift on UC-09 / UC-10.** SPKI pinning + cert-mismatch-screen behaviors stay green; the start-destination check does NOT short-circuit those.

10. **CI green.** `:android:test`, `:android:lint`, `:android:assembleDebug`, `:android:bundleDebug`, `:android:assembleRelease`, `:android:bundleRelease`, `android-ci`, `android-release` all pass.

11. **Ship as `android-v0.3.2`.** Patch bump. Release notes: "Resume to sessions on cold-start when identity already imported."

## Original Description

(Reported by the user on 2026-05-21 after deploying `android-v0.3.1`.)

> "in the mobile phone, after you scan the code and access the list of sessions, if you exit the app and come back, it prompts you to scan a QR instead of sending you to the list of sessions"

## Potential Pitfalls & Open Questions

- **Where the navigation start-destination is decided.** Likely `MainActivity` or a `NavHost`-configuring composable. The analyst's first job is to find the current decision logic and document why it always picks onboarding today (most plausible: a hardcoded `startDestination = "scan"` in the NavHost, with the onboarding success path doing `navController.navigate("sessions")` — but the navigation history doesn't survive a process kill, so on next launch the graph re-enters at the hardcoded start).
- **Suspending state read on the UI thread.** `DataStore` is suspending. The decision either needs a `runBlocking` (heavy but fine in `Application.onCreate` or `Activity.onCreate` pre-setContent), a `LaunchedEffect` that flashes the wrong screen before correcting (bad UX — fails AC1 if visible), or a Splash composable that holds until the decision is made (best UX, modest code; aligns with Android 12+'s built-in `SplashScreen` API).
- **AndroidKeyStore probe cost.** `KeyStore.getInstance("AndroidKeyStore").containsAlias(...)` is cheap (no IO) but it does need `.load(null)`. Don't put this on a hot path; one-shot at process start is fine.
- **Expiry check vs. server-side revocation.** AC4 covers `notAfter` expiry. Server-side revocation (operator deletes the client from `/etc/ai-sandbox-server/clients/`) is NOT detectable from the phone without a server round-trip; the existing UC-09 / UC-10 SPKI-pinning flow handles the revoked-cert case at first-request time. Keep that out of UC-16's scope; document the boundary in the PR body.
- **Wipe-on-route order.** AC3 + AC4 both wipe-then-route. The wipe must succeed before the navigation transition (otherwise a back-press could land on a half-cleared state). Use a single suspending function that completes both writes before the NavHost composes.
- **Mode switch in scan flow.** When the user explicitly chooses "Re-enroll" from Settings (AC6), the start-destination decision should NOT fire — that path is post-startup and goes through the existing wipe + scan UI. Make sure the AC1/AC5 logic lives at app-start only, not as a NavGraph-wide rule.
