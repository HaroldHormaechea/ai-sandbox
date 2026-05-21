# Use Case 14: Fix UC-13 regression — sentinel passphrase for enrollment PKCS#12 (BouncyCastle 1.79 rejects empty char[])

## Summary

UC-13's release `android-v0.3.0` does not fix the Android enrollment-cert import path it claimed to fix. The Android client now bundles upstream BouncyCastle 1.79 (registered under `BC-ai-sandbox-client`) and routes the PKCS#12 unwrap explicitly through that provider — but BouncyCastle 1.79's `PBEPBKDF2$BasePBKDF2.engineGenerateSecret` hard-rejects an empty `char[]` password with `IllegalArgumentException("password empty")`. There is no system property or constructor argument that disables that check. The user-visible error on the phone shifts from UC-13's pre-fix `NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available` to UC-14's `Cannot import client cert: ... password empty` — empirically reproduced on potato-server + phone on 2026-05-21 after the v0.3.0 deploy.

The root-cause failure to catch this in UC-13's `:android:test` run was a SILENT TEST-SKIP regression: `KeyStoreIdentityManagerPkcs12ImportTest` uses `@RunWith(RobolectricTestRunner::class)` (JUnit 4 style) and the `:android` test classpath ships `junit-jupiter` + `useJUnitPlatform()` but NOT `junit-vintage-engine`. Without Vintage on the runtime classpath, JUnit-4 tests are silently discovered as zero tests and the Gradle task reports success on zero failures. CI on UC-13's PR therefore passed without ever running the regression-guard test that would have caught BC's empty-password rejection.

The fix is a coordinated server + Android change: both sides agree on a fixed non-empty sentinel `char[]` (`"ai-sandbox-enrollment"`) for the enrollment-PKCS#12 transport passphrase. The sentinel is NOT a secret — it lives in plaintext source on both sides; the bundle's confidentiality comes from the mTLS tunnel, not from PBES2. Ships as `server-v0.0.13` + `android-v0.3.1`; the in-flight UC-12 (WebFlux exception advice routing fix) is renumbered to `server-v0.0.14` so this hotfix can ship ahead of it.

## Acceptance Criteria

1. **Production symptom gone.** A fresh QR-scan + enrollment flow on the upgraded server and upgraded Android client produces a successful import. No `password empty` toast; no `import_failed` screen. The post-import success path runs (cert usable for subsequent mTLS requests).

2. **Server emits with the sentinel.** `EnrollmentCertMintService.packageInMemoryPkcs12` uses `ENROLLMENT_PKCS12_PASSPHRASE = "ai-sandbox-enrollment"` (declared as a `public static final String` constant on the same class) for both `setKeyEntry` and `store`. The constant's KDoc explains why empty does not work and that this is not a secret.

3. **Android consumes with the matching sentinel.** `KeyStoreIdentityManager.kt` adds a private `ENROLLMENT_PKCS12_PASSPHRASE = "ai-sandbox-enrollment".toCharArray()` constant and passes it to both `source.load(...)` and `source.getKey(...)`. The existing `EMPTY_PASSWORD` constant stays — it's used by the `AndroidKeyStore` `KeyManagerFactory.init` call and AndroidKeyStore has no transport passphrase concept.

4. **Out-of-band header carries the sentinel.** The `X-AI-Sandbox-P12-Passphrase` response header (set in `EnrollmentController.java`) carries the sentinel value, not the empty string. This is informational only; it lets the client sanity-check without hard-coding the convention.

5. **junit-vintage-engine added to `:android` test classpath.** `android/build.gradle.kts` adds `testRuntimeOnly(libs.junit.vintage.engine)` (and the catalog entry in `gradle/libs.versions.toml`). After this change, `./gradlew :android:test` actually executes the JUnit-4-style Robolectric tests that were previously silently skipped. The pre-fix regression-signature is captured in the test's KDoc and the test fails on UC-13's `main` with the BC empty-password error.

6. **UC-13 test fixture updated.** `KeyStoreIdentityManagerPkcs12ImportTest.transportPassphrase` becomes the sentinel `char[]`. After the production change, the test's primary phase (pure-JVM PBES2 unwrap via the BC-routed code path) passes.

