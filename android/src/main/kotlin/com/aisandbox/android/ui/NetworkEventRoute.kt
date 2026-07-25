package com.aisandbox.android.ui

import com.aisandbox.android.net.NetworkEvent

/**
 * Outcome of the UC-56 single-shot network-event routing decision for the
 * destructive server-identity screen. Given a [NetworkEvent] arriving on the
 * process-wide [com.aisandbox.android.net.NetworkEvents] bus and whether that
 * identity screen is ALREADY on top of the back stack, decide whether the root
 * composable should navigate to it, suppress a duplicate, or ignore the event.
 *
 * <p><b>Why this exists (UC-56).</b> A genuine TLS/identity failure
 * ([NetworkEvent.PinMismatch] / [NetworkEvent.HostnameMismatch] /
 * [NetworkEvent.HandshakeError]) must route to the
 * {@code ServerIdentityChangedScreen} exactly ONCE. Pre-fix, every identity
 * event re-pushed the screen via {@code navController.navigate(..)}; when the
 * REST sessions-list {@code refresh()} re-failed through the shared interceptor
 * on each {@code repeatOnLifecycle(STARTED)} re-START (returning from a
 * conversation), the bus re-emitted the event and the screen was re-pushed as
 * fast as the user dismissed it — the flicker loop the use case describes.
 * Gating navigation on an {@code identityRouteActive} flag makes the route
 * single-shot: a second identity event while the screen is already active is a
 * [Suppress] no-op (distinguished from [NoOp] so tests can assert the
 * single-shot guard fired rather than the event simply being irrelevant).
 *
 * <p>Cert-revoked routing is intentionally NOT modelled here — it targets a
 * different destination ({@code Routes.CertRevoked}) with its own
 * {@code launchSingleTop} de-dup, and the caller handles it directly. This
 * decision is solely about the identity-screen single-shot behaviour.
 *
 * <p>The sealed-type-plus-pure-function shape mirrors [StartDestinationDecision]
 * so the routing matrix is unit-testable with raw JUnit 5 (no Compose / no
 * Robolectric — see the UC-14/UC-16 Robolectric-instability note). All Compose
 * and {@code NavController} side effects stay in the [AiSandboxApp] caller.
 */
sealed interface NetworkRouteDecision {

    /**
     * Navigate to the server-identity-changed screen
     * ([Routes.ServerIdentityChanged]). Emitted only for a genuine TLS/identity
     * event AND only when the identity route is not already active.
     */
    data object Navigate : NetworkRouteDecision

    /**
     * Single-shot guard fired: a genuine identity event arrived while the
     * identity screen is ALREADY active. Do nothing — this is what kills the
     * UC-56 re-push flicker loop. Distinct from [NoOp] so a test can prove the
     * guard (not mere irrelevance) suppressed the second event.
     */
    data object Suppress : NetworkRouteDecision

    /**
     * Event is irrelevant to identity-screen routing: the transient
     * [NetworkEvent.ServerUnreachable] connectivity signal (UC-52/UC-54 — drives
     * the recoverable "reconnecting" surface, consumed at the call site), a
     * terminal-local {@code Stream*} event (handled inside the terminal screen),
     * or [NetworkEvent.CertRevoked] (routed separately by the caller).
     */
    data object NoOp : NetworkRouteDecision
}

/**
 * Pure UC-56 single-shot routing decision. No Compose, no Context, no
 * NavController — see [AiSandboxApp] for the wiring (flag set in the same
 * branch at/before {@code navigate}, plus the {@code DisposableEffect} that
 * clears the flag when the identity destination leaves composition, so
 * system-back clears it too, not only Scan/Quit).
 *
 * @param event                the event observed on the {@code NetworkEvents} bus.
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
        if (identityRouteActive) NetworkRouteDecision.Suppress else NetworkRouteDecision.Navigate

    // CertRevoked and (UC-100) ServerUpgradeRequired are routed separately by the
    // caller to their own destinations; transient + terminal-local events never
    // touch the identity screen.
    NetworkEvent.CertRevoked,
    NetworkEvent.ServerUpgradeRequired,
    NetworkEvent.ServerUnreachable,
    is NetworkEvent.StreamReconnecting,
    is NetworkEvent.StreamGaveUp,
    -> NetworkRouteDecision.NoOp
}
