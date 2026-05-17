package com.aisandbox.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Root composable for the ai-sandbox client. Hosts the navigation graph
 * and subscribes to the global network-result flow so the pin-mismatch
 * path (UC04 § ServerIdentityChangedScreen) can force-route from any
 * screen.
 *
 * This is a Checkpoint-1 placeholder — the full NavHost wiring (Onboarding
 * → Sessions → Terminal → Settings + the cert-revoked + pin-mismatch
 * overlays) lands in a later checkpoint.
 */
@Composable
fun AiSandboxApp() {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            Text(
                text = "ai-sandbox · v0",
                color = MaterialTheme.colorScheme.onSurface,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            )
        }
    }
}
