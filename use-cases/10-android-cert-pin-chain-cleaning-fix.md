# Use Case 10: Fix Android cert-pinning chain-cleaning trap (and prevent future masked-symptom recurrences)

## Summary

UC09 (v0.0.10) correctly aligned the server and Android client on the SPKI cert-pin algorithm, but Android enrollment **still fails on every real device** because of a latent OkHttp 5.3.2 chain-cleaning bug: both `EnrollmentClient.kt:140` and `AiSandboxHttpClient.kt:89` return an empty `X509TrustManager.getAcceptedIssuers()`, which causes `BasicCertificateChainCleaner.clean()` to throw `SSLPeerUnverifiedException("Failed to find a trusted cert that signed …")`. That exception is silently swallowed by OkHttp's `Handshake.peerCertificates_delegate`, leaving the peer-cert list empty. `CertificatePinner.check()` then iterates an empty chain and unconditionally throws `SSLPeerUnverifiedException("Certificate pinning failure!")` — *regardless of whether the actual pin would have matched*. The `<bootstrap>` sentinel masked this failure through UC04, UC07, UC08, UC09; the server-side manual smoke gates passed because the curl-based clients dodge OkHttp entirely. UC09's `EnrollmentPinAlgorithmTest` passes only because it uses an `X509ExtendedTrustManager` that returns the server cert from `getAcceptedIssuers()` specifically to dodge the trap — a test-only workaround that does not reflect the production Android trust-manager configuration. UC10 ships three things on one feature branch / one PR: (a) replace `lenient-TrustManager + OkHttp CertificatePinner` in BOTH Android HTTP clients with a single `SpkiPinningTrustManager` that performs the SPKI check inside `checkServerTrusted`, eliminating the chain-cleaning path; (b) add a new Android-side integration test using `MockWebServer` that uses the same production trust-manager configuration the phone uses, so the chain-cleaning trap is regression-blocked going forward; (c) the previously-planned secondary preventers — server-side `--server-url` host vs. `server.crt` SAN validation at QR-mint time, and a catch-block split / screen UX that distinguishes pin mismatch, hostname mismatch, and other handshake errors with the raw exception message surfaced (expandable, copy-to-clipboard) for operator troubleshooting.

## Acceptance Criteria

### Primary fix — `SpkiPinningTrustManager` (production)

1. **`SpkiPinningTrustManager` exists.** A new file (suggested: `android/src/main/kotlin/com/aisandbox/android/net/SpkiPinningTrustManager.kt`) defines a class that takes a `ByteArray` constructor argument (the expected SPKI sha256), implements `X509TrustManager`, and in `checkServerTrusted` (non-null, non-empty chain assertion) computes `MessageDigest.getInstance("SHA-256").digest(chain[0].publicKey.encoded)` and compares to the expected hash via `MessageDigest.isEqual` (constant-time). On mismatch throws `CertificateException` with a structured message of the form `"SPKI pin mismatch: expected=<hex> observed=<hex>"`. `checkClientTrusted` is a no-op. `getAcceptedIssuers()` returns an empty array (now safe — chain cleaning is no longer the verification path).

2. **`EnrollmentClient.kt` migrated.** Removes `acceptAllTrustManager()` and `CertificatePinner` from the bootstrap client. Uses `SpkiPinningTrustManager(hexToBytes(payload.pinSha256Hex))` for both the `SSLContext.init` and `OkHttpClient.Builder.sslSocketFactory` trust-manager slots. The pin host extraction (`hostFromUrl`) and the pin base64 encoding are removed — no longer needed. `HostnameVerifier` left on default so SAN check still applies.

3. **`AiSandboxHttpClient.kt` migrated.** Same swap: `lenientTrustManager()` and `CertificatePinner` removed; replaced with `SpkiPinningTrustManager(hexToBytes(profile.pinSha256Hex))`. The hardware-backed `KeyManager` for the client cert (UC04 KeyStoreIdentityManager) is unchanged. `pinObservingInterceptor` is removed (now redundant — the TM handles the check).

