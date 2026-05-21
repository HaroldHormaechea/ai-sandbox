package com.aisandbox.android.ui

import com.aisandbox.android.net.ServerProfile
import java.security.cert.X509Certificate

/**
 * Outcome of the UC-16 cold-start routing decision. Either the device has
 * a usable identity (profile + non-expired client cert) and resumes to the
 * sessions list ([RouteToSessions]), or it needs to (re-)enroll and the
 * decider also tells the caller which on-disk artefacts to wipe before
 * the QR scanner is shown ([RouteToOnboarding]).
 *
 * <p>The sealed-type-plus-pure-function shape keeps the decision unit-
 * testable without an Android dependency surface (see [decideStartDestination]
 * — no Compose, no Context, no AndroidKeyStore handle). Use-case 16 § AC8
 * requires coverage of the five state combinations; the matrix lives in
 * exactly one place: the function body below.
 */
sealed interface StartDestinationDecision {
    /** Stable navigation route the caller should hand to `NavHost(startDestination = …)`. */
    val route: String
}

/**
 * Identity is valid — profile is on disk AND the client cert is present
 * in AndroidKeyStore AND `notAfter` has not passed. UC-16 § AC1.
 */
data object RouteToSessions : StartDestinationDecision {
    override val route: String = Routes.Sessions
}

/**
 * Identity is missing, half-present, or expired — route to the QR
 * scanner. The two booleans tell the caller which artefacts to wipe
 * before navigating so a back-press cannot land on a half-cleared state
 * (UC-16 § AC3 + AC4).
 *
 * @param wipeProfile  call [com.aisandbox.android.net.ServerProfileStore.clear] before navigating.
 * @param wipeCert     call [com.aisandbox.android.identity.KeyStoreIdentityManager.wipe] before navigating.
 */
data class RouteToOnboarding(
    val wipeProfile: Boolean,
    val wipeCert: Boolean,
) : StartDestinationDecision {
    override val route: String = Routes.Onboarding
}

/**
 * Pure UC-16 cold-start routing decision. Given the persisted server
 * profile, the client cert pulled from AndroidKeyStore, and the current
 * wall-clock time, return the screen the app should boot into and (when
 * onboarding) which on-disk artefacts to wipe first.
 *
 * <p>The matrix (UC-16 § Acceptance Criteria 1–4):
 *
 * <table>
 *   <tr><th>profile</th><th>cert</th><th>notAfter vs now</th><th>decision</th></tr>
 *   <tr><td>null</td>   <td>null</td>   <td>n/a</td>                 <td>RouteToOnboarding(false, false) — first install (AC2)</td></tr>
 *   <tr><td>present</td><td>null</td>   <td>n/a</td>                 <td>RouteToOnboarding(wipeProfile=true) — AC3 stale profile</td></tr>
 *   <tr><td>null</td>   <td>present</td><td>n/a</td>                 <td>RouteToOnboarding(wipeCert=true) — orphan-cert edge</td></tr>
 *   <tr><td>present</td><td>present</td><td>notAfter ≥ now</td>      <td>RouteToSessions — happy path (AC1)</td></tr>
 *   <tr><td>present</td><td>present</td><td>notAfter &lt; now</td>   <td>RouteToOnboarding(both) — AC4 expired</td></tr>
 * </table>
 *
 * <p>There is intentionally no grace window on `notAfter`. UC-09 / UC-10's
 * SPKI-pinning + TLS-handshake-error routing catches "expired in the
 * server's view" at the first network request (well within the same
 * session). If field reports show clock-skew false-positives, adding
 * a 60-second grace window here is a one-line follow-up; we keep the
 * decision strict until evidence forces the relaxation.
 *
 * <p>This function is pure (no IO, no platform calls). All Android-side
 * state reads happen in the caller — see [AiSandboxApp].
 */
fun decideStartDestination(
    profile: ServerProfile?,
    cert: X509Certificate?,
    nowMs: Long,
): StartDestinationDecision = when {
    profile == null && cert == null -> RouteToOnboarding(wipeProfile = false, wipeCert = false)
    profile != null && cert == null -> RouteToOnboarding(wipeProfile = true, wipeCert = false)
    profile == null && cert != null -> RouteToOnboarding(wipeProfile = false, wipeCert = true)
    // profile != null && cert != null — gate on expiry.
    cert!!.notAfter.time >= nowMs -> RouteToSessions
    else -> RouteToOnboarding(wipeProfile = true, wipeCert = true)
}
