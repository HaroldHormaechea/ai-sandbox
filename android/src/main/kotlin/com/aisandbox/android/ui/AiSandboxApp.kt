package com.aisandbox.android.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.requireContainer
import com.aisandbox.android.ui.screens.CertRevokedScreen
import com.aisandbox.android.ui.screens.OnboardingScreen
import com.aisandbox.android.ui.screens.ServerIdentityChangedScreen
import com.aisandbox.android.ui.screens.SessionsPlaceholder
import com.aisandbox.android.ui.screens.SettingsPlaceholder
import com.aisandbox.android.ui.screens.TerminalPlaceholder

/**
 * Root composable. Hosts the navigation graph and the network-event
 * subscription that force-routes to [Routes.ServerIdentityChanged] /
 * [Routes.CertRevoked] from anywhere in the app.
 *
 * <p>Start destination is decided once at composition time by the
 * presence/absence of a persisted [com.aisandbox.android.net.ServerProfile]:
 *
 * <ul>
 *   <li>No profile → [Routes.Onboarding] (first run, or after AC6 replace).</li>
 *   <li>Profile present → [Routes.Sessions] (the warm-start path).</li>
 * </ul>
 */
@Composable
fun AiSandboxApp() {
    val context = LocalContext.current
    val container = remember { requireContainer(context) }
    val profile by container.profileStore.profile.collectAsState(initial = null)

    val navController = rememberNavController()

    // Routing decision: first composition uses whatever the cold-start
    // profile read returns; subsequent profile changes (after clear()
    // in the replace-flow) don't auto-route — those flows navigate
    // explicitly.
    var startDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile) {
        if (startDestination == null) {
            startDestination = if (profile == null) Routes.Onboarding else Routes.Sessions
        }
    }

    // PinMismatch / CertRevoked surface globally — subscribe once.
    val pinMismatchState = remember { mutableStateOf<NetworkEvent.PinMismatch?>(null) }
    val certRevokedState = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        NetworkEvents.flow.collect { event ->
            when (event) {
                is NetworkEvent.PinMismatch -> {
                    pinMismatchState.value = event
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
        val start = startDestination ?: return@Surface
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
                SessionsPlaceholder(
                    onOpen = { n -> navController.navigate(Routes.terminalFor(n)) },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                )
            }

            composable(
                route = Routes.TerminalPattern,
                arguments = listOf(navArgument("n") { type = NavType.IntType }),
            ) { backStackEntry ->
                val n = backStackEntry.arguments?.getInt("n") ?: 0
                TerminalPlaceholder(sessionN = n, onBack = { navController.popBackStack() })
            }

            composable(Routes.Settings) {
                SettingsPlaceholder(onBack = { navController.popBackStack() })
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
                val event = pinMismatchState.value
                val expected = event?.expectedPinHex ?: ""
                val observed = event?.observedPinHex ?: ""
                ServerIdentityChangedScreen(
                    expectedPinHex = expected,
                    observedPinHex = observed,
                    onScanNewQr = {
                        pinMismatchState.value = null
                        navController.navigate(Routes.Onboarding) {
                            popUpTo(start) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onQuit = {
                        pinMismatchState.value = null
                        (context as? Activity)?.finish()
                    },
                )
            }
        }
    }
}

