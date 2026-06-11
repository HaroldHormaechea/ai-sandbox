package com.aisandbox.android.ui

import com.aisandbox.android.net.NetworkEvent

/**
 * Outcome of the UC-56 single-shot network-event routing decision. Given a
 * [NetworkEvent] arriving on the process-wide [com.aisandbox.android.net.NetworkEvents]
 * bus and whether the destructive server-identity screen is ALREADY active,
 * decide where (if anywhere) the root composable should navigate.
 *
 * <p><b>Why this exists (UC-56).</b> A genuine TLS/identity failure
 * ([NetworkEvent.PinMismatch] / [NetworkEvent.HostnameMismatch] /
 * [NetworkEvent.HandshakeError]) must route to the
 * {@code ServerIdentityChangedScreen} exactly ONCE. Pre-fix, every identity
 * event re-pushed the screen via {@code navController.navigate(..)}; when the
 * REST sessions-list refresh re-failed on each {@code repeatOnLifecycle(STARTED)}
 * re-START (returning from a conversation), the bus re-emitted the event and the
 * screen was re-pushed as fast as the user dismissed it — the flicker loop the
 * use case describes. Gating the navigation on an {@code identityRouteActive}
 * flag makes the route single-shot: a second identity event while the identity
 * route is already on top is a [NoNavigation] no-op.
 *
 * <p>The sealed-type-plus-pure-function shape mirrors [StartDestinationDecision]
 * so the routing matrix is unit-testable with raw JUnit 5 (no Compose / no
 * Robolectric — see the UC-14/UC-16 Robolectric-instability note). All Compose
 * and {@code NavController} side effects stay in the [AiSandboxApp] caller.
 */
sealed interface NetworkRouteDecision

/**
 * Navigate to the destructive server-identity-changed screen
 * ([Routes.ServerIdentityChanged]). Emitted only for a genuine TLS/identity
 * event AND only when the identity route is not already active (single-shot).
 */
data object RouteToIdentity : NetworkRouteDecision

/**
 * Navigate to the cert-revoked dialog ([Routes.CertRevoked]). Emitted for
 * [NetworkEvent.CertRevoked]. Independent of the identity-route flag — the
 * revoke dialog is its own destination and is de-duped by {@code launchSingleTop}.
 */
data object RouteToCertRevoked : NetworkRouteDecision

/**
 * Do not navigate. Covers three cases:
 * <ul>
 *   <li>a genuine identity event arriving while the identity route is ALREADY
 *       active — the UC-56 single-shot guard that kills the re-push loop;</li>
 *   <li>the transient [NetworkEvent.ServerUnreachable] connectivity signal,
 *       which must NEVER route to the identity screen (UC-52/UC-54 — it drives
 *       the recoverable "reconnecting" surface, consumed at the call site);</li>
 *   <li>the terminal-local {@code Stream*} events, handled inside the terminal
 *       screen rather than the root composable.</li>
 * </ul>
 */
data object NoNavigation : NetworkRouteDecision

/**
 * Pure UC-56 single-shot routing decision. No Compose, no Context, no
 * NavController — see [AiSandboxApp] for the wiring (flag maintenance + the
 * {@code DisposableEffect} that clears the flag when the identity destination
 * leaves composition, so system-back clears it too, not only Scan/Quit).
 *
 * @param event                the event observed on the [NetworkEvents] bus.
 * @param identityRouteActive  whether the server-identity screen is currently
 *                             on top of the back stack.
 */
fun decideNetworkRoute(
    event: NetworkEvent,
    identityRouteActive: Boolean,
): NetworkRouteDecision = when (event) {
    is NetworkEvent.PinMismatch,
    is NetworkEvent.HostnameMismatch,
    is NetworkEvent.HandshakeError,
    ->
        // Single-shot: a second identity event while the identity route is
        // already active is a no-op (kills the UC-56 re-push flicker loop).
        if (identityRouteActive) NoNavigation else RouteToIdentity

    NetworkEvent.CertRevoked -> RouteToCertRevoked

    // UC-52/UC-54 — transient connectivity is NEVER an identity route. It is
    // consumed at the call site (sessions-list reconnecting surface), so even
    // if it reached the bus the root composable does nothing.
    NetworkEvent.ServerUnreachable,
    is NetworkEvent.StreamReconnecting,
    is NetworkEvent.StreamGaveUp,
    -> NoNavigation
}
