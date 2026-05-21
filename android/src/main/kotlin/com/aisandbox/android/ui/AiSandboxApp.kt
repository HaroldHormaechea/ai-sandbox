package com.aisandbox.android.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import com.aisandbox.android.ui.screens.CertRevokedScreen
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
    LaunchedEffect(Unit) {
        NetworkEvents.flow.collect { event ->
            when (event) {
                is NetworkEvent.PinMismatch,
                is NetworkEvent.HostnameMismatch,
                is NetworkEvent.HandshakeError -> {
                    mismatchState.value = TlsFailureTranslation.toMismatch(event)
                    navController.navigate(Routes.ServerIdentityChanged) {
                        launchSingleTop = true
                    }
                }
                NetworkEvent.CertRevoked -> {
                    certRevokedState.value = true
                    navController.navigate(Routes.CertRevoked) {
                        launchSingleTop = true
                    }
                }
                is NetworkEvent.StreamReconnecting,
                is NetworkEvent.StreamGaveUp -> {
                    // Handled inside TerminalScreen — root composable is a no-op.
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
                    onOpen = { n -> navController.navigate(Routes.terminalFor(n)) },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                )
            }

            composable(
                route = Routes.TerminalPattern,
                arguments = listOf(navArgument("n") { type = NavType.IntType }),
            ) { backStackEntry ->
                val n = backStackEntry.arguments?.getInt("n") ?: 0
                TerminalScreen(sessionN = n, onBack = { navController.popBackStack() })
            }

            composable(Routes.Settings) {
                SettingsScreen(onBack = { navController.popBackStack() })
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