4. **Catch-block split.** Both client classes' exception handling now distinguishes three failure modes by exception class, not by message-prefix matching:
   - `SSLHandshakeException` whose cause chain contains `CertificateException` with a `"SPKI pin mismatch"`-prefixed message → `NetworkEvent.PinMismatch(expectedPinHex, observedPinHex, rawMessage)`. `observedPinHex` is now the **actual** SPKI hash of the cert the server presented (extracted from the structured exception message), NOT `<bootstrap>`. `rawMessage` carries the full exception detail.
   - `SSLPeerUnverifiedException` (the hostname verifier still fires after the TM) → `NetworkEvent.HostnameMismatch(expectedHost, rawMessage)`. Reachable for real SAN mismatches.
   - Any other `SSLException` / `IOException` → `NetworkEvent.HandshakeError(rawMessage)`. New event type; new screen variant.

5. **`<bootstrap>` sentinel removed from production code.** With the TM-based check, the observed pin is always real. UC09 § AC6's preservation rationale is obsolete; the inline Javadoc updates to point at UC10 as the reason it's now gone. Grep returns zero matches in `android/src/main/**`; remaining references only in `use-cases/09-spki-cert-pin-algorithm.md` (historical) and this file.

### Secondary fix — server SAN-vs-URL validation at QR mint

6. **`ClientInviteCommand` validates `--server-url` host against `server.crt`'s SAN list.** Reads the cert, parses SAN entries into a normalized list (lowercased DNS, canonical IPv4 strings), parses the host portion of `--server-url`, refuses with exit 2 + stderr remediation message if the host is not present. The error message names the URL host, the cert's actual SAN entries, and the remediation command (`aisandboxctl pki init --force --san <tag>:<host>`). Validation runs whether the pin came from `autoDiscoverPin` or `--server-pin`. No escape-hatch flag. IPv6 literals (bracketed `[…]` syntax in the URL host) refused explicitly with `"--server-url with an IPv6 literal is not supported yet; pass a DNS name or IPv4 address"`.

7. **Server-side test-first cascade.** New `ClientInviteCommandTest` cases:
   - URL host not in SAN → exits 2 with the documented message (red on pre-fix, green post-fix).
   - URL host in SAN (`localhost`, `127.0.0.1`, configured `--san` entry) → exits 0 as today.
   - `--server-pin` override + URL host not in SAN → still refused.
   - IPv6 literal → exits 2 with the documented message.
   QA writes these first; failing-on-main is the "go" signal per UC09 § AC4 / UC07 § AC1 orchestration.

### Secondary fix — Android UI surface

8. **`ServerIdentityChangedScreen` extended with `cause: Mismatch` parameter.** Sealed Kotlin class:
   ```kotlin
   sealed interface Mismatch {
     data class Pin(val expectedHex: String, val observedHex: String, val rawMessage: String) : Mismatch
     data class Hostname(val expectedHost: String, val rawMessage: String) : Mismatch
     data class HandshakeError(val rawMessage: String) : Mismatch
   }
   ```
   The screen renders:
   - **Pin**: today's expected/observed hex block, but with `observedHex` showing the real hash.
   - **Hostname**: connect-host and cert SAN summary block.
   - **HandshakeError**: a generic "Connection refused TLS" block.
   - Shared **"Show technical details" expandable section** at the bottom of every variant: collapsed by default; tap to reveal the `rawMessage` in mono font; copy-to-clipboard button next to it. Implements the user-requested raw-exception surfacing for post-UC10 stabilisation troubleshooting.

   Same red-badge + "Scan new QR" / "Quit" chrome. New strings in `strings.xml`. Compose test verifies all three branches render their context block correctly and that the technical-details expander toggles + copy action works.

### Test integrity — close UC09's test gap

