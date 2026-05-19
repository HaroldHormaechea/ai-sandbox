# Use Case 09: SPKI cert-pin algorithm reconciliation

## Summary

The server computes the QR's `pin` field via `PemUtils.fingerprintHex(cert) = sha256(cert.getEncoded())` (full DER cert hash), but the Android client passes that value to OkHttp's `CertificatePinner`, which by industry convention (HPKP / RFC 7469 / OkHttp default) verifies against `sha256(cert.publicKey.encoded)` — the SPKI hash. Empirically verified on a v0.0.9 server: full-DER hash `29cea7c1…` and SPKI hash `76215955…` never match, so every enrollment fails with `SSLPeerUnverifiedException` on the first POST `/v1/enrollment`. The Android client's pin-mismatch dialog hardcodes `observedPinHex = "<bootstrap>"` for the enrollment-POST path (`EnrollmentClient.kt:82`), which masked the algorithm bug through UC-04, UC-07, and UC-08 manual smoke gates. v0.0.10 ships: a new `PemUtils.spkiFingerprintHex(cert)` method, `ClientInviteCommand.autoDiscoverPin` switched to use it, Javadoc updated on both server and Android sides, a unit test pinning the SPKI algorithm against the canonical openssl invocation, and a Spring Boot integration test wiring a real OkHttp `CertificatePinner` from a freshly-issued QR pin and performing an end-to-end enrollment POST (test-first per UC-07 AC1 cascade). The client-cert allowlist code (`ClientAllowlistService`, `client list`, audit logs) keeps `fingerprintHex` (full DER) — that's a different contract with a different threat model.

## Acceptance Criteria

1. `PemUtils.spkiFingerprintHex(X509Certificate)` exists and returns `sha256(cert.getPublicKey().getEncoded())` as a lowercase hex string. Unit test asserts the output matches the canonical openssl invocation (`openssl x509 -in <pem> -noout -pubkey | openssl pkey -pubin -outform DER | sha256sum`) against a fixture cert under `server/src/test/resources/`.

2. `ClientInviteCommand.autoDiscoverPin` calls `PemUtils.spkiFingerprintHex(cert)` (NOT `fingerprintHex`) to compute the QR `pin` field when `--server-pin` is not supplied. `--server-pin <hex>` operator-override semantics are unchanged.

3. Javadoc updates:
   - Server `ClientInviteCommand` class-level docstring + `@Option(names = "--server-pin")` description: "SHA-256 of the server cert's SubjectPublicKeyInfo (SPKI), lowercase hex".
   - Server `PemUtils.fingerprintHex` Javadoc: explicit clarifying note "full DER-encoded certificate hash; used for client-cert allowlist fingerprints. For the server pin in the QR see `spkiFingerprintHex`."
   - Server `PemUtils.spkiFingerprintHex` Javadoc: "SPKI hash; used for the server pin in the QR + Android OkHttp `CertificatePinner`. Matches HPKP / RFC 7469."
   - Android `ServerProfile.pinSha256Hex` Javadoc (`android/src/main/kotlin/com/aisandbox/android/net/ServerProfile.kt:10`): replace "SHA-256 of the server cert's DER" with "SHA-256 of the server cert's SubjectPublicKeyInfo (SPKI)".
   - Android `QrPayload.pinSha256Hex` Javadoc (`android/src/main/kotlin/com/aisandbox/android/net/QrPayload.kt:25`): same wording fix.

4. **Test-first cascade.** A new Spring Boot integration test (suggested: `server/src/test/java/com/aisandbox/server/enrollment/EnrollmentPinAlgorithmTest.java`) wires a real OkHttp client with `CertificatePinner` configured from a freshly-issued invite's QR `pin` field, performs POST `/v1/enrollment` with the matching token, asserts the response status is 201 AND the returned `.p12` parses. QA writes this test FIRST and runs it against the pre-fix codebase — it must fail with `SSLPeerUnverifiedException` (full-DER hash doesn't match OkHttp's SPKI verification). After the developer's production fix lands, the same test passes. Echoes UC-07 § AC1 strict ordering: QA captures the pre-fix failure trace, signals "go", developer lands the fix, QA re-runs to green.

5. `aisandboxctl client list`, `client revoke`, `client mint` — code paths for CLIENT cert fingerprinting are unchanged. Existing client-allowlist files written under previous versions continue to parse identically. A regression test on `ClientListCommand` (or `ClientAllowlistService`) confirms full-DER fingerprints are still produced and consumed correctly.

