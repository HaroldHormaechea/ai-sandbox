# Use Case 61: Duplicate session row on spawn (optimistic-`n` vs server-`n`) + non-QR TLS failures misrouted to the "re-scan a fresh invite QR" identity screen

## Summary
Two related Android-client defects on the "create a new server/session" flow, plus a scoping cleanup of the invalid-QR error surface.

1. **Duplicate row on creation.** Creating a new session (the app is single-tenant — one *server profile*, many numbered *sessions*; "a new server" = spawning a new session) briefly shows the **same session twice with two different numbers** until the list stabilizes on one row. `SessionsCoordinator.spawn()` inserts an **optimistic placeholder keyed by a guessed `n`** (`optimisticN = max(n)+1`) but then **discards the authoritative `SessionSummary` the server returns** and merely calls `refresh()`. Meanwhile the UC-32 live push feed merges incrementally **by `n`** (`applyDelta` does `byN[it.n] = it`), so when the server-assigned `n` ≠ the client's `max+1` guess (recycled/gapped slots, multi-device, any non-`max+1` numbering), the real row is **added alongside** the optimistic guess instead of replacing it. The list renders one row per distinct `n` (`LazyColumn(key = { it.n })`), so both appear until a full snapshot/`refresh()` replace lands.

2. **Spurious "invalid QR" during create/refresh.** During that same flow, a generic, non-identity TLS hiccup (`HandshakeError`) on a routine REST call (`list`/`spawn`/`refresh`) force-routes the user to the full-screen **`ServerIdentityChangedScreen`**, whose copy tells them to *"re-scan a fresh invite QR"* — even though the QR was valid and the certificate pin is unchanged. UC-52/UC-54 already split plain connectivity (`ServerUnreachable`) out of the identity path, but the **generic `HandshakeError` bucket is still bus-routed for every authenticated REST call**, so any SSL-layer fault on spawn/list/refresh still lands on the destructive re-enroll screen.

