package com.aisandbox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aisandbox.android.ui.theme.BgWorkbench

/**
 * UC-16 cold-start placeholder. Rendered for the brief window between
 * process start and the first emission from [decideStartDestination] —
 * during this window the app does not yet know whether to land on the
 * QR scanner or the sessions list.
 *
 * <p>Deliberately empty (no logo, no spinner): the decision read is a
 * single AndroidKeyStore probe plus a DataStore Preferences read, both
 * sub-frame on warm starts. Painting any chrome would cause a visible
 * flash on devices fast enough to never need the placeholder.
 *
 * <p>The fill colour matches [BgWorkbench] — the same tone the rest of
 * the app uses for the workbench background — so the transition into
 * either Sessions or Onboarding is just a content swap on an unchanged
 * surface, not a colour-jump.
 */
@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgWorkbench),
    )
}
