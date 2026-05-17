package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aisandbox.android.R
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.ErrorContainer
import com.aisandbox.android.ui.theme.ErrorTone

/**
 * AC7 hard-refusal screen — surfaced when the OkHttp [CertificatePinner]
 * rejects a server cert that doesn't match the persisted pin. Distinct
 * from UC04-7 cert-revoked: this is "the SERVER changed identity", not
 * "MY identity got revoked".
 *
 * <p>Per the proposal § A5: dedicated screen, NOT a dialog —
 * "{@code Scan new QR} + {@code Quit} actions". Quit closes the
 * Activity; Scan new QR clears the stored profile + KeyStore identity
 * and returns to OnboardingScreen.
 *
 * <p>Receives the pinned (expected) and observed sha256 hex strings so
 * the operator can sanity-check the mismatch against the server logs.
 */
@Composable
fun ServerIdentityChangedScreen(
    expectedPinHex: String,
    observedPinHex: String,
    onScanNewQr: () -> Unit,
    onQuit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Error-tone circular badge with a warning icon — matches the
            // design's hard-refusal chrome.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ErrorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = ErrorTone,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.server_identity_changed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.server_identity_changed_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            // Diagnostic block — operator-readable hex; mono font so it
            // round-trips through screenshots cleanly.
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("expected:", style = AiSandboxMonoTypography.metadata)
                Text(expectedPinHex, style = AiSandboxMonoTypography.fingerprint)
                Spacer(Modifier.height(8.dp))
                Text("observed:", style = AiSandboxMonoTypography.metadata)
                Text(observedPinHex, style = AiSandboxMonoTypography.fingerprint)
            }
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onQuit) {
                    Text(stringResource(R.string.server_identity_changed_quit))
                }
                Button(
                    onClick = onScanNewQr,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        text = stringResource(R.string.server_identity_changed_rescan),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