3. **Over-broad invalid-QR error paths.** The "invite/QR is invalid" / "re-scan a fresh invite QR" wording is reachable from code paths that are **not** QR processing (the REST interceptor's `HandshakeError`, and the enrollment-redeem network failure), not only from genuine QR-payload parsing. The error must be **scoped to genuine QR-payload scan/parse failures**; non-QR transport faults get their own non-destructive, retryable surface, and "re-scan a fresh invite QR" is reserved for verified identity compromise (`PinMismatch`/`HostnameMismatch`) and explicit cert-revoke.

Root-cause references (all under `android/src/main/kotlin/com/aisandbox/android/`):
- Optimistic guess + discarded server row: `ui/screens/SessionsCoordinator.kt:203,213,231-238`; authoritative return in `net/SessionsApi.kt:68-77`.
- Merge-by-`n` adds a second row: `SessionsCoordinator.kt:158-171` (`applyDelta`/`applySnapshot`); UI keying `ui/screens/SessionsScreen.kt:330`; sort `SessionsViewModel.kt:160-161`. Existing test documenting the additive behavior: `SessionsCoordinatorEventsTest.kt:76-80` (`delta_upsert_adds_a_new_row`).
- Optimistic rollback predicate to preserve: `SessionsCoordinator.kt:224,244,261`.
- Non-QR `HandshakeError` → identity screen: `net/AiSandboxHttpClient.kt:103-124` → `net/TlsFailureTranslation.kt:136-168` → `ui/AiSandboxApp.kt:88-97` → `ui/screens/ServerIdentityChangedScreen.kt:85-86` → `res/values/strings.xml:142,148`.
- Enrollment-redeem network failure path: `net/EnrollmentClient.kt:90-116,165-174`.
- Genuine QR-parse failure (the only legitimate "invalid invite" origin): `ui/screens/OnboardingViewModel.kt:64-72` → `net/QrPayload.kt:41-61`.
- Legitimate (unchanged) re-scan triggers: `PinMismatch`/`HostnameMismatch` routing, and `ui/screens/CertRevokedScreen.kt:31,62`.

## Acceptance Criteria
1. After a successful spawn, the sessions list converges to **exactly one** row for the new session with no transient duplicate, **even when the server-assigned `n` differs from the client's optimistic `max(n)+1` guess** (assert a single entry for the new session across the spawn-Success → push-delta → `refresh` sequence, in any arrival order).
2. The authoritative `SessionSummary` returned by `SessionsApi.spawn()` is **used** to reconcile/replace the optimistic placeholder (it is no longer discarded); the placeholder is keyed/correlated so a server-sourced row for the same spawn replaces it rather than coexisting.
3. A UC-32 `applyDelta`/`applySnapshot` carrying the server's real row for a just-spawned session **removes/replaces** the optimistic placeholder instead of adding a second row (regression test extending the `delta_upsert_adds_a_new_row` scenario to the optimistic case).
4. The optimistic placeholder is still rolled back on **every** spawn failure path (no profile, `HttpFailure`, transport throw) with no orphaned phantom row — the current behavior at `SessionsCoordinator.kt:224,244,261` is preserved under the new keying.
5. A non-pin / non-SAN TLS `HandshakeError` raised during a **session list / spawn / refresh** REST call does **not** navigate to `ServerIdentityChangedScreen` / show "re-scan a fresh invite QR"; it surfaces as a retryable, non-destructive state (consistent with the UC-54 connectivity surface). Assert no `Routes.ServerIdentityChanged` navigation for a generic `HandshakeError` from the REST interceptor.
6. Genuine identity failures STILL route to `ServerIdentityChangedScreen` with no security regression: SPKI **`PinMismatch`**, hostname/SAN **`HostnameMismatch`**, and the `IOException`-wrapping-`SSLException` "identity wins" case (`hasTlsCause`) are unchanged.
7. The "QR payload is not a valid ai-sandbox invite" / "re-scan a fresh invite QR" copy is reachable **only** from a genuine QR-payload parse failure (`OnboardingViewModel.onQrPayload`/`QrPayload.parse`) or a verified identity/cert-revoke event — **never** from a generic transport/handshake failure on the sessions flow. Assert by error-type → surface mapping.
8. The `CertRevoked` (WS 4401) path continues to route to `CertRevokedScreen` unchanged.
9. Unit tests pin both the row-reconciliation (AC1–AC4) and the error-type → surface mapping (AC5–AC8); existing UC-32/UC-52/UC-54 tests still pass.

## Potential Pitfalls & Open Questions
- **Don't dedup by the wrong key.** Labels are blank/duplicable (default `""`, `SessionsApi.kt:151`) and the guessed `n` is precisely what's wrong — reconcile via the server-returned `SessionSummary` or a stable client-side correlation id, not `label` or guessed `n`.
- **Keep `applyDelta`/`applySnapshot` pure** and never let them touch `loading` (UC-32 contract); any provisional-row eviction must live in the merge logic without breaking that purity.
- **Race between spawn-Success `refresh()` and the push feed** — the fix must converge regardless of which lands first, and must not reintroduce a stuck FAB/spinner (`spawning` cleared in `finally`).
- **Single-surface invariant.** `surfaceTransportThrow` (`SessionsCoordinator.kt:391-405`) assumes the interceptor already routed identity events and enforces `unreachable` XOR `lastError`; reclassifying `HandshakeError` must update the interceptor routing **and** the call-site handling together to avoid double-surfacing or a silently-dropped error.
- **Security — never weaken identity routing.** Real `PinMismatch`/`HostnameMismatch` and the `hasTlsCause` "when in doubt, identity wins" rule (`TlsFailureTranslation.kt:160-168`) must stay. Only the generic `HandshakeError`-from-REST bucket is narrowed.
- **Copy split.** Give non-QR TLS/transport failures their own copy ("couldn't reach the server securely / retry") that does **not** instruct re-scanning; keep the genuine-invite-invalid copy on the QR-parse path only.
- **Check the stream paths too** (`StreamClient`, `ReconnectController`) so they don't independently emit a `HandshakeError` that re-introduces the misroute.
- **Relationship to UC-52/UC-54.** UC-52 split connectivity from TLS; UC-54 replaced the unreachable banner with a tri-state dot. This UC closes the remaining gap: the generic `HandshakeError` bucket on the spawn/refresh path still reaches the identity screen, and the spawn flow still double-renders. Reuse the UC-54 connectivity surface rather than adding a parallel one.

## Original Description
Bugfix (reported against a real backend + emulator): when creating a new server in the Android app it shows twice with different numbers until it stabilizes, and in that scenario it sometimes shows the "invalid QR" error even though the QR was valid. Also scope the paths that show that error so it only appears during genuine QR processing.

## Clarifications
- Q: What is the "number" that doubles? A: The session index `n` — the client's optimistic `max(n)+1` guess vs. the server's actually-assigned session number; the list keys rows by `n`, so two `n`s = two rows until a full sync.
- Q: Why does the duplicate appear? A: `spawn()` inserts an optimistic row by guessed `n`, discards the server's authoritative `SessionSummary`, and the UC-32 push feed merges by `n` — a real `n` ≠ guess is added as a second row instead of replacing the placeholder.
- Q: Why does "invalid QR" show during create? A: A generic, non-identity `HandshakeError` on a routine REST call is bus-routed to `ServerIdentityChangedScreen`, whose copy says "re-scan a fresh invite QR" — a non-QR origin surfacing the QR error.
- Q: What should change about the error paths? A: Scope the invalid-invite/"re-scan QR" copy to genuine QR-payload parse failures (and reserve the identity screen for verified `PinMismatch`/`HostnameMismatch`/cert-revoke); generic transport/handshake faults get a retryable non-destructive surface.
- Q: Any security constraint? A: Yes — genuine pin/hostname mismatch and the `hasTlsCause` "identity wins" rule must keep routing to the identity screen; only the generic `HandshakeError`-from-REST bucket is narrowed.