6. Android `EnrollmentClient.kt`'s hardcoded `observedPinHex = "<bootstrap>"` sentinel (line 82) gets an inline Javadoc note explaining why it exists — there's no observed pin to extract before the TLS handshake completes on the enrollment POST, so the sentinel substitutes. Not blocking the fix; just documents the masked-symptom story so the next maintainer can understand it.

7. `docs/THREAT_MODEL.md` audited: any reference to the pin algorithm updated to reflect SPKI for the server pin. Client-cert allowlist hashing references remain full-DER. If no references exist, the audit is recorded as audited-and-clean in the implementation commit body.

8. UC-07 § AC11 expanded for v0.0.10 and going forward: the manual Android smoke gate before tagging explicitly covers the enrollment-POST step. The release-runbook procedure says "AC11 passes only when the phone's UI confirms 'Enrolled' AND the server's audit log shows a successful `client_enroll` entry for the enrolled name." Captured as text inside this UC's commit body so it carries forward.

9. README operator-visible docs that mention `pin` get the SPKI clarification. Audit candidates: top-level `README.md`'s `## Remote management → ### Install` block (the symlink + Install steps we just touched in UC-08), `server/README.md` if it mentions the pin format. Audit during implementation; commit body records audited-and-clean for anything that didn't need a change.

10. **Backward compatibility**: pre-v0.0.10 enrollments never succeeded (the algorithm was always wrong), so no operator state needs migration. PR body + release notes call this out explicitly: "v0.0.10 is the first release where Android enrollment actually works end-to-end. If you tested against earlier versions and the phone showed a 'pin mismatch' error, that's the bug fixed here." No `pki init` re-run required — the server cert itself is unchanged; only the way the pin is computed from it.

11. Full `:server:test` green, `:server:spotlessCheck` clean, CI green on build + integration + both `release-install-smoke` phases. Release workflow attaches v0.0.10 `.zip` and `.deb`.

12. **CI scope** (resolved during clarifications): `release-install-smoke` continues to use curl for the bulk smoke. The new Spring Boot integration test (AC4) is sufficient algorithm-level coverage; CI does not gain a separate OkHttp-pinned smoke step in v0.0.10. A future ticket can revisit if a packaging-tier regression slips past the JVM-integration test.

13. **Android stale-pin UX** (resolved during clarifications): no detection/prompt logic added in v0.0.10. Stored pins on phones that "enrolled" against pre-v0.0.10 servers are useless but harmless; the documented post-upgrade step is "clear the app data or delete the stale server entry, then scan a fresh QR." Captured in the v0.0.10 release notes.

## Potential Pitfalls & Open Questions

- **Assumption** — Java's `X509Certificate.getPublicKey().getEncoded()` returns SPKI-DER (the X.509 `SubjectPublicKeyInfo` structure). Per `java.security.spec.X509EncodedKeySpec`'s contract this is what the JDK produces for RSA/EC public keys. The AC1 unit test against the canonical openssl invocation empirically validates this on the test fixture — if a future JDK / BC update changes the encoding, the test breaks loudly.

- **Risk** — Test-first cascade ordering (AC4) requires committing a test that fails on `main`. The orchestrator must spawn QA's test-writing slice BEFORE the developer's production change, exactly the way UC-07's AC1 was orchestrated. QA's pre-fix failure trace becomes the "go" signal for the developer.

- **Risk** — Profile conformance: `profile-java-server-architecture` is not impacted; changes are confined to `pki/` (utility code) and `cli/` (UC06 § AC25 documented exception). No Controller/Facade/Service/Repository chain involved.

- **Edge case** — `--server-pin <hex>` overrides documented in CI scripts, example invocations, or operator docs. If any explicit pin value appears anywhere outside the use-case files, it would have been a full-DER hash and now needs to be recomputed via the SPKI algorithm. Audit during implementation with `grep -rn "server-pin\|pin=[0-9a-f]" --include='*.md' --include='*.yaml' --include='*.yml' --include='*.sh'`.

## Original Description

