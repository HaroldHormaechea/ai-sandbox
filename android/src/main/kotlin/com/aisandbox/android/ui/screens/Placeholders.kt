package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder shells for the screens whose full content lands in later
 * checkpoints. They render a labelled empty box so the NavHost wiring
 * can be verified end-to-end before the screens themselves arrive.
 *
 * <p>Lands real content:
 *   - {@link OnboardingPlaceholder} → checkpoint 13 (full QR scan flow)
 *   - {@link SessionsPlaceholder}   → checkpoint 14
 *   - {@link TerminalPlaceholder}   → checkpoint 15
 *   - {@link SettingsPlaceholder}   → checkpoint 15
 */

@Composable
fun OnboardingPlaceholder() = Placeholder("Onboarding (UC04-1) — coming in checkpoint 13")

@Composable
fun SessionsPlaceholder(onOpen: (Int) -> Unit, onOpenSettings: () -> Unit) =
    Placeholder("Sessions (UC04-2) — coming in checkpoint 14")

@Composable
fun TerminalPlaceholder(sessionN: Int, onBack: () -> Unit) =
    Placeholder("Terminal (UC04-3) — coming in checkpoint 15.  n=$sessionN")

@Composable
fun SettingsPlaceholder(onBack: () -> Unit) =
    Placeholder("Settings (UC04-5) — coming in checkpoint 15")

@Composable
private fun Placeholder(label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "ai-sandbox",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
