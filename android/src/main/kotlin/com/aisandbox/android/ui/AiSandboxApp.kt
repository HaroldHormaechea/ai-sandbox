package com.aisandbox.android.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aisandbox.android.net.Mismatch
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.net.TlsFailureTranslation
import com.aisandbox.android.requireContainer
import com.aisandbox.android.ui.screens.AppUpdateScreen
import com.aisandbox.android.ui.screens.CertRevokedScreen
import com.aisandbox.android.ui.screens.ConversationScreen
import com.aisandbox.android.ui.screens.McpScreen
import com.aisandbox.android.ui.screens.OnboardingScreen
import com.aisandbox.android.ui.screens.ServerIdentityChangedScreen
import com.aisandbox.android.ui.screens.SessionsScreen
import com.aisandbox.android.ui.screens.SettingsScreen
import com.aisandbox.android.ui.screens.TerminalScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root composable. Hosts the navigation graph and the network-event
 * subscription that force-routes to [Routes.ServerIdentityChanged] /
 * [Routes.CertRevoked] from anywhere in the app.
 *
 * <p>UC-16 — Start destination is decided once at process start by
 * [decideStartDestination] against the persisted
 * [com.aisandbox.android.net.ServerProfile] and the AndroidKeyStore
 * client cert. While that one-shot suspending read is in flight, a
 * full-screen [SplashScreen] holds the surface so the QR scanner never
 * flashes on devices that already have a valid identity (AC1, AC5). The
 * decider also reports whether stale profile / cert artefacts need to
 * be wiped before the QR scanner is shown (AC3 / AC4); those writes
 * complete before [decision] is assigned, so a back-press from the QR
 * scanner cannot land on a half-cleared state.
 *
 * <p>Subscriptions to {@link NetworkEvents} for the UC-09 / UC-10
 * pin-mismatch + cert-revoke flows are unchanged.
 */