9. **New Android-side integration test exercises the production trust-manager configuration.** A new test under `android/src/test/kotlin/com/aisandbox/android/net/EnrollmentClientIntegrationTest.kt` using OkHttp's `MockWebServer`:
   - Generates a self-signed RSA-2048 server cert in-test (programmatically via Bouncy Castle, same generator the production server uses) with SAN `IP:127.0.0.1` so the verifier passes.
   - Reads the cert's SPKI sha256 dynamically and passes it as the QR pin to a real `EnrollmentClient`.
   - Configures `MockWebServer` to serve HTTP/2 over the generated cert and respond `201 application/octet-stream` with a fake PKCS#12 blob.
   - Calls `enrollmentClient.redeem()` and asserts `Outcome.Success` with the expected byte content.

   The test MUST FAIL against the pre-fix codebase (current `EnrollmentClient` with empty `getAcceptedIssuers()`) — proving the chain-cleaning bug exists — and PASS post-fix. QA writes it first; failing-on-main is the "go" signal for the developer's production change.

10. **UC09 test gap surfaced.** `EnrollmentPinAlgorithmTest`'s class-level Javadoc is amended with a UC10 cross-reference: *"This test validates the server's `autoDiscoverPin` SPKI algorithm; it does NOT validate the Android client's production trust-manager configuration, which is covered by UC10's `EnrollmentClientIntegrationTest`. See the `TRUST_ALL` field's Javadoc for the OkHttp 5.3.2 chain-cleaning trap this test sidesteps."* No code change to `EnrollmentPinAlgorithmTest` — its scope was always server-side; just under-documented.

### Cross-cutting