7. **Server tests updated.** `EnrollmentCertMintServiceTest` round-trips with the sentinel; the renamed `sentinel_passphrase_is_required_to_open_the_bundle` test confirms a wrong passphrase still throws. `EnrollmentControllerTest`'s header assertion checks the sentinel. `EnrollmentPinAlgorithmTest`'s body-shape check loads with the sentinel.

8. **Server-only release tag.** Ships as `server-v0.0.13` (the tag UC-12 was originally targeting). UC-12's release tag is renumbered to `server-v0.0.14` in the UC-12 PR.

9. **Android release tag.** Ships as `android-v0.3.1` (patch bump over UC-13's `android-v0.3.0` — same JCE-provider surface, no new dependencies, no breaking API change).

10. **Documentation updated.** `docs/THREAT_MODEL.md` § "PKCS#12 transport-passphrase" renamed and rewritten to describe the sentinel rationale. `server/openapi.yaml` description of `POST /v1/enrollment` mentions the sentinel. `PROJECT_BRIEF.md`'s UC04 flow description updated.

11. **Test-first cascade.** The empirical capture is: run `./gradlew :android:test` against UC-13's `main` AFTER adding `junit-vintage-engine` but BEFORE the production sentinel change. The previously-silently-skipped `primary_loadPbes2Envelope_via_BC_succeeds_and_round_trips_key_and_cert` test runs and fails with `IllegalArgumentException("password empty")` — the precise regression signature. Production change + fixture-passphrase update flips it green.

12. **CI green.** `:server:test`, `:server:spotlessCheck`, `:android:test` (now with Vintage), `:android:lint`, `:android:assembleDebug`, `:android:bundleDebug`, `:android:assembleRelease`, `:android:bundleRelease`, `release-install-smoke`, `android-ci`, `android-release` all pass.

## Original Description

After deploying `android-v0.3.0` (UC-13's release), the Android app's import flow surfaced "Cannot import client cert: password empty". UC-13 changed the failure mode but not the outcome — the user can still not enroll.

## Clarifications

- **Why a sentinel and not server-side `null`?** JDK 21's `KeyStore.store(stream, null)` still emits a PKCS#12 with an encrypted key bag (different ciphertext but still PBES2-shaped). BouncyCastle still routes through `PBEPBKDF2$BasePBKDF2.engineGenerateSecret`, which still rejects the empty/null derivation. Empirically verified via JVM experiment 2026-05-21.
- **Why not drop BouncyCastle and use the alias trick?** The "register an OID alias from `1.2.840.113549.1.5.12` to `PBKDF2WithHmacSHA256`" idea fails because JCA's alias mechanism is per-provider: the named algorithm has to live on the SAME provider as the alias. A thin alias-only provider cannot borrow Conscrypt's named PBKDF2. Bundling a working `SecretKeyFactory` for the bare OID is therefore still required — and BouncyCastle is the smallest available option that ships one.
- **Why this UC ships ahead of UC-12.** UC-12 is mid-test-writing when this regression surfaces. UC-14 is a tiny coordinated change (no merge conflict with UC-12's `server/api/error/**` work); shipping it first unblocks production users. UC-12 inherits the renumbered `server-v0.0.14` tag.

## Potential Pitfalls & Open Questions

- **MAC check on the server-emitted bundle.** JDK 21 wraps the PKCS#12 with a MAC computed over the sentinel passphrase. BouncyCastle's MAC verification path uses a different (non-PBKDF2) code path that does NOT have the empty-password rejection — confirmed empirically with the sentinel-vs-sentinel test. AC1 + AC11 are the regression guard.
- **Pkcs12Writer (`aisandboxctl client mint --pem`) is OUT OF SCOPE.** That code path takes the passphrase as a parameter and is only used by operators on the host side, not by the Android enrollment flow. No change needed.
- **MtlsDispatchTest / MtlsDispatchOverH2Test / ServerCertHotReloadTest** use their own self-generated PKCS#12 client-cert fixtures (not the production emission) with `new char[0]`. They go through the JDK's `KeyStore`, not BouncyCastle, so empty is fine there. No change needed.
- **Vintage engine surfaces other previously-skipped tests.** Adding `junit-vintage-engine` runs every `@RunWith(...)` test in the `:android` module — including `AiSandboxApplicationBootstrapTest` and any other JUnit-4 tests. The Robolectric infrastructure should handle them; if any fail at compile or runtime, that is a UC-14 follow-up but does not block this hotfix's primary contract (the import works).