@Composable
fun AiSandboxApp() {
    val context = LocalContext.current
    val container = remember { requireContainer(context) }

    val navController = rememberNavController()

    // UC-16 — one-shot suspending probe of the persisted profile + the
    // AndroidKeyStore cert; the pure [decideStartDestination] function
    // owns the routing matrix (AC1–AC4 + orphan-cert edge). Any wipe
    // the decider asks for completes BEFORE `decision` is set so the
    // NavHost composes against a settled disk state — back-press from
    // Onboarding cannot fall through to a half-cleared profile/cert.
    var decision by remember { mutableStateOf<StartDestinationDecision?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val profile = container.profileStore.current()
            val cert = container.identity.leafCertificate()
            val d = decideStartDestination(profile, cert, System.currentTimeMillis())
            if (d is RouteToOnboarding) {
                if (d.wipeProfile) container.profileStore.clear()
                if (d.wipeCert) container.identity.wipe()
            }
            decision = d
        }
    }

    // UC10 § AC4 / AC8 — three TLS-failure variants share one screen.
    // [TlsFailureTranslation.toMismatch] converts a NetworkEvent into
    // the screen's parameter shape; CertRevoked + Stream* fall through
    // to their own routes. The Mismatch value is held in state and
    // surfaced to the ServerIdentityChanged composable below.
    val mismatchState = remember { mutableStateOf<Mismatch?>(null) }
    val certRevokedState = remember { mutableStateOf(false) }
    // UC-56 — single-shot guard for the destructive server-identity route.
    // `true` while ServerIdentityChangedScreen is on top of the back stack;
    // the DisposableEffect inside that composable clears it when the screen
    // LEAVES composition (covers system-back, not just Scan-new-QR / Quit).
    // The pure [decideNetworkRoute] reads this flag so a second identity event
    // arriving while the screen is already shown is a no-op — killing the
    // conversation→sessions-list re-push flicker loop (AC1, AC2).
    val identityRouteActive = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        NetworkEvents.flow.collect { event ->
            // CertRevoked targets its own destination (separate single-top
            // de-dup) and is not part of the UC-56 identity single-shot logic,
            // so it is handled directly rather than via decideNetworkRoute.
            if (event == NetworkEvent.CertRevoked) {
                certRevokedState.value = true
                navController.navigate(Routes.CertRevoked) {
                    launchSingleTop = true
                }
                return@collect
            }
            // UC-100 (AC8) — the single /v1/mux socket was refused for a protocol
            // version mismatch (server closed 4426 after the hello/welcome
            // handshake disagreed, or the /v1/capabilities probe reported a
            // different ws_protocol). The hard cut requires matched client+server
            // versions, so route to the actionable update-required screen rather
            // than looping the reconnect back-off. Its own launchSingleTop de-dups.
            if (event == NetworkEvent.ServerUpgradeRequired) {
                navController.navigate(Routes.AppUpdate) {
                    launchSingleTop = true
                }
                return@collect
            }
            when (decideNetworkRoute(event, identityRouteActive.value)) {
                NetworkRouteDecision.Navigate -> {
                    // UC-56 — set the single-shot flag BEFORE navigating, in
                    // this same branch, so a same-frame burst of identity
                    // events cannot double-navigate.
                    identityRouteActive.value = true
                    mismatchState.value = TlsFailureTranslation.toMismatch(event)
                    navController.navigate(Routes.ServerIdentityChanged) {
                        launchSingleTop = true
                    }
                }
                NetworkRouteDecision.Suppress -> {
                    // UC-56 single-shot guard: a genuine identity event arrived
                    // while ServerIdentityChangedScreen is already on top. Do
                    // nothing — this is what kills the re-push flicker loop.
                }
                NetworkRouteDecision.NoOp -> {
                    // Irrelevant to identity routing: transient ServerUnreachable
                    // (consumed at the call site — UC-52/UC-54) or a
                    // terminal-local Stream* event (handled in TerminalScreen).
                }
            }
        }
    }

    Surface(
        modifier = Modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        // UC-16 — hold a themed empty surface until the cold-start
        // decision lands. Avoids the AC1/AC5 regression of flashing
        // the QR scanner before the decider notices a valid identity.
        val resolved = decision
        if (resolved == null) {
            SplashScreen()
            return@Surface
        }
        // Single source of truth for both `NavHost(startDestination)`
        // and the `popUpTo(start)` callbacks on the cert-revoked /
        // server-identity-changed screens — keeps the back-stack
        // bottom aligned with the cold-start destination so re-scan
        // flows fully clear it (UC-16 pitfall note: keep both
        // popUpTo call sites converted).
        val start: String = resolved.route
        // UC-69 — consume a pending deep-link (from a tapped pending-question
        // notification) ONLY after the start destination has settled. Navigate to
        // the conversation only when the start destination is the sessions list —
        // an un-enrolled cold start (Onboarding / identity screens) has no
        // conversation to open, so the request is dropped. consume() clears the
        // latched value either way, so a configuration change cannot re-navigate.
        LaunchedEffect(start) {
            container.deepLinkEvents.pendingSession.collect { n ->
                if (n != null) {
                    if (start == Routes.Sessions) {
                        // UC-93 — on Navigation-Compose 2.9.8, launchSingleTop
                        // alone already re-keys this destination on a warm deep-link
                        // (verified on-device: warm A→B and A→A both re-enter
                        // ConversationScreen and re-fire its LaunchedEffect(sessionN)
                        // → attach(target)). So popUpTo is NOT what fixes the attach
                        // path / the wedged-question defect. popUpTo(ConversationPattern)
                        // { inclusive = true } is retained purely as back-stack hygiene:
                        // it bounds the warm deep-link to a single conversation entry
                        // (popUpTo targets conversation/{n}, not the start, so the
                        // sessions list stays underneath per UC-69 AC4) and keeps
                        // parity with UC-91's prescribed nav. The sessions-list
                        // navigate (a plain navigate, below) is intentionally untouched.
                        navController.navigate(Routes.conversationFor(n)) {
                            popUpTo(Routes.ConversationPattern) { inclusive = true }
                            launchSingleTop = true
                        }
                        // UC-93 (Case R) — a warm deep-link can re-enter the process-cached
                        // ConversationController for the target session while it is still
                        // selecting a read-only `subagent:` pane (left there by a prior
                        // background-subagent pill tap). That selection is re-asserted on every
                        // reconnect, so the server tails the subagent pane, the pending-question
                        // re-emit finds no answerable ask, and ConversationScreen.readOnly hides
                        // the question box + composer = the wedge. Re-focus the answerable `main`
                        // pane on the same cached controller (idempotent; a strict no-op when the
                        // selection is already `main`/`swarm:`) so the pending question renders.
                        container.conversationController(n).focusAnswerableTargetForDeepLink()
                    }
                    container.deepLinkEvents.consume()
                }
            }
        }
        NavHost(navController = navController, startDestination = start) {

            composable(Routes.Onboarding) {
                OnboardingScreen(
                    onContinue = {
                        navController.navigate(Routes.Sessions) {
                            popUpTo(Routes.Onboarding) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.Sessions) {
                SessionsScreen(
                    // UC-37 — single-tap opens the structured conversation view;
                    // long-press opens the tmux/terminal view (the raw fallback).
                    onOpen = { n -> navController.navigate(Routes.conversationFor(n)) },
                    onOpenTerminal = { n -> navController.navigate(Routes.terminalFor(n)) },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    // UC-87 — third hamburger item opens the app self-update screen.
                    onOpenAppUpdate = { navController.navigate(Routes.AppUpdate) },
                )
            }

            composable(
                route = Routes.TerminalPattern,
                arguments = listOf(navArgument("n") { type = NavType.IntType }),
            ) { backStackEntry ->
                val n = backStackEntry.arguments?.getInt("n") ?: 0
                TerminalScreen(sessionN = n, onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.ConversationPattern,
                arguments = listOf(navArgument("n") { type = NavType.IntType }),
            ) { backStackEntry ->
                val n = backStackEntry.arguments?.getInt("n") ?: 0
                ConversationScreen(
                    sessionN = n,
                    onBack = { navController.popBackStack() },
                    // UC-67 — overflow "MCP" item opens the full-screen MCP manager.
                    onOpenMcp = { navController.navigate(Routes.mcpFor(n)) },
                )
            }

            composable(
                route = Routes.McpPattern,
                arguments = listOf(navArgument("n") { type = NavType.IntType }),
            ) { backStackEntry ->
                val n = backStackEntry.arguments?.getInt("n") ?: 0
                McpScreen(sessionN = n, onBack = { navController.popBackStack() })
            }

            composable(Routes.Settings) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.AppUpdate) {
                // UC-87 — dedicated app self-update screen.
                AppUpdateScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.CertRevoked) {
                CertRevokedScreen(
                    onLater = {
                        certRevokedState.value = false
                        navController.popBackStack()
                    },
                    onScanNewQr = {
                        // AC26 — dialog dismisses to the import screen, not
                        // back to sessions. Clear the whole back stack so a
                        // back gesture can't fall through to stale state.
                        certRevokedState.value = false
                        navController.navigate(Routes.Onboarding) {
                            popUpTo(start) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.ServerIdentityChanged) {
                // UC-56 — clear the single-shot guard the moment this screen
                // leaves composition, for ANY exit path: Scan-new-QR, Quit, OR
                // a system-back gesture. Doing it here (rather than only in the
                // button callbacks) means a back-press also re-arms routing, so
                // a LATER genuine identity failure can still surface the screen,
                // while the re-push loop stays suppressed for as long as the
                // screen is actually on top.
                DisposableEffect(Unit) {
                    onDispose { identityRouteActive.value = false }
                }
                // Defensive fallback: routing here without a Mismatch loaded
                // shouldn't happen, but render the generic handshake-error
                // variant rather than crashing if it does.
                val cause = mismatchState.value ?: Mismatch.HandshakeError(rawMessage = "")
                ServerIdentityChangedScreen(
                    cause = cause,
                    onScanNewQr = {
                        mismatchState.value = null
                        navController.navigate(Routes.Onboarding) {
                            popUpTo(start) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onQuit = {
                        mismatchState.value = null
                        (context as? Activity)?.finish()
                    },
                )
            }
        }
    }
}