11. **OkHttp pinned at 5.3.2.** `libs.versions.toml` keeps the existing pin. If a future bump changes `BasicCertificateChainCleaner` behaviour (less likely now since we don't depend on it), AC9's test catches any regression.

12. **Backward compatibility.** Operators with pre-UC10 enrollment attempts have stored profiles that may already carry the right pin format (the pin hex was always SPKI on the v0.0.10 server side); the trust-manager migration is purely on the verification path. Existing post-enrollment client certs continue to work — the mTLS KeyManager wiring is unchanged. Release notes call out: *"v0.0.10's algorithm fix landed correctly, but Android enrollment did not actually work end-to-end on a real device until v0.0.11. If you sideloaded a UC04/UC07/UC08/UC09 APK and saw the `<bootstrap>` screen, that's the chain-cleaning bug fixed here."*

13. **Documentation audit.** `README.md`, `server/README.md`, `android/README.md`, `docs/` — every `client invite --server-url=…` example with a non-`localhost` host gains a one-line "host must be in `server.crt`'s SAN" note + pointer to `pki init --san`. PR body records audited-and-clean for files that needed no change.

14. **CI green, single PR, parallel slices.** Server (B1: AC6, AC7) and Android (B2: AC1-5, AC8, AC9) slices land on the same feature branch. Commits split per slice for review readability (e.g. server commits first, then Android). `:server:test`, `:server:spotlessCheck`, `:android:test`, `:android:lint`, `release-install-smoke`, `android-release` all pass.

15. **Manual smoke gate.** UC07 § AC11 / UC09 § AC8 expanded for v0.0.11: the Android smoke gate now requires a real phone successfully enrolling against a freshly-installed `.deb` server. The release runbook procedure says *"AC11 passes only when (a) the phone's UI confirms 'Enrolled', AND (b) the server's audit log shows a successful `client_enroll` entry for the enrolled name, AND (c) re-launching the app reaches the post-enrollment Sessions screen without the identity-changed dialog firing."* Captured inside this UC's commit body so it carries forward.

## Potential Pitfalls & Open Questions

- **Risk** — The chain-cleaning bug is OkHttp-version-specific. The TM-based fix avoids the cleaner entirely, so future OkHttp bumps don't reintroduce the bug. AC11 keeps the version pinned; AC9's regression test catches any bump-time regression.
- **Risk** — `MessageDigest.isEqual` is constant-time. `Arrays.equals` is **not** and would be a side-channel leak — AC1 is explicit about which API to use.
- **Risk** — The hardware-backed `KeyManager` flow in `AiSandboxHttpClient` (UC04 KeyStoreIdentityManager) is untouched by UC10 in scope, but the TM swap requires re-wiring the `SSLContext.init` call. The developer must verify the StrongBox / TEE-backed key still gets attached after the swap (AC9's integration test should cover this if extended to a follow-on test against an mTLS endpoint, OR added as a regression test inside the existing `AiSandboxHttpClientTest`).
- **Risk** — Parallel slices on one PR enlarge the review surface. Mitigation: keep commits cleanly split per slice; reviewer can read each slice independently.
- **Risk** — `profile-java-call-graph-tool` is active. The dev-team should use it to locate callers of `acceptAllTrustManager`, `lenientTrustManager`, `pinObservingInterceptor`, and the `<bootstrap>` literal before deleting any of them — make sure no production code path expects the old shapes.
- **Edge case** — Hostname verifier still uses Android's default `OkHostnameVerifier`. With the cert's IP SAN correctly encoded as RFC 5280 type-7 (verified empirically on `potato-server` during this investigation: `87 04 C0 A8 00 1C` octet string), the verifier accepts `IP:192.168.0.28`. No additional change needed.
- **Edge case** — `MockWebServer`'s HTTP/2 + self-signed cert handling: the test must use `MockWebServer.useHttps(socketFactory, false)` with a `HandshakeCertificates`-built socket factory whose `HeldCertificate` is the in-test-generated cert. Standard OkHttp testing pattern; documented in OkHttp's test suite.
- **Ambiguity** — When extracting the observed SPKI hex from the `CertificateException` thrown by `SpkiPinningTrustManager`, the parser must be robust. Decision: TM emits its message in the exact form `"SPKI pin mismatch: expected=<hex> observed=<hex>"`, and the catch-block uses a fixed regex `Regex("expected=([0-9a-f]{64}) observed=([0-9a-f]{64})")`. Both producer and consumer are in-repo, no third-party coupling; a unit test pins the format.
- **Assumption** — `SSLHandshakeException` (wrapping the TM-thrown `CertificateException`) is reliably distinguishable from `SSLPeerUnverifiedException` (from hostname verifier) by class. OkHttp source: hostname verifier fires AFTER trust-manager validation, and the two exception classes don't share a leaf ancestor below `SSLException`. The catch order in EnrollmentClient/AiSandboxHttpClient is `SSLPeerUnverifiedException` first, `SSLHandshakeException` second, generic `IOException` last.
- **Assumption** — Removing the `<bootstrap>` sentinel doesn't break any external consumer. The sentinel is internal to the Android app; the server never sees or logs it. Safe to remove from production code; historical references in use-case files stay.
- **Project-quality** — UC09's `EnrollmentPinAlgorithmTest` is now annotated (AC10) but not refactored. Its scope was always server-side; UC10 doesn't pull at it. A future use case might add a higher-level "real Android client against real server" smoke that subsumes both, but that's out of scope for v0.0.11.

## Original Description

UC09 (v0.0.10) fixed the SPKI cert-pin algorithm — server now emits sha256(SPKI) in the QR and Android's OkHttp CertificatePinner verifies against the same. The fix is correct and verified in source (ClientInviteCommand.autoDiscoverPin calls PemUtils.spkiFingerprintHex).

However, the v0.0.10 fix did NOT resolve the operator-visible "Server identity changed" failure on first enrollment, because the catch block in EnrollmentClient.kt:75 lumps every SSLPeerUnverifiedException into the same `<bootstrap>` pin-mismatch surfacing. A separate failure mode — hostname/SAN mismatch — throws the exact same exception class for a completely different reason: OkHttp's HostnameVerifier rejects when the URL in the QR's `u` field references a host that is not in server.crt's SubjectAlternativeName list.

Empirical case (real operator, 2026-05-20): `pki init` with default settings produced SAN `DNS:potato-server, DNS:localhost, IP:127.0.0.1`. `client invite --server-url=https://192.168.0.28:12410` minted a QR pointing at `192.168.0.28`, which is not in the cert's SAN. The phone hit `192.168.0.28`, HostnameVerifier threw SSLPeerUnverifiedException, and the UI showed "Server identity changed, expected <fae…> and observed <bootstrap>" — the same string operators saw pre-v0.0.10 for the algorithm bug.

[Update during investigation, same date]: After re-issuing the cert with `IP:192.168.0.28` in the SAN list and confirming the new SPKI hash (`37b34076…`) was correctly emitted in a freshly-minted QR, the phone STILL showed the `<bootstrap>` screen. curl tests from the host confirmed TLS, SAN, and SPKI pin all work correctly end-to-end (HTTP/2 handshake completes, hostname verifies, server returns a real HTTP response). The phone's browser sees the correct cert fingerprint `D0:12:AE:F3:…:E0:37`, confirming no network interposition. Root cause discovered by reading `EnrollmentPinAlgorithmTest.java`'s `TRUST_ALL` field Javadoc, which explicitly documents the OkHttp 5.3.2 chain-cleaning trap: empty `getAcceptedIssuers()` → `BasicCertificateChainCleaner` throws → `Handshake.peerCertificates` silently swallows → `CertificatePinner.check` iterates empty chain → throws "Certificate pinning failure!" regardless of algorithm. The production `EnrollmentClient.kt:140` and `AiSandboxHttpClient.kt:89` both fall into this trap. The integration test sidesteps it by returning the server cert from `getAcceptedIssuers()`, which is why the test passes while production fails.

UC10 primary scope: replace `lenient-TrustManager + CertificatePinner` with a `SpkiPinningTrustManager` that performs the SPKI check inside `checkServerTrusted`, eliminating the chain-cleaning path entirely. Secondary scope: server-side `--server-url` host vs. SAN validation at QR-mint time (prevents minting a QR for an unclaimed host); Android UI surface that distinguishes pin mismatch, hostname mismatch, and other handshake errors with the raw exception text surfaced for troubleshooting; new Android-side integration test that exercises the production trust-manager configuration.

## Clarifications

- Q: UI surface for hostname-mismatch failures — dedicated screen or repurpose `ServerIdentityChangedScreen` with a `cause` parameter?  
  A: Extend the existing screen with a `cause: Mismatch` parameter. Same chrome, three context blocks.
- Q: Android pin-vs-hostname discriminator strategy (OkHttp message-prefix vs. structural inspection vs. both)?  
  A: Originally chosen as message-prefix; superseded by the `SpkiPinningTrustManager` design which makes the failure modes distinguishable by exception class (`SSLHandshakeException` vs. `SSLPeerUnverifiedException`).
- Q: Server-side `--allow-san-mismatch` escape hatch?  
  A: No — strict refusal only.
- Q: Slice order — sequential, parallel, or split into two use cases?  
  A: Parallel — both server (B1) and Android (B2) slices on the same feature branch / PR.
- Q: How to proceed given the chain-cleaning finding?  
  A: Pivot UC10 to address the chain-cleaning bug as primary scope and run `/develop`.
- Q: UC09 test gap?  
  A: Call out in UC10's AC10 (Javadoc cross-reference on `EnrollmentPinAlgorithmTest`) + add a real Android-side integration test in UC10's scope (AC9).
- Q: Show raw exception message on the screen?  
  A: Yes — expandable "Show technical details" section with copy-to-clipboard, applied to all three `Mismatch` variants. Implements the user-requested troubleshooting aid for the post-UC10 stabilisation period.
- Q: Android-side integration test framework?  
  A: MockWebServer (OkHttp's official mock server; standard testing pattern; runs as a JUnit unit test).