The QR's `pin` field is computed by the server as `sha256(cert.getEncoded())` (full DER cert hash via `PemUtils.fingerprintHex`), but the Android client hands it to OkHttp's `CertificatePinner` which by industry convention verifies against `sha256(publicKey.encoded)` (the SPKI hash, matching RFC 7469 / HPKP / OkHttp default). The two values are never equal for any cert — the user empirically verified on a real v0.0.9 server: full DER hash = `29cea7c1…`, SPKI hash = `76215955…`. Every enrollment fails with `SSLPeerUnverifiedException` on the very first POST /v1/enrollment request. The Android client's pin-mismatch dialog hardcodes `observedPinHex = "<bootstrap>"` for the enrollment-POST code path (EnrollmentClient.kt:82), which masked the symptom — operators couldn't tell whether the cert was wrong or the algorithm was wrong, so the bug shipped through UC-04, UC-07, and UC-08 manual smoke gates without being identified. Fix in v0.0.10: switch the server's QR pin computation to SPKI, leaving the client-cert allowlist code (which legitimately needs full-DER-cert hashing per the threat model) untouched.

Scope:
- Add `PemUtils.spkiFingerprintHex(cert)` that returns `sha256(cert.publicKey.encoded)` in lowercase hex.
- `ClientInviteCommand.autoDiscoverPin` calls `spkiFingerprintHex` (not `fingerprintHex`) for the QR `pin` field. `--server-pin <hex>` operator override still works the same way.
- Update Javadoc on `ClientInviteCommand`'s `--server-pin` description, the QR payload contract, and `ServerProfile.pinSha256Hex` to say "SHA-256 of the server cert's SubjectPublicKeyInfo (SPKI), lowercase hex" instead of "SHA-256 of the server cert's DER".
- Add a unit test in `PemUtilsTest` (or sibling) asserting `spkiFingerprintHex` matches the canonical openssl command (`openssl x509 -in <pem> -noout -pubkey | openssl pkey -pubin -outform DER | sha256sum`) against a known fixture cert.
- Add a Spring Boot integration test that wires a real OkHttp client with `CertificatePinner` configured from a freshly-issued QR pin, then performs `POST /v1/enrollment` end-to-end — the failure mode the existing unit tests miss is exactly that the pin contract is only verified against mocks. The test must FAIL on pre-fix main (with full-DER hash) and PASS post-fix (with SPKI hash). Test-first cascade per the v0.0.6 lesson and UC-07 § AC1 pattern.
- `aisandboxctl client list`, `client revoke`, `client mint` — keep `fingerprintHex` (full-DER) for CLIENT cert fingerprints. The client allowlist semantics are a different contract; this UC only touches the SERVER pin in the QR.
- Update `docs/THREAT_MODEL.md` if it references the pin algorithm; otherwise leave alone.
- README — surface the change in operator-visible docs if anything mentions the pin format; minor.
- AC11 (UC-07 manual smoke gate) — expand the contract to explicitly cover the enrollment-POST step against a real OkHttp client, not just post-enrollment session operations. The bug got past AC11 because the manual smoke only verified end-to-end calls AFTER enrollment was somehow done. Capture as an AC; the test from earlier in this UC implements the automated version.

Constraints:
- Backward compatibility: pre-v0.0.10 enrollments are already broken (the algorithm was never working), so there's no operator state to preserve. v0.0.10's first enrollment establishes the SPKI pin and works going forward.
- The client-cert allowlist code (`ClientAllowlistService`, audit logs, `client_add` audit events, fingerprint columns in `client list` output) MUST continue to use `fingerprintHex` (full DER). Mixing pin algorithms there would break the existing allowlist file format.
- The CI release-install-smoke H2/H1.1 round-trips don't currently exercise OkHttp pinning (they use curl). Add a follow-up note OR a new CI step that does the OkHttp-pinned POST end-to-end. Yes/no in scope to be decided during clarifications.
- Out of scope: changing the `pin` field's wire format (still hex), introducing pin-rotation, supporting multiple pins per server, or changing the QR payload schema.

## Clarifications

- Q: Should v0.0.10 add a separate CI `release-install-smoke` step that performs a real OkHttp-pinned POST against the just-installed packaged artifact?
  A: Skip — integration test covers it. The AC4 Spring Boot integration test wires a real OkHttp `CertificatePinner` inside the same JVM as the server under test; that's sufficient algorithm coverage. CI's `release-install-smoke` continues to use curl for the bulk smoke. Revisit if a packaging-tier regression slips past the JVM-integration test.

- Q: What to do about Android-side stale pin entries from pre-v0.0.10 enrollment attempts?
  A: Leave as-is. User clears app data or deletes the stale entry from the server list (existing UI flow). Documented as a one-time post-upgrade step in the v0.0.10 release notes. No code change on the Android side.
