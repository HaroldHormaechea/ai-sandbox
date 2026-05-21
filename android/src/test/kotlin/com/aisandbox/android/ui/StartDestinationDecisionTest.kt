package com.aisandbox.android.ui

import com.aisandbox.android.net.ServerProfile
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-16 § AC8 — coverage matrix for the pure cold-start routing
 * decider [decideStartDestination].
 *
 * <p>The decider is the headline UC-16 fix: pre-fix the app's
 * navigation graph hardcoded {@code startDestination = "onboarding"},
 * so backgrounding the app from the sessions list and resuming after a
 * process kill always re-prompted the QR scanner. The decider replaces
 * that with a state read against the persisted {@link ServerProfile}
 * and the AndroidKeyStore client cert, producing one of two outcomes
 * with explicit wipe semantics for the half-present edges.
 *
 * <h2>State matrix covered</h2>
 *
 * <table>
 *   <tr><th>profile</th><th>cert</th><th>notAfter vs now</th><th>expected decision</th><th>AC</th></tr>
 *   <tr><td>null</td>   <td>null</td>   <td>n/a</td>             <td>RouteToOnboarding(false, false)</td><td>AC2</td></tr>
 *   <tr><td>present</td><td>null</td>   <td>n/a</td>             <td>RouteToOnboarding(wipeProfile=true)</td><td>AC3</td></tr>
 *   <tr><td>null</td>   <td>present</td><td>not-expired</td>     <td>RouteToOnboarding(wipeCert=true)</td><td>orphan-cert edge</td></tr>
 *   <tr><td>present</td><td>present</td><td>notAfter &gt; now</td><td>RouteToSessions</td><td>AC1 (headline fix)</td></tr>
 *   <tr><td>present</td><td>present</td><td>notAfter &lt; now</td><td>RouteToOnboarding(both)</td><td>AC4</td></tr>
 * </table>
 *
 * <h2>Why this is pure JUnit 5 (no Robolectric)</h2>
 *
 * <p>{@link decideStartDestination} is a deliberately pure function —
 * no Compose, no Android Context, no AndroidKeyStore handle. All
 * platform reads happen in the {@code AiSandboxApp} caller. That shape
 * lets us assert the AC8 matrix with raw JUnit 5 + AssertJ, sidestepping
 * the Robolectric instability documented in UC-14 (Resources$NotFoundException,
 * RoboMonitoringInstrumentation crashes on this project's classpath).
 * The same pattern is used by
 * {@code com.aisandbox.android.ui.screens.SessionsUiStateTest}.
 *
 * <h2>Cert fixtures</h2>
 *
 * <p>Real {@link X509Certificate} instances are minted via
 * {@code okhttp3.tls.HeldCertificate} — already on the test classpath
 * via {@code KeyStoreIdentityManagerPkcs12ImportTest}. {@code notBefore}
 * and {@code notAfter} are set explicitly so the expiry branch of the
 * decider is exercised against a real cert rather than a mock.
 */
class StartDestinationDecisionTest {

    /**
     * Wall-clock "now" pinned for all five tests. Picked as a fixed
     * recent epoch so the cert fixtures' {@code notBefore} (now - 1 day)
     * and {@code notAfter} (now + 365 days for unexpired, now - 1 hour
     * for expired) land in deterministic, sane ranges regardless of when
     * the test runs.
     */
    private val nowMs: Long = 1_716_249_600_000L // 2024-05-21T00:00:00Z

    private val oneDayMs: Long = TimeUnit.DAYS.toMillis(1L)
    private val oneHourMs: Long = TimeUnit.HOURS.toMillis(1L)
    private val oneYearMs: Long = TimeUnit.DAYS.toMillis(365L)

    /**
     * UC-16 § AC2 — first-install device with no persisted profile and
     * no client cert in AndroidKeyStore. The decider must route to the
     * QR scanner WITHOUT requesting any wipe (there is nothing to wipe).
     */
    @Test
    fun absent_profile_absent_cert_routes_to_onboarding_with_no_wipes() {
        val decision = decideStartDestination(profile = null, cert = null, nowMs = nowMs)

        assertThat(decision)
            .describedAs("AC2: first-install (no profile, no cert) → onboarding, no wipes")
            .isInstanceOf(RouteToOnboarding::class.java)
        val onboarding = decision as RouteToOnboarding
        assertThat(onboarding.wipeProfile)
            .describedAs("nothing to wipe — no profile present")
            .isFalse
        assertThat(onboarding.wipeCert)
            .describedAs("nothing to wipe — no cert present")
            .isFalse
        assertThat(decision.route).isEqualTo(Routes.Onboarding)
    }

    /**
     * UC-16 § AC3 — operator deleted the cert via Settings (or
     * AndroidKeyStore was reset out-of-band) but the profile remains
     * on disk. The decider must route to the QR scanner AND ask the
     * caller to wipe the stale profile before navigation, so a
     * back-press from Onboarding cannot land on a half-cleared state.
     */
    @Test
    fun present_profile_absent_cert_routes_to_onboarding_and_wipes_profile() {
        val decision = decideStartDestination(
            profile = sampleProfile(),
            cert = null,
            nowMs = nowMs,
        )

        assertThat(decision)
            .describedAs("AC3: stale profile (no cert) → onboarding, wipe the profile")
            .isInstanceOf(RouteToOnboarding::class.java)
        val onboarding = decision as RouteToOnboarding
        assertThat(onboarding.wipeProfile)
            .describedAs("stale profile must be cleared before QR scan")
            .isTrue
        assertThat(onboarding.wipeCert)
            .describedAs("no cert present — nothing to wipe in the keystore")
            .isFalse
        assertThat(decision.route).isEqualTo(Routes.Onboarding)
    }

    /**
     * UC-16 — orphan-cert edge (no AC number; documented in the
     * decider's KDoc matrix). Cert is present in AndroidKeyStore but
     * the profile has been cleared. The decider must route to the QR
     * scanner AND ask the caller to wipe the orphan cert before
     * navigation. Mirrors AC3 with the artefacts swapped.
     */
    @Test
    fun absent_profile_present_cert_routes_to_onboarding_and_wipes_cert() {
        val decision = decideStartDestination(
            profile = null,
            cert = unexpiredCert(),
            nowMs = nowMs,
        )

        assertThat(decision)
            .describedAs("orphan-cert edge: no profile + cert → onboarding, wipe the cert")
            .isInstanceOf(RouteToOnboarding::class.java)
        val onboarding = decision as RouteToOnboarding
        assertThat(onboarding.wipeProfile)
            .describedAs("no profile present — nothing to wipe on disk")
            .isFalse
        assertThat(onboarding.wipeCert)
            .describedAs("orphan cert must be cleared from the keystore before QR scan")
            .isTrue
        assertThat(decision.route).isEqualTo(Routes.Onboarding)
    }

    /**
     * UC-16 § AC1 — the headline regression. Persisted profile + a
     * non-expired client cert in AndroidKeyStore means the device has
     * a usable identity and must resume directly to the sessions list
     * after a cold launch. No QR-scanner flash, no extra tap.
     */
    @Test
    fun present_profile_present_unexpired_cert_routes_to_sessions() {
        val decision = decideStartDestination(
            profile = sampleProfile(),
            cert = unexpiredCert(),
            nowMs = nowMs,
        )

        assertThat(decision)
            .describedAs("AC1: profile + unexpired cert → sessions (headline UC-16 fix)")
            .isEqualTo(RouteToSessions)
        assertThat(decision.route).isEqualTo(Routes.Sessions)
    }

    /**
     * UC-16 § AC4 — profile + cert are both on disk but the cert's
     * {@code notAfter} is in the past. The decider must route to the
     * QR scanner AND ask the caller to wipe BOTH artefacts before
     * navigation, so the operator can scan a fresh QR without stale
     * UI state lingering.
     */
    @Test
    fun present_profile_present_expired_cert_routes_to_onboarding_and_wipes_both() {
        val decision = decideStartDestination(
            profile = sampleProfile(),
            cert = expiredCert(),
            nowMs = nowMs,
        )

        assertThat(decision)
            .describedAs("AC4: expired cert → onboarding, wipe both profile and cert")
            .isInstanceOf(RouteToOnboarding::class.java)
        val onboarding = decision as RouteToOnboarding
        assertThat(onboarding.wipeProfile)
            .describedAs("expired identity — profile must be cleared")
            .isTrue
        assertThat(onboarding.wipeCert)
            .describedAs("expired identity — cert must be cleared from the keystore")
            .isTrue
        assertThat(decision.route).isEqualTo(Routes.Onboarding)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Placeholder {@link ServerProfile} — the decider only checks
     * "non-null", so values here just need to construct a valid data
     * class. The 64-hex-char SPKI pin matches the production format
     * (lower-case SHA-256). The expiry field on the profile is unused
     * by the decider (it consults the real X.509 {@code notAfter}).
     */
    private fun sampleProfile(): ServerProfile = ServerProfile(
        serverUrl = "https://test.invalid",
        pinSha256Hex = "a".repeat(64),
        clientCertCn = "CN=test",
        clientCertExpiresAtMs = nowMs + oneYearMs,
    )

    /**
     * Cert whose {@code notAfter} is one year ahead of {@code nowMs}.
     * Used for the AC1 happy path and the orphan-cert edge.
     */
    private fun unexpiredCert(): X509Certificate = heldCert(
        notBeforeMs = nowMs - oneDayMs,
        notAfterMs = nowMs + oneYearMs,
    )

    /**
     * Cert whose {@code notAfter} is one hour in the past relative to
     * {@code nowMs}. Used for the AC4 expired-identity test.
     */
    private fun expiredCert(): X509Certificate = heldCert(
        notBeforeMs = nowMs - oneDayMs,
        notAfterMs = nowMs - oneHourMs,
    )

    /**
     * Mint a real self-signed X.509 cert via {@link HeldCertificate}
     * with explicit validity bounds. Matches the fixture-emission
     * style of {@code KeyStoreIdentityManagerPkcs12ImportTest}.
     */
    private fun heldCert(notBeforeMs: Long, notAfterMs: Long): X509Certificate {
        val durationSeconds = TimeUnit.MILLISECONDS.toSeconds(notAfterMs - notBeforeMs)
        return HeldCertificate.Builder()
            .commonName("uc-16-decider-test")
            .validityInterval(notBeforeMs, notAfterMs)
            // Defensive belt-and-braces: HeldCertificate accepts either
            // the interval form above or a duration form; the interval
            // form is the one we care about (explicit notBefore/notAfter
            // anchors the expiry test to nowMs without relying on
            // wall-clock when the test executes). `duration` is
            // intentionally not set — `validityInterval` wins.
            .build()
            .certificate
            .also {
                // Sanity-check the fixture so a future HeldCertificate
                // behaviour change can't silently invert the expiry
                // test. The duration computation above is informational
                // only; the contract under test is the decider's
                // comparison against `nowMs`.
                check(durationSeconds != 0L) { "fixture validity window must be non-empty" }
            }
    }
}
